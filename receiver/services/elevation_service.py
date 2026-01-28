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


def fetch_and_update_elevations(
    db_session: Session,
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

    # Extract coordinates from telemetry points
    coordinates = [
        (point.latitude, point.longitude)
        for point in telemetry_points
        if point.latitude is not None and point.longitude is not None
    ]

    if len(coordinates) < 2:
        logger.debug("Not enough GPS coordinates for elevation fetch")
        return 0

    # Sample coordinates to reduce API calls
    sampled_coords = sample_coordinates(coordinates, max_samples=max_samples)

    # Create a mapping from original coordinates to sampled indices
    # We'll use the nearest sampled point for each original point
    coord_to_idx = {}
    for i, (lat, lon) in enumerate(coordinates):
        # Find closest sampled coordinate
        min_dist = float("inf")
        closest_idx = 0
        for j, (slat, slon) in enumerate(sampled_coords):
            dist = (lat - slat) ** 2 + (lon - slon) ** 2
            if dist < min_dist:
                min_dist = dist
                closest_idx = j
        coord_to_idx[i] = closest_idx

    # Fetch elevations for sampled points
    elevations = get_elevation_for_points(sampled_coords)

    if not elevations:
        logger.debug("Elevation API returned no data")
        return 0

    # Update telemetry points with elevation data
    updated_count = 0
    for i, point in enumerate(telemetry_points):
        if point.latitude is None or point.longitude is None:
            continue

        # Get the sampled index for this point
        original_idx = None
        for idx, (lat, lon) in enumerate(coordinates):
            if lat == point.latitude and lon == point.longitude:
                original_idx = idx
                break

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
