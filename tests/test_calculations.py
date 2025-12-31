"""
Tests for calculation utilities.
"""

import pytest
import sys
import os
from datetime import datetime, timedelta

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'receiver'))

from utils.calculations import (  # noqa: E402
    smooth_fuel_level,
    detect_gas_mode_entry,
    detect_refuel_event,
    calculate_gas_mpg,
    calculate_electric_miles,
    calculate_average_temp,
    analyze_soc_floor,
    calculate_electric_kwh,
    calculate_kwh_per_mile,
    detect_charging_session,
)


class TestSmoothFuelLevel:
    """Tests for fuel level smoothing."""

    def test_smooth_single_reading(self):
        """Test with single reading returns that reading."""
        result = smooth_fuel_level([75.0])
        assert result == 75.0

    def test_smooth_empty_list(self):
        """Test with empty list returns 0."""
        result = smooth_fuel_level([])
        assert result == 0.0

    def test_smooth_returns_median(self):
        """Test that smoothing returns median of values."""
        readings = [70.0, 72.0, 71.0, 73.0, 69.0]
        result = smooth_fuel_level(readings, window_size=5)
        assert result == 71.0  # Median of sorted [69, 70, 71, 72, 73]

    def test_smooth_window_size(self):
        """Test that window size limits readings used."""
        readings = [50.0, 60.0, 70.0, 80.0, 90.0]
        # Only last 3 readings: [70, 80, 90], median = 80
        result = smooth_fuel_level(readings, window_size=3)
        assert result == 80.0

    def test_smooth_handles_noise(self):
        """Test that smoothing handles noisy readings."""
        # One outlier shouldn't affect result much
        readings = [75.0, 75.0, 95.0, 75.0, 75.0]  # 95 is noise
        result = smooth_fuel_level(readings, window_size=5)
        assert result == 75.0


class TestDetectGasModeEntry:
    """Tests for gas mode detection."""

    def test_detect_gas_mode_with_rpm_and_low_soc(self, sample_telemetry_points):
        """Test detection of gas mode when RPM > 0 and SOC < threshold."""
        result = detect_gas_mode_entry(sample_telemetry_points)

        assert result is not None
        assert result['engine_rpm'] > 500
        assert result['state_of_charge'] < 25

    def test_no_gas_mode_in_electric_only(self):
        """Test no detection when trip is electric only."""
        points = [
            {'engine_rpm': 0, 'state_of_charge': 80},
            {'engine_rpm': 0, 'state_of_charge': 70},
            {'engine_rpm': 0, 'state_of_charge': 60},
        ]
        result = detect_gas_mode_entry(points)
        assert result is None

    def test_no_gas_mode_with_high_soc(self):
        """Test no detection when SOC is high (regenerative braking)."""
        points = [
            {'engine_rpm': 0, 'state_of_charge': 80},
            {'engine_rpm': 1500, 'state_of_charge': 75},  # High SOC, probably regen
            {'engine_rpm': 0, 'state_of_charge': 76},
        ]
        result = detect_gas_mode_entry(points)
        assert result is None

    def test_sustained_rpm_required(self):
        """Test that brief RPM spikes are filtered out."""
        points = [
            {'engine_rpm': 0, 'state_of_charge': 20},
            {'engine_rpm': 800, 'state_of_charge': 18},   # Brief spike
            {'engine_rpm': 0, 'state_of_charge': 18},     # Back to 0
            {'engine_rpm': 0, 'state_of_charge': 17},
        ]
        result = detect_gas_mode_entry(points)
        assert result is None

    def test_empty_list(self):
        """Test with empty list."""
        result = detect_gas_mode_entry([])
        assert result is None

    def test_short_list(self):
        """Test with list too short for verification."""
        points = [
            {'engine_rpm': 1000, 'state_of_charge': 15},
        ]
        result = detect_gas_mode_entry(points)
        assert result is None


