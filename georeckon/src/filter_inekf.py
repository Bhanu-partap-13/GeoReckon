"""
GeoReckon Invariant Extended Kalman Filter (InEKF) on SE_2(3)
Implements right-invariant error dynamics on the Double Direct Spatial Isometry group SE_2(3),
with additive sensor bias augmentation (b_g, b_a).
Features:
- Closed-loop group retraction and reset Jacobian.
- Joseph-form covariance measurement updates.
- Non-Holonomic Constraints (NHC) in vehicle chassis frame.
- Adaptive Huber covariance gating preventing filter lockout.
"""

from dataclasses import dataclass, field
import numpy as np
from .geometry import skew, so3_exp, left_jac

# Fixed chi-squared gate threshold at 99th percentile for 3-DOF (the highest-DOF
# update used in this filter). Using a DOF-dependent threshold was the original
# lockout bug: for 1-DOF (chi2.ppf(0.99,1)=6.63) and 2-DOF (9.21) updates,
# a routine 1.5 m/s accelerometer bias spike would push NIS above the threshold
# on every step, causing V to inflate without bound and soft-freezing those
# measurement channels. A fixed 11.3 is the conservative limit that only fires
# on genuine shock/outlier events, not normal MEMS drift.
_CHI2_GATE_99 = 11.3

GRAVITY_WORLD = np.array([0.0, 0.0, -9.80665], dtype=np.float64)


@dataclass
class InEKFState:
    R: np.ndarray = field(default_factory=lambda: np.eye(3, dtype=np.float64))
    v: np.ndarray = field(default_factory=lambda: np.zeros(3, dtype=np.float64))
    p: np.ndarray = field(default_factory=lambda: np.zeros(3, dtype=np.float64))
    bg: np.ndarray = field(default_factory=lambda: np.zeros(3, dtype=np.float64))
    ba: np.ndarray = field(default_factory=lambda: np.zeros(3, dtype=np.float64))


def retract(state: InEKFState, dx: np.ndarray) -> InEKFState:
    """
    Left-group action retraction on SE_2(3) x R^6:
    R_new = Exp(dx_theta) @ R
    v_new = Exp(dx_theta) @ v + J_l @ dx_v
    p_new = Exp(dx_theta) @ p + J_l @ dx_p
    bg_new = bg + dx_bg
    ba_new = ba + dx_ba
    """
    E = so3_exp(dx[:3])
    J = left_jac(dx[:3])
    return InEKFState(
        R=E @ state.R,
        v=E @ state.v + J @ dx[3:6],
        p=E @ state.p + J @ dx[6:9],
        bg=state.bg + dx[9:12],
        ba=state.ba + dx[12:15]
    )


def compute_reset_jacobian(dx: np.ndarray) -> np.ndarray:
    """Computes the reset Jacobian for right-invariant error."""
    ad = np.zeros((15, 15), dtype=np.float64)
    A = skew(dx[:3])
    for k in (0, 3, 6):
        ad[k:k+3, k:k+3] = A
    ad[3:6, :3] = skew(dx[3:6])
    ad[6:9, :3] = skew(dx[6:9])

    J = np.eye(15, dtype=np.float64)
    term = np.eye(15, dtype=np.float64)
    for n in range(1, 16):
        term = term @ ad / (n + 1)
        J += term
    return J


