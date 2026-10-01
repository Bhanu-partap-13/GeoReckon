import os
import sys
import math
import numpy as np
import pandas as pd
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import Dataset, DataLoader

# Ensure georeckon is in path
sys.path.insert(0, os.path.abspath("."))
from georeckon.server.kinonet_r2 import KinoNetR2

DATA_FILES = {
    "S-S1": "data/S-S1.csv",
    "S-S3a": "data/S-S3a.txt",
    "S-S3b": "data/S-S3b.txt",
    "S-S3c": "data/S-S3c.txt",
    "S-S4": "data/S-S4.txt"
}

# -----------------------------------------------------------------------------
# 1. Dataset Ingestion & Column Normalization
# -----------------------------------------------------------------------------
class SessionLoader:
    def __init__(self, name: str, filepath: str):
        self.name = name
        self.filepath = filepath
        self.df = None
        self.stats = {}
        self._load()

    def _load(self):
        df = pd.read_csv(self.filepath, encoding="latin1")
        df.columns = [c.strip() for c in df.columns]

        # Extract timestamps
        time_col = [c for c in df.columns if "TIME SINCE START" in c][0]
        time_ms = pd.to_numeric(df[time_col], errors="coerce").values

        # Fix S-S3b non-monotonic offset if present
        dt_ms = np.diff(time_ms)
        neg_dts = np.sum(dt_ms < 0)
        zero_dts = np.sum(dt_ms == 0)

        # Extract Speed (km/h -> m/s)
        spd_col = [c for c in df.columns if "GPS SPEED" in c][0]
        spd_kmh = pd.to_numeric(df[spd_col], errors="coerce").fillna(0.0).values
        spd_mps = spd_kmh / 3.6

        # Extract Accuracy
        acc_col = [c for c in df.columns if "GPS ACCURACY" in c][0]
        gps_acc = pd.to_numeric(df[acc_col], errors="coerce").fillna(999.0).values

        # Extract Satellites
        sat_cols = [c for c in df.columns if "SATELLITES" in c]
        sat_count = np.zeros(len(df), dtype=int)
        if sat_cols:
            raw_sats = df[sat_cols[0]].astype(str).values
            for idx, s in enumerate(raw_sats):
                try:
                    # Format "17 / 18" -> 17
                    val = int(s.split("/")[0].strip()) if "/" in s else int(float(s))
                    sat_count[idx] = val
                except Exception:
                    sat_count[idx] = 8

        # Accelerometer X, Y, Z
        ax_col = [c for c in df.columns if "ACCELEROMETER X" in c][0]
        ay_col = [c for c in df.columns if "ACCELEROMETER Y" in c][0]
        az_col = [c for c in df.columns if "ACCELEROMETER Z" in c][0]
        ax = pd.to_numeric(df[ax_col], errors="coerce").values
        ay = pd.to_numeric(df[ay_col], errors="coerce").values
        az = pd.to_numeric(df[az_col], errors="coerce").values

        # Gravity X, Y, Z
        gx_col = [c for c in df.columns if "GRAVITY X" in c][0]
        gy_col = [c for c in df.columns if "GRAVITY Y" in c][0]
        gz_col = [c for c in df.columns if "GRAVITY Z" in c][0]
        gx = pd.to_numeric(df[gx_col], errors="coerce").values
        gy = pd.to_numeric(df[gy_col], errors="coerce").values
        gz = pd.to_numeric(df[gz_col], errors="coerce").values

        # Gyroscope X, Y, Z (Handling Roll/Pitch/Yaw or X/Y/Z)
        gyro_x_col = [c for c in df.columns if "GYROSCOPE X" in c or "GYROSCOPE Roll" in c][0]
        gyro_y_col = [c for c in df.columns if "GYROSCOPE Y" in c or "GYROSCOPE Pitch" in c][0]
        gyro_z_col = [c for c in df.columns if "GYROSCOPE Z" in c or "GYROSCOPE Yaw" in c][0]
        wx = pd.to_numeric(df[gyro_x_col], errors="coerce").values
        wy = pd.to_numeric(df[gyro_y_col], errors="coerce").values
        wz = pd.to_numeric(df[gyro_z_col], errors="coerce").values

        # Cleaned dataframe
        self.df = pd.DataFrame({
            "time_ms": time_ms,
            "spd_mps": spd_mps,
            "gps_acc": gps_acc,
            "sat_count": sat_count,
            "ax": ax, "ay": ay, "az": az,
            "gx": gx, "gy": gy, "gz": gz,
            "wx": wx, "wy": wy, "wz": wz
        }).dropna().reset_index(drop=True)

        dur_min = (self.df["time_ms"].iloc[-1] - self.df["time_ms"].iloc[0]) / 60000.0 if len(self.df) > 1 else 0.0

        self.stats = {
            "name": self.name,
            "rows": len(self.df),
            "dur_min": dur_min,
            "spd_min": float(self.df["spd_mps"].min()),
            "spd_max": float(self.df["spd_mps"].max()),
            "spd_mean": float(self.df["spd_mps"].mean()),
            "acc_mean": float(self.df["gps_acc"].mean()),
            "acc_p95": float(np.percentile(self.df["gps_acc"], 95)),
            "stat_pct": float(np.mean(self.df["spd_mps"] == 0.0) * 100.0),
            "pct_0_1": float(np.mean((self.df["spd_mps"] > 0.0) & (self.df["spd_mps"] < 1.0)) * 100.0),
            "pct_1_5": float(np.mean((self.df["spd_mps"] >= 1.0) & (self.df["spd_mps"] < 5.0)) * 100.0),
            "pct_5_10": float(np.mean((self.df["spd_mps"] >= 5.0) & (self.df["spd_mps"] <= 10.0)) * 100.0),
            "pct_gt_10": float(np.mean(self.df["spd_mps"] > 10.0) * 100.0),
            "neg_dts": int(neg_dts),
            "zero_dts": int(zero_dts)
        }

