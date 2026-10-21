"""
GeoReckon AirIMU-Style Sensor Calibration Layer
================================================
Inspired by: AirIMU — Learning Uncertainty Propagation for Inertial Odometry
             (haleqiu/AirIMU, CMU & University at Buffalo, 2024)

WHAT THIS DOES
--------------
Classical DSP (Butterworth LPF + MAD clipper in vibration_filter.py) removes:
  - High-frequency vibrational noise (>1.5 Hz)
  - Pothole impulse spikes

What it CANNOT remove:
  - Device-specific bias drift (varies with phone model, temperature, mount orientation)
  - Gravity-coupling errors from imperfect virtual alignment
  - Low-frequency systematic errors below the LPF cutoff

This module adds a learned residual correction layer on top of the classical filter:
  corrected = filtered_imu + delta(filtered_imu)
where delta is predicted by a causal TCN+GRU network trained on IO-VNBD ground truth.

INTEGRATION POINT
-----------------
Sits BETWEEN KinematicVibrationFilter.filter_batch() and the window extractor.
Does NOT touch InEKF, KinoNet, or GPS fusion logic.

Input:  (N, 3) clean_accel, (N, 3) clean_gyro  [after vibration filter]
Output: (N, 3) cal_accel,   (N, 3) cal_gyro    [same shape — drop-in replacement]
"""

from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Optional, Tuple

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

logger = logging.getLogger(__name__)


# ==============================================================================
# MODEL ARCHITECTURE — AirIMU-Style Correction Network
# ==============================================================================

class _CausalConv1d(nn.Conv1d):
    """Causal 1D conv: pads only on the left (past), no future leakage."""
    def forward(self, x: torch.Tensor) -> torch.Tensor:
        pad = self.dilation[0] * (self.kernel_size[0] - 1)
        return super().forward(F.pad(x, (pad, 0)))


class _TCNBlock(nn.Module):
    """Dilated residual TCN block with causal convolutions."""
    def __init__(self, channels: int, kernel_size: int = 3, dilation: int = 1):
        super().__init__()
        self.conv1 = _CausalConv1d(channels, channels, kernel_size, dilation=dilation)
        self.conv2 = _CausalConv1d(channels, channels, kernel_size, dilation=dilation)
        self.norm1 = nn.LayerNorm(channels)
        self.norm2 = nn.LayerNorm(channels)
        self.act = nn.SiLU()

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        # x: (B, C, T)
        residual = x
        out = self.act(self.norm1(self.conv1(x).transpose(1, 2)).transpose(1, 2))
        out = self.norm2(self.conv2(out).transpose(1, 2)).transpose(1, 2)
        return self.act(out + residual)


