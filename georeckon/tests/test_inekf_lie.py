import unittest
import numpy as np
from georeckon.src.filter_inekf import InEKF, InEKFState, retract, compute_reset_jacobian
from georeckon.src.geometry import so3_exp, skew, left_jac


class TestInEKF(unittest.TestCase):
    def test_retraction_properties(self):
        """Verifies left-group retraction identity and small perturbation behavior."""
        state = InEKFState(
            R=np.eye(3),
            v=np.array([10.0, 0.0, 0.0]),
            p=np.array([100.0, 50.0, 0.0]),
            bg=np.zeros(3),
            ba=np.zeros(3)
        )
        dx = np.zeros(15)
        dx[3] = 1.0 # 1 m/s velocity perturbation along X
        new_state = retract(state, dx)

        self.assertAlmostEqual(new_state.v[0], 11.0, places=5)
        self.assertAlmostEqual(new_state.v[1], 0.0, places=5)
        self.assertAlmostEqual(new_state.p[0], 100.0, places=5)

    def test_propagation_constant_velocity(self):
        """Verifies that in absence of external force (accel = -G), velocity is conserved."""
        init_v = np.array([15.0, 0.0, 0.0])
        f = InEKF(InEKFState(R=np.eye(3), v=init_v.copy(), p=np.zeros(3)))

        dt = 0.1
        # Specific force opposing gravity
        a_meas = np.array([0.0, 0.0, 9.80665])
        w_meas = np.zeros(3)

        for _ in range(10):
            f.predict(a_meas, w_meas, dt)

        # Velocity should remain exactly constant
        diff_v = np.max(np.abs(f.state.v - init_v))
        self.assertLess(diff_v, 1e-4)
        # Position should advance by 15 * 1.0 = 15 meters
        self.assertAlmostEqual(f.state.p[0], 15.0, places=3)

    def test_non_holonomic_constraint_update(self):
        """Verifies that NHC update constrains lateral and vertical velocities."""
        f = InEKF(InEKFState(
            R=np.eye(3),
            v=np.array([12.0, 2.5, -1.0]), # Has spurious lateral and vertical speeds
            p=np.zeros(3)
        ))

        R_b_to_v = np.eye(3)
        # NHC targets: v_y = 0, v_z = 0
        success = f.update_chassis_velocity(
            v_chassis_target=np.array([0.0, 0.0, 0.0]),
            R_b_to_v=R_b_to_v,
            variances=np.array([1.0, 0.01, 0.01]),
            axes=(1, 2)
        )
        self.assertTrue(success)
        # Lateral and vertical velocities should be pulled toward 0
        self.assertLess(abs(f.state.v[1]), 1.0)
        self.assertLess(abs(f.state.v[2]), 0.5)

    def test_covariance_psd(self):
        """Verifies covariance matrix remains strictly positive semi-definite (symmetric)."""
        f = InEKF()
        f.predict(np.array([0.0, 0.0, 9.8]), np.array([0.01, 0.0, 0.0]), dt=0.1)
        sym_diff = np.max(np.abs(f.P - f.P.T))
        self.assertLess(sym_diff, 1e-6)
        eigvals = np.linalg.eigvalsh(f.P)
        self.assertTrue(np.all(eigvals > 0))


if __name__ == '__main__':
    unittest.main()
