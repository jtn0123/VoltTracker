"""
Weather API Integration for VoltTracker

Uses Open-Meteo API (free, no API key required) to fetch weather data
for correlation with trip efficiency.
"""

import logging
import time
from datetime import datetime
from typing import Any, Dict, Optional, cast

import requests
from exceptions import WeatherAPIError
from utils.timezone import normalize_datetime, utc_now

logger = logging.getLogger(__name__)

OPEN_METEO_URL = "https://api.open-meteo.com/v1/forecast"
OPEN_METEO_HISTORICAL_URL = "https://archive-api.open-meteo.com/v1/archive"

# Retry configuration - keep short to avoid blocking scheduler
MAX_RETRIES = 2  # Reduce retries to minimize blocking time
RETRY_DELAY_SECONDS = 0.5  # Short delay between retries
WEATHER_API_TIMEOUT = 3  # Default timeout per request (seconds)


def _request_with_retry(url: str, params: Dict[str, Any], timeout: int) -> Optional[Dict[str, Any]]:
    """
    Make an HTTP GET request with retry logic and exponential backoff.

    Args:
        url: API endpoint URL
        params: Query parameters
        timeout: Request timeout in seconds

    Returns:
        JSON response as dict, or None if all retries failed
    """
    last_error: Optional[Exception] = None
    delay = RETRY_DELAY_SECONDS

    for attempt in range(MAX_RETRIES):
        try:
            response = requests.get(url, params=params, timeout=timeout)
            response.raise_for_status()
            return cast(Dict[str, Any], response.json())
        except requests.exceptions.Timeout as e:
            last_error = e
            logger.warning(f"Weather API timeout (attempt {attempt + 1}/{MAX_RETRIES})")
        except requests.exceptions.ConnectionError as e:
            last_error = e
            logger.warning(f"Weather API connection error (attempt {attempt + 1}/{MAX_RETRIES})")
        except requests.exceptions.HTTPError as e:
            # Don't retry on 4xx client errors (bad request, not found, etc.)
            if e.response is not None and 400 <= e.response.status_code < 500:
                logger.warning(f"Weather API client error: {e}")
                return None
            last_error = e
            logger.warning(f"Weather API HTTP error (attempt {attempt + 1}/{MAX_RETRIES}): {e}")
        except requests.exceptions.JSONDecodeError as e:
            # Invalid JSON response - don't retry
            logger.warning(f"Weather API invalid JSON response: {e}")
            return None
        except Exception as e:
            # Log full traceback for unexpected errors
            logger.exception(f"Weather API unexpected error (attempt {attempt + 1}/{MAX_RETRIES}): {e}")
            last_error = e

        # Wait before retrying (exponential backoff)
        if attempt < MAX_RETRIES - 1:
            time.sleep(delay)
            delay *= 2  # Double delay for next attempt

    logger.warning(f"Weather API failed after {MAX_RETRIES} attempts: {last_error}")
    return None


def get_weather_for_location(
    latitude: float, longitude: float, timestamp: Optional[datetime] = None, timeout: int = WEATHER_API_TIMEOUT
) -> Optional[Dict[str, Any]]:
    """
    Fetch weather data for a location at a given time.

    Uses Open-Meteo API (free, no API key needed).
    Total max blocking time: ~7s (2 retries × 3s timeout + 0.5s delay)

    Args:
        latitude: GPS latitude
        longitude: GPS longitude
        timestamp: Time to get weather for (defaults to now)
        timeout: Request timeout in seconds (default: 3s to avoid blocking scheduler)

    Returns:
        Dictionary with weather data or None if request failed
    """
    if timestamp is None:
        timestamp = utc_now()

    # Determine if we need historical or forecast API
    # Normalize both datetimes to handle mixed timezone states
    now = normalize_datetime(utc_now())
    normalized_timestamp = normalize_datetime(timestamp)
    days_ago = (now - normalized_timestamp).days if normalized_timestamp else 0

    try:
        if days_ago > 5:
            # Use historical API for older data
            return _get_historical_weather(latitude, longitude, timestamp, timeout)
        else:
            # Use forecast API for recent/current data
            return _get_forecast_weather(latitude, longitude, timestamp, timeout)
    except WeatherAPIError:
        raise
    except (ValueError, KeyError, TypeError) as e:
        error = WeatherAPIError(f"Weather API parsing error: {e}", latitude=latitude, longitude=longitude)
        logger.warning(str(error))
        return None
    except Exception as e:
        logger.exception(f"Unexpected error fetching weather for ({latitude}, {longitude}): {e}")
        return None


def _get_forecast_weather(
    latitude: float, longitude: float, timestamp: datetime, timeout: int
) -> Optional[Dict[str, Any]]:
    """Fetch weather from forecast API with retry logic."""
    params = {
        "latitude": latitude,
        "longitude": longitude,
        "hourly": "temperature_2m,precipitation,wind_speed_10m,weather_code",
        "temperature_unit": "fahrenheit",
        "wind_speed_unit": "mph",
        "precipitation_unit": "inch",
        "timezone": "auto",
    }

    data = _request_with_retry(OPEN_METEO_URL, params, timeout)
    if data is None:
        return None

    return _parse_weather_response(data, timestamp)


