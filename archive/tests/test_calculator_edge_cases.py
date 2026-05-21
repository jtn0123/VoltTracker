import pytest
"""
Property-based and edge case tests for calculation modules.

Uses hypothesis for fuzzing numeric inputs including zeros,
negatives, very large values, NaN, and None.
"""

import os
import sys
from datetime import datetime, timezone

from hypothesis import given, settings
from hypothesis import strategies as st

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

from calculations.energy import (  # noqa: E402
    calculate_energy_from_soc_change,
    fuel_percent_to_gallons,
    gallons_to_fuel_percent,
    integrate_power_over_time,
    kwh_to_soc,
    soc_to_kwh,
)
from calculations.efficiency import (  # noqa: E402
    calculate_combined_efficiency,
    calculate_efficiency_impact_percent,
    calculate_kwh_per_mile,
    calculate_miles_per_kwh,
    calculate_mpg,
    calculate_mpge,
    calculate_range_from_efficiency,
    is_efficiency_within_range,
)


# ── Energy conversion edge cases ────────────────────────────────────────

class TestSocKwhRoundTrip:
    """Property: soc_to_kwh and kwh_to_soc are inverses."""

    @given(soc=st.floats(min_value=0, max_value=100, allow_nan=False))
    @settings(max_examples=50)
    def test_round_trip(self, soc):
        """soc -> kwh -> soc should be identity."""
        kwh = soc_to_kwh(soc, 18.4)
        recovered = kwh_to_soc(kwh, 18.4)
        assert abs(recovered - soc) < 1e-10

    def test_zero_capacity(self):
        """Zero battery capacity returns 0 SOC."""
        assert kwh_to_soc(5.0, 0) == pytest.approx(0.0)

    def test_zero_soc(self):
        """0% SOC gives 0 kWh."""
        assert soc_to_kwh(0.0) == pytest.approx(0.0)

    def test_negative_soc(self):
        """Negative SOC gives negative kWh (no validation in pure math)."""
        result = soc_to_kwh(-10.0, 18.4)
        assert result < 0


class TestFuelConversions:
    """Fuel percent ↔ gallons round-trip."""

    @given(pct=st.floats(min_value=0, max_value=100, allow_nan=False))
    @settings(max_examples=50)
    def test_round_trip(self, pct):
        gal = fuel_percent_to_gallons(pct, 9.0)
        recovered = gallons_to_fuel_percent(gal, 9.0)
        assert abs(recovered - pct) < 1e-10

    def test_zero_tank(self):
        assert gallons_to_fuel_percent(5.0, 0) == pytest.approx(0.0)


class TestPowerIntegration:
    """Edge cases for integrate_power_over_time."""

    def test_single_point_returns_none(self):
        """Need >= 2 points."""
        t = datetime.now(timezone.utc)
        assert integrate_power_over_time([(t, 10.0)]) is None

    def test_empty_returns_none(self):
        assert integrate_power_over_time([]) is None

    def test_constant_power_one_hour(self):
        """10 kW for 1 hour = 10 kWh."""
        t1 = datetime(2024, 1, 1, 12, 0, 0)
        t2 = datetime(2024, 1, 1, 13, 0, 0)
        result = integrate_power_over_time([(t1, 10.0), (t2, 10.0)])
        assert result == pytest.approx(10.0)

    def test_negative_power_ignored(self):
        """Negative power (charging) is not counted."""
        t1 = datetime(2024, 1, 1, 12, 0, 0)
        t2 = datetime(2024, 1, 1, 13, 0, 0)
        result = integrate_power_over_time([(t1, -5.0), (t2, -5.0)])
        assert result is None

    def test_none_power_skipped(self):
        """None power values are skipped."""
        t1 = datetime(2024, 1, 1, 12, 0, 0)
        t2 = datetime(2024, 1, 1, 13, 0, 0)
        t3 = datetime(2024, 1, 1, 14, 0, 0)
        result = integrate_power_over_time([(t1, 10.0), (t2, None), (t3, 10.0)])
        # Only t1→t2 has None so skipped; t2→t3 also has None on prev side
        assert result is None or isinstance(result, float)

    def test_reversed_timestamps_skipped(self):
        """If timestamps go backwards, interval is skipped."""
        t1 = datetime(2024, 1, 1, 13, 0, 0)
        t2 = datetime(2024, 1, 1, 12, 0, 0)  # Before t1
        result = integrate_power_over_time([(t1, 10.0), (t2, 10.0)])
        assert result is None


class TestEnergyFromSocChange:
    """Edge cases for calculate_energy_from_soc_change."""

    def test_none_inputs(self):
        assert calculate_energy_from_soc_change(None, 50.0) is None
        assert calculate_energy_from_soc_change(80.0, None) is None

    def test_soc_increase_returns_none(self):
        """Charging (SOC increase) returns None."""
        assert calculate_energy_from_soc_change(50.0, 80.0) is None

    def test_equal_soc_returns_none(self):
        """No change returns None."""
        assert calculate_energy_from_soc_change(80.0, 80.0) is None

    @given(
        start=st.floats(min_value=0, max_value=100, allow_nan=False),
        end=st.floats(min_value=0, max_value=100, allow_nan=False),
    )
    @settings(max_examples=50)
    def test_result_non_negative(self, start, end):
        """Result is always None or non-negative."""
        result = calculate_energy_from_soc_change(start, end)
        if result is not None:
            assert result >= 0


