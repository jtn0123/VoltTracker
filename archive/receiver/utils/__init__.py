"""Utility modules for Volt Tracker receiver."""

from .calculations import (
    analyze_soc_floor,
    calculate_average_temp,
    calculate_electric_kwh,
    calculate_electric_miles,
    calculate_gas_mpg,
    calculate_kwh_per_mile,
    detect_charging_session,
    detect_gas_mode_entry,
    detect_refuel_event,
    fuel_percent_to_gallons,
    smooth_fuel_level,
    soc_to_kwh,
)
from .timezone import ensure_utc, is_after, is_before, normalize_datetime, utc_now
from .torque_parser import TorqueParser

__all__ = [
    "TorqueParser",
    "calculate_gas_mpg",
    "smooth_fuel_level",
    "detect_gas_mode_entry",
    "detect_refuel_event",
    "calculate_electric_miles",
    "calculate_average_temp",
    "analyze_soc_floor",
    "calculate_electric_kwh",
    "calculate_kwh_per_mile",
    "detect_charging_session",
    "fuel_percent_to_gallons",
    "soc_to_kwh",
    "utc_now",
    "normalize_datetime",
    "ensure_utc",
    "is_before",
    "is_after",
]
