"""
GeoReckon Core Geometry & Lie Group Utilities
Provides SO(3) matrix exponential, skew-symmetric mapping, left Jacobian,
and rotation conversions for invariant inertial navigation.
"""

import numpy as np
from scipy.spatial.transform import Rotation


def skew(x: np.ndarray) -> np.ndarray:
    """
    Computes the 3x3 skew-symmetric matrix [x]_x for a 3D vector.
    [x]_x * y = x cross y
    """
    a, b, c = x
    return np.array([
        [0.0, -c,   b],
        [c,    0.0, -a],
        [-b,   a,   0.0]
    ], dtype=np.float64)


def so3_exp(phi: np.ndarray) -> np.ndarray:
    """
    Computes the SO(3) matrix exponential using Rodrigues' formula.
    Exp(phi) = I + (sin theta / theta) [phi]_x + ((1 - cos theta) / theta^2) [phi]_x^2
    """
    theta = np.linalg.norm(phi)
    if theta < 1e-8:
        return np.eye(3, dtype=np.float64) + skew(phi)
    axis = phi / theta
    k = skew(axis)
    return np.eye(3, dtype=np.float64) + np.sin(theta) * k + (1.0 - np.cos(theta)) * (k @ k)


def left_jac(phi: np.ndarray) -> np.ndarray:
    """
    Computes the left Jacobian of SO(3).
    J_l(phi) maps Lie algebra perturbations to group tangents.
    """
    theta = np.linalg.norm(phi)
    A = skew(phi)
    if theta < 1e-6:
        return np.eye(3, dtype=np.float64) + 0.5 * A + (1.0 / 6.0) * (A @ A)
    term1 = (1.0 - np.cos(theta)) / (theta ** 2) * A
    term2 = (theta - np.sin(theta)) / (theta ** 3) * (A @ A)
    return np.eye(3, dtype=np.float64) + term1 + term2


def enu_from_latlon(latlon_array: np.ndarray) -> np.ndarray:
    """
    Converts (lat, lon) in degrees to local East-North-Up (ENU) coordinates in meters
    relative to the first coordinate point.
    """
    lat, lon = np.deg2rad(latlon_array).T
    lat0, lon0 = lat[0], lon[0]
    r_earth = 6371000.0 # WGS84 mean radius in meters
    east = (lon - lon0) * r_earth * np.cos(lat0)
    north = (lat - lat0) * r_earth
    up = np.zeros(len(latlon_array), dtype=np.float64)
    return np.column_stack([east, north, up])
