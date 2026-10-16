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
        Solves for R_b_to_v using pre-outage telematics window via Dynamic PCA.

        ALIGNMENT ALGORITHM (Fix #3):
            Step 1: Extract vertical unit vector z_b = f_static / |f_static| from gravity.
            Step 2: Project linear acceleration onto horizontal plane.
            Step 3: PCA on C_h = (1/M) sum(a_horiz @ a_horiz^T) -> dominant
                    eigenvector u_1 (max eigenvalue) locks vehicle forward axis x_b.
            Step 4: Disambiguate +/-x_b using GPS speed-change correlation with
                    duplicate-sample filtering and a robust IMU-only fallback.
            Step 5: Construct y_b = z_b x x_b via right-hand rule; re-orthogonalize.
            Step 6: Assemble R_b_to_v = [x_b; y_b; z_b]^T and enforce det(R) = +1.

        Args:
            gravity_history: (N, 3) gravity vector in phone frame
            accel_history:   (N, 3) total acceleration in phone frame
            speed_history:   (N,) vehicle speed in m/s (GPS, interpolated onto IMU times)
            time_history:    (N,) timestamps in seconds

        Returns:
            R_b_to_v: (3, 3) proper rotation matrix, det = +1
            health:   float confidence metric in [0, 1]
        """
        if len(accel_history) < 20:
            return np.eye(3), 0.0

        # ---- Step 1: Vertical Z-axis from gravity reaction vector -----------
        # z_b = median(gravity) / |median(gravity)|
        # Median is used to suppress vibration transients in the static estimate.
        up_vec = np.median(gravity_history, axis=0)
        norm_up = np.linalg.norm(up_vec)
        if norm_up < 1e-4:
            up_vec = np.array([0.0, 0.0, 1.0])
        else:
            up_vec = up_vec / norm_up

        # ---- Step 2: Horizontal linear acceleration -------------------------
        lin_acc = accel_history - gravity_history
        # Project out vertical component to isolate the horizontal driving plane
        horiz_acc = lin_acc - (lin_acc @ up_vec)[:, None] * up_vec

        # ---- Step 3: PCA on horizontal acceleration -------------------------
        # C_h = (1/M) sum(a_horiz @ a_horiz^T); dominant eigenvector = x_b
        # The primary variance in a ground vehicle is longitudinal accel/braking.
        cov = np.cov(horiz_acc, rowvar=False)
        eigvals, eigvecs = np.linalg.eigh(cov)

        # Largest eigenvalue -> longitudinal axis
        fwd_vec = eigvecs[:, -1]
        # Re-project onto horizontal plane (PCA eigenvec may have small vertical leak)
        fwd_vec -= (fwd_vec @ up_vec) * up_vec
        norm_fwd = np.linalg.norm(fwd_vec)
        if norm_fwd < 1e-4:
            # Degenerate: no clear longitudinal variance; orthogonal fallback
            arb = np.array([1.0, 0.0, 0.0]) if abs(up_vec[0]) < 0.8 else np.array([0.0, 1.0, 0.0])
            fwd_vec = np.cross(arb, up_vec)
            fwd_vec /= np.linalg.norm(fwd_vec)
        else:
            fwd_vec /= norm_fwd

        # ---- Step 4: Disambiguate +/-fwd_vec (forward vs backward) ---------
        #
        # PCA gives the axis of max variance but cannot determine sign (+/-).
        # We correlate projected IMU acceleration with GPS-derived acceleration.
        #
        # FIX #3 - Two bugs existed in the original code:
        #
        #   BUG A: `dt < 1.0` (strict less-than) excluded genuine 1 Hz GPS
        #          samples where dt is exactly 1.000 s. Combined with typical
        #          10 Hz IMU (dt ~= 0.1 s), the ONLY samples with dt near 1.0 s
        #          were the real GPS-update intervals where dv is meaningful.
        #          The 9 intermediate IMU samples had dv = 0 (GPS sample-hold),
        #          so they flooded ref_acc with zeros and dominated the correlation.
        #
        #   BUG B: `corr < 0` flipped on ANY negative correlation including
        #          spurious near-zero values from the GPS duplicate problem above.
        #          Observed Deltav = 0.018..0.120 m/s meant corr was near-random.
        #
        # FIXES:
        #   * `dt < 1.1` admits 1 Hz GPS intervals (GPS timestamp jitter <= 100 ms)
        #   * `|dv| > 0.03` filters sample-hold duplicates (unchanged GPS reading)
        #   * `corr < -0.25` only flips on STRONG negative correlation
        #   * IMU-only fallback when GPS data too sparse to form correlation
        dt = np.diff(time_history)
        dv = np.diff(speed_history)

        # Genuine speed changes only -- not GPS sample-hold duplicates
        valid = (dt > 0.02) & (dt < 1.1) & (np.abs(dv) > 0.03)

        if np.sum(valid) >= 3:
            ref_acc = dv[valid] / dt[valid]
            proj_acc = horiz_acc[:-1][valid] @ fwd_vec
            corr = np.corrcoef(proj_acc, ref_acc)[0, 1]
            # Only flip on STRONGLY negative correlation.
            # Threshold -0.25 (not 0) prevents spurious reversals from noisy GPS.
            if np.isfinite(corr) and corr < -0.25:
                fwd_vec = -fwd_vec
        else:
            # GPS data too sparse or all duplicates: IMU-only fallback.
            # During calibration the vehicle is moving forward. The mean
            # projection of forward-phase linear acceleration onto the candidate
            # forward axis should be near-zero (cruise) or lightly negative
            # (driver braking into tunnel). If strongly negative AND vehicle is
            # clearly moving, PCA axis is reversed -- flip it.
            n_tail = min(50, len(horiz_acc))
            mean_proj = float(np.mean(horiz_acc[-n_tail:] @ fwd_vec))
            last_speed = float(np.mean(speed_history[-5:])) if len(speed_history) >= 5 else 0.0
            if last_speed > 1.0 and mean_proj < -0.5:
                fwd_vec = -fwd_vec

        # ---- Step 5: Lateral Y-axis via right-hand rule --------------------
        # Vehicle frame: X = Forward, Y = Left, Z = Up
        lat_vec = np.cross(up_vec, fwd_vec)
        norm_lat = np.linalg.norm(lat_vec)
        if norm_lat < 1e-4:
            # Degenerate: fwd and up collinear; arbitrary perpendicular
            arb = np.array([0.0, 1.0, 0.0]) if abs(up_vec[1]) < 0.8 else np.array([1.0, 0.0, 0.0])
            lat_vec = np.cross(up_vec, arb)
        lat_vec /= np.linalg.norm(lat_vec)

        # Re-orthogonalize forward vector for strict orthogonality
        fwd_vec = np.cross(lat_vec, up_vec)
        fwd_vec /= np.linalg.norm(fwd_vec)

        # ---- Step 6: Assemble R_b_to_v and enforce det(R) = +1 -------------
        # Spec requirement: det(R_b^v) = +1 (proper rotation, no reflection).
        # A reflection (det = -1) mirrors the vehicle frame, swapping left/right
        # acceleration -- fatal for lateral NHC and trajectory integration.
        R_candidate = np.row_stack([fwd_vec, lat_vec, up_vec])
        if np.linalg.det(R_candidate) < 0:
            # Flip lateral axis to restore right-handed frame, re-derive forward
            lat_vec = -lat_vec
            fwd_vec = np.cross(lat_vec, up_vec)
            fwd_vec /= np.linalg.norm(fwd_vec)
            R_candidate = np.row_stack([fwd_vec, lat_vec, up_vec])

        self.R_b_to_v = R_candidate
        self.is_calibrated = True

        # Confidence: ratio of longitudinal to lateral variance.
        # High ratio -> clear forward-backward driving pattern -> reliable alignment.
        ratio = float(eigvals[-1] / max(eigvals[-2], 1e-6))
        self.alignment_health = float(np.clip((ratio - 1.0) / 4.0, 0.05, 1.0))

        return self.R_b_to_v, self.alignment_health

    def transform(self, vector_phone: np.ndarray) -> np.ndarray:
        """Transforms a 3D vector from body frame to vehicle frame."""
        return self.R_b_to_v @ vector_phone