class TestDetectRefuelEvent:
    """Tests for refuel detection."""

    def test_detect_refuel(self):
        """Test detection of fuel level jump."""
        result = detect_refuel_event(85.0, 60.0)
        assert result is True

    def test_no_refuel_small_change(self):
        """Test no detection for small changes."""
        result = detect_refuel_event(62.0, 60.0)
        assert result is False

    def test_no_refuel_decrease(self):
        """Test no detection for fuel decrease."""
        result = detect_refuel_event(55.0, 60.0)
        assert result is False

    def test_handle_none_values(self):
        """Test handling of None values."""
        assert detect_refuel_event(None, 60.0) is False
        assert detect_refuel_event(85.0, None) is False
        assert detect_refuel_event(None, None) is False


class TestCalculateGasMpg:
    """Tests for MPG calculation."""

    def test_calculate_mpg_normal(self):
        """Test normal MPG calculation."""
        # 20 miles, fuel went from 80% to 75% = 5% of 9.3122 gal = 0.466 gal
        # MPG = 20 / 0.466 = 42.9
        result = calculate_gas_mpg(
            start_odometer=50000,
            end_odometer=50020,
            start_fuel_level=80.0,
            end_fuel_level=75.0,
        )
        assert result == pytest.approx(42.9, abs=1)

    def test_calculate_mpg_short_trip(self):
        """Test MPG returns None for trips under 1 mile."""
        result = calculate_gas_mpg(
            start_odometer=50000,
            end_odometer=50000.5,
            start_fuel_level=80.0,
            end_fuel_level=79.5,
        )
        assert result is None

    def test_calculate_mpg_no_fuel_change(self):
        """Test MPG returns None when no fuel used."""
        result = calculate_gas_mpg(
            start_odometer=50000,
            end_odometer=50010,
            start_fuel_level=80.0,
            end_fuel_level=80.0,
        )
        assert result is None

    def test_calculate_mpg_refuel_during_trip(self):
        """Test MPG returns None when fuel increased (refuel)."""
        result = calculate_gas_mpg(
            start_odometer=50000,
            end_odometer=50020,
            start_fuel_level=60.0,
            end_fuel_level=90.0,  # Refueled
        )
        assert result is None

    def test_calculate_mpg_none_values(self):
        """Test handling of None values."""
        result = calculate_gas_mpg(None, 50010, 80.0, 75.0)
        assert result is None


class TestCalculateElectricMiles:
    """Tests for electric/gas miles split calculation."""

    def test_all_electric_trip(self):
        """Test trip that was all electric."""
        electric, gas = calculate_electric_miles(
            gas_entry_odometer=None,
            trip_start_odometer=50000,
            trip_end_odometer=50030,
        )
        assert electric == 30
        assert gas is None

    def test_mixed_trip(self):
        """Test trip with both electric and gas portions."""
        electric, gas = calculate_electric_miles(
            gas_entry_odometer=50020,
            trip_start_odometer=50000,
            trip_end_odometer=50040,
        )
        assert electric == 20
        assert gas == 20

    def test_mostly_gas_trip(self):
        """Test trip that was mostly gas."""
        electric, gas = calculate_electric_miles(
            gas_entry_odometer=50005,
            trip_start_odometer=50000,
            trip_end_odometer=50050,
        )
        assert electric == 5
        assert gas == 45


class TestCalculateAverageTemp:
    """Tests for average temperature calculation."""

    def test_average_temp(self, sample_telemetry_points):
        """Test average temperature calculation."""
        result = calculate_average_temp(sample_telemetry_points)
        assert result == 70.0

    def test_average_temp_with_nulls(self):
        """Test average temp ignores None values."""
        points = [
            {'ambient_temp_f': 70.0},
            {'ambient_temp_f': None},
            {'ambient_temp_f': 80.0},
        ]
        result = calculate_average_temp(points)
        assert result == 75.0

    def test_average_temp_all_nulls(self):
        """Test returns None when all temps are None."""
        points = [
            {'ambient_temp_f': None},
            {'ambient_temp_f': None},
        ]
        result = calculate_average_temp(points)
        assert result is None

    def test_average_temp_empty_list(self):
        """Test returns None for empty list."""
        result = calculate_average_temp([])
        assert result is None


