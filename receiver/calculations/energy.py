"""
Energy Conversion Calculations

Handles conversions between different energy units and representations:
- SOC percentage <-> kWh
- Fuel percentage <-> gallons
- Power integration over time -> energy
"""

from datetime import datetime
from typing import List, Optional, Tuple

from .constants import BATTERY_CAPACITY_KWH, TANK_CAPACITY_GALLONS


def soc_to_kwh(
    soc_percent: float,
    battery_capacity_kwh: float = BATTERY_CAPACITY_KWH
) -> float:
    """
    Convert State of Charge percentage to kWh.

    Args:
        soc_percent: Battery state of charge (0-100%)
        battery_capacity_kwh: Total battery capacity in kWh

    Returns:
        Energy in kWh

    Examples:
        >>> soc_to_kwh(50.0, 18.4)
        9.2
        >>> soc_to_kwh(100.0, 18.4)
        18.4
    """
    return (soc_percent / 100.0) * battery_capacity_kwh


def kwh_to_soc(
    kwh: float,
    battery_capacity_kwh: float = BATTERY_CAPACITY_KWH
) -> float:
    """
    Convert kWh to State of Charge percentage.

    Args:
        kwh: Energy in kWh
        battery_capacity_kwh: Total battery capacity in kWh

    Returns:
        SOC percentage (0-100%)

    Examples:
        >>> kwh_to_soc(9.2, 18.4)
        50.0
        >>> kwh_to_soc(18.4, 18.4)
        100.0
    """
    if battery_capacity_kwh == 0:
        return 0.0
    return (kwh / battery_capacity_kwh) * 100.0


def fuel_percent_to_gallons(
    fuel_percent: float,
    tank_capacity_gallons: float = TANK_CAPACITY_GALLONS
) -> float:
    """
    Convert fuel level percentage to gallons.

    Args:
        fuel_percent: Fuel tank level (0-100%)
        tank_capacity_gallons: Total tank capacity in gallons

    Returns:
        Fuel volume in gallons

    Examples:
        >>> fuel_percent_to_gallons(50.0, 9.0)
        4.5
        >>> fuel_percent_to_gallons(100.0, 9.0)
        9.0
    """
    return (fuel_percent / 100.0) * tank_capacity_gallons


def gallons_to_fuel_percent(
    gallons: float,
    tank_capacity_gallons: float = TANK_CAPACITY_GALLONS
) -> float:
    """
    Convert gallons to fuel level percentage.

    Args:
        gallons: Fuel volume in gallons
        tank_capacity_gallons: Total tank capacity in gallons

    Returns:
        Fuel level percentage (0-100%)

    Examples:
        >>> gallons_to_fuel_percent(4.5, 9.0)
        50.0
        >>> gallons_to_fuel_percent(9.0, 9.0)
        100.0
    """
    if tank_capacity_gallons == 0:
        return 0.0
    return (gallons / tank_capacity_gallons) * 100.0


def integrate_power_over_time(
    power_readings: List[Tuple[datetime, float]]
) -> Optional[float]:
    """
    Integrate power readings over time to calculate total energy consumed.

    Uses trapezoidal integration: for each interval, calculates average power
    and multiplies by time delta.

    Args:
        power_readings: List of (timestamp, power_kw) tuples, sorted by time.
                       Only positive power values (discharging) are counted.

    Returns:
        Total kWh consumed, or None if insufficient data

    Examples:
        >>> from datetime import datetime, timedelta
        >>> t1 = datetime(2024, 1, 1, 12, 0, 0)
        >>> t2 = datetime(2024, 1, 1, 13, 0, 0)  # 1 hour later
        >>> readings = [(t1, 10.0), (t2, 10.0)]  # 10 kW for 1 hour
        >>> integrate_power_over_time(readings)
        10.0
    """
    if len(power_readings) < 2:
        return None

    total_kwh = 0.0

    for i in range(1, len(power_readings)):
        prev_time, prev_power = power_readings[i - 1]
        curr_time, curr_power = power_readings[i]

        # Convert timestamp strings to datetime if needed
        if isinstance(prev_time, str):
            prev_time = datetime.fromisoformat(prev_time.replace("Z", "+00:00"))
        if isinstance(curr_time, str):
            curr_time = datetime.fromisoformat(curr_time.replace("Z", "+00:00"))

        # Calculate time delta in hours
        delta_hours = (curr_time - prev_time).total_seconds() / 3600

        # Skip if timestamps are identical or reversed
        if delta_hours <= 0:
            continue

        # Skip if power values are None
        if prev_power is None or curr_power is None:
            continue

        # Average power during interval (only count positive = discharging)
        avg_power = (prev_power + curr_power) / 2
        if avg_power > 0:  # Positive = discharging (consuming energy)
            total_kwh += avg_power * delta_hours

    return round(total_kwh, 2) if total_kwh > 0 else None


def calculate_energy_from_soc_change(
    start_soc: float,
    end_soc: float,
    battery_capacity_kwh: float = BATTERY_CAPACITY_KWH
) -> Optional[float]:
    """
    Calculate energy consumed based on SOC change.

    This is a fallback method when power integration data is unavailable.
    Only calculates consumption (SOC decrease), not charging (SOC increase).

    Args:
        start_soc: Starting state of charge (%)
        end_soc: Ending state of charge (%)
        battery_capacity_kwh: Total battery capacity in kWh

    Returns:
        kWh consumed, or None if SOC increased (charging)

    Examples:
        >>> calculate_energy_from_soc_change(80.0, 50.0, 18.4)
        5.52
        >>> calculate_energy_from_soc_change(50.0, 80.0, 18.4)  # Charging
        None
    """
    if start_soc is None or end_soc is None:
        return None

    # Only calculate if SOC decreased (not charging)
    if start_soc <= end_soc:
        return None

    soc_change = start_soc - end_soc
    kwh_used = (soc_change / 100.0) * battery_capacity_kwh

    return round(kwh_used, 2)
