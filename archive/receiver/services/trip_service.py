"""
Trip service for VoltTracker.

Handles trip finalization logic, calculating statistics for completed trips.
"""

import logging
import time
from datetime import datetime
from typing import Dict

from config import Config
from exceptions import WeatherAPIError
from models import SocTransition, TelemetryRaw, Trip
from utils import (
    calculate_average_temp,
    calculate_electric_kwh,
    calculate_electric_miles,
    calculate_gas_mpg,
    calculate_kwh_per_mile,
    detect_gas_mode_entry,
    normalize_datetime,
)
from utils.context_enrichment import enrich_event_with_vehicle_context
from utils.elevation import (
    calculate_elevation_profile,
    get_elevation_for_points,
    sample_coordinates,
)
from utils.error_codes import ErrorCode, StructuredError
from utils.weather import get_weather_for_location, get_weather_impact_factor
from utils.wide_events import WideEvent

logger = logging.getLogger(__name__)


def calculate_trip_basics(trip: Trip, telemetry: list) -> None:
    """
    Calculate basic trip metrics: end time, odometer, distance, temperature.

    Args:
        trip: Trip to update
        telemetry: List of TelemetryRaw records (ordered by timestamp)
    """
    if not telemetry:
        logger.warning(f"No telemetry data for trip {trip.id}")
        return

    trip.end_time = telemetry[-1].timestamp

    # Use the last telemetry point that actually has an odometer reading.
    # The final point can have a None odometer (sensor dropout at trip end),
    # which would otherwise wipe out a valid end_odometer / distance.
    end_odometer = next(
        (t.odometer_miles for t in reversed(telemetry) if t.odometer_miles is not None),
        None,
    )
    if end_odometer is not None:
        trip.end_odometer = end_odometer

    if trip.start_odometer and trip.end_odometer:
        trip.distance_miles = trip.end_odometer - trip.start_odometer

    # Calculate average temperature from telemetry points
    points = [t.to_dict() for t in telemetry]
    trip.ambient_temp_avg_f = calculate_average_temp(points)


def _calculate_gas_electric_split(trip, gas_entry):
    """Calculate electric and gas mile split from gas entry data."""
    if gas_entry.get("odometer_miles") and trip.start_odometer and trip.end_odometer:
        trip.electric_miles, trip.gas_miles = calculate_electric_miles(
            gas_entry.get("odometer_miles"), trip.start_odometer, trip.end_odometer
        )
        return
    trip.electric_miles = 0.0
    if trip.distance_miles:
        trip.gas_miles = trip.distance_miles


def _calculate_fuel_usage(trip):
    """Calculate fuel used during gas mode portion of trip."""
    if not trip.fuel_level_at_gas_entry or not trip.fuel_level_at_end:
        return
    fuel_change = trip.fuel_level_at_gas_entry - trip.fuel_level_at_end
    if fuel_change > 0:
        from utils import fuel_percent_to_gallons
        trip.fuel_used_gallons = fuel_percent_to_gallons(fuel_change)
    elif fuel_change < -5:
        logger.info(
            f"Refuel detected during trip {trip.id}: "
            f"{trip.fuel_level_at_gas_entry:.1f}% → {trip.fuel_level_at_end:.1f}%"
        )


def _record_soc_transition(db, trip, gas_entry):
    """Record SOC transition for battery health tracking (avoid duplicates)."""
    if trip.soc_at_gas_transition is None:
        return
    existing = db.query(SocTransition).filter(SocTransition.trip_id == trip.id).first()
    if existing:
        return
    db.add(SocTransition(
        trip_id=trip.id,
        timestamp=trip.gas_mode_entry_time,
        soc_at_transition=trip.soc_at_gas_transition,
        ambient_temp_f=gas_entry.get("ambient_temp_f"),
        odometer_miles=gas_entry.get("odometer_miles"),
    ))


