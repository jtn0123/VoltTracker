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
]
