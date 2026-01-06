"""
Trip service for VoltTracker.

Handles trip finalization logic, calculating statistics for completed trips.
"""

import logging
import time
from datetime import datetime

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
    trip.end_odometer = telemetry[-1].odometer_miles

    if trip.start_odometer and trip.end_odometer:
        trip.distance_miles = trip.end_odometer - trip.start_odometer

    # Calculate average temperature from telemetry points
    points = [t.to_dict() for t in telemetry]
    trip.ambient_temp_avg_f = calculate_average_temp(points)


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
        trip.gas_mode_entered = True

        # Parse and normalize entry timestamp
        entry_time = gas_entry.get("timestamp")
        if isinstance(entry_time, str):
            entry_time = datetime.fromisoformat(entry_time.replace("Z", "+00:00"))
        trip.gas_mode_entry_time = normalize_datetime(entry_time)

        trip.soc_at_gas_transition = gas_entry.get("state_of_charge")
        trip.fuel_level_at_gas_entry = gas_entry.get("fuel_level_percent")
        trip.fuel_level_at_end = telemetry[-1].fuel_level_percent

        # Calculate electric and gas miles (need all three odometer values)
        if gas_entry.get("odometer_miles") and trip.start_odometer and trip.end_odometer:
            trip.electric_miles, trip.gas_miles = calculate_electric_miles(
                gas_entry.get("odometer_miles"), trip.start_odometer, trip.end_odometer
            )
        else:
            # Fallback: gas mode entered but odometer missing → use distance_miles
            trip.electric_miles = 0.0
            if trip.distance_miles:
                trip.gas_miles = trip.distance_miles

        # Calculate gas MPG (only if we drove at least 1 mile on gas)
        if trip.gas_miles and trip.gas_miles >= 1.0:
            trip.gas_mpg = calculate_gas_mpg(
                gas_entry.get("odometer_miles"), trip.end_odometer, trip.fuel_level_at_gas_entry, trip.fuel_level_at_end
            )

        # Calculate fuel used (independent of MPG validity for accurate totals)
        if trip.fuel_level_at_gas_entry and trip.fuel_level_at_end:
            fuel_change = trip.fuel_level_at_gas_entry - trip.fuel_level_at_end
            if fuel_change > 0:  # Only count fuel consumption, not refuels
                trip.fuel_used_gallons = (fuel_change / 100) * Config.TANK_CAPACITY_GALLONS
            elif fuel_change < -5:  # Significant increase = refuel during trip
                logger.info(
                    f"Refuel detected during trip {trip.id}: "
                    f"{trip.fuel_level_at_gas_entry:.1f}% → {trip.fuel_level_at_end:.1f}%"
                )

        # Ensure gas_miles is set for gas_mode_entered trips (defensive fallback)
        if trip.gas_mode_entered and trip.gas_miles is None and trip.distance_miles:
            logger.debug(f"Trip {trip.id}: Applying fallback gas_miles = distance_miles")
            trip.gas_miles = trip.distance_miles
            trip.electric_miles = 0.0

        # Record SOC transition for battery health tracking (avoid duplicates)
        if trip.soc_at_gas_transition:
            existing = db.query(SocTransition).filter(SocTransition.trip_id == trip.id).first()
            if not existing:
                soc_transition = SocTransition(
                    trip_id=trip.id,
                    timestamp=trip.gas_mode_entry_time,
                    soc_at_transition=trip.soc_at_gas_transition,
                    ambient_temp_f=gas_entry.get("ambient_temp_f"),
                    odometer_miles=gas_entry.get("odometer_miles"),
                )
                db.add(soc_transition)
    else:
        # Entire trip was electric - all distance is electric miles
        if trip.start_odometer and trip.end_odometer:
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


def fetch_trip_weather(trip: Trip, points: list) -> None:
    """
    Fetch weather data for the trip location and time.

    Args:
        trip: Trip to update
        points: List of telemetry dicts (must have GPS data)
    """
    gps_point = None
    try:
        # Find first point with GPS coordinates
        gps_point = next((p for p in points if p.get("latitude") and p.get("longitude")), None)
        if gps_point and trip.start_time:
            weather = get_weather_for_location(gps_point["latitude"], gps_point["longitude"], trip.start_time)
            if weather:
                trip.weather_temp_f = weather.get("temperature_f")
                trip.weather_precipitation_in = weather.get("precipitation_in")
                trip.weather_wind_mph = weather.get("wind_speed_mph")
                trip.weather_conditions = weather.get("conditions")
                trip.weather_impact_factor = get_weather_impact_factor(weather)
                logger.debug(
                    f"Weather for trip {trip.id}: " f"{weather.get('conditions')}, {weather.get('temperature_f')}°F"
                )
    except (ConnectionError, TimeoutError, ValueError) as e:
        error = WeatherAPIError(
            f"Failed to fetch weather for trip {trip.id}: {e}",
            latitude=gps_point.get("latitude") if gps_point else None,
            longitude=gps_point.get("longitude") if gps_point else None,
        )
        logger.warning(str(error))
    except Exception as e:
        logger.exception(f"Unexpected error fetching weather for trip {trip.id}: {e}")


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
                fetch_trip_weather(trip, points)
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
