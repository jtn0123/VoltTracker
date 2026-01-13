"""
Calculations for MPG, SOC analysis, and trip processing.

DEPRECATED: This module now re-exports functions from the new consolidated
calculations package (receiver.calculations). All new code should import
from `calculations` directly.

This file maintains backward compatibility for existing code.
"""

import logging
import statistics
from datetime import datetime
from typing import Dict, List, Optional, Tuple

# Import from consolidated calculations package
from calculations import (
    calculate_energy_from_soc_change,
    calculate_gas_mpg,
    calculate_kwh_per_mile,
    detect_refuel_event,
    fuel_percent_to_gallons,
    smooth_fuel_level,
    soc_to_kwh,
)
from calculations.constants import (
    BATTERY_CAPACITY_KWH,
    MIN_GAS_MILES_FOR_MPG,
    RPM_THRESHOLD,
    SOC_GAS_THRESHOLD,
    TANK_CAPACITY_GALLONS,
)
from config import Config

logger = logging.getLogger(__name__)


def detect_gas_mode_entry(
    telemetry_points: List[dict], soc_threshold: float = SOC_GAS_THRESHOLD, rpm_threshold: float = RPM_THRESHOLD
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
        rpm = point.get("engine_rpm", 0) or 0
        soc = point.get("state_of_charge", 100) or 100

        # Check if engine is running and SOC is depleted
        if rpm > rpm_threshold and soc < soc_threshold:
            # Verify it's sustained - check next 2 points if available
            sustained = True
            for j in range(i + 1, min(i + 3, len(telemetry_points))):
                next_rpm = telemetry_points[j].get("engine_rpm", 0) or 0
                if next_rpm < rpm_threshold / 2:
                    sustained = False
                    break

            if sustained:
                return point

    return None


def calculate_electric_miles(
    gas_entry_odometer: Optional[float], trip_start_odometer: float, trip_end_odometer: float
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
    temps: List[float] = [
        float(p.get("ambient_temp_f")) for p in telemetry_points if p.get("ambient_temp_f") is not None
    ]

    if not temps:
        return None

    return float(round(statistics.mean(temps), 1))


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
            "average_soc": None,
            "min_soc": None,
            "max_soc": None,
            "count": 0,
            "histogram": {},
            "temperature_correlation": None,
        }

    soc_values = [t["soc_at_transition"] for t in transitions if t.get("soc_at_transition")]

    if not soc_values:
        return {
            "average_soc": None,
            "min_soc": None,
            "max_soc": None,
            "count": 0,
            "histogram": {},
            "temperature_correlation": None,
        }

    # Build histogram (1% buckets)
    histogram: Dict[int, int] = {}
    for soc in soc_values:
        bucket = int(soc)
        histogram[bucket] = histogram.get(bucket, 0) + 1

    # Temperature correlation
    temp_correlation = None
    temp_soc_pairs = [
        (t["ambient_temp_f"], t["soc_at_transition"])
        for t in transitions
        if t.get("ambient_temp_f") is not None and t.get("soc_at_transition") is not None
    ]

    if len(temp_soc_pairs) >= 5:
        # Simple correlation: average SOC at cold vs warm temps
        cold_socs = [soc for temp, soc in temp_soc_pairs if temp < 50]
        warm_socs = [soc for temp, soc in temp_soc_pairs if temp >= 50]

        if cold_socs and warm_socs:
            temp_correlation = {
                "cold_avg_soc": round(statistics.mean(cold_socs), 1),
                "warm_avg_soc": round(statistics.mean(warm_socs), 1),
                "cold_count": len(cold_socs),
                "warm_count": len(warm_socs),
            }

    return {
        "average_soc": round(statistics.mean(soc_values), 1),
        "min_soc": round(min(soc_values), 1),
        "max_soc": round(max(soc_values), 1),
        "count": len(soc_values),
        "histogram": histogram,
        "temperature_correlation": temp_correlation,
    }


def calculate_electric_kwh(
    telemetry_points: List[dict], battery_capacity_kwh: float = BATTERY_CAPACITY_KWH
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
    from calculations.energy import integrate_power_over_time

    if len(telemetry_points) < 2:
        return None

    # Method 1: Integrate power over time if HV battery power data available
    power_readings = [
        (p.get("timestamp"), p.get("hv_battery_power_kw"))
        for p in telemetry_points
        if p.get("hv_battery_power_kw") is not None and p.get("timestamp") is not None
    ]

    if len(power_readings) >= 2:
        result = integrate_power_over_time(power_readings)
        if result is not None:
            return result

    # Method 2: Estimate from SOC change
    soc_readings: List[float] = [
        float(p.get("state_of_charge")) for p in telemetry_points if p.get("state_of_charge") is not None
    ]

    if len(soc_readings) >= 2:
        start_soc = soc_readings[0]
        end_soc = soc_readings[-1]
        return calculate_energy_from_soc_change(start_soc, end_soc, battery_capacity_kwh)

    return None


def detect_charging_session(telemetry_points: List[dict], min_power_kw: float = 0.5) -> Optional[dict]:
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
    charger_readings = [p for p in telemetry_points if p.get("charger_connected") is True]

    if not charger_readings:
        return None

    # Get power readings during charging
    power_readings = [
        p.get("charger_ac_power_kw", 0) or 0 for p in charger_readings if p.get("charger_ac_power_kw") is not None
    ]

    if not power_readings or max(power_readings) < min_power_kw:
        return None

    # Calculate charging stats
    soc_readings = [p.get("state_of_charge") for p in charger_readings if p.get("state_of_charge") is not None]

    result = {
        "is_charging": True,
        "peak_power_kw": round(max(power_readings), 2),
        "avg_power_kw": round(statistics.mean(power_readings), 2) if power_readings else None,
        "start_soc": soc_readings[0] if soc_readings else None,
        "current_soc": soc_readings[-1] if soc_readings else None,
    }

    # Estimate charge type based on power level
    if max(power_readings) > 6.0:
        result["charge_type"] = "L2"
    elif max(power_readings) > 1.2:
        result["charge_type"] = "L1-high"
    else:
        result["charge_type"] = "L1"

    return result