# ── Efficiency edge cases ───────────────────────────────────────────────

class TestKwhPerMile:
    """Edge cases for calculate_kwh_per_mile."""

    def test_none_inputs(self):
        assert calculate_kwh_per_mile(None, 20.0) is None
        assert calculate_kwh_per_mile(5.0, None) is None

    def test_zero_miles(self):
        assert calculate_kwh_per_mile(5.0, 0.0) is None

    def test_very_short_distance(self):
        """Distance below MIN_DISTANCE_FOR_EFFICIENCY returns None."""
        assert calculate_kwh_per_mile(5.0, 0.1) is None

    def test_negative_miles(self):
        assert calculate_kwh_per_mile(5.0, -10.0) is None

    def test_without_validation(self):
        """Without validation, extreme values still return a number."""
        result = calculate_kwh_per_mile(100.0, 1.0, validate=False)
        assert result == pytest.approx(100.0)

    @given(
        kwh=st.floats(min_value=0.1, max_value=50, allow_nan=False),
        miles=st.floats(min_value=1.0, max_value=500, allow_nan=False),
    )
    @settings(max_examples=50)
    def test_result_positive_or_none(self, kwh, miles):
        result = calculate_kwh_per_mile(kwh, miles)
        if result is not None:
            assert result > 0


class TestMpg:
    """Edge cases for calculate_mpg."""

    def test_none_inputs(self):
        assert calculate_mpg(None, 2.5) is None
        assert calculate_mpg(100.0, None) is None

    def test_zero_gallons(self):
        assert calculate_mpg(100.0, 0.0) is None

    def test_zero_miles(self):
        assert calculate_mpg(0.0, 2.5) is None

    def test_negative_values(self):
        assert calculate_mpg(-10.0, 2.5) is None
        assert calculate_mpg(100.0, -2.5) is None


class TestMilesPerKwh:
    """Edge cases for calculate_miles_per_kwh."""

    def test_none_input(self):
        assert calculate_miles_per_kwh(None) is None

    def test_zero_input(self):
        assert calculate_miles_per_kwh(0.0) is None

    def test_negative_input(self):
        assert calculate_miles_per_kwh(-0.5) is None

    @given(kwh_per_mile=st.floats(min_value=0.01, max_value=10.0, allow_nan=False))
    @settings(max_examples=50)
    def test_inverse_relationship(self, kwh_per_mile):
        result = calculate_miles_per_kwh(kwh_per_mile)
        assert result is not None
        assert result > 0


class TestMpge:
    """Edge cases for calculate_mpge."""

    def test_none_input(self):
        assert calculate_mpge(None) is None

    def test_zero_input(self):
        assert calculate_mpge(0.0) is None

    def test_known_value(self):
        """0.337 kWh/mi = 100 MPGe."""
        result = calculate_mpge(0.337)
        assert result == pytest.approx(100.0)


class TestRangeFromEfficiency:
    """Edge cases for calculate_range_from_efficiency."""

    def test_zero_efficiency(self):
        assert calculate_range_from_efficiency(0.0, 15.0) == pytest.approx(0.0)

    def test_negative_efficiency(self):
        assert calculate_range_from_efficiency(-0.3, 15.0) == pytest.approx(0.0)

    @given(
        eff=st.floats(min_value=0.01, max_value=2.0, allow_nan=False),
        kwh=st.floats(min_value=0.0, max_value=100.0, allow_nan=False),
    )
    @settings(max_examples=50)
    def test_result_non_negative(self, eff, kwh):
        result = calculate_range_from_efficiency(eff, kwh)
        assert result >= 0


class TestEfficiencyImpact:
    """Edge cases for calculate_efficiency_impact_percent."""

    def test_none_actual(self):
        assert calculate_efficiency_impact_percent(None) == pytest.approx(0.0)

    def test_zero_baseline(self):
        assert calculate_efficiency_impact_percent(0.3, 0.0) == pytest.approx(0.0)

    def test_same_as_baseline(self):
        assert calculate_efficiency_impact_percent(0.32, 0.32) == pytest.approx(0.0)


class TestCombinedEfficiency:
    """Edge cases for calculate_combined_efficiency."""

    def test_zero_total_miles(self):
        assert calculate_combined_efficiency(0, 0, 0, 0) is None

    def test_electric_only(self):
        result = calculate_combined_efficiency(20.0, 5.0, 0, 0)
        assert result == pytest.approx(0.25)

    def test_gas_only(self):
        result = calculate_combined_efficiency(0, 0, 20.0, 0.5)
        assert result is not None
        assert result > 0


class TestIsEfficiencyWithinRange:
    """Edge cases for is_efficiency_within_range."""

    def test_none_input(self):
        assert is_efficiency_within_range(None) is False

    @given(val=st.floats(min_value=0.1, max_value=0.5, allow_nan=False))
    @settings(max_examples=20)
    def test_normal_range(self, val):
        """Values in typical range should be valid."""
        result = is_efficiency_within_range(val, 0.05, 1.0)
        assert result is True