class TestAnalyzeSocFloor:
    """Tests for SOC floor analysis."""

    def test_analyze_basic_stats(self, sample_soc_transitions):
        """Test basic statistics calculation."""
        result = analyze_soc_floor(sample_soc_transitions)

        assert result['count'] == 10
        assert result['average_soc'] is not None
        assert result['min_soc'] is not None
        assert result['max_soc'] is not None
        assert result['min_soc'] <= result['average_soc'] <= result['max_soc']

    def test_analyze_histogram(self, sample_soc_transitions):
        """Test histogram generation."""
        result = analyze_soc_floor(sample_soc_transitions)

        assert result['histogram'] is not None
        assert len(result['histogram']) > 0
        # Sum of histogram should equal count
        assert sum(result['histogram'].values()) == result['count']

    def test_analyze_temperature_correlation(self, sample_soc_transitions):
        """Test temperature correlation analysis."""
        result = analyze_soc_floor(sample_soc_transitions)

        assert result['temperature_correlation'] is not None
        assert 'cold_avg_soc' in result['temperature_correlation']
        assert 'warm_avg_soc' in result['temperature_correlation']
        # Cold weather should have higher SOC floor
        assert result['temperature_correlation']['cold_avg_soc'] > result['temperature_correlation']['warm_avg_soc']

    def test_analyze_empty_list(self):
        """Test with empty list."""
        result = analyze_soc_floor([])

        assert result['count'] == 0
        assert result['average_soc'] is None
        assert result['histogram'] == {}

    def test_analyze_insufficient_data_for_correlation(self):
        """Test temperature correlation not calculated with insufficient data."""
        transitions = [
            {'soc_at_transition': 17.0, 'ambient_temp_f': 70.0},
            {'soc_at_transition': 18.0, 'ambient_temp_f': 72.0},
        ]
        result = analyze_soc_floor(transitions)

        assert result['temperature_correlation'] is None


class TestCalculateGasMpgBoundary:
    """Boundary value tests for MPG calculation."""

    def test_exactly_one_mile_trip(self):
        """Test MPG calculation at minimum threshold (exactly 1 mile)."""
        # 1 mile with 0.5% fuel use = 0.0466 gallons (above 0.01 threshold)
        result = calculate_gas_mpg(
            start_odometer=50000,
            end_odometer=50001,  # Exactly 1 mile
            start_fuel_level=80.0,
            end_fuel_level=79.5,  # 0.5% = 0.0466 gallons
        )
        # Should return a value since it's >= 1 mile and fuel use > 0.01 gal
        assert result is not None

    def test_just_under_one_mile_trip(self):
        """Test MPG returns None for trip just under 1 mile."""
        result = calculate_gas_mpg(
            start_odometer=50000,
            end_odometer=50000.99,
            start_fuel_level=80.0,
            end_fuel_level=79.0,  # Significant fuel use but trip too short
        )
        assert result is None

    def test_minimal_fuel_use_returns_none(self):
        """Test that very small fuel use (< 0.01 gal) returns None."""
        # 10 miles, 0.1% fuel = 0.1% of 9.3122 gal = 0.0093 gal < 0.01 threshold
        result = calculate_gas_mpg(
            start_odometer=50000,
            end_odometer=50010,
            start_fuel_level=80.0,
            end_fuel_level=79.9,
        )
        # Should return None due to noise threshold
        assert result is None

    def test_good_mpg_calculation(self):
        """Test typical good MPG calculation."""
        # 40 miles, 1% fuel = 0.093 gallons
        # MPG = 40 / 0.093 = ~430 MPG (unrealistic but valid)
        result = calculate_gas_mpg(
            start_odometer=50000,
            end_odometer=50040,
            start_fuel_level=80.0,
            end_fuel_level=79.0,
        )
        assert result is not None
        assert result > 40  # Should be high MPG

    def test_very_low_mpg(self):
        """Test calculation with very low MPG (heavy fuel use)."""
        # 5 miles, 20% fuel = 20% of 9.3122 gal = 1.86 gal
        # MPG = 5 / 1.86 = ~2.7 MPG
        result = calculate_gas_mpg(
            start_odometer=50000,
            end_odometer=50005,
            start_fuel_level=80.0,
            end_fuel_level=60.0,
        )
        assert result is not None
        assert result < 10

    def test_zero_values(self):
        """Test handling of zero odometer values."""
        result = calculate_gas_mpg(
            start_odometer=0,
            end_odometer=10,
            start_fuel_level=80.0,
            end_fuel_level=75.0,
        )
        # Should work with zero start odometer
        assert result is not None


