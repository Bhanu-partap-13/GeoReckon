"""
GeoReckon Autonomous Dynamic Virtual In-Vehicle Alignment Engine
Estimates the 3D rotation matrix R_b_to_v mapping smartphone IMU body frame
to vehicle chassis coordinates:
    v_vehicle = R_b_to_v @ v_phone
    a_vehicle = R_b_to_v @ a_phone

Eliminates the degenerate sparse GPS check of prior prototypes by performing
Principal Component Analysis (PCA) on horizontal acceleration during dynamic phases.
"""

import numpy as np


class VirtualInVehicleAligner:
    def __init__(self):
        self.R_b_to_v = np.eye(3, dtype=np.float64)
        self.is_calibrated = False
        self.alignment_health = 0.0

    def compute_alignment_from_history(self,
                                      gravity_history: np.ndarray,
                                      accel_history: np.ndarray,
                                      speed_history: np.ndarray,
                                      time_history: np.ndarray) -> tuple[np.ndarray, float]:
        """
        Solves for R_b_to_v using pre-outage telematics window.

        Args:
            gravity_history: (N, 3) gravity vector in phone frame
            accel_history: (N, 3) total acceleration in phone frame
            speed_history: (N,) vehicle speed in m/s
            time_history: (N,) timestamps in seconds

        Returns:
            R_b_to_v: (3, 3) orthogonal rotation matrix
            health: float confidence metric in [0, 1]
        """
        if len(accel_history) < 20:
            return np.eye(3), 0.0

        # Step 1: Vertical Z-axis from gravity reaction vector
        up_vec = np.median(gravity_history, axis=0)
        norm_up = np.linalg.norm(up_vec)
        if norm_up < 1e-4:
            up_vec = np.array([0.0, 0.0, 1.0])
        else:
            up_vec = up_vec / norm_up

        # Step 2: Linear horizontal acceleration
        lin_acc = accel_history - gravity_history
        # Project out vertical component to isolate horizontal plane
        horiz_acc = lin_acc - (lin_acc @ up_vec)[:, None] * up_vec

        # Step 3: Principal Component Analysis (PCA) on horizontal acceleration
        # The primary variance in a ground vehicle is longitudinal acceleration/braking
        cov = np.cov(horiz_acc, rowvar=False)
        eigvals, eigvecs = np.linalg.eigh(cov)

        # Largest eigenvalue corresponds to vehicle longitudinal axis
        fwd_vec = eigvecs[:, -1]
        fwd_vec -= (fwd_vec @ up_vec) * up_vec
        norm_fwd = np.linalg.norm(fwd_vec)
        if norm_fwd < 1e-4:
            # Fallback orthogonal vector
            arb = np.array([1.0, 0.0, 0.0]) if abs(up_vec[0]) < 0.8 else np.array([0.0, 1.0, 0.0])
            fwd_vec = np.cross(arb, up_vec)
            fwd_vec /= np.linalg.norm(fwd_vec)
        else:
            fwd_vec /= norm_fwd

        # Step 4: Disambiguate forward vs backward (+X vs -X) using speed changes
        dt = np.diff(time_history)
        dv = np.diff(speed_history)
        valid = (dt > 0.02) & (dt < 1.0)
        if np.sum(valid) > 5:
            ref_acc = dv[valid] / dt[valid]
            proj_acc = horiz_acc[:-1][valid] @ fwd_vec
            corr = np.corrcoef(proj_acc, ref_acc)[0, 1]
            if np.isfinite(corr) and corr < 0:
                fwd_vec = -fwd_vec

        # Step 5: Lateral Y-axis via right-hand rule
        # Vehicle frame: X = Forward, Y = Left, Z = Up
        lat_vec = np.cross(up_vec, fwd_vec)
        lat_vec /= np.linalg.norm(lat_vec)

        # Re-orthogonalize forward vector
        fwd_vec = np.cross(lat_vec, up_vec)
        fwd_vec /= np.linalg.norm(fwd_vec)

        self.R_b_to_v = np.row_stack([fwd_vec, lat_vec, up_vec])
        self.is_calibrated = True

        # Confidence: ratio of longitudinal to lateral variance
        ratio = float(eigvals[-1] / max(eigvals[-2], 1e-6))
        self.alignment_health = float(np.clip((ratio - 1.0) / 4.0, 0.05, 1.0))

        return self.R_b_to_v, self.alignment_health

    def transform(self, vector_phone: np.ndarray) -> np.ndarray:
        """Transforms a 3D vector from body frame to vehicle frame."""
        return self.R_b_to_v @ vector_phone
