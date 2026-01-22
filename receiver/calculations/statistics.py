"""
Statistical Calculations

Handles statistical analysis for analytics:
- Confidence intervals
- Trend calculations
- Correlation metrics
- Standard deviations
"""

import statistics as stats_module
from typing import List, Optional

from .constants import (
    DEFAULT_CONFIDENCE_LEVEL,
    PERCENTAGE_STABLE_THRESHOLD,
    SMALL_SAMPLE_THRESHOLD,
    T_CRITICAL_SMALL_SAMPLE,
    Z_CRITICAL_95_PERCENT,
)


def calculate_confidence_interval(
    values: List[float],
    confidence: float = DEFAULT_CONFIDENCE_LEVEL
) -> Optional[dict]:
    """
    Calculate confidence interval for a list of values.

    Uses t-distribution for small samples (n < 30) and z-distribution for large.

    Args:
        values: List of numeric values
        confidence: Confidence level (default 0.95 for 95% CI)

    Returns:
        Dict with mean, ci_lower, ci_upper, margin, sample_size, std_dev
        Returns None if insufficient data

    Examples:
        >>> values = [0.28, 0.30, 0.32, 0.29, 0.31]
        >>> ci = calculate_confidence_interval(values)
        >>> ci['mean']
        0.3
    """
    if not values or len(values) < 2:
        return None

    mean = stats_module.mean(values)
    stdev = stats_module.stdev(values)
    n = len(values)

    # Use t-distribution for small samples, z for large
    if n < SMALL_SAMPLE_THRESHOLD:
        t_critical = T_CRITICAL_SMALL_SAMPLE
    else:
        t_critical = Z_CRITICAL_95_PERCENT

    margin = t_critical * (stdev / (n ** 0.5))

    return {
        "mean": round(mean, 2),
        "ci_lower": round(mean - margin, 2),
        "ci_upper": round(mean + margin, 2),
        "margin": round(margin, 2),
        "sample_size": n,
        "std_dev": round(stdev, 2)
    }


def calculate_trend_vs_previous(
    current_value: float,
    previous_value: Optional[float]
) -> dict:
    """
    Calculate trend indicator vs previous period.

    Args:
        current_value: Current period value
        previous_value: Previous period value for comparison

    Returns:
        Dict with change_value, change_percent, direction

    Examples:
        >>> trend = calculate_trend_vs_previous(105.0, 100.0)
        >>> trend['direction']
        'up'
        >>> trend['change_percent']
        5.0
    """
    if previous_value is None or previous_value == 0:
        return {
            "change_value": None,
            "change_percent": None,
            "direction": "neutral"
        }

    change_value = current_value - previous_value
    change_percent = (change_value / previous_value) * 100

    # Determine direction
    if abs(change_percent) < PERCENTAGE_STABLE_THRESHOLD:
        direction = "stable"
    elif change_value > 0:
        direction = "up"
    else:
        direction = "down"

    return {
        "change_value": round(change_value, 2),
        "change_percent": round(change_percent, 1),
        "direction": direction
    }


def calculate_percent_change(
    new_value: float,
    old_value: float
) -> Optional[float]:
    """
    Calculate percentage change between two values.

    Args:
        new_value: New/current value
        old_value: Old/previous value

    Returns:
        Percentage change, or None if invalid

    Examples:
        >>> calculate_percent_change(110, 100)
        10.0
        >>> calculate_percent_change(90, 100)
        -10.0
        >>> calculate_percent_change(100, 0)
        None
    """
    if old_value == 0:
        return None

    change = ((new_value - old_value) / old_value) * 100
    return round(change, 1)


def calculate_moving_average(
    values: List[float],
    window_size: int
) -> List[float]:
    """
    Calculate simple moving average.

    Args:
        values: List of values
        window_size: Number of values in each average

    Returns:
        List of moving averages (length = len(values) - window_size + 1)

    Examples:
        >>> calculate_moving_average([1, 2, 3, 4, 5], 3)
        [2.0, 3.0, 4.0]
    """
    # Validate window_size to prevent division by zero and infinite loops
    if window_size <= 0:
        return []

    if len(values) < window_size:
        return []

    moving_avgs = []
    for i in range(len(values) - window_size + 1):
        window = values[i:i + window_size]
        moving_avgs.append(round(sum(window) / window_size, 2))

    return moving_avgs