class AirIMUCorrectionNet(nn.Module):
    """
    AirIMU-style neural IMU correction network for GeoReckon.

    Architecture:
        Input: (B, T, 6)  [omega_x, omega_y, omega_z, a_x, a_y, a_z] -- normalized
          |
          v
        Linear projection: 6 -> hidden_dim
          |
          v
        4x TCN residual blocks (dilations 1, 2, 4, 8) -- causal, no future leakage
          |
          v
        1-layer GRU for long-range temporal dependencies
          |
          v
        Correction head: hidden_dim -> 6  (delta_omega, delta_a additive residuals)
        Covariance head: hidden_dim -> 6  (log per-axis uncertainty)
          |
          v
        Output: (B, T, 6) correction + (B, T, 6) log_cov

    Parameter count: ~47k
    """

    IMU_CHANNELS = 6  # [omega_x, omega_y, omega_z, a_x, a_y, a_z]

    def __init__(self, hidden_dim: int = 64, num_tcn_blocks: int = 4):
        super().__init__()
        self.hidden_dim = hidden_dim

        # Input projection (6 -> hidden_dim)
        self.input_proj = nn.Linear(self.IMU_CHANNELS, hidden_dim)

        # TCN encoder: doubling dilations for exponential receptive field
        self.tcn_blocks = nn.ModuleList([
            _TCNBlock(hidden_dim, kernel_size=3, dilation=2 ** i)
            for i in range(num_tcn_blocks)
        ])

        # GRU for long-range temporal context (1 layer)
        self.gru = nn.GRU(
            input_size=hidden_dim,
            hidden_size=hidden_dim,
            num_layers=1,
            batch_first=True,
        )

        # Correction head: predicts ADDITIVE residuals delta_omega, delta_a
        self.correction_head = nn.Sequential(
            nn.Linear(hidden_dim, 32),
            nn.SiLU(),
            nn.Linear(32, self.IMU_CHANNELS),
        )

        # Log-covariance head: per-axis uncertainty
        self.logcov_head = nn.Sequential(
            nn.Linear(hidden_dim, 32),
            nn.SiLU(),
            nn.Linear(32, self.IMU_CHANNELS),
        )

        # Running normalization stats (set during training or from saved checkpoint)
        self.register_buffer("imu_mean", torch.zeros(self.IMU_CHANNELS))
        self.register_buffer("imu_std",  torch.ones(self.IMU_CHANNELS))

    def normalize(self, x: torch.Tensor) -> torch.Tensor:
        """Normalize IMU input with training statistics."""
        return (x - self.imu_mean) / (self.imu_std + 1e-8)

    def forward(
        self,
        x: torch.Tensor,
        h0: Optional[torch.Tensor] = None,
    ) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        """
        Args:
            x:  (B, T, 6)  -- raw or classically-filtered IMU sequence
            h0: (1, B, H)  -- optional GRU hidden state for streaming mode

        Returns:
            correction: (B, T, 6) -- additive correction delta[omega, a]
            log_cov:    (B, T, 6) -- per-axis log uncertainty (clamped to [-8, 3])
            h_out:      (1, B, H) -- updated GRU hidden state
        """
        x_norm = self.normalize(x)                             # (B, T, 6)
        feat = self.input_proj(x_norm).transpose(1, 2)        # (B, H, T)

        for block in self.tcn_blocks:
            feat = block(feat)

        feat = feat.transpose(1, 2)                            # (B, T, H)
        gru_out, h_out = self.gru(feat, h0)                   # (B, T, H)

        correction = self.correction_head(gru_out)             # (B, T, 6)
        log_cov    = torch.clamp(self.logcov_head(gru_out), min=-8.0, max=3.0)

        return correction, log_cov, h_out


# ==============================================================================
# CALIBRATOR WRAPPER — High-Level Interface for GeoReckon
# ==============================================================================