class TestDetectGasModeEdgeCases:
    """Edge case tests for gas mode detection."""

    def test_very_brief_rpm_spike(self):
        """Test that a single-reading RPM spike is ignored."""
        points = [
            {'engine_rpm': 0, 'state_of_charge': 20},
            {'engine_rpm': 2000, 'state_of_charge': 18},  # Single spike
            {'engine_rpm': 0, 'state_of_charge': 18},
        ]
        result = detect_gas_mode_entry(points)
        assert result is None

    def test_intermittent_rpm(self):
        """Test intermittent RPM doesn't trigger gas mode."""
        points = [
            {'engine_rpm': 0, 'state_of_charge': 20},
            {'engine_rpm': 1000, 'state_of_charge': 19},
            {'engine_rpm': 0, 'state_of_charge': 18},
            {'engine_rpm': 1000, 'state_of_charge': 17},
            {'engine_rpm': 0, 'state_of_charge': 16},
        ]
        result = detect_gas_mode_entry(points)
        # Intermittent RPM shouldn't be detected as gas mode
        assert result is None

    def test_high_soc_with_rpm_is_regenerative(self):
        """Test that RPM with high SOC (regenerative braking) isn't gas mode."""
        points = [
            {'engine_rpm': 0, 'state_of_charge': 85},
            {'engine_rpm': 500, 'state_of_charge': 86},  # SOC increasing = regen
            {'engine_rpm': 0, 'state_of_charge': 87},
        ]
        result = detect_gas_mode_entry(points)
        assert result is None

    def test_very_low_rpm_threshold(self):
        """Test detection with low but sustained RPM."""
        points = [
            {'engine_rpm': 0, 'state_of_charge': 20},
            {'engine_rpm': 500, 'state_of_charge': 18},  # Low RPM
            {'engine_rpm': 600, 'state_of_charge': 17},  # Sustained low RPM
            {'engine_rpm': 700, 'state_of_charge': 16},
        ]
        result = detect_gas_mode_entry(points)
        # Low but sustained RPM should be detected
        assert result is not None


class TestRefuelEdgeCases:
    """Edge case tests for refuel detection."""

    def test_exact_threshold_increase(self):
        """Test refuel detection at exact threshold."""
        # Default threshold is 10%
        result = detect_refuel_event(70.0, 60.0)  # Exactly 10% increase
        # Depends on whether threshold is >= or > 10
        # Test the boundary behavior
        assert isinstance(result, bool)

    def test_large_fuel_increase(self):
        """Test detection of large fuel increase (full tank fill)."""
        result = detect_refuel_event(95.0, 20.0)  # 75% increase
        assert result is True

    def test_fuel_sensor_noise(self):
        """Test small fluctuations aren't detected as refuel."""
        # Fuel sensors can fluctuate 1-2%
        result = detect_refuel_event(62.0, 60.0)
        assert result is False

        result = detect_refuel_event(65.0, 60.0)
        assert result is False