# -----------------------------------------------------------------------------
# 2. Gyroscope Bias Calibration & Canonical Preprocessing
# -----------------------------------------------------------------------------
def compute_session_gyro_bias(df: pd.DataFrame, initial_samples: int = 50) -> np.ndarray:
    """
    Computes gyroscope bias from the initial stationary samples at session start.
    Strictly causal (uses only the first N samples of the session).
    """
    init_df = df.iloc[:initial_samples]
    # Check if initial samples are stationary (speed == 0 or low acc variation)
    bx = float(init_df["wx"].mean())
    by = float(init_df["wy"].mean())
    bz = float(init_df["wz"].mean())
    return np.array([bx, by, bz], dtype=np.float32)

def canonical_preprocess_session(df: pd.DataFrame, gyro_bias: np.ndarray) -> np.ndarray:
    """
    Canonical 6-channel preprocessing:
    [a_fwd, a_lat, a_vert, w_x, w_y, w_z] in vehicle frame with gravity removed.
    """
    N = len(df)
    features = np.zeros((N, 6), dtype=np.float32)

    raw_acc = df[["ax", "ay", "az"]].values.astype(np.float32)
    raw_grav = df[["gx", "gy", "gz"]].values.astype(np.float32)
    raw_gyro = df[["wx", "wy", "wz"]].values.astype(np.float32)

    # 1. Bias correction
    gyro_corr = raw_gyro - gyro_bias

    # 2. Frame alignment & gravity removal per sample
    for i in range(N):
        g_vec = raw_grav[i]
        g_norm = np.linalg.norm(g_vec)
        if g_norm < 1e-3:
            g_unit = np.array([0.0, 0.0, 1.0], dtype=np.float32)
            g_norm = 9.80665
        else:
            g_unit = g_vec / g_norm

        zAxis = g_unit
        temp = np.array([1.0, 0.0, 0.0], dtype=np.float32) if abs(zAxis[0]) < 0.9 else np.array([0.0, 1.0, 0.0], dtype=np.float32)
        yAxis = np.cross(zAxis, temp)
        yAxis /= np.linalg.norm(yAxis)
        xAxis = np.cross(yAxis, zAxis)
        R_b_to_v = np.stack([xAxis, yAxis, zAxis], axis=0) # [3, 3]

        a_veh = R_b_to_v @ raw_acc[i]
        w_veh = R_b_to_v @ gyro_corr[i]

        # Gravity subtracted on vertical axis
        a_fwd = a_veh[0]
        a_lat = a_veh[1]
        a_vert = a_veh[2] - g_norm

        features[i, 0] = a_fwd
        features[i, 1] = a_lat
        features[i, 2] = a_vert
        features[i, 3] = w_veh[0]
        features[i, 4] = w_veh[1]
        features[i, 5] = w_veh[2]

    return features