class IMUCalibrator:
    """
    High-level wrapper around AirIMUCorrectionNet.

    Usage in GeoReckon:
        # Load once at startup
        calibrator = IMUCalibrator.load("models/airimu_georeckon.pt")

        # Inside extract_continuous_windows():
        clean_a, clean_w = calibrator.calibrate(clean_a, clean_w)
        # Then proceed -- nothing else changes.
    """

    def __init__(
        self,
        model: AirIMUCorrectionNet,
        device: str = "cpu",
        window_size: int = 200,
        stride: int = 10,
    ):
        self.model = model.to(device).eval()
        self.device = torch.device(device)
        self.window_size = window_size
        self.stride = stride
        self._gru_state: Optional[torch.Tensor] = None

    @classmethod
    def from_scratch(cls, hidden_dim: int = 64, device: str = "cpu", **kwargs) -> "IMUCalibrator":
        """Creates an untrained calibrator (for training from scratch)."""
        model = AirIMUCorrectionNet(hidden_dim=hidden_dim)
        return cls(model, device=device, **kwargs)

    @classmethod
    def load(cls, checkpoint_path: str, device: str = "cpu", **kwargs) -> "IMUCalibrator":
        """
        Loads a trained calibrator from a .pt checkpoint.

        Checkpoint format:
            {
                'model_state': model.state_dict(),
                'imu_mean': np.ndarray (6,),
                'imu_std':  np.ndarray (6,),
                'hidden_dim': int,
            }
        """
        path = Path(checkpoint_path)
        if not path.exists():
            raise FileNotFoundError(f"IMU calibrator checkpoint not found: {path}")

        ckpt = torch.load(path, map_location="cpu", weights_only=False)
        hidden_dim = ckpt.get("hidden_dim", 64)

        model = AirIMUCorrectionNet(hidden_dim=hidden_dim)
        model.load_state_dict(ckpt["model_state"])

        if "imu_mean" in ckpt:
            model.imu_mean = torch.tensor(ckpt["imu_mean"], dtype=torch.float32)
        if "imu_std" in ckpt:
            model.imu_std  = torch.tensor(ckpt["imu_std"],  dtype=torch.float32)

        logger.info("Loaded IMU calibrator from %s (hidden_dim=%d)", path, hidden_dim)
        return cls(model, device=device, **kwargs)

    def save(self, checkpoint_path: str):
        """Saves trained model state and normalization statistics."""
        Path(checkpoint_path).parent.mkdir(parents=True, exist_ok=True)
        torch.save({
            "model_state": self.model.state_dict(),
            "imu_mean":    self.model.imu_mean.cpu().numpy(),
            "imu_std":     self.model.imu_std.cpu().numpy(),
            "hidden_dim":  self.model.hidden_dim,
        }, checkpoint_path)
        logger.info("Saved IMU calibrator to %s", checkpoint_path)

    def calibrate(
        self,
        clean_accel: np.ndarray,
        clean_gyro: np.ndarray,
    ) -> Tuple[np.ndarray, np.ndarray]:
        """
        Drop-in replacement step: applies learned neural correction on top
        of classical DSP-filtered IMU signals.

        Args:
            clean_accel: (N, 3) in m/s^2  -- output of KinematicVibrationFilter
            clean_gyro:  (N, 3) in rad/s  -- output of KinematicVibrationFilter

        Returns:
            cal_accel: (N, 3) -- corrected accelerometer (same shape)
            cal_gyro:  (N, 3) -- corrected gyroscope     (same shape)
        """
        N = len(clean_accel)
        if N < 2:
            return clean_accel.copy(), clean_gyro.copy()

        # Stack: [gyro | accel] matches AirIMU channel order
        imu_raw = np.concatenate([clean_gyro, clean_accel], axis=1).astype(np.float32)

        corrections = self._batch_infer(imu_raw)   # (N, 6)

        cal_gyro  = clean_gyro  + corrections[:, :3]
        cal_accel = clean_accel + corrections[:, 3:]

        return cal_accel.astype(np.float32), cal_gyro.astype(np.float32)

    def _batch_infer(self, imu: np.ndarray) -> np.ndarray:
        """Runs correction network over full (N, 6) array."""
        self.model.eval()
        with torch.no_grad():
            x = torch.from_numpy(imu).unsqueeze(0).to(self.device)  # (1, N, 6)
            correction, _log_cov, _ = self.model(x)                  # (1, N, 6)
            return correction.squeeze(0).cpu().numpy()                # (N, 6)

    def reset_state(self):
        """Clears GRU hidden state (call between drives in streaming mode)."""
        self._gru_state = None

    def update_sample(
        self,
        raw_acc: np.ndarray,
        raw_gyr: np.ndarray,
    ) -> Tuple[np.ndarray, np.ndarray]:
        """
        Online single-sample correction for real-time use.
        Maintains GRU hidden state across calls. Call reset_state() between drives.

        Args:
            raw_acc: (3,) single accelerometer sample in m/s^2
            raw_gyr: (3,) single gyroscope sample in rad/s

        Returns:
            cal_acc: (3,) corrected accelerometer
            cal_gyr: (3,) corrected gyroscope
        """
        imu_sample = np.concatenate([raw_gyr, raw_acc]).astype(np.float32)
        x = torch.from_numpy(imu_sample).unsqueeze(0).unsqueeze(0).to(self.device)  # (1,1,6)

        self.model.eval()
        with torch.no_grad():
            correction, _log_cov, self._gru_state = self.model(x, self._gru_state)
        corr = correction.squeeze().cpu().numpy()   # (6,)

        return (raw_acc + corr[3:]).astype(np.float32), (raw_gyr + corr[:3]).astype(np.float32)

    def fit_normalization(self, accel_data: np.ndarray, gyro_data: np.ndarray):
        """
        Computes and stores per-axis normalization from training data.

        Call this ONCE before training:
            calibrator.fit_normalization(train_accel, train_gyro)

        Args:
            accel_data: (N_total, 3) concatenated training accelerometer
            gyro_data:  (N_total, 3) concatenated training gyroscope
        """
        imu = np.concatenate([gyro_data, accel_data], axis=1).astype(np.float32)
        mean = imu.mean(axis=0)
        std  = imu.std(axis=0) + 1e-8
        self.model.imu_mean = torch.tensor(mean, dtype=torch.float32).to(self.device)
        self.model.imu_std  = torch.tensor(std,  dtype=torch.float32).to(self.device)
        logger.info("Fitted normalization: mean=%s  std=%s", mean.round(4), std.round(4))


# ==============================================================================
# TRAINING FUNCTION
# ==============================================================================

