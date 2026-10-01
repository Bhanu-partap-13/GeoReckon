import unittest
import numpy as np
from georeckon.src.kinematics_speed_estimator import KinematicSpeedEstimator


class TestSpeedEstimator(unittest.TestCase):
    def test_boundary_continuity(self):
        """Verifies forward speed is seeded continuously from GNSS."""
        est = KinematicSpeedEstimator(dt=0.1)
        est.sync_gnss_speed(18.5)
        self.assertEqual(est.v_forward, 18.5)
        self.assertFalse(est.is_stationary)

    def test_centripetal_cross_validation(self):
        """Verifies centripetal turn constraint v = |a_lat / w_yaw| during cornering."""
        est = KinematicSpeedEstimator(dt=0.1)
        est.sync_gnss_speed(10.0)

        # Vehicle turning at 20 m/s with yaw rate 0.2 rad/s => a_lat = 20 * 0.2 = 4.0 m/s^2
        w_yaw = 0.20
        a_lat = 4.0
        acc_win = np.tile([0.0, 4.0, 9.8], (10, 1))
        gyr_win = np.tile([0.0, 0.0, 0.2], (10, 1))

        # Perform 5 integration steps
        for _ in range(5):
            spd, stopped = est.estimate_step(
                forward_accel=0.0,
                lateral_accel=a_lat,
                yaw_rate=w_yaw,
                accel_window=acc_win,
                gyro_window=gyr_win,
                dt=0.1
            )
        # Should converge toward 20.0 m/s
        self.assertGreater(spd, 13.0)

    def test_momentum_gated_zupt(self):
        """Verifies that vehicle at rest stops and clamps speed to 0."""
        est = KinematicSpeedEstimator(dt=0.1)
        est.sync_gnss_speed(0.4) # Decelerated below 0.8 m/s

        acc_win = np.tile([0.0, 0.0, 9.80665], (10, 1))
        gyr_win = np.tile([0.0, 0.0, 0.0], (10, 1))

        spd, stopped = est.estimate_step(
            forward_accel=0.0,
            lateral_accel=0.0,
            yaw_rate=0.0,
            accel_window=acc_win,
            gyro_window=gyr_win,
            dt=0.1
        )
        self.assertTrue(stopped)
        self.assertEqual(spd, 0.0)


if __name__ == '__main__':
    unittest.main()
