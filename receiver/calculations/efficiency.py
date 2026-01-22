"""
Efficiency Calculations

Handles electric and gas efficiency metrics:
- kWh per mile calculations
- MPG calculations
- Efficiency impact vs baseline
- Efficiency validation and sanity checks
"""

from typing import Optional

from .constants import (
    BASELINE_KWH_PER_MILE,
    MAX_KWH_PER_MILE,
    MAX_MPG,
    MIN_DISTANCE_FOR_EFFICIENCY,
    MIN_KWH_PER_MILE,
    MIN_MPG,
)


def calculate_kwh_per_mile(
    kwh_used: float,
    electric_miles: float,
    validate: bool = True
) -> Optional[float]:
    """
    Calculate electric efficiency in kWh/mile.

    Args:
        kwh_used: Total kWh consumed
        electric_miles: Miles driven on electric
        validate: Whether to apply sanity checks and return None for outliers

    Returns:
        kWh/mile efficiency, or None if insufficient/invalid data

    Examples:
        >>> calculate_kwh_per_mile(5.0, 20.0)
        0.25
        >>> calculate_kwh_per_mile(0, 20.0)
        None
        >>> calculate_kwh_per_mile(5.0, 0.1)  # Too short
        None
    """
    if kwh_used is None or electric_miles is None:
        return None

    if electric_miles < MIN_DISTANCE_FOR_EFFICIENCY:
        return None

    if electric_miles <= 0:  # Guard against division by zero
        return None

    kwh_per_mile = kwh_used / electric_miles

    # Apply sanity check if validation enabled
    if validate:
        if kwh_per_mile < MIN_KWH_PER_MILE or kwh_per_mile > MAX_KWH_PER_MILE:
            return None

    return round(kwh_per_mile, 3)


def calculate_mpg(
    miles_driven: float,
    gallons_consumed: float,
    validate: bool = True
) -> Optional[float]:
    """
    Calculate miles per gallon (MPG).

    Args:
        miles_driven: Distance traveled in miles
        gallons_consumed: Fuel consumed in gallons
        validate: Whether to apply sanity checks and return None for outliers

    Returns:
        MPG value, or None if insufficient/invalid data

    Examples:
        >>> calculate_mpg(100.0, 2.5)
        40.0
        >>> calculate_mpg(0, 2.5)
        None
        >>> calculate_mpg(100.0, 0)
        None
    """
    if miles_driven is None or gallons_consumed is None:
        return None

    if miles_driven <= 0 or gallons_consumed <= 0:
        return None

    mpg = miles_driven / gallons_consumed

    # Apply sanity check if validation enabled (Volt should get 15-60 MPG)
    if validate:
        if mpg < MIN_MPG or mpg > MAX_MPG:
            return None

    return round(mpg, 1)


def calculate_efficiency_impact_percent(
    actual_efficiency: float,
    baseline_efficiency: float = BASELINE_KWH_PER_MILE
) -> float:
    """
    Calculate efficiency impact as percentage vs baseline.

    Positive value = worse than baseline (using more energy)
    Negative value = better than baseline (using less energy)

    Args:
        actual_efficiency: Measured efficiency (kWh/mile)
        baseline_efficiency: Reference baseline (kWh/mile)

    Returns:
        Efficiency impact as percentage

    Examples:
        >>> calculate_efficiency_impact_percent(0.32, 0.32)
        0.0
        >>> calculate_efficiency_impact_percent(0.40, 0.32)
        25.0
        >>> calculate_efficiency_impact_percent(0.25, 0.32)
        -21.9
    """
    if actual_efficiency is None or baseline_efficiency is None or baseline_efficiency == 0:
        return 0.0

    impact = ((actual_efficiency - baseline_efficiency) / baseline_efficiency) * 100
    return round(impact, 1)


