"""
Tests for efficiency calculations
"""

import pytest
from receiver.calculations.efficiency import (
    calculate_kwh_per_mile,
    calculate_mpg,
    calculate_efficiency_impact_percent,
    calculate_range_from_efficiency,
    calculate_miles_per_kwh,
    calculate_mpge,
    is_efficiency_within_range,
    calculate_combined_efficiency,
)


class TestKWhPerMile:
    """Test kWh/mile efficiency calculations"""

    def test_calculate_kwh_per_mile_typical(self):
        """5 kWh over 20 miles = 0.25 kWh/mi"""
        assert calculate_kwh_per_mile(5.0, 20.0) == 0.25

    def test_calculate_kwh_per_mile_less_efficient(self):
        """8 kWh over 20 miles = 0.4 kWh/mi"""
        assert calculate_kwh_per_mile(8.0, 20.0) == 0.4

    def test_calculate_kwh_per_mile_none_values(self):
        assert calculate_kwh_per_mile(None, 20.0) is None
        assert calculate_kwh_per_mile(5.0, None) is None

    def test_calculate_kwh_per_mile_too_short(self):
        """Trip too short (< 0.5 mi) should return None"""
        assert calculate_kwh_per_mile(1.0, 0.3) is None

    def test_calculate_kwh_per_mile_zero_distance(self):
        """Zero distance should return None"""
        assert calculate_kwh_per_mile(5.0, 0.0) is None

    def test_calculate_kwh_per_mile_outlier_too_high(self):
        """Unrealistically high value should return None with validation"""
        assert calculate_kwh_per_mile(50.0, 20.0, validate=True) is None

    def test_calculate_kwh_per_mile_outlier_too_low(self):
        """Unrealistically low value should return None with validation"""
        assert calculate_kwh_per_mile(0.1, 20.0, validate=True) is None

    def test_calculate_kwh_per_mile_no_validation(self):
        """Should allow outliers if validation disabled"""
        result = calculate_kwh_per_mile(50.0, 20.0, validate=False)
        assert result == 2.5


class TestMPG:
    """Test MPG calculations"""

    def test_calculate_mpg_typical(self):
        """100 miles on 2.5 gallons = 40 MPG"""
        assert calculate_mpg(100.0, 2.5) == 40.0

    def test_calculate_mpg_efficient(self):
        """100 miles on 2 gallons = 50 MPG"""
        assert calculate_mpg(100.0, 2.0) == 50.0

    def test_calculate_mpg_none_values(self):
        assert calculate_mpg(None, 2.5) is None
        assert calculate_mpg(100.0, None) is None

    def test_calculate_mpg_zero_distance(self):
        assert calculate_mpg(0.0, 2.5) is None

    def test_calculate_mpg_zero_gallons(self):
        assert calculate_mpg(100.0, 0.0) is None

    def test_calculate_mpg_outlier_too_high(self):
        """Unrealistically high MPG (>60) should return None"""
        assert calculate_mpg(100.0, 1.0, validate=True) is None

    def test_calculate_mpg_outlier_too_low(self):
        """Unrealistically low MPG (<15) should return None"""
        assert calculate_mpg(100.0, 10.0, validate=True) is None

    def test_calculate_mpg_no_validation(self):
        """Should allow outliers if validation disabled"""
        result = calculate_mpg(100.0, 1.0, validate=False)
        assert result == 100.0


class TestEfficiencyImpact:
    """Test efficiency impact calculations"""

    def test_efficiency_impact_same_as_baseline(self):
        """Same as baseline = 0% impact"""
        assert calculate_efficiency_impact_percent(0.32, 0.32) == 0.0

    def test_efficiency_impact_worse_than_baseline(self):
        """0.40 vs 0.32 baseline = 25% worse"""
        assert calculate_efficiency_impact_percent(0.40, 0.32) == 25.0

    def test_efficiency_impact_better_than_baseline(self):
        """0.25 vs 0.32 baseline = -21.9% (improvement)"""
        assert calculate_efficiency_impact_percent(0.25, 0.32) == -21.9

    def test_efficiency_impact_none_value(self):
        assert calculate_efficiency_impact_percent(None, 0.32) == 0.0

    def test_efficiency_impact_zero_baseline(self):
        """Should handle zero baseline"""
        assert calculate_efficiency_impact_percent(0.30, 0.0) == 0.0