def _apply_gas_entry(db, trip, telemetry, gas_entry):
    """Apply gas mode entry data to trip."""
    trip.gas_mode_entered = True

    entry_time = gas_entry.get("timestamp")
    if isinstance(entry_time, str):
        entry_time = datetime.fromisoformat(entry_time.replace("Z", "+00:00"))
    trip.gas_mode_entry_time = normalize_datetime(entry_time)

    trip.soc_at_gas_transition = gas_entry.get("state_of_charge")
    trip.fuel_level_at_gas_entry = gas_entry.get("fuel_level_percent")
    trip.fuel_level_at_end = telemetry[-1].fuel_level_percent

    _calculate_gas_electric_split(trip, gas_entry)

    if trip.gas_miles and trip.gas_miles >= 1.0:
        trip.gas_mpg = calculate_gas_mpg(
            gas_entry.get("odometer_miles"), trip.end_odometer,
            trip.fuel_level_at_gas_entry, trip.fuel_level_at_end
        )

    _calculate_fuel_usage(trip)

    if trip.gas_miles is None and trip.distance_miles:
        logger.debug(f"Trip {trip.id}: Applying fallback gas_miles = distance_miles")
        trip.gas_miles = trip.distance_miles
        trip.electric_miles = 0.0

    _record_soc_transition(db, trip, gas_entry)


def process_gas_mode(db, trip: Trip, telemetry: list, points: list) -> None:
    """
    Process gas mode entry: detect transition, calculate miles/MPG, record SOC.

    Args:
        db: Database session
        trip: Trip to update
        telemetry: List of TelemetryRaw records
        points: List of telemetry dicts (from to_dict())
    """
    if not telemetry or not points:
        return

    gas_entry = detect_gas_mode_entry(points)
    if gas_entry:
        _apply_gas_entry(db, trip, telemetry, gas_entry)
    elif trip.start_odometer and trip.end_odometer:
        trip.electric_miles = trip.distance_miles


def calculate_electric_efficiency(trip: Trip, points: list) -> None:
    """
    Calculate electric efficiency metrics: kWh used, kWh per mile.

    Args:
        trip: Trip to update
        points: List of telemetry dicts
    """
    if trip.electric_miles and trip.electric_miles > 0.5:
        trip.electric_kwh_used = calculate_electric_kwh(points)
        if trip.electric_kwh_used:
            trip.kwh_per_mile = calculate_kwh_per_mile(trip.electric_kwh_used, trip.electric_miles)


def _build_sample_times(start_time, end_time, interval_minutes):
    """Build list of sample timestamps at regular intervals across a time range."""
    from datetime import timedelta

    duration = (end_time - start_time).total_seconds() / 60
    if duration < interval_minutes:
        return [start_time, end_time]

    sample_times = []
    current_time = start_time
    while current_time <= end_time:
        sample_times.append(current_time)
        current_time += timedelta(minutes=interval_minutes)

    if sample_times[-1] < end_time:
        sample_times.append(end_time)
    return sample_times


def _collect_weather_samples(trip_id, gps_points, sample_times, db_session):
    """Fetch weather data for each sample time using nearest GPS point."""
    from datetime import datetime

    samples = []
    last_location = None
    for sample_time in sample_times:
        closest_point = min(
            gps_points,
            key=lambda p, st=sample_time: abs((datetime.fromisoformat(p["timestamp"]) - st).total_seconds())
        )
        last_location = closest_point
        weather = get_weather_for_location(
            closest_point["latitude"], closest_point["longitude"],
            sample_time, db_session=db_session
        )
        if weather:
            samples.append(weather)
            logger.debug(
                f"Trip {trip_id}: Weather sample at {sample_time.strftime('%H:%M')}: "
                f"{weather.get('temperature_f')}°F, {weather.get('conditions')}"
            )
    return samples, last_location


def _dominant_condition(weather_samples):
    """Return the most frequent/severe weather condition from samples."""
    condition_counts: Dict[str, int] = {}
    for w in weather_samples:
        cond = w.get("conditions")
        if cond:
            condition_counts[cond] = condition_counts.get(cond, 0) + 1
    if not condition_counts:
        return None
    severity_order = {
        "Heavy Rain": 5, "Rain": 4, "Light Rain": 3,
        "Cloudy": 2, "Partly Cloudy": 1, "Clear": 0,
    }
    return max(
        condition_counts.keys(),
        key=lambda c: (condition_counts[c], severity_order.get(c, 0))
    )


def _average_impact_factor(weather_samples):
    """Calculate average weather impact factor from samples."""
    import statistics
    factors = [f for w in weather_samples if (f := get_weather_impact_factor(w))]
    return round(statistics.mean(factors), 3) if factors else None


