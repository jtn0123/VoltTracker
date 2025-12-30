"""Calculations for MPG, SOC analysis, and trip processing."""

from typing import List, Optional, Tuple
from datetime import datetime
import statistics
import logging

logger = logging.getLogger(__name__)

# Volt-specific constants
TANK_CAPACITY_GALLONS = 9.3122
SOC_GAS_THRESHOLD = 25.0  # SOC below this indicates depleted battery
RPM_THRESHOLD = 500  # RPM above this indicates engine is running
MIN_GAS_MILES_FOR_MPG = 1.0  # Minimum gas miles for reliable MPG calculation


def smooth_fuel_level(readings: List[float], window_size: int = 10) -> float:
    """
    Apply median filter to fuel level readings to reduce sensor noise.

    Args:
        readings: List of recent fuel level percentages
        window_size: Number of readings to consider

    Returns:
        Smoothed fuel level percentage
    """
    if not readings:
        return 0.0

    # Use the last N readings
    recent = readings[-window_size:]

    if len(recent) == 1:
        return recent[0]

    # Return median value
    return statistics.median(recent)


def detect_gas_mode_entry(
    telemetry_points: List[dict],
    soc_threshold: float = SOC_GAS_THRESHOLD,
    rpm_threshold: float = RPM_THRESHOLD
) -> Optional[dict]:
    """
    Detect when the Volt switches from electric to gas mode.

    Logic:
    1. Find first point where RPM > threshold (engine running)
    2. Verify SOC is below threshold (battery depleted, not regen)
    3. Verify RPM stays elevated (not just a brief generator pulse)

    Args:
        telemetry_points: List of telemetry data points, sorted by timestamp
        soc_threshold: SOC percentage below which gas mode is expected
        rpm_threshold: RPM above which engine is considered running

    Returns:
        The telemetry point where gas mode was entered, or None
    """
    if len(telemetry_points) < 3:
        return None

    for i, point in enumerate(telemetry_points):
        rpm = point.get('engine_rpm', 0) or 0
        soc = point.get('state_of_charge', 100) or 100

        # Check if engine is running and SOC is depleted
        if rpm > rpm_threshold and soc < soc_threshold:
            # Verify it's sustained - check next 2 points if available
            sustained = True
            for j in range(i + 1, min(i + 3, len(telemetry_points))):
                next_rpm = telemetry_points[j].get('engine_rpm', 0) or 0
                if next_rpm < rpm_threshold / 2:
                    sustained = False
                    break

            if sustained:
                return point

    return None


def detect_refuel_event(
    current_fuel_level: float,
    previous_fuel_level: float,
    jump_threshold: float = 10.0
) -> bool:
    """
    Detect if a refueling event occurred based on fuel level jump.

    Args:
        current_fuel_level: Current fuel level percentage
        previous_fuel_level: Previous fuel level percentage
        jump_threshold: Minimum percentage increase to consider a refuel

    Returns:
        True if refueling detected, False otherwise
    """
    if previous_fuel_level is None or current_fuel_level is None:
        return False

    increase = current_fuel_level - previous_fuel_level
    return increase >= jump_threshold


def calculate_gas_mpg(
    start_odometer: float,
    end_odometer: float,
    start_fuel_level: float,
    end_fuel_level: float,
    tank_capacity: float = TANK_CAPACITY_GALLONS
) -> Optional[float]:
    """
    Calculate MPG for gas portion of driving.

    Args:
        start_odometer: Odometer reading at gas mode entry
        end_odometer: Odometer reading at trip end or gas mode exit
        start_fuel_level: Fuel level percentage at gas mode entry
        end_fuel_level: Fuel level percentage at end
        tank_capacity: Tank capacity in gallons

    Returns:
        MPG value, or None if calculation not possible
    """
    if any(v is None for v in [start_odometer, end_odometer, start_fuel_level, end_fuel_level]):
        return None

    gas_miles = end_odometer - start_odometer

    if gas_miles < MIN_GAS_MILES_FOR_MPG:
        logger.debug(f"Gas segment too short for MPG: {gas_miles:.1f} miles")
        return None

    # Calculate gallons used from fuel level change
    fuel_change_percent = start_fuel_level - end_fuel_level

    if fuel_change_percent <= 0:
        logger.debug("No fuel consumption detected (or refuel occurred)")
        return None

    gallons_used = (fuel_change_percent / 100) * tank_capacity

    if gallons_used <= 0.01:  # Less than 0.01 gallons is noise
        return None

    mpg = gas_miles / gallons_used

    # Sanity check - Volt should get 30-50 MPG in gas mode
    if mpg < 10 or mpg > 100:
        logger.warning(f"Unusual MPG calculated: {mpg:.1f} (miles: {gas_miles:.1f}, gallons: {gallons_used:.2f})")

    return round(mpg, 1)


