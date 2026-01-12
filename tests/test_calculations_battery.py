"""
Tests for battery-related calculations
"""

import pytest
from receiver.calculations.battery import (
    capacity_kwh_to_percent,
    capacity_percent_to_kwh,
    calculate_degradation_rate_per_10k_miles,
    is_degradation_rate_normal,
    clamp_battery_capacity,
    predict_capacity_at_mileage,
    calculate_soc_buffer,
)


class TestCapacityConversions:
    """Test battery capacity conversions"""

    def test_capacity_kwh_to_percent_full(self):
        assert capacity_kwh_to_percent(18.4, 18.4) == 100.0

    def test_capacity_kwh_to_percent_degraded(self):
        assert capacity_kwh_to_percent(16.56, 18.4) == 90.0

    def test_capacity_kwh_to_percent_floor(self):
        """70% capacity (severe degradation)"""
        assert capacity_kwh_to_percent(12.88, 18.4) == 70.0

    def test_capacity_kwh_to_percent_zero_nominal(self):
        """Should handle zero nominal capacity"""
        assert capacity_kwh_to_percent(10.0, 0.0) == 0.0

    def test_capacity_percent_to_kwh_full(self):
        assert capacity_percent_to_kwh(100.0, 18.4) == 18.4

    def test_capacity_percent_to_kwh_degraded(self):
        assert capacity_percent_to_kwh(90.0, 18.4) == 16.56

    def test_capacity_roundtrip(self):
        """Converting kWh -> % -> kWh should preserve value"""
        original_kwh = 15.5
        percent = capacity_kwh_to_percent(original_kwh, 18.4)
        result_kwh = capacity_percent_to_kwh(percent, 18.4)
        assert abs(result_kwh - original_kwh) < 0.01


class TestDegradationRate:
    """Test degradation rate calculations"""

    def test_calculate_degradation_rate_typical(self):
        """0.0001 kWh/mile loss = ~0.54% per 10k miles"""
        slope = -0.0001  # Losing 0.0001 kWh per mile
        rate = calculate_degradation_rate_per_10k_miles(slope, 18.4)
        assert rate == 0.54

    def test_calculate_degradation_rate_fast(self):
        """Faster degradation"""
        slope = -0.0002  # Losing 0.0002 kWh per mile
        rate = calculate_degradation_rate_per_10k_miles(slope, 18.4)
        assert rate == 1.09

    def test_calculate_degradation_rate_slow(self):
        """Slower degradation"""
        slope = -0.00005  # Losing 0.00005 kWh per mile
        rate = calculate_degradation_rate_per_10k_miles(slope, 18.4)
        assert rate == 0.27

    def test_is_degradation_rate_normal_typical(self):
        """0.5% per 10k is normal"""
        assert is_degradation_rate_normal(0.5) is True

    def test_is_degradation_rate_normal_boundary_low(self):
        """0.2% is at lower boundary"""
        assert is_degradation_rate_normal(0.2) is True

    def test_is_degradation_rate_normal_boundary_high(self):
        """0.8% is at upper boundary"""
        assert is_degradation_rate_normal(0.8) is True

    def test_is_degradation_rate_normal_too_fast(self):
        """1.5% per 10k is too fast"""
        assert is_degradation_rate_normal(1.5) is False

    def test_is_degradation_rate_normal_too_slow(self):
        """0.1% per 10k is suspiciously slow"""
        assert is_degradation_rate_normal(0.1) is False


class TestCapacityClamping:
    """Test battery capacity clamping"""

    def test_clamp_within_range(self):
        """Value within range should be unchanged"""
        assert clamp_battery_capacity(15.0) == 15.0

    def test_clamp_above_max(self):
        """Value above max should be clamped to max"""
        assert clamp_battery_capacity(20.0) == 18.4

    def test_clamp_below_min(self):
        """Value below min should be clamped to min"""
        assert clamp_battery_capacity(10.0) == 12.88

    def test_clamp_at_boundaries(self):
        """Boundary values should be preserved"""
        assert clamp_battery_capacity(18.4) == 18.4
        assert clamp_battery_capacity(12.88) == 12.88


class TestCapacityPrediction:
    """Test battery capacity prediction"""

    def test_predict_capacity_at_mileage_declining(self):
        """Predict with negative slope (degradation)"""
        # Slope: -0.0001 kWh/mile, Intercept: 18.5 kWh
        # At 50k miles: -0.0001 * 50000 + 18.5 = 13.5 kWh
        result = predict_capacity_at_mileage(50000, -0.0001, 18.5)
        assert result == 13.5

    def test_predict_capacity_clamped_to_min(self):
        """Prediction below min should be clamped"""
        # Predict very low capacity (would be < 12.88)
        result = predict_capacity_at_mileage(100000, -0.0002, 18.0)
        assert result == 12.88

    def test_predict_capacity_clamped_to_max(self):
        """Prediction above max should be clamped"""
        # Predict unrealistically high capacity
        result = predict_capacity_at_mileage(0, 0.0001, 20.0)
        assert result == 18.4


class TestSOCBuffer:
    """Test SOC buffer calculations"""

    def test_soc_buffer_positive(self):
        """Transition at 18.5% with 15% threshold = 3.5% buffer"""
        assert calculate_soc_buffer(18.5, 15.0) == 3.5

    def test_soc_buffer_negative(self):
        """Transition at 12% with 15% threshold = -3.0% buffer"""
        assert calculate_soc_buffer(12.0, 15.0) == -3.0

    def test_soc_buffer_at_threshold(self):
        """Transition exactly at threshold = 0 buffer"""
        assert calculate_soc_buffer(15.0, 15.0) == 0.0

    def test_soc_buffer_cold_weather(self):
        """Cold weather might cause early transition (negative buffer)"""
        assert calculate_soc_buffer(10.0, 15.0) == -5.0
