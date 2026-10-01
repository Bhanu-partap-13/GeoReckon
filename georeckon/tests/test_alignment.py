import unittest
import numpy as np
from georeckon.src.virtual_alignment import VirtualInVehicleAligner


class TestVirtualAlignment(unittest.TestCase):
    def test_ideal_alignment(self):
        """Tests that pure forward acceleration along body X yields identity R_b_to_v."""
        N = 100
        gravity = np.tile([0.0, 0.0, 9.80665], (N, 1))
        # Forward acceleration along body X
        fwd_acc = np.linspace(0.0, 2.0, N)[:, None] * np.array([1.0, 0.0, 0.0])
        total_acc = gravity + fwd_acc
        speed = np.linspace(0.0, 20.0, N)
        t = np.linspace(0.0, 10.0, N)

        aligner = VirtualInVehicleAligner()
        R, health = aligner.compute_alignment_from_history(gravity, total_acc, speed, t)

        # Body X should map to Vehicle X [1, 0, 0]
        fwd_transformed = R @ np.array([1.0, 0.0, 0.0])
        self.assertAlmostEqual(fwd_transformed[0], 1.0, places=3)
        self.assertAlmostEqual(fwd_transformed[1], 0.0, places=3)
        self.assertAlmostEqual(fwd_transformed[2], 0.0, places=3)
        self.assertGreaterEqual(health, 0.1)

    def test_orthogonality(self):
        """Ensures computed rotation matrix is strictly orthogonal (R @ R.T = I)."""
        np.random.seed(42)
        N = 80
        grav = np.random.randn(N, 3) * 0.05 + np.array([0.2, 0.1, 9.8])
        acc = grav + np.random.randn(N, 3) * 0.3
        spd = np.linspace(5.0, 15.0, N)
        t = np.linspace(0.0, 8.0, N)

        aligner = VirtualInVehicleAligner()
        R, health = aligner.compute_alignment_from_history(grav, acc, spd, t)

        eye_diff = np.max(np.abs(R @ R.T - np.eye(3)))
        self.assertLess(eye_diff, 1e-5)
        self.assertAlmostEqual(np.linalg.det(R), 1.0, places=4)


if __name__ == '__main__':
    unittest.main()
