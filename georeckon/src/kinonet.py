"""
GeoReckon KinoNet Neural Network Architecture
Causal temporal 1D convolutional network with receptive field covering 3.0 seconds (30 steps at 10 Hz).
Produces forward speed estimate, heteroscedastic uncertainty, and stop classification.
Uses Huber objective to prevent variance collapse.
"""

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F


class CausalConv(nn.Conv1d):
    """Causal convolution padding the past to ensure strict temporal causality."""
    def forward(self, x):
        pad_size = self.dilation[0] * (self.kernel_size[0] - 1)
        return super().forward(F.pad(x, (pad_size, 0)))


class KinoNet(nn.Module):
    def __init__(self, in_channels: int = 9, hidden_dim: int = 32):
        super().__init__()
        self.net = nn.Sequential(
            CausalConv(in_channels, hidden_dim, kernel_size=3),
            nn.SiLU(),
            CausalConv(hidden_dim, hidden_dim, kernel_size=3, dilation=2),
            nn.SiLU(),
            CausalConv(hidden_dim, hidden_dim, kernel_size=3, dilation=4),
            nn.SiLU(),
            CausalConv(hidden_dim, hidden_dim, kernel_size=3, dilation=8),
            nn.SiLU()
        )
        # Head: [speed_mu, log_variance, stop_logit]
        self.head = nn.Linear(hidden_dim, 3)

        # Buffers for running feature normalization
        self.register_buffer('mean', torch.zeros(1, in_channels, 1))
        self.register_buffer('scale', torch.ones(1, in_channels, 1))

    def sequence(self, x: torch.Tensor):
        norm_x = (x - self.mean) / self.scale
        features = self.net(norm_x).transpose(1, 2)
        h = self.head(features)
        speed = 50.0 * torch.sigmoid(h[..., 0])
        logvar = torch.clamp(h[..., 1], -2.0, 5.0)
        stop_logit = h[..., 2]
        return speed, logvar, stop_logit

    def forward(self, x: torch.Tensor):
        speed_seq, logvar_seq, stop_seq = self.sequence(x)
        return speed_seq[:, -1], logvar_seq[:, -1], stop_seq[:, -1]


def compute_huber_objective(model: KinoNet, x: torch.Tensor, y: torch.Tensor) -> torch.Tensor:
    """
    Robust objective function that avoids heteroscedastic variance collapse.
    Combines Huber regression loss on speed and BCE loss on vehicle standstill.
    """
    speed, logvar, stop_logit = model(x)
    huber = F.huber_loss(speed, y, delta=1.0)
    stop_target = (y < 0.3).float()
    bce = F.binary_cross_entropy_with_logits(stop_logit, stop_target)
    # Variance regularization: penalize unnecessary variance expansion
    var_reg = 0.05 * torch.mean(torch.exp(logvar) + logvar)
    return huber + 0.2 * bce + var_reg


def predict_batch(model: KinoNet, x_array: np.ndarray, batch_size: int = 1024) -> np.ndarray:
    """Inference over numpy array with batching."""
    model.eval()
    outputs = []
    with torch.no_grad():
        for start in range(0, len(x_array), batch_size):
            bx = torch.from_numpy(x_array[start:start+batch_size])
            speed, logvar, stop_logit = model(bx)
            variance = torch.exp(logvar)
            stop_prob = torch.sigmoid(stop_logit)
            chunk = np.column_stack([
                speed.cpu().numpy(),
                variance.cpu().numpy(),
                stop_prob.cpu().numpy()
            ])
            outputs.append(chunk)
    return np.concatenate(outputs, axis=0) if outputs else np.zeros((0, 3))