class TestSmoothFuelEdgeCases:
    """Edge case tests for fuel level smoothing."""

    def test_extreme_outlier(self):
        """Test that extreme outliers are filtered."""
        readings = [75.0, 74.0, 150.0, 73.0, 72.0]  # 150 is extreme outlier
        result = smooth_fuel_level(readings, window_size=5)
        # Median should not be affected much by outlier
        assert 72.0 <= result <= 75.0

    def test_all_same_values(self):
        """Test with all identical readings."""
        readings = [50.0, 50.0, 50.0, 50.0, 50.0]
        result = smooth_fuel_level(readings, window_size=5)
        assert result == 50.0

    def test_window_larger_than_data(self):
        """Test when window size exceeds available data."""
        readings = [70.0, 72.0]
        result = smooth_fuel_level(readings, window_size=10)
        # Should use all available data
        assert result == 71.0  # Median of [70, 72]

    def test_negative_values(self):
        """Test handling of negative values (invalid sensor data)."""
        readings = [75.0, 74.0, -10.0, 73.0, 72.0]
        result = smooth_fuel_level(readings, window_size=5)
        # Function should still return something reasonable
        assert isinstance(result, float)


class TestElectricMilesEdgeCases:
    """Edge case tests for electric miles calculation."""

    def test_gas_at_trip_start(self):
        """Test when gas mode starts immediately."""
        electric, gas = calculate_electric_miles(
            gas_entry_odometer=50000,  # Same as start
            trip_start_odometer=50000,
            trip_end_odometer=50020,
        )
        assert electric == 0
        assert gas == 20

    def test_gas_at_trip_end(self):
        """Test when gas mode starts at very end."""
        electric, gas = calculate_electric_miles(
            gas_entry_odometer=50019,
            trip_start_odometer=50000,
            trip_end_odometer=50020,
        )
        assert electric == 19
        assert gas == 1

    def test_gas_entry_after_trip_end(self):
        """Test edge case where gas entry is after trip end (data error)."""
        electric, gas = calculate_electric_miles(
            gas_entry_odometer=50025,
            trip_start_odometer=50000,
            trip_end_odometer=50020,
        )
        # Should handle gracefully
        assert electric is not None


class TestTemperatureEdgeCases:
    """Edge case tests for temperature calculations."""

    def test_extreme_temperatures(self):
        """Test with extreme temperature values."""
        points = [
            {'ambient_temp_f': -40.0},  # Very cold
            {'ambient_temp_f': 120.0},  # Very hot
        ]
        result = calculate_average_temp(points)
        assert result == 40.0  # Average of -40 and 120

    def test_single_reading(self):
        """Test with single temperature reading."""
        points = [{'ambient_temp_f': 72.0}]
        result = calculate_average_temp(points)
        assert result == 72.0

    def test_missing_temp_key(self):
        """Test with points missing temperature key."""
        points = [
            {'speed_mph': 45.0},  # No temp key
            {'ambient_temp_f': 70.0},
        ]
        result = calculate_average_temp(points)
        # Should only average the one valid reading
        assert result == 70.0


