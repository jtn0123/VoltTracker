"""
Tests for statistical calculations
"""

import pytest
from receiver.calculations.statistics import (
    calculate_confidence_interval,
    calculate_trend_vs_previous,
    calculate_percent_change,
    calculate_moving_average,
    calculate_outlier_bounds,
    filter_outliers,
    calculate_correlation_simple,
    calculate_z_score,
)


class TestConfidenceInterval:
    """Test confidence interval calculations"""

    def test_calculate_confidence_interval_typical(self):
        """Normal distribution of efficiency values"""
        values = [0.28, 0.30, 0.32, 0.29, 0.31, 0.30, 0.28, 0.32]
        result = calculate_confidence_interval(values)
        assert result is not None
        assert result['sample_size'] == 8
        assert 0.29 <= result['mean'] <= 0.31
        assert result['ci_lower'] < result['mean']
        assert result['ci_upper'] > result['mean']

    def test_calculate_confidence_interval_insufficient_data(self):
        """Single value should return None"""
        assert calculate_confidence_interval([0.30]) is None

    def test_calculate_confidence_interval_empty(self):
        """Empty list should return None"""
        assert calculate_confidence_interval([]) is None

    def test_calculate_confidence_interval_large_sample(self):
        """Large sample (>30) should use z-distribution"""
        values = [0.30 + (i % 10) * 0.01 for i in range(50)]
        result = calculate_confidence_interval(values)
        assert result['sample_size'] == 50


class TestTrendCalculation:
    """Test trend vs previous period"""

    def test_calculate_trend_increase(self):
        """5% increase"""
        result = calculate_trend_vs_previous(105.0, 100.0)
        assert result['change_value'] == 5.0
        assert result['change_percent'] == 5.0
        assert result['direction'] == 'up'

    def test_calculate_trend_decrease(self):
        """10% decrease"""
        result = calculate_trend_vs_previous(90.0, 100.0)
        assert result['change_value'] == -10.0
        assert result['change_percent'] == -10.0
        assert result['direction'] == 'down'

    def test_calculate_trend_stable(self):
        """Small change (<1%) = stable"""
        result = calculate_trend_vs_previous(100.5, 100.0)
        assert result['direction'] == 'stable'

    def test_calculate_trend_no_previous(self):
        """No previous value = neutral"""
        result = calculate_trend_vs_previous(105.0, None)
        assert result['direction'] == 'neutral'
        assert result['change_value'] is None

    def test_calculate_trend_zero_previous(self):
        """Zero previous value = neutral"""
        result = calculate_trend_vs_previous(105.0, 0.0)
        assert result['direction'] == 'neutral'


class TestPercentChange:
    """Test percentage change calculations"""

    def test_calculate_percent_change_increase(self):
        """10% increase"""
        assert calculate_percent_change(110, 100) == 10.0

    def test_calculate_percent_change_decrease(self):
        """10% decrease"""
        assert calculate_percent_change(90, 100) == -10.0

    def test_calculate_percent_change_double(self):
        """100% increase (doubled)"""
        assert calculate_percent_change(200, 100) == 100.0

    def test_calculate_percent_change_zero_old(self):
        """Division by zero should return None"""
        assert calculate_percent_change(100, 0) is None


class TestMovingAverage:
    """Test moving average calculations"""

    def test_calculate_moving_average_typical(self):
        """3-point moving average"""
        values = [1, 2, 3, 4, 5]
        result = calculate_moving_average(values, 3)
        # [1,2,3] = 2.0, [2,3,4] = 3.0, [3,4,5] = 4.0
        assert result == [2.0, 3.0, 4.0]

    def test_calculate_moving_average_insufficient_data(self):
        """Not enough values for window"""
        values = [1, 2]
        assert calculate_moving_average(values, 3) == []

    def test_calculate_moving_average_exact_window(self):
        """Values exactly match window size"""
        values = [1, 2, 3]
        result = calculate_moving_average(values, 3)
        assert result == [2.0]

    def test_calculate_moving_average_smooth_noise(self):
        """Should smooth out noise"""
        values = [10, 100, 10, 10, 10]  # 100 is spike
        result = calculate_moving_average(values, 3)
        # [10,100,10] = 40, [100,10,10] = 40, [10,10,10] = 10
        assert result == [40.0, 40.0, 10.0]


