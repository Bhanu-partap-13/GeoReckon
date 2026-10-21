"""
GeoReckon IO-VNBD Data Loader
Loads synchronized telematics logs from smartphone sensors (S-*.csv) and vehicle CAN bus (V-*.csv).
Performs semantic column mapping, timestamp alignment, and continuous window generation.
"""

from dataclasses import dataclass
from pathlib import Path
import numpy as np
import pandas as pd
from .geometry import enu_from_latlon


@dataclass
class TelematicsDrive:
    name: str
    t: np.ndarray
    a: np.ndarray
    w: np.ndarray
    gravity: np.ndarray
    ll: np.ndarray
    speed: np.ndarray
    course: np.ndarray
    accuracy: np.ndarray
    v_true_speed: np.ndarray = None # From V CAN bus if available


def load_drive(csv_path: str, v_csv_path: str = None) -> TelematicsDrive:
    """Loads and semantically validates smartphone and optional vehicle CAN CSV files."""
    path = Path(csv_path)
    try:
        df = pd.read_csv(path, encoding='utf-8')
    except UnicodeDecodeError:
        df = pd.read_csv(path, encoding='latin1')

    df.columns = df.columns.str.strip()

    def get_num(name):
        return pd.to_numeric(df[name], errors='coerce').to_numpy(dtype=np.float64)

    # 1. Timestamps
    t = get_num('TIME SINCE START (ms)') / 1000.0

    # 2. Accelerometer and Gravity
    ax = get_num([c for c in df.columns if 'ACCELEROMETER X' in c][0])
    ay = get_num([c for c in df.columns if 'ACCELEROMETER Y' in c][0])
    az = get_num([c for c in df.columns if 'ACCELEROMETER Z' in c][0])
    a = np.column_stack([ax, ay, az])

    gx = get_num([c for c in df.columns if 'GRAVITY X' in c][0])
    gy = get_num([c for c in df.columns if 'GRAVITY Y' in c][0])
    gz = get_num([c for c in df.columns if 'GRAVITY Z' in c][0])
    gravity = np.column_stack([gx, gy, gz])

    # 3. Gyroscope (Roll, Pitch, Yaw in rad/s)
    wx = get_num([c for c in df.columns if 'GYROSCOPE Roll' in c][0])
    wy = get_num([c for c in df.columns if 'GYROSCOPE Pitch' in c][0])
    wz = get_num([c for c in df.columns if 'GYROSCOPE Yaw' in c][0])
    w = np.column_stack([wx, wy, wz])

    # 4. GPS fixes
    lat = get_num([c for c in df.columns if 'GPS LATITUDE' in c][0])
    lon = get_num([c for c in df.columns if 'GPS LONGITUDE' in c][0])
    ll = np.column_stack([lat, lon])

    # In IO-VNBD, GPS SPEED (Kmh) header contains m/s values (empirically audited)
    speed = get_num([c for c in df.columns if 'GPS SPEED' in c][0])

    course_col = [c for c in df.columns if c.startswith('GPS ORIENT')][0]
    course = get_num(course_col)

    acc_col = [c for c in df.columns if 'GPS ACCURACY' in c][0]
    accuracy = get_num(acc_col)

    # 5. Optional Vehicle CAN bus ground truth speed
    v_true_speed = None
    if v_csv_path and Path(v_csv_path).exists():
        try:
            v_df = pd.read_csv(v_csv_path, encoding='latin1')
            v_df.columns = v_df.columns.str.strip()
            if 'Velocity (km/hr)' in v_df.columns:
                v_true_speed = pd.to_numeric(v_df['Velocity (km/hr)'], errors='coerce').to_numpy(dtype=np.float64) / 3.6
        except Exception:
            pass

    return TelematicsDrive(
        name=path.stem,
        t=t,
        a=a,
        w=w,
        gravity=gravity,
        ll=ll,
        speed=speed,
        course=course,
        accuracy=accuracy,
        v_true_speed=v_true_speed
    )


