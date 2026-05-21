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
from config import Config
from exceptions import WeatherAPIError
from utils.error_codes import ErrorCode, StructuredError
from utils.timezone import normalize_datetime, utc_now
from utils.wide_events import WideEvent

logger = logging.getLogger(__name__)

OPEN_METEO_URL = "https://api.open-meteo.com/v1/forecast"
OPEN_METEO_HISTORICAL_URL = "https://archive-api.open-meteo.com/v1/archive"

# Retry configuration - now configurable via Config
# Defaults: 2 retries, 0.5s delay, 3s timeout
# Keep short to avoid blocking scheduler

# Simple in-memory cache for weather data with LRU eviction
# Key: (lat_rounded, lon_rounded, datetime_hour_str) -> Value: (data, timestamp)
# Max size: 1000 entries (~1 month of unique location+hour combinations)
from collections import OrderedDict

_weather_cache: OrderedDict = OrderedDict()
MAX_WEATHER_CACHE_SIZE = 1000  # Limit to prevent memory leak


def _request_with_retry(url: str, params: Dict[str, Any], timeout: int) -> Optional[Dict[str, Any]]:
    """
    Make an HTTP GET request with retry logic and exponential backoff.

    Emits service boundary event for external API tracking.

    Args:
        url: API endpoint URL
        params: Query parameters
        timeout: Request timeout in seconds

    Returns:
        JSON response as dict, or None if all retries failed
    """
    # Create service boundary event for external API call
    event = WideEvent("external_api_weather")
    event.add_context(
        service="open_meteo",
        url=url,
        latitude=params.get("latitude"),
        longitude=params.get("longitude"),
        timeout_seconds=timeout,
    )

    last_error: Optional[Exception] = None
    delay = Config.WEATHER_API_RETRY_DELAY
    total_attempts = 0
    max_retries = Config.WEATHER_API_MAX_RETRIES

    for attempt in range(max_retries):
        total_attempts += 1
        try:
            # Time the individual request attempt
            with event.timer(f"request_attempt_{attempt + 1}"):
                response = requests.get(url, params=params, timeout=timeout)
                response.raise_for_status()
                data = cast(Dict[str, Any], response.json())

            # Success!
            event.add_context(
                attempts=total_attempts,
                status_code=response.status_code,
                response_size_bytes=len(response.content),
            )
            event.mark_success()
            event.emit()  # Always emit service boundary events
            return data

        except requests.exceptions.Timeout as e:
            last_error = e
            logger.warning(f"Weather API timeout (attempt {attempt + 1}/{max_retries})")
            event.add_technical_metric(f"attempt_{attempt + 1}_timeout", True)

        except requests.exceptions.ConnectionError as e:
            last_error = e
            logger.warning(f"Weather API connection error (attempt {attempt + 1}/{max_retries})")
            event.add_technical_metric(f"attempt_{attempt + 1}_connection_error", True)

        except requests.exceptions.HTTPError as e:
            last_error = e
            # Don't retry on 4xx client errors (bad request, not found, etc.)
            if e.response is not None:
                status_code = e.response.status_code
                event.add_context(status_code=status_code)

                if 400 <= status_code < 500:
                    logger.warning(f"Weather API client error: {e}")
                    structured_error = StructuredError(
                        ErrorCode.E102_WEATHER_API_INVALID_RESPONSE,
                        f"Weather API client error: HTTP {status_code}",
                        exception=e,
                        latitude=params.get("latitude"),
                        longitude=params.get("longitude"),
                    )
                    event.add_error(structured_error)
                    event.add_context(attempts=total_attempts)
                    event.emit(level="warning", force=True)
                    return None

            logger.warning(f"Weather API HTTP error (attempt {attempt + 1}/{max_retries}): {e}")
            event.add_technical_metric(f"attempt_{attempt + 1}_http_error", True)

        except requests.exceptions.JSONDecodeError as e:
            last_error = e
            # Invalid JSON response - don't retry
            logger.warning(f"Weather API invalid JSON response: {e}")
            structured_error = StructuredError(
                ErrorCode.E102_WEATHER_API_INVALID_RESPONSE,
                "Weather API returned invalid JSON",
                exception=e,
                latitude=params.get("latitude"),
                longitude=params.get("longitude"),
            )
            event.add_error(structured_error)
            event.add_context(attempts=total_attempts)
            event.emit(level="warning", force=True)
            return None

        except Exception as e:
            last_error = e
            # Log full traceback for unexpected errors
            logger.exception(f"Weather API unexpected error (attempt {attempt + 1}/{max_retries}): {e}")
            event.add_technical_metric(f"attempt_{attempt + 1}_unexpected_error", True)

        # Wait before retrying (exponential backoff)
        if attempt < max_retries - 1:
            time.sleep(delay)
            delay *= 2  # Double delay for next attempt

    # All retries failed
    logger.warning(f"Weather API failed after {max_retries} attempts: {last_error}")

    # Emit failure event with appropriate error code
    if isinstance(last_error, requests.exceptions.Timeout):
        error_code = ErrorCode.E100_WEATHER_API_TIMEOUT
    elif isinstance(last_error, requests.exceptions.ConnectionError):
        error_code = ErrorCode.E101_WEATHER_API_CONNECTION
    else:
        error_code = ErrorCode.E102_WEATHER_API_INVALID_RESPONSE

    structured_error = StructuredError(
        error_code,
        f"Weather API failed after {total_attempts} attempts: {type(last_error).__name__}",
        exception=last_error,
        latitude=params.get("latitude"),
        longitude=params.get("longitude"),
    )
    event.add_error(structured_error)
    event.add_context(attempts=total_attempts)
    event.emit(level="warning", force=True)

    return None


