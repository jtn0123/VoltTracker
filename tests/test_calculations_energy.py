"""
Tests for energy conversion calculations
"""

import pytest
from datetime import datetime, timedelta
from receiver.calculations.energy import (
    soc_to_kwh,
    kwh_to_soc,
    fuel_percent_to_gallons,
    gallons_to_fuel_percent,
    integrate_power_over_time,
    calculate_energy_from_soc_change,
)


class TestSOCConversions:
    """Test State of Charge conversions"""

    def test_soc_to_kwh_half_charge(self):
        assert soc_to_kwh(50.0, 18.4) == 9.2

    def test_soc_to_kwh_full_charge(self):
        assert soc_to_kwh(100.0, 18.4) == 18.4

    def test_soc_to_kwh_empty(self):
        assert soc_to_kwh(0.0, 18.4) == 0.0

    def test_kwh_to_soc_half_charge(self):
        assert kwh_to_soc(9.2, 18.4) == 50.0

    def test_kwh_to_soc_full_charge(self):
        assert kwh_to_soc(18.4, 18.4) == 100.0

    def test_kwh_to_soc_zero_capacity(self):
        """Should handle zero capacity without crashing"""
        assert kwh_to_soc(10.0, 0.0) == 0.0

    def test_soc_kwh_roundtrip(self):
        """Converting SOC -> kWh -> SOC should preserve value"""
        original_soc = 75.0
        kwh = soc_to_kwh(original_soc, 18.4)
        result_soc = kwh_to_soc(kwh, 18.4)
        assert result_soc == original_soc


class TestFuelConversions:
    """Test fuel level conversions"""

    def test_fuel_percent_to_gallons_half_tank(self):
        assert fuel_percent_to_gallons(50.0, 9.0) == 4.5

    def test_fuel_percent_to_gallons_full_tank(self):
        assert fuel_percent_to_gallons(100.0, 9.0) == 9.0

    def test_fuel_percent_to_gallons_empty(self):
        assert fuel_percent_to_gallons(0.0, 9.0) == 0.0

    def test_gallons_to_fuel_percent_half_tank(self):
        assert gallons_to_fuel_percent(4.5, 9.0) == 50.0

    def test_gallons_to_fuel_percent_full_tank(self):
        assert gallons_to_fuel_percent(9.0, 9.0) == 100.0

    def test_gallons_to_fuel_percent_zero_capacity(self):
        """Should handle zero capacity without crashing"""
        assert gallons_to_fuel_percent(5.0, 0.0) == 0.0

    def test_fuel_roundtrip(self):
        """Converting percent -> gallons -> percent should preserve value"""
        original_percent = 65.0
        gallons = fuel_percent_to_gallons(original_percent, 9.0)
        result_percent = gallons_to_fuel_percent(gallons, 9.0)
        assert result_percent == original_percent


class TestPowerIntegration:
    """Test power integration over time"""

    def test_constant_power_one_hour(self):
        """10 kW for 1 hour = 10 kWh"""
        t1 = datetime(2024, 1, 1, 12, 0, 0)
        t2 = datetime(2024, 1, 1, 13, 0, 0)
        readings = [(t1, 10.0), (t2, 10.0)]
        result = integrate_power_over_time(readings)
        assert result == 10.0

    def test_varying_power(self):
        """Varying power should use trapezoidal integration"""
        t1 = datetime(2024, 1, 1, 12, 0, 0)
        t2 = datetime(2024, 1, 1, 12, 30, 0)  # 30 min later
        t3 = datetime(2024, 1, 1, 13, 0, 0)  # 1 hour from start
        # 10 kW for 30 min, then 20 kW for 30 min
        readings = [(t1, 10.0), (t2, 15.0), (t3, 20.0)]
        # (10+15)/2 * 0.5 + (15+20)/2 * 0.5 = 6.25 + 8.75 = 15.0
        result = integrate_power_over_time(readings)
        assert result == 15.0

    def test_insufficient_readings(self):
        """Should return None with < 2 readings"""
        t1 = datetime(2024, 1, 1, 12, 0, 0)
        readings = [(t1, 10.0)]
        assert integrate_power_over_time(readings) is None

    def test_zero_time_delta(self):
        """Should skip intervals with zero time delta"""
        t1 = datetime(2024, 1, 1, 12, 0, 0)
        readings = [(t1, 10.0), (t1, 15.0)]  # Same timestamp
        assert integrate_power_over_time(readings) is None

    def test_negative_power_ignored(self):
        """Negative power (charging) should be ignored"""
        t1 = datetime(2024, 1, 1, 12, 0, 0)
        t2 = datetime(2024, 1, 1, 13, 0, 0)
        readings = [(t1, -5.0), (t2, -5.0)]  # Charging (negative)
        assert integrate_power_over_time(readings) is None

    def test_mixed_power_signs(self):
        """Only positive power should be counted"""
        t1 = datetime(2024, 1, 1, 12, 0, 0)
        t2 = datetime(2024, 1, 1, 12, 30, 0)
        t3 = datetime(2024, 1, 1, 13, 0, 0)
        readings = [(t1, 10.0), (t2, -5.0), (t3, 10.0)]
        # First interval: (10 + (-5))/2 * 0.5 = 1.25 (positive, counted)
        # Second interval: (-5 + 10)/2 * 0.5 = 1.25 (positive, counted)
        result = integrate_power_over_time(readings)
        assert result == 2.5

    def test_iso_string_timestamps(self):
        """Should handle ISO string timestamps"""
        readings = [
            ("2024-01-01T12:00:00Z", 10.0),
            ("2024-01-01T13:00:00Z", 10.0),
        ]
        result = integrate_power_over_time(readings)
        assert result == 10.0


class TestSOCChangeEnergy:
    """Test energy calculation from SOC change"""

    def test_soc_decrease_normal(self):
        """80% -> 50% = 30% * 18.4 = 5.52 kWh"""
        result = calculate_energy_from_soc_change(80.0, 50.0, 18.4)
        assert result == 5.52

    def test_soc_increase_returns_none(self):
        """SOC increase (charging) should return None"""
        result = calculate_energy_from_soc_change(50.0, 80.0, 18.4)
        assert result is None

    def test_soc_no_change_returns_none(self):
        """No SOC change should return None"""
        result = calculate_energy_from_soc_change(50.0, 50.0, 18.4)
        assert result is None

    def test_soc_none_values(self):
        """None values should return None"""
        assert calculate_energy_from_soc_change(None, 50.0, 18.4) is None
        assert calculate_energy_from_soc_change(50.0, None, 18.4) is None

    def test_full_discharge(self):
        """100% -> 0% = 100% of capacity"""
        result = calculate_energy_from_soc_change(100.0, 0.0, 18.4)
        assert result == 18.4