class InEKF:
    def __init__(self, initial_state: InEKFState = None):
        self.state = initial_state or InEKFState()
        # Covariance P: 3 rot, 3 vel, 3 pos, 3 bg, 3 ba
        self.P = np.diag(np.r_[
            np.full(3, 0.05**2), # Rotation: 0.05 rad
            np.full(3, 1.0**2),  # Velocity: 1.0 m/s
            np.full(3, 2.0**2),  # Position: 2.0 m
            np.full(3, 0.01**2), # Gyro bias: 0.01 rad/s
            np.full(3, 0.15**2)  # Accel bias: 0.15 m/s^2
        ])
        self.inflated = 0   # count of updates where V was inflated (NOT rejected)
        self.accepted = 0   # count of updates that passed the gate without inflation

    def predict(self, a_meas: np.ndarray, w_meas: np.ndarray, dt: float, noise_inflation: float = 1.0):
        """
        Propagates SE_2(3) state and covariance across time step dt.
        """
        if not (0.001 < dt < 1.0):
            return

        s = self.state
        R, v, p = s.R, s.v, s.p

        # Unbiased IMU inputs
        w_clean = w_meas - s.bg
        a_clean = a_meas - s.ba

        # State transition matrix F (invariant continuous-time dynamics)
        F = np.zeros((15, 15), dtype=np.float64)
        F[3:6, :3] = skew(GRAVITY_WORLD)
        F[6:9, 3:6] = np.eye(3)
        F[:3, 9:12] = -R
        F[3:6, 9:12] = -skew(v) @ R
        F[6:9, 9:12] = -skew(p) @ R
        F[3:6, 12:15] = -R

        # Noise transport matrix L
        L = np.zeros((15, 12), dtype=np.float64)
        L[:3, :3] = -R
        L[3:6, :3] = -skew(v) @ R
        L[6:9, :3] = -skew(p) @ R
        L[3:6, 3:6] = -R
        L[9:12, 6:9] = np.eye(3)
        L[12:15, 9:12] = np.eye(3)

        # Process noise covariance spectral density Q
        q = np.repeat([0.015**2, 0.20**2, 0.0003**2, 0.003**2], 3) * noise_inflation

        # Discrete-time transition matrix Phi
        Phi = np.eye(15) + F * dt + 0.5 * (F @ F) * (dt ** 2)
        B = (np.eye(15) + 0.5 * F * dt) @ L
        Q_discrete = (B * q) @ B.T * dt

        # Propagate covariance
        self.P = Phi @ self.P @ Phi.T + Q_discrete
        self.P = 0.5 * (self.P + self.P.T)

        # State kinematics propagation
        rot_dt = so3_exp(w_clean * (dt / 2.0))
        acc_world = R @ rot_dt @ a_clean + GRAVITY_WORLD

        s.p = p + v * dt + 0.5 * acc_world * (dt ** 2)
        s.v = v + acc_world * dt
        s.R = R @ so3_exp(w_clean * dt)

    def update_measurement(self, z: np.ndarray, h: np.ndarray, H: np.ndarray, V: np.ndarray) -> bool:
        """
        Joseph-form Kalman measurement update with Adaptive Huber covariance inflation.
        Prevents irreversible filter lockout when shocks or bias shifts occur.

        GATING STRATEGY (Fix #2):
            Instead of hard-rejecting outlier measurements (which permanently locks the
            filter), we use Adaptive Huber Covariance Inflation:

            1. Compute NIS = innovation^T @ S^{-1} @ innovation
            2. If NIS > _CHI2_GATE_99 (=11.3): scale V proportionally so the
               normalized residual is brought back to the gate boundary.
               → The update PROCEEDS with a down-weighted (softer) correction.
            3. If NIS <= 11.3: update proceeds normally.

            The scale factor is: V_adaptive = V_nominal * (NIS / 11.3)
            This matches the spec exactly and is DOF-independent, preventing
            premature inflation on 1-DOF / 2-DOF velocity updates.
        """
        residual = np.asarray(z) - np.asarray(h)
        S = H @ self.P @ H.T + V

        # Normalized Innovation Squared (NIS)
        try:
            nis = float(residual @ np.linalg.solve(S, residual))
        except np.linalg.LinAlgError:
            return False

        # Adaptive Huber Covariance Inflation: smoothly down-weight instead of hard-reject
        if nis > _CHI2_GATE_99:
            scale_factor = nis / _CHI2_GATE_99
            V_adaptive = V * scale_factor
            S = H @ self.P @ H.T + V_adaptive
            self.inflated += 1
        else:
            V_adaptive = V
            self.accepted += 1

        # Kalman gain computation
        try:
            K = np.linalg.solve(S, H @ self.P).T
        except np.linalg.LinAlgError:
            return False

        dx = K @ residual

        # Joseph stabilized covariance update: P = (I - KH) P (I - KH)^T + K V K^T
        A = np.eye(15) - K @ H
        P_updated = A @ self.P @ A.T + K @ V_adaptive @ K.T

        # Retraction on Lie group
        self.state = retract(self.state, dx)
        reset_J = compute_reset_jacobian(dx)
        self.P = reset_J @ P_updated @ reset_J.T
        self.P = 0.5 * (self.P + self.P.T)

        return True

    def update_chassis_velocity(self, v_chassis_target: np.ndarray, R_b_to_v: np.ndarray, variances: np.ndarray, axes=(0, 1, 2)) -> bool:
        """
        Applies Non-Holonomic Constraints (NHC) and forward speed updates in the chassis frame:
        v_chassis = R_b_to_v @ R_w_to_b @ v_world = R_b_to_v @ R.T @ v
        """
        B = R_b_to_v @ self.state.R.T
        h_full = B @ self.state.v

        H_full = np.zeros((3, 15), dtype=np.float64)
        H_full[:, 3:6] = B

        ax = list(axes)
        return self.update_measurement(
            z=np.asarray(v_chassis_target)[ax],
            h=h_full[ax],
            H=H_full[ax],
            V=np.diag(np.asarray(variances)[ax])
        )

    def update_zupt(self, variance: float = 0.01):
        """Zero velocity update during vehicle standstill."""
        H = np.zeros((3, 15), dtype=np.float64)
        H[:, :3] = -skew(self.state.v)
        H[:, 3:6] = np.eye(3)
        return self.update_measurement(np.zeros(3), self.state.v, H, np.eye(3) * variance)

    def get_position_covariance_2d(self) -> np.ndarray:
        """Returns 2x2 horizontal position covariance matrix."""
        H = np.zeros((2, 15), dtype=np.float64)
        H[0, :3] = -skew(self.state.p)[0]
        H[0, 6:9] = [1.0, 0.0, 0.0]
        H[1, :3] = -skew(self.state.p)[1]
        H[1, 6:9] = [0.0, 1.0, 0.0]
        return H @ self.P @ H.T
