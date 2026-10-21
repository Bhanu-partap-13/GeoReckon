"""
GeoReckon Production Engine
Intelligent Dead Reckoning Navigation for Ground Vehicles in GNSS-Denied Environments.
"""

from .imu_calibrator import AirIMUCorrectionNet, IMUCalibrator, train_imu_calibrator
from .geometry import skew, so3_exp, left_jac, enu_from_latlon
from .virtual_alignment import VirtualInVehicleAligner
from .vibration_filter import KinematicVibrationFilter
from .kinematics_speed_estimator import KinematicSpeedEstimator
from .kinonet import KinoNet, compute_huber_objective, predict_batch
from .filter_inekf import InEKF, InEKFState
from .map_matcher import TopologicalMapMatcher
from .data_loader import TelematicsDrive, load_drive, extract_continuous_windows
from .evaluator import OutageTrial, extract_standardized_outages, evaluate_trial

__version__ = "3.2.1"
__all__ = [
    "skew", "so3_exp", "left_jac", "enu_from_latlon",
    "VirtualInVehicleAligner",
    "KinematicVibrationFilter",
    "KinematicSpeedEstimator",
    "KinoNet", "compute_huber_objective", "predict_batch",
    "InEKF", "InEKFState",
    "TopologicalMapMatcher",
    "TelematicsDrive", "load_drive", "extract_continuous_windows",
    "OutageTrial", "extract_standardized_outages", "evaluate_trial"
]
