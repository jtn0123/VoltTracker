"""
Tests for fuel-related calculations
"""

import pytest
from receiver.calculations.fuel import (
    calculate_fuel_consumed_gallons,
    calculate_gas_mpg,
    detect_refuel_event,
    smooth_fuel_level,
    calculate_fuel_cost,
    estimate_fuel_range,
)


class TestFuelConsumption:
    """Test fuel consumption calculations"""

    def test_calculate_fuel_consumed_normal(self):
        """80% -> 60% in 9 gal tank = 1.8 gal"""
        result = calculate_fuel_consumed_gallons(80.0, 60.0, 9.0)
        assert result == 1.8

    def test_calculate_fuel_consumed_large_drop(self):
        """100% -> 50% = 4.5 gal"""
        result = calculate_fuel_consumed_gallons(100.0, 50.0, 9.0)
        assert result == 4.5

    def test_calculate_fuel_consumed_refuel_detected(self):
        """Fuel increase should return None (refuel)"""
        assert calculate_fuel_consumed_gallons(60.0, 80.0, 9.0) is None

    def test_calculate_fuel_consumed_no_change(self):
        """No change should return None"""
        assert calculate_fuel_consumed_gallons(50.0, 50.0, 9.0) is None

    def test_calculate_fuel_consumed_none_values(self):
        assert calculate_fuel_consumed_gallons(None, 50.0, 9.0) is None
        assert calculate_fuel_consumed_gallons(50.0, None, 9.0) is None

    def test_calculate_fuel_consumed_noise_filtered(self):
        """Tiny changes (< 0.01 gal) should be filtered as noise"""
        # 0.1% of 9 gal = 0.009 gal (below threshold)
        assert calculate_fuel_consumed_gallons(50.1, 50.0, 9.0) is None


class TestGasMPG:
    """Test gas MPG calculations"""

    def test_calculate_gas_mpg_typical(self):
        """40 miles, 80% -> 70% fuel = ~44.4 MPG"""
        result = calculate_gas_mpg(1000, 1040, 80.0, 70.0, 9.0)
        assert result == 44.4

    def test_calculate_gas_mpg_efficient(self):
        """50 miles, 90% -> 80% fuel = 55.6 MPG (50mi / 0.9gal)"""
        result = calculate_gas_mpg(1000, 1050, 90.0, 80.0, 9.0)
        assert result == 55.6

    def test_calculate_gas_mpg_too_short(self):
        """Trip < 1 mile should return None"""
        assert calculate_gas_mpg(1000, 1000.5, 80.0, 79.0, 9.0) is None

    def test_calculate_gas_mpg_none_values(self):
        assert calculate_gas_mpg(None, 1040, 80.0, 70.0, 9.0) is None
        assert calculate_gas_mpg(1000, None, 80.0, 70.0, 9.0) is None
        assert calculate_gas_mpg(1000, 1040, None, 70.0, 9.0) is None
        assert calculate_gas_mpg(1000, 1040, 80.0, None, 9.0) is None

    def test_calculate_gas_mpg_refuel_during_trip(self):
        """Fuel increase should return None"""
        assert calculate_gas_mpg(1000, 1040, 60.0, 80.0, 9.0) is None

    def test_calculate_gas_mpg_outlier_too_high(self):
        """MPG > 60 should be rejected"""
        # 100 miles on 1% fuel change = 0.09 gal = 1111 MPG (unrealistic)
        assert calculate_gas_mpg(1000, 1100, 80.0, 79.0, 9.0, validate=True) is None

    def test_calculate_gas_mpg_outlier_too_low(self):
        """MPG < 15 should be rejected"""
        # 10 miles on 10% fuel = 0.9 gal = 11.1 MPG (unrealistic for Volt)
        assert calculate_gas_mpg(1000, 1010, 80.0, 70.0, 9.0, validate=True) is None

    def test_calculate_gas_mpg_no_validation(self):
        """Should allow outliers without validation"""
        result = calculate_gas_mpg(1000, 1100, 80.0, 79.0, 9.0, validate=False)
        assert result is not None


