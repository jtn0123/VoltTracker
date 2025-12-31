"""Utility modules for Volt Tracker receiver."""

from .torque_parser import TorqueParser
from .calculations import (
    calculate_gas_mpg,
    smooth_fuel_level,
    detect_gas_mode_entry,
    detect_refuel_event,
    calculate_electric_miles,
    calculate_average_temp,
    analyze_soc_floor,
    calculate_electric_kwh,
    calculate_kwh_per_mile,
    detect_charging_session,
)
from .timezone import (
    utc_now,
    normalize_datetime,
    ensure_utc,
    is_before,
    is_after,
)

__all__ = [
    'TorqueParser',
    'calculate_gas_mpg',
    'smooth_fuel_level',
    'detect_gas_mode_entry',
    'detect_refuel_event',
    'calculate_electric_miles',
    'calculate_average_temp',
    'analyze_soc_floor',
    'calculate_electric_kwh',
    'calculate_kwh_per_mile',
    'detect_charging_session',
    'utc_now',
    'normalize_datetime',
    'ensure_utc',
    'is_before',
    'is_after',
]