def _detect_extreme_weather(trip):
    """Detect extreme weather conditions and return (is_extreme, reasons)."""
    reasons = []
    if trip.weather_temp_f is not None:
        if trip.weather_temp_f < 32:
            reasons.append("freezing")
        elif trip.weather_temp_f > 95:
            reasons.append("very hot")
    if trip.weather_precipitation_in is not None and trip.weather_precipitation_in > 0.25:
        reasons.append("heavy rain")
    if trip.weather_wind_mph is not None and trip.weather_wind_mph > 25:
        reasons.append("strong wind")
    return bool(reasons), reasons


def _apply_weather_averages(trip, weather_samples):
    """Apply averaged weather data from samples to trip."""
    import statistics

    temps = [w.get("temperature_f") for w in weather_samples if w.get("temperature_f") is not None]
    trip.weather_temp_f = round(statistics.mean(temps), 1) if temps else None

    precips = [w.get("precipitation_in", 0) for w in weather_samples if w.get("precipitation_in") is not None]
    trip.weather_precipitation_in = round(sum(precips), 2) if precips else None

    winds = [w.get("wind_speed_mph") for w in weather_samples if w.get("wind_speed_mph") is not None]
    trip.weather_wind_mph = round(statistics.mean(winds), 1) if winds else None

    trip.weather_conditions = _dominant_condition(weather_samples)
    trip.weather_impact_factor = _average_impact_factor(weather_samples)

    is_extreme, extreme_reasons = _detect_extreme_weather(trip)
    trip.extreme_weather = is_extreme

    logger.info(
        f"Trip {trip.id}: Averaged weather from {len(weather_samples)} samples - "
        f"{trip.weather_temp_f}°F, {trip.weather_conditions}, "
        f"impact factor: {trip.weather_impact_factor}"
        + (f" [EXTREME: {', '.join(extreme_reasons)}]" if is_extreme else "")
    )


def _log_weather_error(_trip_id, sample_location, error_msg):
    """Log a weather API error with location context."""
    error = WeatherAPIError(
        error_msg,
        latitude=sample_location.get("latitude") if sample_location else None,
        longitude=sample_location.get("longitude") if sample_location else None,
    )
    logger.warning(str(error))


def fetch_trip_weather(trip: Trip, points: list, db_session=None) -> None:
    """
    Fetch weather data for the trip by sampling every 15 minutes and averaging.

    Uses 2-tier caching (database + in-memory) to minimize API calls.

    Args:
        trip: Trip to update
        points: List of telemetry dicts (must have GPS data and timestamps)
        db_session: Optional database session for persistent cache
    """
    sample_location = None
    try:
        import requests

        gps_points = [p for p in points if p.get("latitude") and p.get("longitude") and p.get("timestamp")]
        if not gps_points or not trip.start_time or not trip.end_time:
            logger.debug(f"Trip {trip.id}: Insufficient data for weather sampling")
            return

        sample_times = _build_sample_times(
            trip.start_time, trip.end_time, Config.WEATHER_SAMPLE_INTERVAL_MINUTES
        )
        logger.debug(f"Trip {trip.id}: Sampling weather at {len(sample_times)} time points")

        weather_samples, sample_location = _collect_weather_samples(
            trip.id, gps_points, sample_times, db_session
        )

        if weather_samples:
            _apply_weather_averages(trip, weather_samples)
        else:
            logger.warning(f"Trip {trip.id}: No weather samples collected")

    except requests.RequestException as e:
        _log_weather_error(trip.id, sample_location, f"Failed to fetch weather for trip {trip.id}: {e}")
    except (ValueError, KeyError, TypeError) as e:
        _log_weather_error(trip.id, sample_location, f"Failed to parse weather data for trip {trip.id}: {e}")
    except Exception as e:
        logger.exception(f"Unexpected error fetching weather for trip {trip.id}: {e}")


