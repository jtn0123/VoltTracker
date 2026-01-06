"""
Elevation API Integration for VoltTracker

Uses Open-Meteo Elevation API (same provider as weather) to fetch
elevation data for GPS coordinates.
"""

import logging
import time
from typing import Any, Dict, List, Optional, Tuple

import requests

from config import Config
from utils.wide_events import WideEvent

logger = logging.getLogger(__name__)

ELEVATION_API_URL = "https://api.open-meteo.com/v1/elevation"
MAX_POINTS_PER_REQUEST = 100  # Open-Meteo accepts up to 100 coordinates per request
MAX_RETRIES = 2
RETRY_DELAY_SECONDS = 0.5
DEFAULT_TIMEOUT = 5  # seconds


def get_elevation_for_point(
    latitude: float,
    longitude: float,
    timeout: int = DEFAULT_TIMEOUT,
) -> Optional[float]:
    """
    Get elevation for a single GPS coordinate.

    Args:
        latitude: GPS latitude
        longitude: GPS longitude
        timeout: Request timeout in seconds

    Returns:
        Elevation in meters, or None if request failed
    """
    result = get_elevation_for_points([(latitude, longitude)], timeout=timeout)
    return result[0] if result else None


def get_elevation_for_points(
    coordinates: List[Tuple[float, float]],
    timeout: int = DEFAULT_TIMEOUT,
) -> List[Optional[float]]:
    """
    Batch elevation lookup for multiple coordinates.

    Open-Meteo accepts multiple coordinates in a single request for efficiency.

    Args:
        coordinates: List of (latitude, longitude) tuples
        timeout: Request timeout in seconds

    Returns:
        List of elevations in meters (same order as input), None for failed lookups
    """
    if not coordinates:
        return []

    # Check feature flag
    if hasattr(Config, "FEATURE_ELEVATION_TRACKING") and not Config.FEATURE_ELEVATION_TRACKING:
        return [None] * len(coordinates)

    # Create service boundary event for external API call
    event = WideEvent("external_api_elevation")
    event.add_context(
        service="open_meteo_elevation",
        url=ELEVATION_API_URL,
        coordinate_count=len(coordinates),
        timeout_seconds=timeout,
    )

    # Split into batches if needed
    all_elevations: List[Optional[float]] = []

    for batch_start in range(0, len(coordinates), MAX_POINTS_PER_REQUEST):
        batch = coordinates[batch_start : batch_start + MAX_POINTS_PER_REQUEST]

        # Build API parameters
        latitudes = ",".join(str(lat) for lat, _ in batch)
        longitudes = ",".join(str(lon) for _, lon in batch)

        params = {
            "latitude": latitudes,
            "longitude": longitudes,
        }

        batch_elevations = _request_with_retry(params, timeout, event)
        all_elevations.extend(batch_elevations if batch_elevations else [None] * len(batch))

    event.mark_success()
    event.emit()

    return all_elevations


def _request_with_retry(
    params: Dict[str, Any],
    timeout: int,
    event: WideEvent,
) -> Optional[List[float]]:
    """
    Make an HTTP GET request with retry logic.

    Args:
        params: Query parameters
        timeout: Request timeout
        event: WideEvent for tracking

    Returns:
        List of elevations, or None if all retries failed
    """
    last_error: Optional[Exception] = None
    delay = RETRY_DELAY_SECONDS

    for attempt in range(MAX_RETRIES):
        try:
            response = requests.get(ELEVATION_API_URL, params=params, timeout=timeout)
            response.raise_for_status()
            data = response.json()

            # Parse response
            elevations = data.get("elevation", [])
            if isinstance(elevations, list):
                return elevations
            elif isinstance(elevations, (int, float)):
                return [elevations]
            else:
                logger.warning(f"Unexpected elevation response format: {type(elevations)}")
                return None

        except requests.exceptions.Timeout as e:
            last_error = e
            logger.warning(f"Elevation API timeout (attempt {attempt + 1}/{MAX_RETRIES})")
            event.add_technical_metric(f"attempt_{attempt + 1}_timeout", True)

        except requests.exceptions.ConnectionError as e:
            last_error = e
            logger.warning(f"Elevation API connection error (attempt {attempt + 1}/{MAX_RETRIES})")
            event.add_technical_metric(f"attempt_{attempt + 1}_connection_error", True)

        except requests.exceptions.HTTPError as e:
            last_error = e
            if e.response is not None:
                status_code = e.response.status_code
                if 400 <= status_code < 500:
                    logger.warning(f"Elevation API client error: HTTP {status_code}")
                    return None
            logger.warning(f"Elevation API HTTP error (attempt {attempt + 1}/{MAX_RETRIES}): {e}")

        except Exception as e:
            last_error = e
            logger.exception(f"Elevation API unexpected error: {e}")
            event.add_technical_metric(f"attempt_{attempt + 1}_unexpected_error", True)

        # Wait before retrying
        if attempt < MAX_RETRIES - 1:
            time.sleep(delay)
            delay *= 2

    logger.warning(f"Elevation API failed after {MAX_RETRIES} attempts: {last_error}")
    return None


