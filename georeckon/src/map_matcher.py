"""
GeoReckon Topological Road Corridor & Manifold Snapping Engine
Constrains dead-reckoning trajectory to the physical road manifold,
preventing unbounded orthogonal cross-track drift during GNSS outages.
"""

import numpy as np


class TopologicalMapMatcher:
    def __init__(self, road_width: float = 7.0, max_snap_distance: float = 25.0):
        self.road_width = road_width
        self.half_width = road_width / 2.0
        self.max_snap_dist = max_snap_distance
        self.segments = [] # List of tuples: (p1, p2, unit_tangent, length)

    def load_polyline(self, polyline_pts: np.ndarray):
        """Loads road centerline polyline points in local ENU coordinates."""
        self.segments = []
        for i in range(len(polyline_pts) - 1):
            p1 = polyline_pts[i, :2]
            p2 = polyline_pts[i + 1, :2]
            vec = p2 - p1
            length = float(np.linalg.norm(vec))
            if length > 0.05:
                u = vec / length
                self.segments.append((p1, p2, u, length))

    def constrain_to_road(self, current_pos_2d: np.ndarray, heading_rad: float = None) -> tuple[np.ndarray, float]:
        """
        Projects position onto the closest road segment, clamping lateral error to road corridor.

        Returns:
            snapped_pos: (2,) array in meters
            corrected_heading: float in radians (or original heading if not constrained)
        """
        if not self.segments:
            return current_pos_2d.copy(), heading_rad

        best_dist = 1e9
        best_pt = current_pos_2d.copy()
        best_tangent = None

        for p1, p2, u, length in self.segments:
            rel = current_pos_2d - p1
            proj_t = np.clip(np.dot(rel, u), 0.0, length)
            pt_seg = p1 + proj_t * u
            dist = float(np.linalg.norm(current_pos_2d - pt_seg))

            if dist < best_dist:
                best_dist = dist
                best_pt = pt_seg
                best_tangent = u

        # If vehicle is outside road half-width but within search radius, snap to boundary
        snapped_pos = current_pos_2d.copy()
        if self.half_width < best_dist <= self.max_snap_dist:
            # Soft clamp to road corridor edge
            snapped_pos = best_pt + (current_pos_2d - best_pt) * (self.half_width / best_dist)
        elif best_dist > self.max_snap_dist:
            # Too far: unmapped departure, keep free Dead Reckoning
            snapped_pos = current_pos_2d.copy()

        # Heading alignment: if vehicle is aligned within 45 deg of road, gently pull heading
        corrected_heading = heading_rad
        if best_tangent is not None and heading_rad is not None and best_dist <= self.max_snap_dist:
            road_hdg = np.arctan2(best_tangent[1], best_tangent[0])
            diff = (road_hdg - heading_rad + np.pi) % (2 * np.pi) - np.pi
            if abs(diff) < np.deg2rad(35.0):
                corrected_heading = heading_rad + 0.10 * diff

        return snapped_pos, corrected_heading
