"""
GeoReckon Kinematic Speed Estimator
Estimates vehicle forward speed during GNSS-denied blackouts using:
1. Boundary Continuity: seeded from final GNSS fix.
2. Calibrated Forward Accelerometer Bias Removal.
3. Forward Euler Integration with non-holonomic speed floor (v >= 0).
4. Centripetal Turn Kinematics: v_cp = |a_lat / omega_z| during maneuvers.
5. Momentum-Gated ZUPT: zero velocity updates during complete stops.
"""

import numpy as np


class KinematicSpeedEstimator:
    def __init__(self, dt: float = 0.10):
        self.dt = dt
        self.v_forward = 0.0
        self.fwd_bias = 0.0
        self.is_stationary = False

        # ZUPT thresholds
        self.accel_var_thresh = 0.05
        self.gyro_mag_thresh = 0.035

    def sync_gnss_speed(self, gnss_speed: float):
        """Syncs forward speed to GNSS speed magnitude prior to outage."""
        self.v_forward = float(max(0.0, gnss_speed))
        self.is_stationary = bool(gnss_speed < 0.25)

    def set_calibrated_bias(self, bias: float):
        """Sets calibrated steady-state forward accelerometer bias."""
        self.fwd_bias = float(np.clip(bias, -0.35, 0.35))

    def detect_zupt(self, accel_window: np.ndarray, gyro_window: np.ndarray) -> bool:
        """Evaluates whether vehicle is at complete rest."""
        if len(accel_window) < 5:
            return self.is_stationary
        acc_var = np.std(np.linalg.norm(accel_window, axis=1))
        gyro_mag = np.mean(np.linalg.norm(gyro_window, axis=1))
        return bool(acc_var < self.accel_var_thresh and gyro_mag < self.gyro_mag_thresh)

    def estimate_step(self,
                      forward_accel: float,
                      lateral_accel: float,
                      yaw_rate: float,
                      accel_window: np.ndarray,
                      gyro_window: np.ndarray,
                      dt: float = None) -> tuple[float, bool]:
        """
        Advances forward speed estimation by one time step.

        Returns:
            v_forward: float speed in m/s
            is_stopped: bool True if at zero velocity
        """
        step_dt = self.dt if dt is None else dt

        # 1. Bias-corrected forward acceleration integration
        a_clean = forward_accel - self.fwd_bias
        v_integrated = max(0.0, self.v_forward + a_clean * step_dt)

        # 2. Momentum-gated ZUPT: only allows stopping if speed is already low
        if v_integrated < 0.8 and self.detect_zupt(accel_window, gyro_window):
            self.is_stationary = True
            self.v_forward = 0.0
            return 0.0, True

        self.is_stationary = False

        # 3. Centripetal Kinematic Cross-Validation: a_lat = v * omega_z
        # Active during vehicle cornering
        v_cp = None
        if abs(yaw_rate) > 0.06 and abs(lateral_accel) > 0.25:
            computed_cp = abs(lateral_accel / yaw_rate)
            if 1.5 < computed_cp < 45.0:
                v_cp = computed_cp

        # 4. Sensor fusion: 85% kinematic integration + 15% centripetal turn constraint
        if v_cp is not None:
            self.v_forward = 0.85 * v_integrated + 0.15 * v_cp
        else:
            self.v_forward = v_integrated

        return float(self.v_forward), False