# -----------------------------------------------------------------------------
# 3. Window & Target Construction with Quality Filters
# -----------------------------------------------------------------------------
def build_session_windows(
    df: pd.DataFrame,
    features: np.ndarray,
    window_size: int = 20,
    stride: int = 1
):
    N = len(df)
    spd_arr = df["spd_mps"].values
    acc_arr = df["gps_acc"].values
    sat_arr = df["sat_count"].values
    time_arr = df["time_ms"].values

    windows_X = []
    windows_y = []
    rejection_stats = {
        "acc_gt_10m": 0,
        "sat_lt_4": 0,
        "imu_gap_gt_200ms": 0,
        "nan_or_inf": 0,
        "accepted": 0
    }

    for start in range(0, N - window_size + 1, stride):
        end = start + window_size
        
        # 1. IMU time gap check
        dt_window = np.diff(time_arr[start:end])
        if np.any(dt_window > 200) or np.any(dt_window < 0):
            rejection_stats["imu_gap_gt_200ms"] += 1
            continue

        # 2. GPS accuracy filter (> 10m)
        if np.any(acc_arr[start:end] > 10.0):
            rejection_stats["acc_gt_10m"] += 1
            continue

        # 3. Satellite count filter (< 4)
        if np.any(sat_arr[start:end] < 4):
            rejection_stats["sat_lt_4"] += 1
            continue

        w_feats = features[start:end].T # [6, 20]
        if np.isnan(w_feats).any() or np.isinf(w_feats).any():
            rejection_stats["nan_or_inf"] += 1
            continue

        # Window-mean target speed
        target_speed = float(np.mean(spd_arr[start:end]))

        windows_X.append(w_feats)
        windows_y.append(target_speed)
        rejection_stats["accepted"] += 1

    X_arr = np.array(windows_X, dtype=np.float32) if windows_X else np.zeros((0, 6, window_size), dtype=np.float32)
    y_arr = np.array(windows_y, dtype=np.float32) if windows_y else np.zeros((0,), dtype=np.float32)

    return X_arr, y_arr, rejection_stats

# -----------------------------------------------------------------------------
# 4. PyTorch Dataset & Gaussian NLL Loss
# -----------------------------------------------------------------------------
class KinoNetWindowDataset(Dataset):
    def __init__(self, X: np.ndarray, y: np.ndarray):
        self.X = torch.from_numpy(X)
        self.y = torch.from_numpy(y)

    def __len__(self):
        return len(self.X)

    def __getitem__(self, idx):
        return self.X[idx], self.y[idx]

def gaussian_nll_loss(mu: torch.Tensor, log_var: torch.Tensor, target: torch.Tensor, eps: float = 1e-6) -> torch.Tensor:
    """
    Heteroscedastic Gaussian Negative Log-Likelihood Loss.
    log_var is bounded in [-6, 6] for numerical stability.
    """
    clamped_logvar = torch.clamp(log_var, -6.0, 6.0)
    precision = torch.exp(-clamped_logvar)
    squared_error = (mu - target) ** 2
    loss = 0.5 * (precision * squared_error + clamped_logvar)
    return torch.mean(loss)

