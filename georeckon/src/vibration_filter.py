"""
GeoReckon Kinematic Vibration Isolation & Shock Suppression Engine
Suppresses high-frequency engine RPM harmonics (>15 Hz) and road roughness noise,
clamping Dirac-delta pothole shocks while preserving true vehicle kinematic transients (0.05 - 1.5 Hz).
"""

import numpy as np
from scipy import signal


class KinematicVibrationFilter:
    def __init__(self, sampling_rate: float = 10.0, cutoff_hz: float = 1.5):
        self.fs = sampling_rate
        self.cutoff_hz = cutoff_hz

        # Design Butterworth lowpass filter
        nyq = 0.5 * self.fs
        normal_cutoff = min(cutoff_hz / nyq, 0.95)
        self.b, self.a = signal.butter(2, normal_cutoff, btype='low', analog=False)

        self.buffer_size = int(sampling_rate * 2.0)
        self.accel_buffer = []
        self.gyro_buffer = []

    def filter_batch(self, raw_accel: np.ndarray, raw_gyro: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        """
        Batch zero-phase forward-backward filtering of IMU arrays.

        Args:
            raw_accel: (N, 3) in m/s^2
            raw_gyro: (N, 3) in rad/s

        Returns:
            clean_accel: (N, 3)
            clean_gyro: (N, 3)
            shocks: (N,) boolean mask of detected impulse shocks
        """
        N = len(raw_accel)
        if N < 12:
            return raw_accel.copy(), raw_gyro.copy(), np.zeros(N, dtype=bool)

        clean_accel = np.copy(raw_accel)
        clean_gyro = np.copy(raw_gyro)
        shocks = np.zeros(N, dtype=bool)

        # Stage 1: MAD outlier suppression for pothole/speedbreaker impulses
        for i in range(3):
            med = np.median(clean_accel[:, i])
            mad = np.median(np.abs(clean_accel[:, i] - med)) + 1e-6
            thresh = 4.0 * 1.4826 * mad
            outliers = np.abs(clean_accel[:, i] - med) > thresh
            shocks |= outliers
            clean_accel[outliers, i] = med + np.sign(clean_accel[outliers, i] - med) * thresh

        # Stage 2: Zero-phase Butterworth low-pass filter
        filt_accel = np.zeros_like(clean_accel)
        filt_gyro = np.zeros_like(clean_gyro)
        for i in range(3):
            filt_accel[:, i] = signal.filtfilt(self.b, self.a, clean_accel[:, i])
            filt_gyro[:, i] = signal.filtfilt(self.b, self.a, clean_gyro[:, i])

        return filt_accel, filt_gyro, shocks

    def update_sample(self, raw_acc: np.ndarray, raw_gyr: np.ndarray) -> tuple[np.ndarray, np.ndarray, bool]:
        """Online single-sample sliding window update for streaming telematics."""
        self.accel_buffer.append(raw_acc.copy())
        self.gyro_buffer.append(raw_gyr.copy())

        if len(self.accel_buffer) > self.buffer_size:
            self.accel_buffer.pop(0)
            self.gyro_buffer.pop(0)

        if len(self.accel_buffer) < 12:
            return raw_acc.copy(), raw_gyr.copy(), False

        fa, fg, sk = self.filter_batch(np.array(self.accel_buffer), np.array(self.gyro_buffer))
        return fa[-1], fg[-1], bool(sk[-1])