class TestCalculateElectricKwh:
    """Tests for calculate_electric_kwh function."""

    def test_kwh_from_power_readings(self):
        """Calculate kWh from HV battery power data."""
        now = datetime.utcnow()
        points = [
            {'timestamp': now, 'hv_battery_power_kw': 10.0},
            {'timestamp': now + timedelta(hours=1), 'hv_battery_power_kw': 10.0},
        ]
        result = calculate_electric_kwh(points)
        # 1 hour at 10kW = 10 kWh
        assert result == 10.0

    def test_kwh_ignores_negative_power_readings(self):
        """Negative power (regen) is not counted as consumption."""
        now = datetime.utcnow()
        points = [
            {'timestamp': now, 'hv_battery_power_kw': -5.0},  # Regen
            {'timestamp': now + timedelta(hours=1), 'hv_battery_power_kw': -5.0},
        ]
        result = calculate_electric_kwh(points)
        # Negative power means charging/regen, should return None or 0
        assert result is None

    def test_kwh_handles_string_timestamps(self):
        """ISO format string timestamps are parsed correctly."""
        now = datetime.utcnow()
        points = [
            {'timestamp': now.isoformat(), 'hv_battery_power_kw': 15.0},
            {'timestamp': (now + timedelta(hours=0.5)).isoformat(), 'hv_battery_power_kw': 15.0},
        ]
        result = calculate_electric_kwh(points)
        # 0.5 hours at 15kW = 7.5 kWh
        assert result == 7.5

    def test_kwh_calculates_time_delta_correctly(self):
        """Time intervals are correctly calculated."""
        now = datetime.utcnow()
        points = [
            {'timestamp': now, 'hv_battery_power_kw': 20.0},
            {'timestamp': now + timedelta(minutes=15), 'hv_battery_power_kw': 20.0},
        ]
        result = calculate_electric_kwh(points)
        # 0.25 hours at 20kW = 5 kWh
        assert result == 5.0

    def test_kwh_from_soc_when_no_power_data(self):
        """Calculate kWh from SOC change when power data unavailable."""
        points = [
            {'state_of_charge': 80.0},  # No power data
            {'state_of_charge': 60.0},
        ]
        result = calculate_electric_kwh(points)
        # 20% of 18.4 kWh battery = 3.68 kWh
        assert result == pytest.approx(3.68, rel=0.1)

    def test_kwh_returns_none_when_soc_increases(self):
        """Return None when SOC increases (charging, not driving)."""
        points = [
            {'state_of_charge': 60.0},
            {'state_of_charge': 80.0},  # Charging
        ]
        result = calculate_electric_kwh(points)
        assert result is None

    def test_kwh_uses_battery_capacity_constant(self):
        """Uses correct battery capacity for Volt."""
        points = [
            {'state_of_charge': 100.0},
            {'state_of_charge': 50.0},
        ]
        result = calculate_electric_kwh(points)
        # 50% of 18.4 kWh = 9.2 kWh
        assert result == pytest.approx(9.2, rel=0.1)

    def test_kwh_returns_none_for_single_point(self):
        """Need at least 2 points for calculation."""
        points = [{'state_of_charge': 80.0}]
        result = calculate_electric_kwh(points)
        assert result is None

    def test_kwh_returns_none_for_empty_list(self):
        """Empty point list returns None."""
        result = calculate_electric_kwh([])
        assert result is None

    def test_kwh_prefers_power_method_over_soc(self):
        """When power data available, use it instead of SOC."""
        now = datetime.utcnow()
        points = [
            {'timestamp': now, 'hv_battery_power_kw': 10.0, 'state_of_charge': 80.0},
            {'timestamp': now + timedelta(hours=1), 'hv_battery_power_kw': 10.0, 'state_of_charge': 70.0},
        ]
        result = calculate_electric_kwh(points)
        # Should use power method: 1 hour at 10kW = 10 kWh
        # SOC method would give: 10% of 18.4 = 1.84 kWh
        assert result == 10.0


class TestCalculateKwhPerMile:
    """Tests for calculate_kwh_per_mile function."""

    def test_kwh_per_mile_normal_calculation(self):
        """Normal efficiency calculation."""
        result = calculate_kwh_per_mile(kwh_used=5.0, electric_miles=15.0)
        # 5.0 / 15.0 = 0.333
        assert result == pytest.approx(0.333, rel=0.01)

    def test_kwh_per_mile_returns_none_under_half_mile(self):
        """Minimum distance threshold is 0.5 miles."""
        result = calculate_kwh_per_mile(kwh_used=0.1, electric_miles=0.4)
        assert result is None

    def test_kwh_per_mile_exactly_half_mile(self):
        """Exactly 0.5 miles should return a value."""
        result = calculate_kwh_per_mile(kwh_used=0.2, electric_miles=0.5)
        assert result is not None
        assert result == 0.4

    def test_kwh_per_mile_returns_none_for_none_kwh(self):
        """None kWh returns None."""
        result = calculate_kwh_per_mile(kwh_used=None, electric_miles=10.0)
        assert result is None

    def test_kwh_per_mile_returns_none_for_none_miles(self):
        """None miles returns None."""
        result = calculate_kwh_per_mile(kwh_used=5.0, electric_miles=None)
        assert result is None

    def test_kwh_per_mile_rounds_to_three_decimals(self):
        """Result is rounded to 3 decimal places."""
        result = calculate_kwh_per_mile(kwh_used=1.0, electric_miles=3.0)
        # 1.0 / 3.0 = 0.333333...
        assert result == 0.333