def _get_historical_weather(
    latitude: float, longitude: float, timestamp: datetime, timeout: int
) -> Optional[Dict[str, Any]]:
    """Fetch weather from historical archive API with retry logic."""
    date_str = timestamp.strftime("%Y-%m-%d")

    params = {
        "latitude": latitude,
        "longitude": longitude,
        "start_date": date_str,
        "end_date": date_str,
        "hourly": "temperature_2m,precipitation,wind_speed_10m,weather_code",
        "temperature_unit": "fahrenheit",
        "wind_speed_unit": "mph",
        "precipitation_unit": "inch",
        "timezone": "auto",
    }

    data = _request_with_retry(OPEN_METEO_HISTORICAL_URL, params, timeout)
    if data is None:
        return None

    return _parse_weather_response(data, timestamp)


def _parse_weather_response(data: Dict, timestamp: datetime) -> Optional[Dict[str, Any]]:
    """Parse Open-Meteo response and extract weather for specific hour."""
    if "hourly" not in data:
        return None

    hourly = data["hourly"]
    times = hourly.get("time", [])
    temps = hourly.get("temperature_2m", [])
    precip = hourly.get("precipitation", [])
    wind = hourly.get("wind_speed_10m", [])
    codes = hourly.get("weather_code", [])

    # Find the closest hour
    target_hour = timestamp.replace(minute=0, second=0, microsecond=0)
    target_str = target_hour.strftime("%Y-%m-%dT%H:%M")

    try:
        idx = times.index(target_str)
    except ValueError:
        # Find closest match
        idx = timestamp.hour if timestamp.hour < len(times) else 0

    if idx >= len(temps):
        return None

    weather_code = codes[idx] if idx < len(codes) else None

    return {
        "temperature_f": temps[idx] if idx < len(temps) else None,
        "precipitation_in": precip[idx] if idx < len(precip) else None,
        "wind_speed_mph": wind[idx] if idx < len(wind) else None,
        "weather_code": weather_code,
        "is_raining": precip[idx] > 0 if idx < len(precip) else False,
        "conditions": _weather_code_to_description(weather_code),
        "timestamp": timestamp.isoformat(),
    }


def _weather_code_to_description(code: Optional[int]) -> str:
    """Convert WMO weather code to human-readable description."""
    if code is None:
        return "Unknown"

    # WMO Weather interpretation codes (WW)
    # https://open-meteo.com/en/docs
    weather_codes = {
        0: "Clear",
        1: "Mainly Clear",
        2: "Partly Cloudy",
        3: "Overcast",
        45: "Foggy",
        48: "Rime Fog",
        51: "Light Drizzle",
        53: "Moderate Drizzle",
        55: "Dense Drizzle",
        56: "Freezing Drizzle",
        57: "Heavy Freezing Drizzle",
        61: "Light Rain",
        63: "Moderate Rain",
        65: "Heavy Rain",
        66: "Light Freezing Rain",
        67: "Heavy Freezing Rain",
        71: "Light Snow",
        73: "Moderate Snow",
        75: "Heavy Snow",
        77: "Snow Grains",
        80: "Light Showers",
        81: "Moderate Showers",
        82: "Heavy Showers",
        85: "Light Snow Showers",
        86: "Heavy Snow Showers",
        95: "Thunderstorm",
        96: "Thunderstorm with Light Hail",
        99: "Thunderstorm with Heavy Hail",
    }

    return weather_codes.get(code, f"Code {code}")


def get_weather_impact_factor(weather: Dict[str, Any]) -> float:
    """
    Calculate an impact factor for weather conditions on efficiency.

    Returns a multiplier where:
    - 1.0 = ideal conditions
    - > 1.0 = worse than ideal (expect lower efficiency)
    - < 1.0 = better than ideal (rare)
    """
    if not weather:
        return 1.0

    factor = 1.0

    # Temperature impact (ideal: 65-75°F)
    temp = weather.get("temperature_f")
    if temp is not None:
        if temp < 32:
            factor += 0.20  # Cold weather significantly impacts EV efficiency
        elif temp < 45:
            factor += 0.10
        elif temp < 55:
            factor += 0.05
        elif temp > 95:
            factor += 0.10  # Hot weather (A/C usage)
        elif temp > 85:
            factor += 0.05

    # Rain/precipitation impact
    if weather.get("is_raining"):
        precip = weather.get("precipitation_in", 0)
        if precip > 0.25:
            factor += 0.10  # Heavy rain
        else:
            factor += 0.05  # Light rain

    # Wind impact
    wind = weather.get("wind_speed_mph")
    if wind is not None:
        if wind > 25:
            factor += 0.10  # Strong wind
        elif wind > 15:
            factor += 0.05

    return factor