class TestRefuelDetection:
    """Test refuel event detection"""

    def test_detect_refuel_event_large_increase(self):
        """50% -> 90% = refuel"""
        assert detect_refuel_event(90.0, 50.0) is True

    def test_detect_refuel_event_at_threshold(self):
        """Exactly 10% increase = refuel"""
        assert detect_refuel_event(60.0, 50.0) is True

    def test_detect_refuel_event_small_increase(self):
        """Small increase (< 10%) = not refuel"""
        assert detect_refuel_event(55.0, 50.0) is False

    def test_detect_refuel_event_decrease(self):
        """Fuel decrease = not refuel"""
        assert detect_refuel_event(40.0, 50.0) is False

    def test_detect_refuel_event_none_values(self):
        assert detect_refuel_event(None, 50.0) is False
        assert detect_refuel_event(90.0, None) is False

    def test_detect_refuel_event_custom_threshold(self):
        """Custom threshold test"""
        # 5% increase with 5% threshold = refuel
        assert detect_refuel_event(55.0, 50.0, jump_threshold=5.0) is True


class TestFuelLevelSmoothing:
    """Test fuel level smoothing"""

    def test_smooth_fuel_level_median(self):
        """Should return median of readings"""
        readings = [50.0, 52.0, 48.0, 51.0, 49.0]
        assert smooth_fuel_level(readings) == 50.0

    def test_smooth_fuel_level_removes_outlier(self):
        """Median filter should remove outliers"""
        readings = [50.0, 90.0, 51.0, 49.0]  # 90 is outlier
        result = smooth_fuel_level(readings)
        assert 49.0 <= result <= 51.0

    def test_smooth_fuel_level_single_reading(self):
        """Single reading should return that value"""
        assert smooth_fuel_level([50.0]) == 50.0

    def test_smooth_fuel_level_empty(self):
        """Empty list should return 0"""
        assert smooth_fuel_level([]) == 0.0

    def test_smooth_fuel_level_window_size(self):
        """Should use only last N readings"""
        readings = [10.0, 20.0, 30.0, 40.0, 50.0, 51.0, 49.0]
        # With window=3, should only use [50.0, 51.0, 49.0] -> median = 50.0
        result = smooth_fuel_level(readings, window_size=3)
        assert result == 50.0


class TestFuelCost:
    """Test fuel cost calculations"""

    def test_calculate_fuel_cost_normal(self):
        """10 gallons at $3.50 = $35.00"""
        assert calculate_fuel_cost(10.0, 3.50) == 35.0

    def test_calculate_fuel_cost_partial(self):
        """5.5 gallons at $4.00 = $22.00"""
        assert calculate_fuel_cost(5.5, 4.00) == 22.0

    def test_calculate_fuel_cost_expensive(self):
        """2 gallons at $5.00 = $10.00"""
        assert calculate_fuel_cost(2.0, 5.00) == 10.0


class TestFuelRange:
    """Test fuel range estimation"""

    def test_estimate_fuel_range_half_tank(self):
        """50% fuel at 40 MPG = 180 miles"""
        assert estimate_fuel_range(50.0, 40.0, 9.0) == 180.0

    def test_estimate_fuel_range_full_tank(self):
        """100% fuel at 35 MPG = 315 miles"""
        assert estimate_fuel_range(100.0, 35.0, 9.0) == 315.0

    def test_estimate_fuel_range_low_fuel(self):
        """10% fuel at 40 MPG = 36 miles"""
        assert estimate_fuel_range(10.0, 40.0, 9.0) == 36.0

    def test_estimate_fuel_range_empty(self):
        """0% fuel = 0 miles"""
        assert estimate_fuel_range(0.0, 40.0, 9.0) == 0.0