class TestRangeCalculation:
    """Test range estimation"""

    def test_calculate_range_typical(self):
        """0.30 kWh/mi with 15 kWh = 50 miles"""
        assert calculate_range_from_efficiency(0.30, 15.0) == 50.0

    def test_calculate_range_efficient(self):
        """0.25 kWh/mi with 15 kWh = 60 miles"""
        assert calculate_range_from_efficiency(0.25, 15.0) == 60.0

    def test_calculate_range_zero_efficiency(self):
        """Zero efficiency should return 0"""
        assert calculate_range_from_efficiency(0.0, 15.0) == 0.0

    def test_calculate_range_negative_efficiency(self):
        """Negative efficiency should return 0"""
        assert calculate_range_from_efficiency(-0.3, 15.0) == 0.0


class TestMilesPerKWh:
    """Test miles/kWh (inverse efficiency)"""

    def test_calculate_miles_per_kwh_typical(self):
        """0.25 kWh/mi = 4.0 mi/kWh"""
        assert calculate_miles_per_kwh(0.25) == 4.0

    def test_calculate_miles_per_kwh_less_efficient(self):
        """0.33 kWh/mi = 3.03 mi/kWh"""
        assert calculate_miles_per_kwh(0.33) == 3.03

    def test_calculate_miles_per_kwh_none(self):
        assert calculate_miles_per_kwh(None) is None

    def test_calculate_miles_per_kwh_zero(self):
        assert calculate_miles_per_kwh(0.0) is None


class TestMPGe:
    """Test MPGe calculations"""

    def test_calculate_mpge_volt_typical(self):
        """Volt at 0.337 kWh/mi = 100 MPGe"""
        assert calculate_mpge(0.337) == 100.0

    def test_calculate_mpge_efficient(self):
        """0.25 kWh/mi = 134.8 MPGe"""
        assert calculate_mpge(0.25) == 134.8

    def test_calculate_mpge_none(self):
        assert calculate_mpge(None) is None

    def test_calculate_mpge_zero(self):
        assert calculate_mpge(0.0) is None

    def test_calculate_mpge_custom_conversion(self):
        """Test with custom kWh per gallon equivalent"""
        assert calculate_mpge(0.5, kwh_per_gallon_equivalent=50.0) == 100.0


class TestEfficiencyValidation:
    """Test efficiency range validation"""

    def test_is_efficiency_within_range_typical(self):
        assert is_efficiency_within_range(0.30) is True

    def test_is_efficiency_within_range_boundary_low(self):
        """At minimum boundary (0.15)"""
        assert is_efficiency_within_range(0.15) is True

    def test_is_efficiency_within_range_boundary_high(self):
        """At maximum boundary (0.60)"""
        assert is_efficiency_within_range(0.60) is True

    def test_is_efficiency_within_range_too_low(self):
        assert is_efficiency_within_range(0.05) is False

    def test_is_efficiency_within_range_too_high(self):
        assert is_efficiency_within_range(1.5) is False

    def test_is_efficiency_within_range_none(self):
        assert is_efficiency_within_range(None) is False


class TestCombinedEfficiency:
    """Test combined electric + gas efficiency"""

    def test_calculate_combined_efficiency_mixed(self):
        """20 mi electric @ 0.25 kWh/mi + 20 mi gas @ 40 MPG"""
        # Electric: 20 mi * 0.25 = 5 kWh
        # Gas: 20 mi / 40 = 0.5 gal = 0.5 * 33.7 = 16.85 kWh-equiv
        # Total: 40 mi, 21.85 kWh-equiv = 0.546 kWh-equiv/mi
        result = calculate_combined_efficiency(20, 5.0, 20, 0.5, 33.7)
        assert result == pytest.approx(0.546, abs=0.001)

    def test_calculate_combined_efficiency_electric_only(self):
        """All electric should match regular efficiency"""
        result = calculate_combined_efficiency(40, 10.0, 0, 0, 33.7)
        assert result == 0.25

    def test_calculate_combined_efficiency_gas_only(self):
        """All gas should convert to kWh-equivalent"""
        # 40 mi on 1 gal = 40 MPG
        # 1 gal = 33.7 kWh-equiv
        # 33.7 kWh-equiv / 40 mi = 0.8425 kWh-equiv/mi
        result = calculate_combined_efficiency(0, 0, 40, 1.0, 33.7)
        assert result == pytest.approx(0.843, abs=0.001)

    def test_calculate_combined_efficiency_zero_miles(self):
        """Zero total miles should return None"""
        assert calculate_combined_efficiency(0, 0, 0, 0) is None