def train_imu_calibrator(
    calibrator: IMUCalibrator,
    train_drives: list,
    val_drives: list,
    output_path: str = "models/airimu_georeckon.pt",
    epochs: int = 30,
    lr: float = 3e-4,
    batch_size: int = 32,
    window_size: int = 200,
    stride: int = 10,
) -> dict:
    """
    Trains the IMU calibrator on IO-VNBD drive data.

    Training objective:
        Minimize dead-reckoning speed error: |v_integrated(calibrated_imu) - v_can_truth|

    Args:
        calibrator:   IMUCalibrator instance (call fit_normalization first)
        train_drives: list of TelematicsDrive (with .a, .w, .v_true_speed populated)
        val_drives:   list of TelematicsDrive for validation
        output_path:  where to save the best checkpoint
        epochs:       training epochs
        lr:           AdamW learning rate
        batch_size:   training batch size
        window_size:  IMU window length in samples fed to the network
        stride:       sliding window stride

    Returns:
        dict with 'train_losses' and 'val_losses' history lists
    """
    from torch.utils.data import DataLoader, TensorDataset

    device = calibrator.device
    model  = calibrator.model.to(device)
    dt = 0.1  # 10 Hz (IO-VNBD sample rate)

    def _build_windows(drives):
        Xs, ys = [], []
        for drv in drives:
            if drv.a is None or drv.w is None:
                continue
            N = min(len(drv.a), len(drv.w))
            speeds = drv.v_true_speed if drv.v_true_speed is not None else drv.speed
            if speeds is None:
                continue
            N = min(N, len(speeds))
            imu = np.concatenate([drv.w[:N], drv.a[:N]], axis=1).astype(np.float32)
            for i in range(0, N - window_size, stride):
                Xs.append(imu[i: i + window_size])
                ys.append(float(speeds[i + window_size - 1]))
        if not Xs:
            return None
        return (
            torch.tensor(np.array(Xs), dtype=torch.float32),
            torch.tensor(np.array(ys), dtype=torch.float32),
        )

    train_data = _build_windows(train_drives)
    val_data   = _build_windows(val_drives)

    if train_data is None:
        raise ValueError(
            "No training windows built. Ensure drives have .a, .w, .v_true_speed."
        )

    train_loader = DataLoader(
        TensorDataset(*train_data), batch_size=batch_size, shuffle=True
    )

    optimizer = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)

    best_val = float("inf")
    history  = {"train_losses": [], "val_losses": []}

    for epoch in range(1, epochs + 1):
        model.train()
        epoch_loss = 0.0

        for x_batch, y_batch in train_loader:
            x_batch = x_batch.to(device)   # (B, W, 6)
            y_batch = y_batch.to(device)   # (B,)

            optimizer.zero_grad()

            correction, log_cov, _ = model(x_batch)    # (B, W, 6)
            cal_imu = x_batch + correction             # apply additive correction

            # Dead-reckoning speed proxy: integrate forward accel (a_z channel index 5)
            v_pred  = cal_imu[:, :, 5].cumsum(dim=1)[:, -1] * dt
            speed_loss = F.huber_loss(v_pred, y_batch, delta=1.0)

            # Regularize log-covariance and correction smoothness
            cov_reg    = 0.01 * (log_cov ** 2).mean()
            smooth_reg = 0.01 * ((correction[:, 1:] - correction[:, :-1]) ** 2).mean()

            loss = speed_loss + cov_reg + smooth_reg
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            epoch_loss += loss.item() * x_batch.size(0)

        scheduler.step()
        epoch_loss /= len(train_data[0])

        val_loss = float("inf")
        if val_data is not None:
            model.eval()
            vx, vy = val_data[0].to(device), val_data[1].to(device)
            with torch.no_grad():
                corr, _, _ = model(vx)
                cal = vx + corr
                v_pred_val = cal[:, :, 5].cumsum(dim=1)[:, -1] * dt
                val_loss = F.huber_loss(v_pred_val, vy).item()

        history["train_losses"].append(epoch_loss)
        history["val_losses"].append(val_loss)

        if val_loss < best_val:
            best_val = val_loss
            calibrator.save(output_path)

        if epoch % 5 == 0 or epoch == 1:
            print(
                f"  Epoch [{epoch:>3}/{epochs}] | "
                f"Train: {epoch_loss:.4f} | Val: {val_loss:.4f} | Best: {best_val:.4f}"
            )

    print(f"Training complete. Best checkpoint: {output_path}  (val={best_val:.4f})")
    return history