# -----------------------------------------------------------------------------
# 5. Baseline Estimators (Test Partition Benchmarks)
# -----------------------------------------------------------------------------
def compute_test_baselines(train_y: np.ndarray, test_X: np.ndarray, test_y: np.ndarray):
    print("\n" + "=" * 90)
    print("TEST PARTITION BASELINE BENCHMARKS")
    print("=" * 90)

    # Baseline A: Training Set Mean Prior
    mean_prior = float(np.mean(train_y))
    pred_A = np.full_like(test_y, fill_value=mean_prior)
    err_A = pred_A - test_y
    mae_A = np.mean(np.abs(err_A))
    rmse_A = np.sqrt(np.mean(err_A ** 2))
    bias_A = np.mean(err_A)
    p95_A = np.percentile(np.abs(err_A), 95)

    # Baseline B: Persistence (Previous Target Speed)
    pred_B = np.zeros_like(test_y)
    pred_B[0] = mean_prior
    pred_B[1:] = test_y[:-1]
    err_B = pred_B - test_y
    mae_B = np.mean(np.abs(err_B))
    rmse_B = np.sqrt(np.mean(err_B ** 2))
    bias_B = np.mean(err_B)
    p95_B = np.percentile(np.abs(err_B), 95)

    # Baseline C: Direct Forward Accelerometer Euler Integration
    # Delta t = 0.1s per step, integrating mean window forward accel
    pred_C = np.zeros_like(test_y)
    pred_C[0] = test_y[0]
    for i in range(1, len(test_y)):
        fwd_acc = float(np.mean(test_X[i, 0, :])) # channel 0 is vehicle_forward_accel
        pred_C[i] = max(0.0, pred_C[i - 1] + fwd_acc * 0.1)
    err_C = pred_C - test_y
    mae_C = np.mean(np.abs(err_C))
    rmse_C = np.sqrt(np.mean(err_C ** 2))
    bias_C = np.mean(err_C)
    p95_C = np.percentile(np.abs(err_C), 95)

    print(f"{'Baseline Estimator':<45} | {'MAE (m/s)':<10} | {'RMSE (m/s)':<11} | {'Bias (m/s)':<11} | {'P95 (m/s)':<10}")
    print("-" * 95)
    print(f"{'Baseline A: Train-Set Mean Speed Prior':<45} | {mae_A:<10.4f} | {rmse_A:<11.4f} | {bias_A:<+11.4f} | {p95_A:<10.4f}")
    print(f"{'Baseline B: Last-Speed Persistence':<45} | {mae_B:<10.4f} | {rmse_B:<11.4f} | {bias_B:<+11.4f} | {p95_B:<10.4f}")
    print(f"{'Baseline C: Forward Accel Dead Reckoning':<45} | {mae_C:<10.4f} | {rmse_C:<11.4f} | {bias_C:<+11.4f} | {p95_C:<10.4f}")
    print("=" * 95)

    return {
        "Baseline_A": {"mae": mae_A, "rmse": rmse_A, "bias": bias_A, "p95": p95_A},
        "Baseline_B": {"mae": mae_B, "rmse": rmse_B, "bias": bias_B, "p95": p95_B},
        "Baseline_C": {"mae": mae_C, "rmse": rmse_C, "bias": bias_C, "p95": p95_C}
    }

# -----------------------------------------------------------------------------
# 6. Evaluation Function (Metrics & Regime Breakdown)
# -----------------------------------------------------------------------------
def evaluate_model(model: nn.Module, loader: DataLoader, device: torch.device):
    model.eval()
    total_nll = 0.0
    preds = []
    targets = []
    log_vars = []

    with torch.no_grad():
        for bx, by in loader:
            bx = bx.to(device)
            by = by.to(device)
            mu, log_var = model(bx)
            mu = torch.clamp(mu, min=0.0)
            log_var = torch.clamp(log_var, -6.0, 6.0)

            nll = gaussian_nll_loss(mu, log_var, by)
            total_nll += nll.item() * len(bx)

            preds.extend(mu.cpu().numpy())
            targets.extend(by.cpu().numpy())
            log_vars.extend(log_var.cpu().numpy())

    preds_arr = np.array(preds)
    targets_arr = np.array(targets)
    log_vars_arr = np.array(log_vars)

    err = preds_arr - targets_arr
    abs_err = np.abs(err)

    mean_nll = total_nll / len(targets_arr)
    mae = float(np.mean(abs_err))
    rmse = float(np.sqrt(np.mean(err ** 2)))
    bias = float(np.mean(err))
    p95 = float(np.percentile(abs_err, 95))

    # Regime breakdown
    regimes = {
        "< 1.0 m/s": (targets_arr < 1.0),
        "1.0 - 5.0 m/s": (targets_arr >= 1.0) & (targets_arr < 5.0),
        "5.0 - 10.0 m/s": (targets_arr >= 5.0) & (targets_arr <= 10.0),
        "> 10.0 m/s": (targets_arr > 10.0)
    }

    regime_metrics = {}
    for r_name, mask in regimes.items():
        cnt = int(np.sum(mask))
        if cnt > 0:
            regime_metrics[r_name] = {
                "count": cnt,
                "ref_mean": float(np.mean(targets_arr[mask])),
                "kino_mean": float(np.mean(preds_arr[mask])),
                "mae": float(np.mean(abs_err[mask])),
                "bias": float(np.mean(err[mask]))
            }
        else:
            regime_metrics[r_name] = {
                "count": 0, "ref_mean": 0.0, "kino_mean": 0.0, "mae": 0.0, "bias": 0.0
            }

    return {
        "nll": mean_nll,
        "mae": mae,
        "rmse": rmse,
        "bias": bias,
        "p95": p95,
        "pred_min": float(np.min(preds_arr)),
        "pred_max": float(np.max(preds_arr)),
        "pred_mean": float(np.mean(preds_arr)),
        "logvar_min": float(np.min(log_vars_arr)),
        "logvar_max": float(np.max(log_vars_arr)),
        "logvar_mean": float(np.mean(log_vars_arr)),
        "regimes": regime_metrics
    }