def _check_db_cache(db_session, lat_key, lon_key, timestamp_hour, latitude, longitude):
    """Check database cache for weather data. Returns cached data or None."""
    try:
        from models import WeatherCache

        db_cache = db_session.query(WeatherCache).filter(
            WeatherCache.latitude_key == lat_key,
            WeatherCache.longitude_key == lon_key,
            WeatherCache.timestamp_hour == timestamp_hour
        ).first()

        if not db_cache:
            return None

        # fetched_at is a DateTime(timezone=True) column: Postgres returns it
        # tz-aware while utc_now() is naive. Normalize both to naive UTC so the
        # subtraction does not raise TypeError (which would silently disable
        # the persistent weather cache).
        cache_age = utc_now() - normalize_datetime(db_cache.fetched_at)
        if cache_age.total_seconds() >= Config.WEATHER_CACHE_TIMEOUT_SECONDS:
            db_session.delete(db_cache)
            db_session.commit()
            logger.debug(f"DB cache expired for ({latitude:.2f}, {longitude:.2f}), deleted")
            return None

        logger.debug(
            f"DB cache hit for ({latitude:.2f}, {longitude:.2f}) "
            f"at {timestamp_hour} (age: {cache_age.total_seconds():.0f}s)"
        )
        # Populate in-memory cache
        cache_key = (lat_key, lon_key, timestamp_hour)
        _weather_cache[cache_key] = (db_cache.to_dict(), time.time())
        if len(_weather_cache) > MAX_WEATHER_CACHE_SIZE:
            _weather_cache.popitem(last=False)
        return db_cache.to_dict()
    except Exception as e:
        logger.warning(f"Error checking database cache: {e}")
        return None


def _check_memory_cache(cache_key, latitude, longitude, timestamp_hour):
    """Check in-memory cache for weather data. Returns cached data or None."""
    current_time = time.time()
    if cache_key not in _weather_cache:
        return None
    cached_data, cache_timestamp = _weather_cache[cache_key]
    age_seconds = current_time - cache_timestamp
    if age_seconds >= Config.WEATHER_CACHE_TIMEOUT_SECONDS:
        del _weather_cache[cache_key]
        logger.debug(f"Memory cache expired for ({latitude:.2f}, {longitude:.2f})")
        return None
    _weather_cache.move_to_end(cache_key)
    logger.debug(
        f"Memory cache hit for ({latitude:.2f}, {longitude:.2f}) "
        f"at {timestamp_hour} (age: {age_seconds:.0f}s)"
    )
    return cached_data


def _store_in_caches(cache_key, data, db_session, lat_key, lon_key, timestamp_hour, api_source, latitude, longitude):
    """Store weather data in both memory and DB caches."""
    current_time = time.time()
    if len(_weather_cache) >= MAX_WEATHER_CACHE_SIZE:
        _weather_cache.popitem(last=False)
    _weather_cache[cache_key] = (data, current_time)
    _weather_cache.move_to_end(cache_key)

    if db_session and timestamp_hour:
        try:
            from models import WeatherCache
            existing = db_session.query(WeatherCache).filter(
                WeatherCache.latitude_key == lat_key,
                WeatherCache.longitude_key == lon_key,
                WeatherCache.timestamp_hour == timestamp_hour
            ).one_or_none()
            if existing:
                existing.temperature_f = data.get("temperature_f")
                existing.precipitation_in = data.get("precipitation_in")
                existing.wind_speed_mph = data.get("wind_speed_mph")
                existing.weather_code = data.get("weather_code")
                existing.conditions = data.get("conditions")
                existing.api_source = api_source
                existing.fetched_at = utc_now()
            else:
                db_cache = WeatherCache(
                    latitude_key=lat_key,
                    longitude_key=lon_key,
                    timestamp_hour=timestamp_hour,
                    temperature_f=data.get("temperature_f"),
                    precipitation_in=data.get("precipitation_in"),
                    wind_speed_mph=data.get("wind_speed_mph"),
                    weather_code=data.get("weather_code"),
                    conditions=data.get("conditions"),
                    api_source=api_source,
                    fetched_at=utc_now()
                )
                db_session.add(db_cache)
            db_session.commit()
            logger.debug(f"Stored in DB cache for ({latitude:.2f}, {longitude:.2f}) at {timestamp_hour}")
        except Exception as e:
            logger.warning(f"Failed to store in database cache: {e}")
            db_session.rollback()

    logger.debug(
        f"Weather cached for ({latitude:.2f}, {longitude:.2f}) "
        f"at {timestamp_hour} "
        f"(memory: {len(_weather_cache)}/{MAX_WEATHER_CACHE_SIZE})"
    )


