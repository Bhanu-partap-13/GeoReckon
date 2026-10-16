"""
GeoReckon KinoNet-R2 AI Model Architecture
Verbatim extraction of KinoNetR2 from InertiaX/georeckon_pipeline.py (lines 1100-1247).
Used in strict shadow mode for speed and variance inference.
"""

from typing import Tuple
import torch
import torch.nn as nn
import torch.nn.functional as F


class Config:
    INPUT_CHANNELS = 6
    HIDDEN_DIM = 128
    DROPOUT_RATE = 0.2


class KinoNetR2(nn.Module):
    """
    KinoNet-R2: AI model for vehicle speed prediction with uncertainty.

    ARCHITECTURE:
        Input (6, 20) → Conv1D blocks → Global Avg Pool → FC → (μ, log_σ²)

    WHY 1D-CNN?
        - FASTER than LSTM/Transformer for short windows (20 steps)
        - Captures local temporal patterns (braking, turning, bumps)
        - LIGHTWEIGHT enough for smartphone edge deployment
        - 1D convolutions slide along the time axis, detecting temporal features

    WHY NOT TRANSFORMER?
        Transformers shine on LONG sequences (100+ steps).
        Our 20-step window is too short — a Transformer would overfit.
        1D-CNN is the sweet spot for ≤ 50 timesteps.

    DROPOUT FOR EPISTEMIC UNCERTAINTY:
        During training AND inference, we keep dropout active.
        Running N forward passes gives N different predictions.
        Variance of those N predictions = epistemic uncertainty
        (uncertainty about the model itself, not the data).
    """

    def __init__(
        self,
        in_channels: int = Config.INPUT_CHANNELS,
        hidden_dim: int = Config.HIDDEN_DIM,
        dropout_rate: float = Config.DROPOUT_RATE
    ):
        super().__init__()

        # ---- Convolutional Feature Extractor ----
        # WHY 3 layers? Each layer doubles receptive field:
        #   Layer 1: kernel=3, sees 3 timesteps (0.3s)
        #   Layer 2: kernel=3, sees 5 timesteps (0.5s)
        #   Layer 3: kernel=3, sees 7 timesteps (0.7s)
        # Combined with pooling: sees ~15 timesteps (1.5s) — covers full braking event

        self.conv1 = nn.Conv1d(in_channels, hidden_dim // 2, kernel_size=3, padding=1)
        self.bn1 = nn.BatchNorm1d(hidden_dim // 2)

        self.conv2 = nn.Conv1d(hidden_dim // 2, hidden_dim, kernel_size=3, padding=1)
        self.bn2 = nn.BatchNorm1d(hidden_dim)

        self.conv3 = nn.Conv1d(hidden_dim, hidden_dim, kernel_size=3, padding=1)
        self.bn3 = nn.BatchNorm1d(hidden_dim)

        # ---- Dropout (for MC Dropout uncertainty) ----
        self.dropout = nn.Dropout(dropout_rate)

        # ---- Prediction Head ----
        # Output: 2 values — [predicted_speed (μ), predicted_log_variance (log_σ²)]
        self.fc1 = nn.Linear(hidden_dim, hidden_dim // 2)
        self.fc_out = nn.Linear(hidden_dim // 2, 2)  # 2 outputs: μ and log_σ²

    def forward(self, x: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor]:
        """
        Forward pass.

        Args:
            x: (batch, 6, 20) — 6 IMU channels × 20 timesteps

        Returns:
            mu: (batch,) — predicted forward speed in m/s
            log_var: (batch,) — predicted log-variance (confidence)
        """
        # Conv block 1
        h = F.relu(self.bn1(self.conv1(x)))
        h = self.dropout(h)

        # Conv block 2
        h = F.relu(self.bn2(self.conv2(h)))
        h = self.dropout(h)

        # Conv block 3
        h = F.relu(self.bn3(self.conv3(h)))
        h = self.dropout(h)

        # Global average pooling: (batch, hidden, time) → (batch, hidden)
        # WHY GAP? Makes the model input-length-agnostic.
        # At deployment, window size could vary slightly without breaking.
        h = h.mean(dim=2)

        # Fully connected prediction
        h = F.relu(self.fc1(h))
        h = self.dropout(h)
        out = self.fc_out(h)  # (batch, 2)

        mu = out[:, 0]               # Predicted speed
        log_var = out[:, 1]           # Predicted log-variance

        # Clamp log_var to physically bounded uncertainty range.
        # WHY BOUNDS?
        #   min=-7.0 → σ_min = exp(-7/2) ≈ 0.03 m/s (never over-confident)
        #   max=+5.0 → σ_max = exp(5/2)  ≈ 12.2 m/s (physically plausible vehicle speed noise)
        # PREVIOUSLY min=-10, max=+10 allowed σ up to ~148 m/s, which let the network
        # cheat during training by driving log_var → +10 to zero out the Huber gradient,
        # causing it to predict only the dataset mean (~10 m/s) with no real-time correlation.
        log_var = torch.clamp(log_var, min=-7.0, max=5.0)

        return mu, log_var

    def predict_with_uncertainty(
        self, x: torch.Tensor, n_mc_samples: int = 20
    ) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        """
        Monte Carlo Dropout inference for epistemic + aleatoric uncertainty.

        WHAT: Run N forward passes with dropout active, collect statistics.

        PHYSICS OF UNCERTAINTY:
            Total uncertainty = Aleatoric (data noise) + Epistemic (model ignorance)

            Aleatoric σ²_data = mean of predicted σ² across MC samples
            Epistemic σ²_model = variance of predicted μ across MC samples
            Total σ²_total = σ²_data + σ²_model

        WHY THIS MATTERS FOR THE FILTER:
            If total uncertainty is high → Kalman gain K decreases
            → Filter relies more on physics (NHC constraints)
            → Position doesn't jump around erratically

        Args:
            x: (batch, 6, 20) input
            n_mc_samples: Number of MC forward passes

        Returns:
            mean_speed: (batch,) — average predicted speed
            aleatoric_var: (batch,) — data uncertainty
            epistemic_var: (batch,) — model uncertainty
        """
        self.train()  # Keep dropout ACTIVE during inference
        mu_samples = []
        var_samples = []

        with torch.no_grad():
            for _ in range(n_mc_samples):
                mu, log_var = self.forward(x)
                mu_samples.append(mu)
                var_samples.append(torch.exp(log_var))

        mu_stack = torch.stack(mu_samples)     # (N, batch)
        var_stack = torch.stack(var_samples)    # (N, batch)

        mean_speed = mu_stack.mean(dim=0)           # Average prediction
        aleatoric_var = var_stack.mean(dim=0)        # Average predicted variance
        epistemic_var = mu_stack.var(dim=0)           # Variance of predictions

        return mean_speed, aleatoric_var, epistemic_var

