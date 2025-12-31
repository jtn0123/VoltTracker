"""Calculations for MPG, SOC analysis, and trip processing."""

from typing import List, Optional, Tuple
from datetime import datetime
import statistics
import logging

from config import Config

logger = logging.getLogger(__name__)

# Use centralized config values
TANK_CAPACITY_GALLONS = Config.TANK_CAPACITY_GALLONS
SOC_GAS_THRESHOLD = Config.SOC_GAS_THRESHOLD
RPM_THRESHOLD = Config.RPM_THRESHOLD
MIN_GAS_MILES_FOR_MPG = 1.0  # Minimum gas miles for reliable MPG calculation
BATTERY_CAPACITY_KWH = Config.BATTERY_CAPACITY_KWH


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
    if mpg < Config.MIN_MPG or mpg > Config.MAX_MPG:
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


def calculate_electric_kwh(
    telemetry_points: List[dict],
    battery_capacity_kwh: float = BATTERY_CAPACITY_KWH
) -> Optional[float]:
    """
    Calculate kWh consumed during electric driving.

    Uses two methods depending on available data:
    1. If HV battery power data available: integrate power over time
    2. Otherwise: estimate from SOC change and battery capacity

    Args:
        telemetry_points: List of telemetry data points, sorted by timestamp
        battery_capacity_kwh: Total battery capacity in kWh

    Returns:
        kWh consumed, or None if insufficient data
    """
    if len(telemetry_points) < 2:
        return None

    # Method 1: Integrate power over time if HV battery power data available
    power_readings = [
        (p.get('timestamp'), p.get('hv_battery_power_kw'))
        for p in telemetry_points
        if p.get('hv_battery_power_kw') is not None and p.get('timestamp') is not None
    ]

    if len(power_readings) >= 2:
        total_kwh = 0.0
        for i in range(1, len(power_readings)):
            prev_time, prev_power = power_readings[i - 1]
            curr_time, curr_power = power_readings[i]

            # Convert timestamp to datetime if string
            if isinstance(prev_time, str):
                prev_time = datetime.fromisoformat(prev_time.replace('Z', '+00:00'))
            if isinstance(curr_time, str):
                curr_time = datetime.fromisoformat(curr_time.replace('Z', '+00:00'))

            # Calculate time delta in hours
            delta_hours = (curr_time - prev_time).total_seconds() / 3600

            # Average power during interval (only count positive = discharging)
            avg_power = (prev_power + curr_power) / 2
            if avg_power > 0:  # Positive = discharging (consuming energy)
                total_kwh += avg_power * delta_hours

        if total_kwh > 0:
            return round(total_kwh, 2)

    # Method 2: Estimate from SOC change
    soc_readings = [
        p.get('state_of_charge')
        for p in telemetry_points
        if p.get('state_of_charge') is not None
    ]

    if len(soc_readings) >= 2:
        start_soc = soc_readings[0]
        end_soc = soc_readings[-1]

        # Only calculate if SOC decreased (not charging)
        if start_soc > end_soc:
            soc_change = start_soc - end_soc
            kwh_used = (soc_change / 100) * battery_capacity_kwh
            return round(kwh_used, 2)

    return None


def calculate_kwh_per_mile(
    kwh_used: float,
    electric_miles: float
) -> Optional[float]:
    """
    Calculate electric efficiency in kWh/mile.

    Args:
        kwh_used: Total kWh consumed
        electric_miles: Miles driven on electric

    Returns:
        kWh/mile efficiency, or None if insufficient data
    """
    if kwh_used is None or electric_miles is None:
        return None

    if electric_miles < 0.5:  # Need at least half a mile for meaningful data
        return None

    kwh_per_mile = kwh_used / electric_miles

    # Sanity check - Volt typically gets 0.25-0.40 kWh/mile
    if kwh_per_mile < Config.MIN_KWH_PER_MILE or kwh_per_mile > Config.MAX_KWH_PER_MILE:
        logger.warning(f"Unusual kWh/mile: {kwh_per_mile:.3f} (kWh: {kwh_used:.2f}, miles: {electric_miles:.1f})")

    return round(kwh_per_mile, 3)


def detect_charging_session(
    telemetry_points: List[dict],
    min_power_kw: float = 0.5
) -> Optional[dict]:
    """
    Detect if a charging session is occurring based on telemetry data.

    Args:
        telemetry_points: List of telemetry data points
        min_power_kw: Minimum power level to consider as active charging

    Returns:
        Charging session info dict, or None if not charging
    """
    if not telemetry_points:
        return None

    # Check for charger connected
    charger_readings = [
        p for p in telemetry_points
        if p.get('charger_connected') is True
    ]

    if not charger_readings:
        return None

    # Get power readings during charging
    power_readings = [
        p.get('charger_ac_power_kw', 0) or 0
        for p in charger_readings
        if p.get('charger_ac_power_kw') is not None
    ]

    if not power_readings or max(power_readings) < min_power_kw:
        return None

    # Calculate charging stats
    soc_readings = [
        p.get('state_of_charge')
        for p in charger_readings
        if p.get('state_of_charge') is not None
    ]

    result = {
        'is_charging': True,
        'peak_power_kw': round(max(power_readings), 2),
        'avg_power_kw': round(statistics.mean(power_readings), 2) if power_readings else None,
        'start_soc': soc_readings[0] if soc_readings else None,
        'current_soc': soc_readings[-1] if soc_readings else None,
    }

    # Estimate charge type based on power level
    if max(power_readings) > 6.0:
        result['charge_type'] = 'L2'
    elif max(power_readings) > 1.2:
        result['charge_type'] = 'L1-high'
    else:
        result['charge_type'] = 'L1'

    return result
