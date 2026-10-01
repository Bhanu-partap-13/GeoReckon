import unittest
import numpy as np
from georeckon.src.vibration_filter import KinematicVibrationFilter


class TestVibrationFilter(unittest.TestCase):
    def test_high_frequency_attenuation(self):
        """Verifies that high frequency vibration (e.g. 25 Hz) is strongly attenuated."""
        fs = 100.0
        filter_eng = KinematicVibrationFilter(sampling_rate=fs, cutoff_hz=3.0)

        t = np.linspace(0.0, 1.0, int(fs))
        # 0.5 Hz kinematic signal + 25 Hz engine noise
        kinematic = np.sin(2.0 * np.pi * 0.5 * t)
        vibration = 2.0 * np.sin(2.0 * np.pi * 25.0 * t)
        raw_sig = kinematic + vibration

        raw_a = np.column_stack([raw_sig, np.zeros(len(t)), np.full(len(t), 9.8)])
        raw_w = np.zeros_like(raw_a)

        clean_a, clean_w, shocks = filter_eng.filter_batch(raw_a, raw_w)

        # Residual between clean and true kinematic signal should be small
        error = np.std(clean_a[15:-15, 0] - kinematic[15:-15])
        self.assertLess(error, 0.20)

    def test_shock_impulse_clamping(self):
        """Verifies that extreme pothole impulses (>3.5 sigma) are clamped."""
        fs = 10.0
        filter_eng = KinematicVibrationFilter(sampling_rate=fs, cutoff_hz=1.5)

        N = 50
        raw_a = np.tile([0.0, 0.0, 9.8], (N, 1))
        # Inject severe shock impulse at index 25
        raw_a[25, 0] = 45.0 # 45 m/s^2 pothole shock
        raw_w = np.zeros_like(raw_a)

        clean_a, clean_w, shocks = filter_eng.filter_batch(raw_a, raw_w)

        self.assertTrue(shocks[25])
        # Cleaned peak must be attenuated significantly below 45 m/s^2
        self.assertLess(np.max(np.abs(clean_a[:, 0])), 12.0)


if __name__ == '__main__':
    unittest.main()