def calculate_outlier_bounds(values: List[float], iqr_multiplier: float = 1.5) -> dict:
    """
    Calculate outlier bounds using Interquartile Range (IQR) method.

    Args:
        values: List of numeric values
        iqr_multiplier: Multiplier for IQR (1.5 = standard, 3.0 = extreme)

    Returns:
        Dict with q1, q3, iqr, lower_bound, upper_bound

    Examples:
        >>> values = [1, 2, 3, 4, 5, 100]  # 100 is outlier
        >>> bounds = calculate_outlier_bounds(values)
        >>> bounds['upper_bound'] < 100
        True
    """
    if len(values) < 4:
        return {
            "q1": None,
            "q3": None,
            "iqr": None,
            "lower_bound": None,
            "upper_bound": None
        }

    sorted_values = sorted(values)
    n = len(sorted_values)

    # Calculate quartiles
    q1_idx = n // 4
    q3_idx = 3 * n // 4

    q1 = sorted_values[q1_idx]
    q3 = sorted_values[q3_idx]
    iqr = q3 - q1

    lower_bound = q1 - (iqr_multiplier * iqr)
    upper_bound = q3 + (iqr_multiplier * iqr)

    return {
        "q1": round(q1, 2),
        "q3": round(q3, 2),
        "iqr": round(iqr, 2),
        "lower_bound": round(lower_bound, 2),
        "upper_bound": round(upper_bound, 2)
    }


def filter_outliers(values: List[float], iqr_multiplier: float = 1.5) -> List[float]:
    """
    Remove outliers from list using IQR method.

    Args:
        values: List of numeric values
        iqr_multiplier: Multiplier for IQR

    Returns:
        List with outliers removed

    Examples:
        >>> filter_outliers([1, 2, 3, 4, 5, 100])
        [1, 2, 3, 4, 5]
    """
    if len(values) < 4:
        return values

    bounds = calculate_outlier_bounds(values, iqr_multiplier)

    if bounds["lower_bound"] is None:
        return values

    filtered = [
        v for v in values
        if bounds["lower_bound"] <= v <= bounds["upper_bound"]
    ]

    return filtered


def calculate_correlation_simple(
    x_values: List[float],
    y_values: List[float]
) -> Optional[float]:
    """
    Calculate Pearson correlation coefficient between two variables.

    Simplified implementation without scipy. Returns value between -1 and 1:
    - 1 = perfect positive correlation
    - 0 = no correlation
    - -1 = perfect negative correlation

    Args:
        x_values: First variable values
        y_values: Second variable values (must be same length as x_values)

    Returns:
        Correlation coefficient, or None if insufficient data

    Examples:
        >>> calculate_correlation_simple([1, 2, 3, 4], [2, 4, 6, 8])
        1.0
        >>> calculate_correlation_simple([1, 2, 3, 4], [4, 3, 2, 1])
        -1.0
    """
    if len(x_values) != len(y_values) or len(x_values) < 2:
        return None

    n = len(x_values)
    mean_x = sum(x_values) / n
    mean_y = sum(y_values) / n

    # Calculate covariance and standard deviations
    covariance = sum((x - mean_x) * (y - mean_y) for x, y in zip(x_values, y_values)) / n
    std_x = (sum((x - mean_x) ** 2 for x in x_values) / n) ** 0.5
    std_y = (sum((y - mean_y) ** 2 for y in y_values) / n) ** 0.5

    if std_x == 0 or std_y == 0:
        return None

    correlation = covariance / (std_x * std_y)
    return round(correlation, 3)


def calculate_z_score(value: float, mean: float, std_dev: float) -> Optional[float]:
    """
    Calculate z-score (standard score) for a value.

    Indicates how many standard deviations a value is from the mean.

    Args:
        value: Value to score
        mean: Population mean
        std_dev: Population standard deviation

    Returns:
        Z-score, or None if std_dev is zero

    Examples:
        >>> calculate_z_score(110, 100, 10)
        1.0
        >>> calculate_z_score(85, 100, 10)
        -1.5
    """
    if std_dev == 0:
        return None

    z_score = (value - mean) / std_dev
    return round(z_score, 2)