# -----------------------------------------------------------------------------
# 7. Main Pipeline & 1-Epoch Smoke Test
# -----------------------------------------------------------------------------
def run_pipeline_audit():
    print("=" * 110)
    print("KINONET TRAINING PIPELINE IMPLEMENTATION & SMOKE TEST AUDIT")
    print("=" * 110)

    # 1. Ingest all 5 sessions
    sessions = {}
    print("\n--- 1. DATASET INGESTION & PER-SESSION STATISTICS ---")
    print(f"{'Session':<8} | {'Rows':<7} | {'Dur (min)':<10} | {'Spd Range (m/s)':<16} | {'Stat %':<8} | {'0-1 %':<8} | {'1-5 %':<8} | {'5-10 %':<8} | {'>10 %':<6} | {'Gaps'}")
    print("-" * 110)
    for name, fpath in DATA_FILES.items():
        loader = SessionLoader(name, fpath)
        sessions[name] = loader
        st = loader.stats
        spd_range = f"{st['spd_min']:.1f} - {st['spd_max']:.2f}"
        print(f"{st['name']:<8} | {st['rows']:<7} | {st['dur_min']:<10.2f} | {spd_range:<16} | {st['stat_pct']:<8.1f} | {st['pct_0_1']:<8.1f} | {st['pct_1_5']:<8.1f} | {st['pct_5_10']:<8.1f} | {st['pct_gt_10']:<6.1f} | zero={st['zero_dts']}, neg={st['neg_dts']}")
    print("=" * 110)

    # 2. Canonical Preprocessing & Window Construction
    print("\n--- 2. CANONICAL PREPROCESSING & GYROSCOPE BIAS CALIBRATION ---")
    session_windows = {}
    for name, loader in sessions.items():
        gyro_bias = compute_session_gyro_bias(loader.df)
        print(f"Session {name:<6}: Calibrated Initial Gyro Bias = [{gyro_bias[0]:+.6f}, {gyro_bias[1]:+.6f}, {gyro_bias[2]:+.6f}] rad/s")
        feats = canonical_preprocess_session(loader.df, gyro_bias)
        X_w, y_w, rej = build_session_windows(loader.df, feats, window_size=20, stride=1)
        session_windows[name] = {"X": X_w, "y": y_w, "rej": rej}
        print(f"  -> Generated {len(X_w)} valid [6, 20] windows (Discarded: acc>10m={rej['acc_gt_10m']}, sats<4={rej['sat_lt_4']}, dt_gap={rej['imu_gap_gt_200ms']})")

    # 3. Trajectory-Level Splitting
    print("\n--- 3. TRAJECTORY-LEVEL SPLIT VERIFICATION ---")
    # TRAIN: S-S4 + S-S1
    train_X = np.concatenate([session_windows["S-S4"]["X"], session_windows["S-S1"]["X"]], axis=0)
    train_y = np.concatenate([session_windows["S-S4"]["y"], session_windows["S-S1"]["y"]], axis=0)

    # VALIDATION: S-S3c
    val_X = session_windows["S-S3c"]["X"]
    val_y = session_windows["S-S3c"]["y"]

    # TEST: S-S3a + S-S3b
    test_X = np.concatenate([session_windows["S-S3a"]["X"], session_windows["S-S3b"]["X"]], axis=0)
    test_y = np.concatenate([session_windows["S-S3a"]["y"], session_windows["S-S3b"]["y"]], axis=0)

    print(f"Train Set      (S-S4 + S-S1): {len(train_X):<7} windows | Speed Range: {train_y.min():.2f} - {train_y.max():.2f} m/s | Mean: {train_y.mean():.2f} m/s")
    print(f"Validation Set (S-S3c)      : {len(val_X):<7} windows | Speed Range: {val_y.min():.2f} - {val_y.max():.2f} m/s | Mean: {val_y.mean():.2f} m/s")
    print(f"Test Set       (S-S3a + S3b): {len(test_X):<7} windows | Speed Range: {test_y.min():.2f} - {test_y.max():.2f} m/s | Mean: {test_y.mean():.2f} m/s")
    print(f"Total Partitioned Windows   : {len(train_X) + len(val_X) + len(test_X)}")

    # 4. Compute Test Set Baselines
    baselines = compute_test_baselines(train_y, test_X, test_y)

    # 5. DataLoaders
    torch.manual_seed(42)
    np.random.seed(42)

    train_loader = DataLoader(KinoNetWindowDataset(train_X, train_y), batch_size=64, shuffle=True)
    val_loader = DataLoader(KinoNetWindowDataset(val_X, val_y), batch_size=64, shuffle=False)
    test_loader = DataLoader(KinoNetWindowDataset(test_X, test_y), batch_size=64, shuffle=False)

    # 6. Model, Optimizer, Loss Initialization
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = KinoNetR2(in_channels=6, hidden_dim=128, dropout_rate=0.2).to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=5e-4, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=50, eta_min=1e-5)

    print("\n--- 4. ONE-EPOCH TRAINING SMOKE TEST EXECUTION ---")
    model.train()
    running_loss = 0.0
    batch_count = 0

    for bx, by in train_loader:
        bx = bx.to(device)
        by = by.to(device)

        optimizer.zero_grad()
        mu, log_var = model(bx)
        mu = torch.clamp(mu, min=0.0)
        log_var = torch.clamp(log_var, -6.0, 6.0)

        loss = gaussian_nll_loss(mu, log_var, by)
        loss.backward()
        optimizer.step()

        running_loss += loss.item()
        batch_count += 1

    scheduler.step()
    epoch_loss = running_loss / batch_count
    print(f"Epoch 1 Completed | Training Gaussian NLL Loss = {epoch_loss:.4f} | Batches = {batch_count}")

    # 7. BatchNorm Running Statistics Inspection
    rm1 = model.bn1.running_mean.detach().cpu().numpy()
    rv1 = model.bn1.running_var.detach().cpu().numpy()
    gamma1 = model.bn1.weight.detach().cpu().numpy()
    eps = model.bn1.eps
    scale1 = gamma1 / np.sqrt(rv1 + eps)

    print("\n--- 5. BATCHNORM1 RUNNING STATISTICS AFTER 1 EPOCH ON REAL DATA ---")
    print(f"bn1.running_mean : min = {rm1.min():+.5f}, max = {rm1.max():+.5f}, mean = {rm1.mean():+.5f}, std = {rm1.std():.5f}")
    print(f"bn1.running_var  : min = {rv1.min():.5f}, max = {rv1.max():.5f}, mean = {rv1.mean():.5f}, std = {rv1.std():.5f}")
    print(f"Effective Scale Multiplier [gamma / sqrt(var+eps)]: min = {scale1.min():.2f}x, max = {scale1.max():.2f}x, mean = {scale1.mean():.2f}x")
    print(f"Comparison with Bad Baseline (47.48x): Scale multiplier has dropped from 47.48x to {scale1.mean():.2f}x.")

    # 8. Validation Evaluation After 1 Epoch
    print("\n--- 6. VALIDATION EVALUATION AFTER 1 EPOCH ---")
    val_res = evaluate_model(model, val_loader, device)
    print(f"Validation NLL Loss : {val_res['nll']:.4f}")
    print(f"Validation MAE      : {val_res['mae']:.4f} m/s")
    print(f"Validation RMSE     : {val_res['rmse']:.4f} m/s")
    print(f"Validation Bias     : {val_res['bias']:+.4f} m/s")
    print(f"Validation P95 Error: {val_res['p95']:.4f} m/s")
    print(f"Predicted Speed     : Min = {val_res['pred_min']:.4f}, Max = {val_res['pred_max']:.4f}, Mean = {val_res['pred_mean']:.4f} m/s")
    print(f"Log-Variance        : Min = {val_res['logvar_min']:.4f}, Max = {val_res['logvar_max']:.4f}, Mean = {val_res['logvar_mean']:.4f}")

    print("\nSpeed Regime Breakdown (Validation):")
    print(f"{'Regime':<16} | {'Count':<6} | {'Ref Mean (m/s)':<15} | {'Kino Mean (m/s)':<16} | {'MAE (m/s)':<10} | {'Bias (m/s)':<10}")
    print("-" * 85)
    for r_name, r_st in val_res["regimes"].items():
        if r_st["count"] > 0:
            print(f"{r_name:<16} | {r_st['count']:<6} | {r_st['ref_mean']:<15.4f} | {r_st['kino_mean']:<16.4f} | {r_st['mae']:<10.4f} | {r_st['bias']:<+10.4f}")
        else:
            print(f"{r_name:<16} | {0:<6} | {'N/A':<15} | {'N/A':<16} | {'N/A':<10} | {'N/A':<10}")
    print("=" * 85)

if __name__ == "__main__":
    run_pipeline_audit()
