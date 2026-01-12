"""
VoltTracker Calculation Module

Consolidated calculation utilities for energy, efficiency, fuel, battery,
financial, and statistical calculations.

This module provides a single source of truth for all mathematical operations
related to vehicle telemetry analysis.

Usage:
    from calculations import calculate_kwh_per_mile, calculate_mpg
    from calculations.constants import BATTERY_CAPACITY_KWH
"""

# Energy conversions
from .energy import (
    calculate_energy_from_soc_change,
    fuel_percent_to_gallons,
    gallons_to_fuel_percent,
    integrate_power_over_time,
    kwh_to_soc,
    soc_to_kwh,
)

# Battery calculations
from .battery import (
    calculate_degradation_rate_per_10k_miles,
    calculate_soc_buffer,
    capacity_kwh_to_percent,
    capacity_percent_to_kwh,
    clamp_battery_capacity,
    is_degradation_rate_normal,
    predict_capacity_at_mileage,
)

# Efficiency calculations
from .efficiency import (
    calculate_combined_efficiency,
    calculate_efficiency_impact_percent,
    calculate_kwh_per_mile,
    calculate_miles_per_kwh,
    calculate_mpg,
    calculate_mpge,
    calculate_range_from_efficiency,
    is_efficiency_within_range,
)

# Fuel calculations
from .fuel import (
    calculate_fuel_consumed_gallons,
    calculate_gas_mpg,
    detect_refuel_event,
    estimate_fuel_range,
    smooth_fuel_level,
)

# Financial calculations
from .financial import (
    calculate_charging_cost,
    calculate_cost_savings_vs_gas_only,
    calculate_electric_cost_per_mile,
    calculate_fuel_cost,
    calculate_gas_cost_per_mile,
    calculate_payback_period_years,
    calculate_trip_cost,
)

# Statistical calculations
from .statistics import (
    calculate_confidence_interval,
    calculate_correlation_simple,
    calculate_moving_average,
    calculate_outlier_bounds,
    calculate_percent_change,
    calculate_trend_vs_previous,
    calculate_z_score,
    filter_outliers,
)

# Constants (re-export for convenience)
from .constants import (
    BASELINE_KWH_PER_MILE,
    BATTERY_CAPACITY_KWH,
    ELECTRICITY_COST_PER_KWH,
    GAS_COST_PER_GALLON,
    MAX_BATTERY_CAPACITY_KWH,
    MAX_KWH_PER_MILE,
    MAX_MPG,
    MIN_BATTERY_CAPACITY_KWH,
    MIN_KWH_PER_MILE,
    MIN_MPG,
    REFUEL_JUMP_THRESHOLD_PERCENT,
    RPM_THRESHOLD,
    SOC_GAS_THRESHOLD,
    TANK_CAPACITY_GALLONS,
)

__all__ = [
    # Energy
    "soc_to_kwh",
    "kwh_to_soc",
    "fuel_percent_to_gallons",
    "gallons_to_fuel_percent",
    "integrate_power_over_time",
    "calculate_energy_from_soc_change",
    # Battery
    "capacity_kwh_to_percent",
    "capacity_percent_to_kwh",
    "calculate_degradation_rate_per_10k_miles",
    "is_degradation_rate_normal",
    "clamp_battery_capacity",
    "predict_capacity_at_mileage",
    "calculate_soc_buffer",
    # Efficiency
    "calculate_kwh_per_mile",
    "calculate_mpg",
    "calculate_efficiency_impact_percent",
    "calculate_range_from_efficiency",
    "calculate_miles_per_kwh",
    "calculate_mpge",
    "is_efficiency_within_range",
    "calculate_combined_efficiency",
    # Fuel
    "calculate_fuel_consumed_gallons",
    "calculate_gas_mpg",
    "detect_refuel_event",
    "smooth_fuel_level",
    "estimate_fuel_range",
    # Financial
    "calculate_charging_cost",
    "calculate_fuel_cost",
    "calculate_electric_cost_per_mile",
    "calculate_gas_cost_per_mile",
    "calculate_trip_cost",
    "calculate_cost_savings_vs_gas_only",
    "calculate_payback_period_years",
    # Statistics
    "calculate_confidence_interval",
    "calculate_trend_vs_previous",
    "calculate_percent_change",
    "calculate_moving_average",
    "calculate_outlier_bounds",
    "filter_outliers",
    "calculate_correlation_simple",
    "calculate_z_score",
    # Constants
    "BATTERY_CAPACITY_KWH",
    "TANK_CAPACITY_GALLONS",
    "BASELINE_KWH_PER_MILE",
    "MIN_KWH_PER_MILE",
    "MAX_KWH_PER_MILE",
    "MIN_MPG",
    "MAX_MPG",
    "SOC_GAS_THRESHOLD",
    "RPM_THRESHOLD",
    "ELECTRICITY_COST_PER_KWH",
    "GAS_COST_PER_GALLON",
    "MIN_BATTERY_CAPACITY_KWH",
    "MAX_BATTERY_CAPACITY_KWH",
    "REFUEL_JUMP_THRESHOLD_PERCENT",
]

__version__ = "1.0.0"