def calculate_electric_miles(
    gas_entry_odometer: Optional[float],
    trip_start_odometer: float,
    trip_end_odometer: float
) -> Tuple[Optional[float], Optional[float]]:
    """
    Calculate electric and gas miles for a trip.

    Args:
        gas_entry_odometer: Odometer at gas mode entry (None if never entered gas mode)
        trip_start_odometer: Odometer at trip start
        trip_end_odometer: Odometer at trip end

    Returns:
        Tuple of (electric_miles, gas_miles)
    """
    total_miles = trip_end_odometer - trip_start_odometer

    if gas_entry_odometer is None:
        # Entire trip was electric
        return total_miles, None

    electric_miles = gas_entry_odometer - trip_start_odometer
    gas_miles = trip_end_odometer - gas_entry_odometer

    return electric_miles, gas_miles


def calculate_average_temp(telemetry_points: List[dict]) -> Optional[float]:
    """
    Calculate average ambient temperature for a trip.

    Args:
        telemetry_points: List of telemetry data points

    Returns:
        Average ambient temperature in Fahrenheit, or None
    """
    temps = [
        p.get('ambient_temp_f')
        for p in telemetry_points
        if p.get('ambient_temp_f') is not None
    ]

    if not temps:
        return None

    return round(statistics.mean(temps), 1)


def analyze_soc_floor(transitions: List[dict]) -> dict:
    """
    Analyze SOC transition data to determine battery floor characteristics.

    Args:
        transitions: List of SOC transition records

    Returns:
        Analysis results including average, histogram, and trends
    """
    if not transitions:
        return {
            'average_soc': None,
            'min_soc': None,
            'max_soc': None,
            'count': 0,
            'histogram': {},
            'temperature_correlation': None,
        }

    soc_values = [t['soc_at_transition'] for t in transitions if t.get('soc_at_transition')]

    if not soc_values:
        return {
            'average_soc': None,
            'min_soc': None,
            'max_soc': None,
            'count': 0,
            'histogram': {},
            'temperature_correlation': None,
        }

    # Build histogram (1% buckets)
    histogram = {}
    for soc in soc_values:
        bucket = int(soc)
        histogram[bucket] = histogram.get(bucket, 0) + 1

    # Temperature correlation
    temp_correlation = None
    temp_soc_pairs = [
        (t['ambient_temp_f'], t['soc_at_transition'])
        for t in transitions
        if t.get('ambient_temp_f') is not None and t.get('soc_at_transition') is not None
    ]

    if len(temp_soc_pairs) >= 5:
        # Simple correlation: average SOC at cold vs warm temps
        cold_socs = [soc for temp, soc in temp_soc_pairs if temp < 50]
        warm_socs = [soc for temp, soc in temp_soc_pairs if temp >= 50]

        if cold_socs and warm_socs:
            temp_correlation = {
                'cold_avg_soc': round(statistics.mean(cold_socs), 1),
                'warm_avg_soc': round(statistics.mean(warm_socs), 1),
                'cold_count': len(cold_socs),
                'warm_count': len(warm_socs),
            }

    return {
        'average_soc': round(statistics.mean(soc_values), 1),
        'min_soc': round(min(soc_values), 1),
        'max_soc': round(max(soc_values), 1),
        'count': len(soc_values),
        'histogram': histogram,
        'temperature_correlation': temp_correlation,
    }
