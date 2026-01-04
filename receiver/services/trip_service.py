"""
Trip service for VoltTracker.

Handles trip finalization logic, calculating statistics for completed trips.
"""

import logging
from datetime import datetime

from config import Config
from models import Trip, TelemetryRaw, SocTransition
from exceptions import WeatherAPIError, TripProcessingError
from utils import (
    calculate_gas_mpg,
    detect_gas_mode_entry,
    calculate_electric_miles,
    calculate_average_temp,
    calculate_electric_kwh,
    calculate_kwh_per_mile,
    normalize_datetime,
)
from utils.weather import get_weather_for_location, get_weather_impact_factor

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
        entry_time = gas_entry.get('timestamp')
        if isinstance(entry_time, str):
            entry_time = datetime.fromisoformat(entry_time.replace('Z', '+00:00'))
        trip.gas_mode_entry_time = normalize_datetime(entry_time)

        trip.soc_at_gas_transition = gas_entry.get('state_of_charge')
        trip.fuel_level_at_gas_entry = gas_entry.get('fuel_level_percent')
        trip.fuel_level_at_end = telemetry[-1].fuel_level_percent

        # Calculate electric and gas miles
        if gas_entry.get('odometer_miles') and trip.start_odometer:
            trip.electric_miles, trip.gas_miles = calculate_electric_miles(
                gas_entry.get('odometer_miles'),
                trip.start_odometer,
                trip.end_odometer or gas_entry.get('odometer_miles')
            )

        # Calculate gas MPG (only if we drove at least 1 mile on gas)
        if trip.gas_miles and trip.gas_miles >= 1.0:
            trip.gas_mpg = calculate_gas_mpg(
                gas_entry.get('odometer_miles'),
                trip.end_odometer,
                trip.fuel_level_at_gas_entry,
                trip.fuel_level_at_end
            )

            if trip.gas_mpg and trip.fuel_level_at_gas_entry and trip.fuel_level_at_end:
                trip.fuel_used_gallons = (
                    (trip.fuel_level_at_gas_entry - trip.fuel_level_at_end) / 100
                    * Config.TANK_CAPACITY_GALLONS
                )

        # Record SOC transition for battery health tracking
        if trip.soc_at_gas_transition:
            soc_transition = SocTransition(
                trip_id=trip.id,
                timestamp=trip.gas_mode_entry_time,
                soc_at_transition=trip.soc_at_gas_transition,
                ambient_temp_f=gas_entry.get('ambient_temp_f'),
                odometer_miles=gas_entry.get('odometer_miles')
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
            trip.kwh_per_mile = calculate_kwh_per_mile(
                trip.electric_kwh_used, trip.electric_miles
            )


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
        gps_point = next(
            (p for p in points if p.get('latitude') and p.get('longitude')),
            None
        )
        if gps_point and trip.start_time:
            weather = get_weather_for_location(
                gps_point['latitude'],
                gps_point['longitude'],
                trip.start_time
            )
            if weather:
                trip.weather_temp_f = weather.get('temperature_f')
                trip.weather_precipitation_in = weather.get('precipitation_in')
                trip.weather_wind_mph = weather.get('wind_speed_mph')
                trip.weather_conditions = weather.get('conditions')
                trip.weather_impact_factor = get_weather_impact_factor(weather)
                logger.debug(
                    f"Weather for trip {trip.id}: "
                    f"{weather.get('conditions')}, {weather.get('temperature_f')}°F"
                )
    except (ConnectionError, TimeoutError, ValueError) as e:
        error = WeatherAPIError(
            f"Failed to fetch weather for trip {trip.id}: {e}",
            latitude=gps_point.get('latitude') if gps_point else None,
            longitude=gps_point.get('longitude') if gps_point else None
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
    # Get all telemetry for this trip
    telemetry = db.query(TelemetryRaw).filter(
        TelemetryRaw.session_id == trip.session_id
    ).order_by(TelemetryRaw.timestamp).all()

    if not telemetry:
        trip.is_closed = True
        return

    # Convert to dicts for calculation functions
    points = [t.to_dict() for t in telemetry]

    # Calculate basic trip metrics
    calculate_trip_basics(trip, telemetry)

    # Process gas mode entry and related calculations
    process_gas_mode(db, trip, telemetry, points)

    # Calculate electric efficiency
    calculate_electric_efficiency(trip, points)

    # Fetch weather data
    fetch_trip_weather(trip, points)

    # Mark trip as complete
    trip.is_closed = True
    logger.info(
        f"Trip {trip.id} finalized: {(trip.distance_miles or 0):.1f} mi "
        f"(electric: {(trip.electric_miles or 0):.1f}, gas: {(trip.gas_miles or 0):.1f}, "
        f"MPG: {trip.gas_mpg or 'N/A'}, kWh/mi: {trip.kwh_per_mile or 'N/A'})"
    )