def calculate_range_from_efficiency(
    kwh_per_mile: float,
    available_kwh: float
) -> float:
    """
    Calculate estimated range based on efficiency and available energy.

    Args:
        kwh_per_mile: Efficiency (kWh/mile)
        available_kwh: Available battery energy (kWh)

    Returns:
        Estimated range in miles

    Examples:
        >>> calculate_range_from_efficiency(0.30, 15.0)
        50.0
        >>> calculate_range_from_efficiency(0.25, 15.0)
        60.0
    """
    if kwh_per_mile <= 0:
        return 0.0

    range_miles = available_kwh / kwh_per_mile
    return round(range_miles, 1)


def calculate_miles_per_kwh(kwh_per_mile: float) -> Optional[float]:
    """
    Convert kWh/mile to miles/kWh (inverse metric).

    This is sometimes more intuitive as "higher is better" like MPG.

    Args:
        kwh_per_mile: Efficiency in kWh/mile

    Returns:
        Efficiency in miles/kWh, or None if invalid

    Examples:
        >>> calculate_miles_per_kwh(0.25)
        4.0
        >>> calculate_miles_per_kwh(0.33)
        3.03
    """
    if kwh_per_mile is None or kwh_per_mile <= 0:
        return None

    return round(1.0 / kwh_per_mile, 2)


def calculate_mpge(kwh_per_mile: float, kwh_per_gallon_equivalent: float = 33.7) -> Optional[float]:
    """
    Calculate MPGe (miles per gallon equivalent) from electric efficiency.

    EPA defines 1 gallon of gasoline equivalent = 33.7 kWh of electricity.

    Args:
        kwh_per_mile: Electric efficiency (kWh/mile)
        kwh_per_gallon_equivalent: EPA standard (33.7 kWh)

    Returns:
        MPGe rating, or None if invalid

    Examples:
        >>> calculate_mpge(0.337)
        100.0
        >>> calculate_mpge(0.25)
        134.8
    """
    if kwh_per_mile is None or kwh_per_mile <= 0:
        return None

    mpge = kwh_per_gallon_equivalent / kwh_per_mile
    return round(mpge, 1)


def is_efficiency_within_range(
    kwh_per_mile: float,
    min_efficiency: float = MIN_KWH_PER_MILE,
    max_efficiency: float = MAX_KWH_PER_MILE
) -> bool:
    """
    Check if efficiency is within acceptable range.

    Args:
        kwh_per_mile: Efficiency to validate
        min_efficiency: Minimum acceptable (lower = better)
        max_efficiency: Maximum acceptable (higher = worse)

    Returns:
        True if within range, False otherwise

    Examples:
        >>> is_efficiency_within_range(0.30)
        True
        >>> is_efficiency_within_range(1.5)
        False
        >>> is_efficiency_within_range(0.05)
        False
    """
    if kwh_per_mile is None:
        return False
    return min_efficiency <= kwh_per_mile <= max_efficiency


def calculate_combined_efficiency(
    electric_miles: float,
    electric_kwh: float,
    gas_miles: float,
    gas_gallons: float,
    kwh_per_gallon_equivalent: float = 33.7
) -> Optional[float]:
    """
    Calculate combined efficiency for trips with both electric and gas usage.

    Converts gas consumption to kWh-equivalent and calculates overall kWh/mile.

    Args:
        electric_miles: Miles driven on electric
        electric_kwh: kWh consumed from battery
        gas_miles: Miles driven on gas
        gas_gallons: Gallons of gas consumed
        kwh_per_gallon_equivalent: EPA conversion factor

    Returns:
        Combined efficiency in kWh-equivalent per mile

    Examples:
        >>> # 20 mi electric @ 0.25 kWh/mi + 20 mi gas @ 40 MPG
        >>> calculate_combined_efficiency(20, 5.0, 20, 0.5, 33.7)
        0.547
    """
    total_miles = (electric_miles or 0) + (gas_miles or 0)

    if total_miles <= 0:
        return None

    # Calculate total energy in kWh-equivalent
    total_kwh_equiv = (electric_kwh or 0) + (gas_gallons or 0) * kwh_per_gallon_equivalent

    return round(total_kwh_equiv / total_miles, 3)
