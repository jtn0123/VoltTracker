"""
Fuel-related Calculations

Handles gas fuel calculations:
- MPG calculations
- Fuel consumption tracking
- Refuel detection
- Fuel level smoothing
"""

import statistics
from typing import List, Optional

from .constants import (
    FUEL_LEVEL_SMOOTHING_WINDOW,
    MIN_FUEL_CONSUMPTION_GALLONS,
    MIN_GAS_MILES_FOR_MPG,
    REFUEL_JUMP_THRESHOLD_PERCENT,
    TANK_CAPACITY_GALLONS,
)
from .efficiency import calculate_mpg


def calculate_fuel_consumed_gallons(
    start_fuel_percent: float,
    end_fuel_percent: float,
    tank_capacity_gallons: float = TANK_CAPACITY_GALLONS
) -> Optional[float]:
    """
    Calculate fuel consumed in gallons from fuel level change.

    Args:
        start_fuel_percent: Starting fuel level (%)
        end_fuel_percent: Ending fuel level (%)
        tank_capacity_gallons: Tank capacity in gallons

    Returns:
        Gallons consumed, or None if invalid (refuel detected or negative)

    Examples:
        >>> calculate_fuel_consumed_gallons(80.0, 60.0, 9.0)
        1.8
        >>> calculate_fuel_consumed_gallons(60.0, 80.0, 9.0)  # Refuel
        None
    """
    if start_fuel_percent is None or end_fuel_percent is None:
        return None

    fuel_change_percent = start_fuel_percent - end_fuel_percent

    # Negative change = refuel or invalid reading
    if fuel_change_percent <= 0:
        return None

    gallons_used = (fuel_change_percent / 100.0) * tank_capacity_gallons

    # Filter out noise (less than 0.01 gallons)
    if gallons_used < MIN_FUEL_CONSUMPTION_GALLONS:
        return None

    return round(gallons_used, 2)


def calculate_gas_mpg(
    start_odometer: float,
    end_odometer: float,
    start_fuel_level: float,
    end_fuel_level: float,
    tank_capacity: float = TANK_CAPACITY_GALLONS,
    validate: bool = True
) -> Optional[float]:
    """
    Calculate MPG for gas portion of driving.

    Args:
        start_odometer: Odometer reading at gas mode entry
        end_odometer: Odometer reading at trip end or gas mode exit
        start_fuel_level: Fuel level percentage at gas mode entry
        end_fuel_level: Fuel level percentage at end
        tank_capacity: Tank capacity in gallons
        validate: Whether to apply sanity checks

    Returns:
        MPG value, or None if calculation not possible/invalid

    Examples:
        >>> calculate_gas_mpg(1000, 1040, 80.0, 70.0, 9.0)
        44.4
    """
    if any(v is None for v in [start_odometer, end_odometer, start_fuel_level, end_fuel_level]):
        return None

    gas_miles = end_odometer - start_odometer

    if gas_miles < MIN_GAS_MILES_FOR_MPG:
        return None

    # Calculate gallons used
    gallons_used = calculate_fuel_consumed_gallons(start_fuel_level, end_fuel_level, tank_capacity)

    if gallons_used is None:
        return None

    # Use the general MPG calculation with validation
    return calculate_mpg(gas_miles, gallons_used, validate=validate)


def detect_refuel_event(
    current_fuel_level: float,
    previous_fuel_level: float,
    jump_threshold: float = REFUEL_JUMP_THRESHOLD_PERCENT
) -> bool:
    """
    Detect if a refueling event occurred based on fuel level jump.

    Args:
        current_fuel_level: Current fuel level percentage
        previous_fuel_level: Previous fuel level percentage
        jump_threshold: Minimum percentage increase to consider a refuel

    Returns:
        True if refueling detected, False otherwise

    Examples:
        >>> detect_refuel_event(90.0, 50.0)
        True
        >>> detect_refuel_event(50.0, 48.0)
        False
        >>> detect_refuel_event(40.0, 50.0)
        False
    """
    if previous_fuel_level is None or current_fuel_level is None:
        return False

    increase = current_fuel_level - previous_fuel_level
    return increase >= jump_threshold


def smooth_fuel_level(
    readings: List[float],
    window_size: int = FUEL_LEVEL_SMOOTHING_WINDOW
) -> float:
    """
    Apply median filter to fuel level readings to reduce sensor noise.

    Fuel level sensors are notoriously noisy, especially during acceleration
    and cornering. Median filter removes outliers better than mean.

    Args:
        readings: List of recent fuel level percentages
        window_size: Number of readings to consider

    Returns:
        Smoothed fuel level percentage

    Examples:
        >>> smooth_fuel_level([50.0, 52.0, 48.0, 51.0, 49.0])
        50.0
        >>> smooth_fuel_level([50.0, 90.0, 51.0, 49.0])  # Outlier
        50.5
    """
    if not readings:
        return 0.0

    # Use the last N readings
    recent = readings[-window_size:]

    if len(recent) == 1:
        return recent[0]

    # Return median value (robust to outliers)
    return statistics.median(recent)


def calculate_fuel_cost(
    gallons_consumed: float,
    price_per_gallon: float
) -> float:
    """
    Calculate cost of fuel consumed.

    Args:
        gallons_consumed: Gallons of gas used
        price_per_gallon: Gas price ($/gallon)

    Returns:
        Total fuel cost in dollars

    Examples:
        >>> calculate_fuel_cost(10.0, 3.50)
        35.0
        >>> calculate_fuel_cost(5.5, 4.00)
        22.0
    """
    return round(gallons_consumed * price_per_gallon, 2)


def estimate_fuel_range(
    current_fuel_percent: float,
    average_mpg: float,
    tank_capacity_gallons: float = TANK_CAPACITY_GALLONS
) -> float:
    """
    Estimate remaining range on gas based on fuel level and average MPG.

    Args:
        current_fuel_percent: Current fuel level (%)
        average_mpg: Recent average MPG
        tank_capacity_gallons: Tank capacity in gallons

    Returns:
        Estimated range in miles

    Examples:
        >>> estimate_fuel_range(50.0, 40.0, 9.0)
        180.0
        >>> estimate_fuel_range(100.0, 35.0, 9.0)
        315.0
    """
    gallons_remaining = (current_fuel_percent / 100.0) * tank_capacity_gallons
    range_miles = gallons_remaining * average_mpg
    return round(range_miles, 1)