class TestOutlierDetection:
    """Test outlier detection using IQR"""

    def test_calculate_outlier_bounds_typical(self):
        """Standard dataset with one outlier"""
        values = [1, 2, 3, 4, 5, 100]
        bounds = calculate_outlier_bounds(values)
        assert bounds['q1'] is not None
        assert bounds['q3'] is not None
        assert bounds['upper_bound'] < 100  # 100 should be outlier

    def test_calculate_outlier_bounds_insufficient_data(self):
        """< 4 values should return None"""
        values = [1, 2, 3]
        bounds = calculate_outlier_bounds(values)
        assert bounds['q1'] is None

    def test_filter_outliers_removes_extreme(self):
        """Should remove extreme values"""
        values = [1, 2, 3, 4, 5, 100]
        filtered = filter_outliers(values)
        assert 100 not in filtered
        assert len(filtered) == 5

    def test_filter_outliers_keeps_normal(self):
        """Normal values should all be kept"""
        values = [1, 2, 3, 4, 5]
        filtered = filter_outliers(values)
        assert len(filtered) == 5

    def test_filter_outliers_insufficient_data(self):
        """Should return original with insufficient data"""
        values = [1, 2, 3]
        filtered = filter_outliers(values)
        assert filtered == values


class TestCorrelation:
    """Test correlation calculations"""

    def test_calculate_correlation_perfect_positive(self):
        """Perfect positive correlation = 1.0"""
        x = [1, 2, 3, 4]
        y = [2, 4, 6, 8]
        result = calculate_correlation_simple(x, y)
        assert result == 1.0

    def test_calculate_correlation_perfect_negative(self):
        """Perfect negative correlation = -1.0"""
        x = [1, 2, 3, 4]
        y = [4, 3, 2, 1]
        result = calculate_correlation_simple(x, y)
        assert result == -1.0

    def test_calculate_correlation_no_correlation(self):
        """No correlation = ~0.0"""
        x = [1, 2, 3, 4]
        y = [2, 1, 4, 3]
        result = calculate_correlation_simple(x, y)
        assert -0.5 < result < 0.5

    def test_calculate_correlation_mismatched_lengths(self):
        """Different lengths should return None"""
        x = [1, 2, 3]
        y = [1, 2]
        assert calculate_correlation_simple(x, y) is None

    def test_calculate_correlation_insufficient_data(self):
        """< 2 values should return None"""
        x = [1]
        y = [1]
        assert calculate_correlation_simple(x, y) is None

    def test_calculate_correlation_constant_values(self):
        """Constant values (zero std) should return None"""
        x = [1, 1, 1, 1]
        y = [2, 3, 4, 5]
        assert calculate_correlation_simple(x, y) is None


class TestZScore:
    """Test z-score calculations"""

    def test_calculate_z_score_above_mean(self):
        """110 with mean=100, std=10 -> z=1.0"""
        assert calculate_z_score(110, 100, 10) == 1.0

    def test_calculate_z_score_below_mean(self):
        """85 with mean=100, std=10 -> z=-1.5"""
        assert calculate_z_score(85, 100, 10) == -1.5

    def test_calculate_z_score_at_mean(self):
        """Value = mean -> z=0"""
        assert calculate_z_score(100, 100, 10) == 0.0

    def test_calculate_z_score_zero_std(self):
        """Zero std should return None"""
        assert calculate_z_score(100, 100, 0) is None

    def test_calculate_z_score_far_outlier(self):
        """Value 3+ std away"""
        result = calculate_z_score(130, 100, 10)
        assert result == 3.0
