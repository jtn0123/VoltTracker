"""
Calculation Constants for VoltTracker

Centralized location for all mathematical and physical constants used in calculations.
All values should be imported from Config where possible to maintain single source of truth.
"""

from config import Config

# Battery Constants
BATTERY_CAPACITY_KWH = Config.BATTERY_CAPACITY_KWH  # Total usable battery capacity
SOC_GAS_THRESHOLD = Config.SOC_GAS_THRESHOLD  # SOC % below which gas mode typically engages
MIN_BATTERY_CAPACITY_KWH = 12.88  # 70% of nominal (degradation floor)
MAX_BATTERY_CAPACITY_KWH = 18.4  # 100% of nominal capacity

# Fuel Constants
TANK_CAPACITY_GALLONS = Config.TANK_CAPACITY_GALLONS  # Gas tank capacity
MIN_FUEL_CONSUMPTION_GALLONS = 0.01  # Minimum gallons for valid consumption
REFUEL_JUMP_THRESHOLD_PERCENT = 10.0  # Minimum % increase to detect refuel

# Efficiency Constants
BASELINE_KWH_PER_MILE = 0.32  # Ideal efficiency baseline for comparisons
MIN_KWH_PER_MILE = Config.MIN_KWH_PER_MILE  # Lower bound for sanity checks
MAX_KWH_PER_MILE = Config.MAX_KWH_PER_MILE  # Upper bound for sanity checks
MIN_DISTANCE_FOR_EFFICIENCY = 0.5  # Minimum miles for valid efficiency calculation

# MPG Constants
MIN_MPG = Config.MIN_MPG  # Minimum valid MPG (sanity check)
MAX_MPG = Config.MAX_MPG  # Maximum valid MPG (sanity check)
MIN_GAS_MILES_FOR_MPG = 1.0  # Minimum gas miles for reliable MPG

# Engine Constants
RPM_THRESHOLD = Config.RPM_THRESHOLD  # RPM above which engine is considered running

# Charging Constants
MIN_CHARGING_POWER_KW = 0.5  # Minimum power to consider as active charging
L1_CHARGING_POWER_THRESHOLD_KW = 1.2  # Below this = L1
L2_CHARGING_POWER_THRESHOLD_KW = 3.0  # Below this = L2, above = potential DCFC
DCFC_POWER_THRESHOLD_KW = 20.0  # Above this = DC Fast Charging

# Cost Constants
ELECTRICITY_COST_PER_KWH = Config.ELECTRICITY_COST_PER_KWH  # Default electricity rate
GAS_COST_PER_GALLON = 3.50  # Default gas price (can be overridden)

# Statistical Constants
DEFAULT_CONFIDENCE_LEVEL = 0.95  # 95% confidence interval
T_CRITICAL_SMALL_SAMPLE = 2.0  # Approximation for t-distribution (n < 30)
Z_CRITICAL_95_PERCENT = 1.96  # Z-score for 95% CI (large samples)
SMALL_SAMPLE_THRESHOLD = 30  # Sample size below which to use t-distribution

# Smoothing Constants
FUEL_LEVEL_SMOOTHING_WINDOW = 10  # Number of readings for median filter

# Degradation Constants
NORMAL_DEGRADATION_MIN_PCT_PER_10K = 0.2  # Minimum normal degradation rate
NORMAL_DEGRADATION_MAX_PCT_PER_10K = 0.8  # Maximum normal degradation rate

# Temperature Thresholds
FREEZING_TEMP_F = 32.0  # Freezing point
COLD_TEMP_F = 45.0  # Cold weather threshold
COOL_TEMP_F = 55.0  # Cool weather threshold
IDEAL_TEMP_MAX_F = 75.0  # Upper bound of ideal range
WARM_TEMP_F = 85.0  # Warm weather threshold
HOT_TEMP_F = 95.0  # Hot weather threshold

# Validation Thresholds
MIN_PERCENTAGE = 0.0  # Minimum valid percentage
MAX_PERCENTAGE = 100.0  # Maximum valid percentage
MIN_POSITIVE_VALUE = 0.001  # Minimum positive value for division safety
PERCENTAGE_STABLE_THRESHOLD = 1.0  # % change below which trend is "stable"
