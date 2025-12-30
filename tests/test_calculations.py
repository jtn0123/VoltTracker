"""
Tests for calculation utilities.
"""

import pytest
import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'receiver'))

from utils.calculations import (
    smooth_fuel_level,
    detect_gas_mode_entry,
    detect_refuel_event,
    calculate_gas_mpg,
    calculate_electric_miles,
    calculate_average_temp,
    analyze_soc_floor,
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