def fetch_trip_elevation(trip: Trip, points: list) -> None:
    """
    Fetch elevation data for the trip GPS coordinates.

    Samples GPS points to reduce API calls, then calculates elevation
    gain/loss/net change.

    Args:
        trip: Trip to update
        points: List of telemetry dicts (must have GPS data)
    """
    try:
        # Extract GPS coordinates from telemetry
        gps_points = [
            (p["latitude"], p["longitude"])
            for p in points
            if p.get("latitude") and p.get("longitude")
        ]

        if len(gps_points) < 2:
            logger.debug(f"Trip {trip.id}: Not enough GPS points for elevation ({len(gps_points)})")
            return

        # Sample coordinates to reduce API calls
        max_samples = getattr(Config, "ELEVATION_SAMPLE_RATE", 25)
        sampled = sample_coordinates(gps_points, max_samples=max_samples)

        # Fetch elevations
        elevations = get_elevation_for_points(sampled)
        if not elevations:
            logger.debug(f"Trip {trip.id}: Elevation API returned no data")
            return

        # Calculate profile
        profile = calculate_elevation_profile(elevations)

        # Update trip with elevation data
        if elevations[0] is not None:
            trip.elevation_start_m = elevations[0]
        if elevations[-1] is not None:
            trip.elevation_end_m = elevations[-1]
        trip.elevation_gain_m = profile.get("total_gain_m")
        trip.elevation_loss_m = profile.get("total_loss_m")
        trip.elevation_net_change_m = profile.get("net_change_m")
        trip.elevation_max_m = profile.get("max_elevation_m")
        trip.elevation_min_m = profile.get("min_elevation_m")

        logger.debug(
            f"Elevation for trip {trip.id}: "
            f"gain={trip.elevation_gain_m}m, loss={trip.elevation_loss_m}m"
        )

    except Exception as e:
        logger.exception(f"Unexpected error fetching elevation for trip {trip.id}: {e}")