def extract_continuous_windows(drive: TelematicsDrive, window_len: int = 30, stride: int = 5,
                               sampling_rate: float = 10.0, calibrator=None):
    """
    Constructs rolling causal windows of 9-axis IMU features for KinoNet.
    Window shape: (N_windows, 9, window_len)

    FIX #4: Raw smartphone acceleration (sigma ~1.19 m/s^2) was being fed directly
    into state integration without any pre-filtering. This caused road vibrations
    and pothole spikes to produce rapid random-walk velocity divergence.
    The dual-stage filter (MAD shock clipper + Butterworth LPF at 1.5 Hz) is
    applied to the full drive before windowing.

    FIX #5 (d.fresh bug):
    The previous loader filtered rows by GPS coordinate freshness ('d.fresh'),
    keeping a row only when GPS lat/lon changed from the previous row. Because
    GPS updates at <=1 Hz while the IMU logs at 10 Hz, 9 of every 10 rows were
    discarded. On Drive S3 (68,617 IMU samples) only 716 rows survived, causing
    extreme overfitting. This function now ingests ALL continuous 10 Hz IMU rows
    regardless of GPS freshness. The speed target is still derived from the
    interpolated GPS speed (sample-held at IMU rate), which is correct -- all
    IMU samples genuinely occurred at those vehicle speeds.

    Gravity is NOT filtered -- it is a low-frequency attitude vector and
    filtering it would corrupt the alignment engine inputs.
    """
    from .vibration_filter import KinematicVibrationFilter

    raw_a = drive.a       # (N, 3) raw accelerometer -- ALL rows, no GPS gate
    raw_w = drive.w       # (N, 3) raw gyroscope    -- ALL rows, no GPS gate

    # Apply dual-stage filter to the full drive array (batch mode)
    vib_filt = KinematicVibrationFilter(sampling_rate=sampling_rate, cutoff_hz=1.5)
    if len(raw_a) >= 12:
        clean_a, clean_w, _ = vib_filt.filter_batch(raw_a, raw_w)
    else:
        clean_a, clean_w = raw_a.copy(), raw_w.copy()

    # AirIMU neural calibration layer (optional, non-breaking).
    # Applies learned residual corrections on top of the classical DSP filter.
    # Only active when a trained IMUCalibrator is passed; otherwise a no-op.
    if calibrator is not None:
        clean_a, clean_w = calibrator.calibrate(clean_a, clean_w)

    # Use filtered accel and gyro; keep raw gravity (low-frequency attitude vector)
    sensors = np.column_stack([clean_a, clean_w, drive.gravity])  # (N, 9)
    targets = drive.v_true_speed if drive.v_true_speed is not None else drive.speed

    X, y = [], []
    N = len(sensors)
    for i in range(0, N - window_len, stride):
        win = sensors[i:i+window_len].T  # (9, window_len)
        tgt = targets[i+window_len-1]
        # Accept all rows; only skip NaN/negative speed (sensor fault, not GPS freshness)
        if np.isfinite(win).all() and np.isfinite(tgt) and tgt >= 0:
            X.append(win)
            y.append(tgt)

    return np.array(X, dtype=np.float32), np.array(y, dtype=np.float32)


def split_drives_by_name(drive_names: list[str],
                          train_names: list[str] = None,
                          test_names: list[str] = None) -> tuple[list[str], list[str]]:
    """
    Splits a list of drive identifiers into train and test sets by whole-drive
    boundaries (not by row shuffling).

    FIX #5 (data leakage):
    The prior approach evaluated all drives together without any split, meaning
    the training distribution overlapped with the evaluation distribution.
    Splitting by WHOLE DRIVES prevents temporal data leakage: no IMU window
    from a test drive can appear in training data, because the vehicle dynamics,
    road geometry, and driver behaviour of each drive are temporally correlated.

    Default split convention (matches IO-VNBD benchmark protocol):
        Train: S1, S3a, S3b, S3c   (longer drives, richer manoeuvre diversity)
        Test:  S2, S4              (held-out drives, never seen during training)

    Args:
        drive_names: Full list of drive name strings (e.g. ['S1','S2','S3a','S4'])
        train_names: Explicit list of train drive names (overrides default)
        test_names:  Explicit list of test drive names (overrides default)

    Returns:
        (train_drives, test_drives): two lists of drive name strings
    """
    if train_names is None:
        train_names = [n for n in drive_names if n.startswith('S1') or n.startswith('S3')]
    if test_names is None:
        test_names  = [n for n in drive_names if n.startswith('S2') or n.startswith('S4')]

    # Warn if any drive appears in both sets or neither
    overlap = set(train_names) & set(test_names)
    if overlap:
        raise ValueError(f"Drive split overlap detected: {overlap}. "
                         "Drives must belong to EITHER train OR test, not both.")
    unassigned = set(drive_names) - set(train_names) - set(test_names)
    if unassigned:
        import warnings
        warnings.warn(f"Drives not assigned to train or test: {unassigned}", stacklevel=2)

    return train_names, test_names