def get_weather_for_location(
    latitude: float, longitude: float,
    timestamp: Optional[datetime] = None,
    timeout: Optional[int] = None, db_session=None
) -> Optional[Dict[str, Any]]:
    """
    Fetch weather data for a location at a given time with 2-tier caching.

    Caching strategy:
    1. Check database cache (persistent, survives restarts)
    2. Check in-memory cache (fast, but lost on restart)
    3. Fetch from API and store in both caches

    Uses Open-Meteo API (free, no API key needed).
    Caches results for 1 hour (configurable via WEATHER_CACHE_TIMEOUT_SECONDS).
    Total max blocking time: ~7s (2 retries × 3s timeout + 0.5s delay)

    Args:
        latitude: GPS latitude
        longitude: GPS longitude
        timestamp: Time to get weather for (defaults to now)
        timeout: Request timeout in seconds (default: 3s to avoid blocking scheduler)
        db_session: Optional database session for persistent cache (if None, skips DB cache)

    Returns:
        Dictionary with weather data or None if request failed
    """
    if timestamp is None:
        timestamp = utc_now()

    if timeout is None:
        timeout = Config.WEATHER_API_TIMEOUT

    # Create cache key: round coordinates to 2 decimals (~1km precision) and hour
    # This allows cache sharing between nearby locations at same time
    normalized_timestamp = normalize_datetime(timestamp)
    timestamp_hour = normalized_timestamp.strftime("%Y-%m-%d-%H") if normalized_timestamp else None
    lat_key, lon_key, _ = (round(latitude, 2), round(longitude, 2), timestamp_hour)

    # Check database cache first (persistent across restarts)
    if db_session and timestamp_hour:
        db_result = _check_db_cache(
            db_session, lat_key, lon_key, timestamp_hour, latitude, longitude
        )
        if db_result is not None:
            return db_result

    # Check in-memory cache with LRU behavior
    cache_key = (lat_key, lon_key, timestamp_hour)
    mem_result = _check_memory_cache(cache_key, latitude, longitude, timestamp_hour)
    if mem_result is not None:
        return mem_result

    # Cache miss or expired - fetch from API
    # Determine if we need historical or forecast API
    now = normalize_datetime(utc_now())
    days_ago = (now - normalized_timestamp).days if normalized_timestamp else 0

    try:
        api_source = "historical" if days_ago > 5 else "forecast"

        if days_ago > 5:
            # Use historical API for older data
            data = _get_historical_weather(latitude, longitude, timestamp, timeout)
        else:
            # Use forecast API for recent/current data
            data = _get_forecast_weather(latitude, longitude, timestamp, timeout)

        # Cache successful result in both caches
        if data is not None:
            _store_in_caches(
                cache_key, data, db_session, lat_key, lon_key,
                timestamp_hour, api_source, latitude, longitude
            )

        return data

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
    precip_value = precip[idx] if idx < len(precip) else None

    return {
        "temperature_f": temps[idx] if idx < len(temps) else None,
        "precipitation_in": precip_value,
        "wind_speed_mph": wind[idx] if idx < len(wind) else None,
        "weather_code": weather_code,
        # Open-Meteo can return null for missing hourly data; guard against None
        "is_raining": precip_value is not None and precip_value > 0,
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


def _temperature_impact(temp_f):
    """Calculate efficiency impact from temperature."""
    if temp_f is None:
        return 0.0
    if temp_f < 32:
        return 0.20
    if temp_f < 45:
        return 0.10
    if temp_f < 55:
        return 0.05
    if temp_f > 95:
        return 0.10
    if temp_f > 85:
        return 0.05
    return 0.0


def _precipitation_impact(weather):
    """Calculate efficiency impact from precipitation."""
    if not weather.get("is_raining"):
        return 0.0
    precip = weather.get("precipitation_in", 0)
    return 0.10 if precip > 0.25 else 0.05


def _wind_impact(wind_mph):
    """Calculate efficiency impact from wind speed."""
    if wind_mph is None:
        return 0.0
    if wind_mph > 25:
        return 0.10
    if wind_mph > 15:
        return 0.05
    return 0.0


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

    return (
        1.0
        + _temperature_impact(weather.get("temperature_f"))
        + _precipitation_impact(weather)
        + _wind_impact(weather.get("wind_speed_mph"))
    )