def calculate_elevation_profile(elevations: List[float]) -> Dict[str, Any]:
    """
    Calculate elevation statistics from a series of readings.

    Args:
        elevations: List of elevation values in meters

    Returns:
        Dictionary with elevation statistics
    """
    if not elevations:
        return {
            "total_gain_m": None,
            "total_loss_m": None,
            "net_change_m": None,
            "max_elevation_m": None,
            "min_elevation_m": None,
        }

    # Filter out None values
    valid_elevations = [e for e in elevations if e is not None]
    if len(valid_elevations) < 2:
        return {
            "total_gain_m": None,
            "total_loss_m": None,
            "net_change_m": None,
            "max_elevation_m": valid_elevations[0] if valid_elevations else None,
            "min_elevation_m": valid_elevations[0] if valid_elevations else None,
        }

    total_gain = 0.0
    total_loss = 0.0

    for i in range(1, len(valid_elevations)):
        diff = valid_elevations[i] - valid_elevations[i - 1]
        if diff > 0:
            total_gain += diff
        else:
            total_loss += abs(diff)

    return {
        "total_gain_m": round(total_gain, 1),
        "total_loss_m": round(total_loss, 1),
        "net_change_m": round(valid_elevations[-1] - valid_elevations[0], 1),
        "max_elevation_m": round(max(valid_elevations), 1),
        "min_elevation_m": round(min(valid_elevations), 1),
    }


def estimate_elevation_impact_factor(
    gain_m: float,
    loss_m: float,
    distance_miles: float,
) -> float:
    """
    Estimate efficiency impact from elevation changes.

    Uphill driving uses more energy (roughly 5-10% per 100m gain per mile).
    Downhill driving can regenerate energy (50-70% efficiency recovery).

    Args:
        gain_m: Total elevation gained in meters
        loss_m: Total elevation lost in meters
        distance_miles: Trip distance in miles

    Returns:
        Impact factor (1.0 = neutral, >1.0 = worse efficiency, <1.0 = better)
    """
    if distance_miles <= 0 or (gain_m == 0 and loss_m == 0):
        return 1.0

    # Calculate gain/loss per mile
    gain_per_mile = gain_m / distance_miles
    loss_per_mile = loss_m / distance_miles

    # Estimate impact
    # Uphill: ~7% efficiency loss per 100m of gain per mile
    uphill_penalty = (gain_per_mile / 100) * 0.07

    # Downhill: ~4% efficiency gain per 100m of loss per mile (regen is ~60% efficient)
    downhill_benefit = (loss_per_mile / 100) * 0.04

    impact = 1.0 + uphill_penalty - downhill_benefit

    # Clamp to reasonable range
    return max(0.7, min(1.5, round(impact, 3)))


def sample_coordinates(
    coordinates: List[Tuple[float, float]],
    max_samples: int = 25,
) -> List[Tuple[float, float]]:
    """
    Sample coordinates to reduce API calls while maintaining profile accuracy.

    Always includes first and last point.

    Args:
        coordinates: Full list of coordinates
        max_samples: Maximum number of samples to return

    Returns:
        Sampled list of coordinates
    """
    if len(coordinates) <= max_samples:
        return coordinates

    # Always include first and last
    if max_samples < 2:
        return [coordinates[0], coordinates[-1]]

    # Calculate sample rate
    sample_rate = len(coordinates) / (max_samples - 1)

    sampled = [coordinates[0]]
    for i in range(1, max_samples - 1):
        idx = int(i * sample_rate)
        if idx < len(coordinates):
            sampled.append(coordinates[idx])

    sampled.append(coordinates[-1])

    return sampled
