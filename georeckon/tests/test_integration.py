import unittest
import numpy as np
from georeckon.src.virtual_alignment import VirtualInVehicleAligner
from georeckon.src.vibration_filter import KinematicVibrationFilter
from georeckon.src.kinematics_speed_estimator import KinematicSpeedEstimator
from georeckon.src.map_matcher import TopologicalMapMatcher
from georeckon.src.filter_inekf import InEKF, InEKFState


class TestEndToEndIntegration(unittest.TestCase):
    def test_straight_tunnel_outage(self):
        """Simulates 45-second straight tunnel traversal at 20 m/s with 0.15 m/s^2 accel bias."""
        dt = 0.1
        duration = 45.0
        N = int(duration / dt) # 450 steps

        # Ground truth route: 900 meters straight East
        true_speed = 20.0 # m/s
        true_pos = np.column_stack([np.linspace(0.0, 900.0, N), np.zeros(N)])

        # Sensor readings corrupted with forward bias and road vibrations
        accel_bias = 0.12 # m/s^2
        noise = np.random.randn(N) * 0.4
        raw_ax = np.full(N, accel_bias) + noise
        raw_ay = np.random.randn(N) * 0.3
        raw_az = np.full(N, 9.80665) + np.random.randn(N) * 0.4
        raw_a = np.column_stack([raw_ax, raw_ay, raw_az])
        raw_w = np.random.randn(N, 3) * 0.005 # Minor gyro noise

        # Pipeline setup
        vib_filter = KinematicVibrationFilter(sampling_rate=10.0, cutoff_hz=1.5)
        clean_a, clean_w, _ = vib_filter.filter_batch(raw_a, raw_w)

        speed_est = KinematicSpeedEstimator(dt=dt)
        speed_est.sync_gnss_speed(true_speed)
        speed_est.set_calibrated_bias(accel_bias) # Calibrated bias applied

        map_matcher = TopologicalMapMatcher(road_width=7.0)
        map_matcher.load_polyline(np.column_stack([true_pos, np.zeros(N)]))

        pos = np.array([0.0, 0.0])
        heading = 0.0 # Facing East

        for k in range(N):
            cur_speed, _ = speed_est.estimate_step(
                forward_accel=clean_a[k, 0],
                lateral_accel=clean_a[k, 1],
                yaw_rate=clean_w[k, 2],
                accel_window=clean_a[max(0, k-10):k+1],
                gyro_window=clean_w[max(0, k-10):k+1],
                dt=dt
            )
            heading += clean_w[k, 2] * dt
            pos[0] += cur_speed * np.cos(heading) * dt
            pos[1] += cur_speed * np.sin(heading) * dt
            pos, heading = map_matcher.constrain_to_road(pos, heading)

        endpoint_error = np.linalg.norm(pos - true_pos[-1])
        drift_pct = 100.0 * endpoint_error / 900.0

        # With calibrated bias and road corridor snapping, drift should easily be <= 5.0% (>= 95% accuracy)
        self.assertLess(drift_pct, 5.0)
        self.assertLess(endpoint_error, 45.0)


if __name__ == '__main__':
    unittest.main()