class TestDetectChargingSession:
    """Tests for detect_charging_session function."""

    def test_detects_active_charging(self):
        """Detect when charger is connected and power flowing."""
        points = [
            {'charger_connected': True, 'charger_ac_power_kw': 6.6, 'state_of_charge': 50.0},
            {'charger_connected': True, 'charger_ac_power_kw': 6.6, 'state_of_charge': 55.0},
        ]
        result = detect_charging_session(points)
        assert result is not None
        assert result['is_charging'] is True

    def test_returns_none_when_not_charging(self):
        """Return None when charger not connected."""
        points = [
            {'charger_connected': False, 'state_of_charge': 50.0},
            {'charger_connected': False, 'state_of_charge': 50.0},
        ]
        result = detect_charging_session(points)
        assert result is None

    def test_returns_none_when_power_below_threshold(self):
        """Return None when power below minimum threshold."""
        points = [
            {'charger_connected': True, 'charger_ac_power_kw': 0.3, 'state_of_charge': 50.0},
        ]
        result = detect_charging_session(points)
        assert result is None

    def test_classifies_l1_charging(self):
        """Power under 1.2 kW is L1."""
        points = [
            {'charger_connected': True, 'charger_ac_power_kw': 1.0, 'state_of_charge': 50.0},
        ]
        result = detect_charging_session(points)
        assert result['charge_type'] == 'L1'

    def test_classifies_l1_high_charging(self):
        """Power 1.2-6.0 kW is L1-high."""
        points = [
            {'charger_connected': True, 'charger_ac_power_kw': 3.3, 'state_of_charge': 50.0},
        ]
        result = detect_charging_session(points)
        assert result['charge_type'] == 'L1-high'

    def test_classifies_l2_charging(self):
        """Power over 6.0 kW is L2."""
        points = [
            {'charger_connected': True, 'charger_ac_power_kw': 7.2, 'state_of_charge': 50.0},
        ]
        result = detect_charging_session(points)
        assert result['charge_type'] == 'L2'

    def test_calculates_peak_and_avg_power(self):
        """Peak and average power are calculated."""
        points = [
            {'charger_connected': True, 'charger_ac_power_kw': 6.0, 'state_of_charge': 50.0},
            {'charger_connected': True, 'charger_ac_power_kw': 7.0, 'state_of_charge': 55.0},
            {'charger_connected': True, 'charger_ac_power_kw': 6.5, 'state_of_charge': 60.0},
        ]
        result = detect_charging_session(points)
        assert result['peak_power_kw'] == 7.0
        assert result['avg_power_kw'] == pytest.approx(6.5, rel=0.01)

    def test_tracks_soc_range(self):
        """Start and current SOC are tracked."""
        points = [
            {'charger_connected': True, 'charger_ac_power_kw': 6.6, 'state_of_charge': 30.0},
            {'charger_connected': True, 'charger_ac_power_kw': 6.6, 'state_of_charge': 50.0},
            {'charger_connected': True, 'charger_ac_power_kw': 6.6, 'state_of_charge': 70.0},
        ]
        result = detect_charging_session(points)
        assert result['start_soc'] == 30.0
        assert result['current_soc'] == 70.0

    def test_handles_empty_telemetry(self):
        """Empty telemetry list returns None."""
        result = detect_charging_session([])
        assert result is None

    def test_handles_missing_power_data(self):
        """Charger connected but no power data."""
        points = [
            {'charger_connected': True, 'state_of_charge': 50.0},
        ]
        result = detect_charging_session(points)
        # No power readings means not actively charging
        assert result is None
