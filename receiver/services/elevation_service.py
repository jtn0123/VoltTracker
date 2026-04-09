"""
Elevation Service for VoltTracker

Provides functions to fetch and update elevation data for telemetry points
using the Open-Meteo Elevation API.
"""

import logging
from typing import Any, Dict, List

from sqlalchemy.orm import Session

from models import TelemetryRaw
from utils.elevation import (
    get_elevation_for_points,
    sample_coordinates,
    calculate_elevation_profile,
)

logger = logging.getLogger(__name__)


def _find_nearest_sampled_index(lat: float, lon: float, sampled_coords: list) -> int:
    """Find the index of the nearest sampled coordinate."""
    min_dist = float("inf")
    closest_idx = 0
    for j, (slat, slon) in enumerate(sampled_coords):
        dist = (lat - slat) ** 2 + (lon - slon) ** 2
        if dist < min_dist:
            min_dist = dist
            closest_idx = j
    return closest_idx


def _build_coordinate_mapping(coordinates: list, sampled_coords: list) -> dict:
    """Map each original coordinate index to its nearest sampled coordinate index."""
    return {
        i: _find_nearest_sampled_index(lat, lon, sampled_coords)
        for i, (lat, lon) in enumerate(coordinates)
    }


def _build_point_to_original_index(telemetry_points: list, coordinates: list) -> dict:
    """Map telemetry point index to original coordinate index."""
    coord_lookup = {}
    for idx, (lat, lon) in enumerate(coordinates):
        coord_lookup.setdefault((lat, lon), idx)

    result = {}
    for i, point in enumerate(telemetry_points):
        if point.latitude is not None and point.longitude is not None:
            key = (point.latitude, point.longitude)
            if key in coord_lookup:
                result[i] = coord_lookup[key]
    return result


def fetch_and_update_elevations(
    db_session: Session,  # noqa: S1172 - part of public API
    telemetry_points: List[TelemetryRaw],
    max_samples: int = 50,
) -> int:
    """
    Fetch elevation data for telemetry points and update them in the database.

    Args:
        db_session: Database session
        telemetry_points: List of TelemetryRaw objects with GPS coordinates
        max_samples: Maximum number of points to sample for API calls (default: 50)

    Returns:
        Number of telemetry points updated with elevation data
    """
    if not telemetry_points:
        return 0

    coordinates = [
        (point.latitude, point.longitude)
        for point in telemetry_points
        if point.latitude is not None and point.longitude is not None
    ]

    if len(coordinates) < 2:
        logger.debug("Not enough GPS coordinates for elevation fetch")
        return 0

    sampled_coords = sample_coordinates(coordinates, max_samples=max_samples)
    coord_to_idx = _build_coordinate_mapping(coordinates, sampled_coords)

    elevations = get_elevation_for_points(sampled_coords)
    if not elevations:
        logger.debug("Elevation API returned no data")
        return 0

    point_to_original = _build_point_to_original_index(telemetry_points, coordinates)

    updated_count = 0
    for i, point in enumerate(telemetry_points):
        original_idx = point_to_original.get(i)
        if original_idx is None:
            continue
        sampled_idx = coord_to_idx.get(original_idx)
        if sampled_idx is not None and sampled_idx < len(elevations):
            elevation = elevations[sampled_idx]
            if elevation is not None:
                point.elevation_meters = elevation
                updated_count += 1

    logger.debug(f"Updated {updated_count} telemetry points with elevation data")
    return updated_count


def get_elevation_profile_for_telemetry(
    telemetry_points: List[TelemetryRaw],
) -> Dict[str, Any]:
    """
    Calculate elevation profile from telemetry points that have elevation data.

    Args:
        telemetry_points: List of TelemetryRaw objects with elevation_meters set

    Returns:
        Dictionary with elevation statistics (gain, loss, net change, min, max)
    """
    elevations = [
        point.elevation_meters
        for point in telemetry_points
        if point.elevation_meters is not None
    ]

    return calculate_elevation_profile(elevations)
