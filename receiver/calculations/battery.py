"""
Battery-related Calculations

Handles battery capacity, health, and degradation calculations:
- Capacity <-> percentage conversions
- Degradation rate calculations
- Battery health metrics
"""

from typing import Optional

from .constants import (
    MAX_BATTERY_CAPACITY_KWH,
    MIN_BATTERY_CAPACITY_KWH,
    NORMAL_DEGRADATION_MIN_PCT_PER_10K,
    NORMAL_DEGRADATION_MAX_PCT_PER_10K,
)


def capacity_kwh_to_percent(
    capacity_kwh: float,
    nominal_capacity_kwh: float = MAX_BATTERY_CAPACITY_KWH
) -> float:
    """
    Convert battery capacity in kWh to percentage of nominal capacity.

    Args:
        capacity_kwh: Current battery capacity in kWh
        nominal_capacity_kwh: Nominal (new) battery capacity in kWh

    Returns:
        Capacity as percentage of nominal (0-100+%)

    Examples:
        >>> capacity_kwh_to_percent(18.4, 18.4)
        100.0
        >>> capacity_kwh_to_percent(16.56, 18.4)
        90.0
        >>> capacity_kwh_to_percent(12.88, 18.4)
        70.0
    """
    if nominal_capacity_kwh == 0:
        return 0.0
    return (capacity_kwh / nominal_capacity_kwh) * 100.0


def capacity_percent_to_kwh(
    capacity_percent: float,
    nominal_capacity_kwh: float = MAX_BATTERY_CAPACITY_KWH
) -> float:
    """
    Convert battery capacity percentage to kWh.

    Args:
        capacity_percent: Capacity as percentage of nominal (0-100%)
        nominal_capacity_kwh: Nominal (new) battery capacity in kWh

    Returns:
        Capacity in kWh

    Examples:
        >>> capacity_percent_to_kwh(100.0, 18.4)
        18.4
        >>> capacity_percent_to_kwh(90.0, 18.4)
        16.56
        >>> capacity_percent_to_kwh(70.0, 18.4)
        12.88
    """
    return (capacity_percent / 100.0) * nominal_capacity_kwh


def calculate_degradation_rate_per_10k_miles(
    slope_kwh_per_mile: float,
    nominal_capacity_kwh: float = MAX_BATTERY_CAPACITY_KWH
) -> float:
    """
    Calculate battery degradation rate in percent per 10,000 miles.

    Args:
        slope_kwh_per_mile: Linear regression slope (kWh capacity change per mile)
        nominal_capacity_kwh: Nominal battery capacity in kWh

    Returns:
        Degradation rate as percentage per 10k miles

    Examples:
        >>> # 0.00001 kWh/mile loss = 0.1 kWh per 10k miles = 0.54% degradation
        >>> calculate_degradation_rate_per_10k_miles(-0.00001, 18.4)
        0.54
    """
    degradation_kwh_per_10k = abs(slope_kwh_per_mile * 10000)
    degradation_percent_per_10k = (degradation_kwh_per_10k / nominal_capacity_kwh) * 100
    return round(degradation_percent_per_10k, 2)


def is_degradation_rate_normal(degradation_percent_per_10k: float) -> bool:
    """
    Determine if battery degradation rate is within normal range.

    Normal Volt Gen 2 degradation: 2-3% per 50k miles = 0.4-0.6% per 10k miles.
    We use a wider range (0.2-0.8%) to account for variation.

    Args:
        degradation_percent_per_10k: Degradation rate (% per 10k miles)

    Returns:
        True if within normal range, False otherwise

    Examples:
        >>> is_degradation_rate_normal(0.5)
        True
        >>> is_degradation_rate_normal(1.5)
        False
        >>> is_degradation_rate_normal(0.1)
        False
    """
    return NORMAL_DEGRADATION_MIN_PCT_PER_10K <= degradation_percent_per_10k <= NORMAL_DEGRADATION_MAX_PCT_PER_10K


def clamp_battery_capacity(
    capacity_kwh: float,
    min_capacity: float = MIN_BATTERY_CAPACITY_KWH,
    max_capacity: float = MAX_BATTERY_CAPACITY_KWH
) -> float:
    """
    Clamp battery capacity to realistic range (70-100% of nominal).

    Args:
        capacity_kwh: Unconstrained capacity value
        min_capacity: Minimum realistic capacity (70% of nominal)
        max_capacity: Maximum realistic capacity (100% of nominal)

    Returns:
        Clamped capacity in kWh

    Examples:
        >>> clamp_battery_capacity(20.0)
        18.4
        >>> clamp_battery_capacity(10.0)
        12.88
        >>> clamp_battery_capacity(15.0)
        15.0
    """
    return max(min_capacity, min(max_capacity, capacity_kwh))


def predict_capacity_at_mileage(
    odometer_miles: float,
    slope: float,
    intercept: float
) -> float:
    """
    Predict battery capacity at a given mileage using linear regression model.

    Args:
        odometer_miles: Target mileage for prediction
        slope: Linear regression slope (capacity_kwh per mile)
        intercept: Linear regression intercept (capacity_kwh at mile 0)

    Returns:
        Predicted capacity in kWh, clamped to realistic range

    Examples:
        >>> # Slope: -0.0001 kWh/mile, Intercept: 18.5 kWh
        >>> predict_capacity_at_mileage(50000, -0.0001, 18.5)
        13.5
    """
    predicted_capacity = slope * odometer_miles + intercept
    return clamp_battery_capacity(predicted_capacity)


def calculate_soc_buffer(
    soc_at_gas_transition: float,
    expected_threshold: float = 15.0
) -> float:
    """
    Calculate SOC buffer (reserve) beyond expected gas transition threshold.

    The Volt typically transitions to gas mode around 15% SOC, but the actual
    transition point varies with temperature and battery health. This calculates
    how much buffer exists.

    Args:
        soc_at_gas_transition: Actual SOC when gas mode engaged (%)
        expected_threshold: Expected transition threshold (%)

    Returns:
        Buffer in percentage points (negative = transition happened early)

    Examples:
        >>> calculate_soc_buffer(18.5, 15.0)
        3.5
        >>> calculate_soc_buffer(12.0, 15.0)
        -3.0
    """
    return soc_at_gas_transition - expected_threshold
