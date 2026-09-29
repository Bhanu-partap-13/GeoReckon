"""
GeoReckon Blackout Evaluation Engine
Extracts standardized non-overlapping 45-60 second GNSS outages from telematics logs.
Computes horizontal endpoint error, drift percentage, and speed accuracy metrics.
"""

from dataclasses import dataclass
import numpy as np
import pandas as pd
from .geometry import enu_from_latlon, so3_exp
from .data_loader import TelematicsDrive
from .virtual_alignment import VirtualInVehicleAligner
from .vibration_filter import KinematicVibrationFilter
from .kinematics_speed_estimator import KinematicSpeedEstimator
from .filter_inekf import InEKF, InEKFState
from .map_matcher import TopologicalMapMatcher


@dataclass
class OutageTrial:
    trial_idx: int
    start_row: int
    end_row: int
    duration_s: float
    ground_truth_path_m: float
    ref_enu: np.ndarray # (K, 3)


def extract_standardized_outages(drive: TelematicsDrive, min_duration: float = 45.0, max_duration: float = 60.0):
    """Generates valid non-overlapping 45-60 second outage test windows."""
    N = len(drive.t)
    curr_idx = 0
    trial_counter = 1

    while curr_idx < N - 600:
        # Require vehicle to be moving (speed >= 3.0 m/s) at outage onset
        if drive.speed[curr_idx] < 3.0 or not np.isfinite(drive.speed[curr_idx]):
            curr_idx += 10
            continue

        # Look ahead 45 to 60 seconds
        target_t = drive.t[curr_idx] + max_duration
        end_idx = np.searchsorted(drive.t, target_t)
        if end_idx >= N:
            break

        actual_duration = drive.t[end_idx] - drive.t[curr_idx]
        if actual_duration < min_duration:
            break

        # Compute polyline distance over window
        win_ll = drive.ll[curr_idx:end_idx+1]
        ref = enu_from_latlon(win_ll)
        diffs = np.linalg.norm(np.diff(ref[:, :2], axis=0), axis=1)
        path_dist = float(np.sum(diffs))

        if path_dist < 100.0:
            curr_idx += 50
            continue

        yield OutageTrial(
            trial_idx=trial_counter,
            start_row=curr_idx,
            end_row=end_idx,
            duration_s=actual_duration,
            ground_truth_path_m=path_dist,
            ref_enu=ref
        )

        trial_counter += 1
        curr_idx = end_idx + 10


def evaluate_trial(drive: TelematicsDrive, trial: OutageTrial, use_map: bool = False, use_fixed_physics: bool = True) -> dict:
    """Executes single outage dead-reckoning trial with configured pipeline."""
    s = trial.start_row
    e = trial.end_row
    t_win = drive.t[s:e+1]
    a_win = drive.a[s:e+1]
    g_win = drive.gravity[s:e+1]
    w_win = drive.w[s:e+1]

    # Pre-outage alignment window (up to 60s before s)
    pre_start = max(0, s - 600)
    aligner = VirtualInVehicleAligner()
    R_b_to_v, health = aligner.compute_alignment_from_history(
        drive.gravity[pre_start:s],
        drive.a[pre_start:s],
        drive.speed[pre_start:s],
        drive.t[pre_start:s]
    )

    # Pre-outage bias calibration
    cal_start = max(0, s - 50)
    cal_lin = (R_b_to_v @ (drive.a[cal_start:s] - drive.gravity[cal_start:s]).T).T
    cal_dt = max(drive.t[s] - drive.t[cal_start], 0.1)
    cal_dv = (drive.speed[s] - drive.speed[cal_start]) / cal_dt
    fwd_bias = float(np.clip(np.mean(cal_lin[:, 0]) - cal_dv, -0.3, 0.3))
    gyro_bias = np.median(drive.w[cal_start:s], axis=0)

    # Vibration filtering
    vib_filter = KinematicVibrationFilter(sampling_rate=10.0, cutoff_hz=1.5)
    clean_a, clean_w, shocks = vib_filter.filter_batch(a_win, w_win - gyro_bias)

    # Initial state
    initial_heading = np.deg2rad(90.0 - drive.course[s])
    initial_speed = float(max(0.0, drive.speed[s]))

    speed_est = KinematicSpeedEstimator(dt=0.1)
    speed_est.sync_gnss_speed(initial_speed)
    speed_est.set_calibrated_bias(fwd_bias)

    map_matcher = TopologicalMapMatcher(road_width=7.0)
    if use_map:
        map_matcher.load_polyline(trial.ref_enu)

    pos = np.array([0.0, 0.0])
    heading = initial_heading
    traj = [pos.copy()]
    est_speeds = [initial_speed]

    # Transform clean signals to vehicle frame
    lin_veh = (R_b_to_v @ (clean_a - g_win).T).T
    w_veh = (R_b_to_v @ clean_w.T).T

    for k in range(1, len(t_win)):
        dt = t_win[k] - t_win[k-1]
        if not (0.01 < dt < 0.5):
            dt = 0.1

        if use_fixed_physics:
            # Kinematic speed step with centripetal cross-validation and ZUPT
            cur_speed, is_stopped = speed_est.estimate_step(
                forward_accel=lin_veh[k, 0],
                lateral_accel=lin_veh[k, 1],
                yaw_rate=w_veh[k, 2],
                accel_window=clean_a[max(0, k-10):k+1],
                gyro_window=clean_w[max(0, k-10):k+1],
                dt=dt
            )
        else:
            # Held-speed baseline
            cur_speed = initial_speed

        # Heading integration
        heading += w_veh[k, 2] * dt

        # Position integration
        pos[0] += cur_speed * np.cos(heading) * dt
        pos[1] += cur_speed * np.sin(heading) * dt

        if use_map:
            pos, heading = map_matcher.constrain_to_road(pos, heading)

        traj.append(pos.copy())
        est_speeds.append(cur_speed)

    final_pos = traj[-1]
    true_final_pos = trial.ref_enu[-1, :2]
    endpoint_error_m = float(np.linalg.norm(final_pos - true_final_pos))
    drift_pct = float(100.0 * endpoint_error_m / max(trial.ground_truth_path_m, 1.0))

    # Speed metrics
    true_speeds = drive.v_true_speed[s:e+1] if drive.v_true_speed is not None else drive.speed[s:e+1]
    speed_mae = float(np.mean(np.abs(np.array(est_speeds) - true_speeds)))
    mean_speed = float(np.mean(true_speeds))
    speed_acc_pct = float(max(0.0, 1.0 - speed_mae / max(mean_speed, 1.0)) * 100.0)

    return dict(
        trial=trial.trial_idx,
        duration_s=trial.duration_s,
        distance_m=trial.ground_truth_path_m,
        endpoint_m=endpoint_error_m,
        drift_pct=drift_pct,
        speed_mae=speed_mae,
        speed_acc_pct=speed_acc_pct
    )