def finalize_trip(db, trip: Trip):
    """
    Finalize a trip by calculating statistics.

    Orchestrates the trip finalization process by calling specialized helpers:
    - calculate_trip_basics: End time, distance, temperature
    - process_gas_mode: Gas/electric split, MPG, SOC transition
    - calculate_electric_efficiency: kWh used, efficiency
    - fetch_trip_weather: Weather conditions during trip

    Args:
        db: Database session
        trip: Trip to finalize
    """
    start_time = time.time()

    # Initialize wide event with trace_id to link all operations for this trip
    event = WideEvent("trip_finalization", trace_id=str(trip.session_id))
    event.add_context(
        trip_id=trip.id,
        session_id=str(trip.session_id),
        start_odometer=trip.start_odometer,
        start_soc=trip.start_soc,
    )

    # Add feature flags to track enabled features during finalization
    event.add_feature_flags(
        weather_integration=Config.FEATURE_WEATHER_INTEGRATION,
        enhanced_route_detection=Config.FEATURE_ENHANCED_ROUTE_DETECTION,
        predictive_range=Config.FEATURE_PREDICTIVE_RANGE,
        elevation_tracking=Config.FEATURE_ELEVATION_TRACKING,
    )

    try:
        # Get all telemetry for this trip with performance timing
        with event.timer("db_query_telemetry"):
            telemetry = (
                db.query(TelemetryRaw)
                .filter(TelemetryRaw.session_id == trip.session_id)
                .order_by(TelemetryRaw.timestamp)
                .all()
            )

        event.add_business_metric("telemetry_points", len(telemetry))

        if not telemetry:
            trip.is_closed = True
            event.add_context(no_telemetry=True)
            structured_error = StructuredError(
                ErrorCode.E402_NO_TELEMETRY_DATA,
                f"Trip {trip.id} has no telemetry data",
                trip_id=trip.id,
                session_id=str(trip.session_id),
            )
            event.add_error(structured_error)
            event.mark_failure("no_telemetry_data")
            duration_ms = (time.time() - start_time) * 1000
            event.context["duration_ms"] = round(duration_ms, 2)
            event.emit(level="warning", force=True)
            return

        # Convert to dicts for calculation functions
        points = [t.to_dict() for t in telemetry]

        # Calculate basic trip metrics with performance timing
        with event.timer("calculate_basics"):
            calculate_trip_basics(trip, telemetry)

        # Add trip metrics to event
        event.add_context(
            end_odometer=trip.end_odometer,
            distance_miles=trip.distance_miles,
            avg_temp_f=trip.ambient_temp_avg_f,
        )

        # Process gas mode entry and related calculations
        with event.timer("process_gas_mode"):
            process_gas_mode(db, trip, telemetry, points)

        # Add gas mode context
        event.add_context(
            gas_mode_entered=trip.gas_mode_entered,
            electric_miles=trip.electric_miles,
            gas_miles=trip.gas_miles,
        )

        if trip.gas_mode_entered:
            event.add_business_metric("soc_at_gas_transition", trip.soc_at_gas_transition)
            event.add_business_metric("gas_mpg", trip.gas_mpg)
            event.add_business_metric("fuel_used_gallons", trip.fuel_used_gallons)
            # Mark gas mode as critical business event (always logged)
            event.add_business_metric("gas_mode_entered", True)

        # Calculate electric efficiency
        with event.timer("calculate_efficiency"):
            calculate_electric_efficiency(trip, points)

        # Add efficiency metrics
        event.add_business_metric("electric_kwh_used", trip.electric_kwh_used)
        event.add_business_metric("kwh_per_mile", trip.kwh_per_mile)

        # Fetch weather data (if feature enabled)
        if Config.FEATURE_WEATHER_INTEGRATION:
            with event.timer("fetch_weather"):
                fetch_trip_weather(trip, points, db_session=db)
        else:
            # Skip weather fetch if feature disabled
            event.add_technical_metric("weather_skipped", True)

        # Add weather context
        if trip.weather_temp_f:
            event.add_context(
                weather_temp_f=trip.weather_temp_f,
                weather_conditions=trip.weather_conditions,
                weather_impact_factor=trip.weather_impact_factor,
            )

        # Fetch elevation data (if feature enabled)
        if Config.FEATURE_ELEVATION_TRACKING:
            with event.timer("fetch_elevation"):
                fetch_trip_elevation(trip, points)
        else:
            event.add_technical_metric("elevation_skipped", True)

        # Add elevation context
        if trip.elevation_gain_m is not None:
            event.add_context(
                elevation_gain_m=trip.elevation_gain_m,
                elevation_loss_m=trip.elevation_loss_m,
                elevation_net_change_m=trip.elevation_net_change_m,
            )

        # Enrich event with vehicle context (loggingsucks.com progressive enrichment pattern)
        # Include battery health for trip finalization (business-critical operation)
        with event.timer("context_enrichment"):
            enrich_event_with_vehicle_context(event, db, include_battery_health=True)

        # Mark trip as complete
        trip.is_closed = True

        # Calculate duration and mark success
        duration_ms = (time.time() - start_time) * 1000
        event.context["duration_ms"] = round(duration_ms, 2)
        event.mark_success()

        # Emit comprehensive wide event with configurable sampling
        # Default: 100% sampling for trip finalization (business critical)
        # Always logs: errors, slow finalization (>1s), gas mode transitions
        if event.should_emit(
            sample_rate=Config.LOGGING_SAMPLE_RATE_TRIP,
            slow_threshold_ms=Config.LOGGING_SLOW_THRESHOLD_MS,
        ):
            event.emit()

        logger.info(
            f"Trip {trip.id} finalized: {(trip.distance_miles or 0):.1f} mi "
            f"(electric: {(trip.electric_miles or 0):.1f}, gas: {(trip.gas_miles or 0):.1f}, "
            f"MPG: {trip.gas_mpg or 'N/A'}, kWh/mi: {trip.kwh_per_mile or 'N/A'})"
        )

    except Exception as e:
        # Add error to wide event with appropriate error code
        duration_ms = (time.time() - start_time) * 1000
        event.context["duration_ms"] = round(duration_ms, 2)

        # Determine error code based on exception type
        if isinstance(e, (ValueError, ZeroDivisionError, TypeError)):
            error_code = ErrorCode.E405_EFFICIENCY_CALCULATION_FAILED
        elif hasattr(e, "__module__") and "sqlalchemy" in e.__module__:
            error_code = ErrorCode.E200_DB_CONNECTION_FAILED
        else:
            error_code = ErrorCode.E500_INTERNAL_SERVER_ERROR

        structured_error = StructuredError(
            error_code,
            f"Trip finalization error: {type(e).__name__}",
            exception=e,
            trip_id=trip.id,
            session_id=str(trip.session_id),
        )
        event.add_error(structured_error)
        event.mark_failure(f"finalization_error: {type(e).__name__}")
        event.emit(level="error", force=True)
        raise
