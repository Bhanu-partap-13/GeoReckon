"""
GeoReckon Spherical Geodesic Transformations (Earth R = 6,371,000.0 m)
Implements forward and inverse ENU projections relative to calibration origin (lat0, lon0).
"""

import math
from typing import Tuple

EARTH_RADIUS_M = 6371000.0


def latlon_to_enu(lat: float, lon: float, lat0: float, lon0: float) -> Tuple[float, float, float]:
    """
    Forward geodesic projection from (lat, lon) in degrees to local ENU in meters.
    x = R * (lon - lon0) * cos(lat0) * (pi / 180)
    y = R * (lat - lat0) * (pi / 180)
    z = 0.0
    """
    lat_rad = math.radians(lat)
    lon_rad = math.radians(lon)
    lat0_rad = math.radians(lat0)
    lon0_rad = math.radians(lon0)

    x_east = EARTH_RADIUS_M * (lon_rad - lon0_rad) * math.cos(lat0_rad)
    y_north = EARTH_RADIUS_M * (lat_rad - lat0_rad)
    z_up = 0.0

    return x_east, y_north, z_up


def enu_to_latlon(x_east: float, y_north: float, lat0: float, lon0: float) -> Tuple[float, float]:
    """
    Inverse geodesic projection from local ENU (meters) to (lat, lon) in degrees.
    lat = lat0 + (y / R) * (180 / pi)
    lon = lon0 + (x / (R * cos(lat0))) * (180 / pi)
    """
    lat0_rad = math.radians(lat0)
    lat_rad = lat0_rad + (y_north / EARTH_RADIUS_M)
    lon_rad = math.radians(lon0) + (x_east / (EARTH_RADIUS_M * math.cos(lat0_rad)))

    lat = math.degrees(lat_rad)
    lon = math.degrees(lon_rad)

    return lat, lon
