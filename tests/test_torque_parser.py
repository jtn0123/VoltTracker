"""
Tests for Torque Pro data parser.
"""

import os
import sys
import uuid
from datetime import datetime, timezone

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

from utils.timezone import utc_now  # noqa: E402
from utils.torque_parser import TorqueParser  # noqa: E402


class TestTorqueParser:
    """Tests for TorqueParser class."""

    def test_parse_basic_fields(self, sample_torque_data):
        """Test parsing of basic Torque fields."""
        result = TorqueParser.parse(sample_torque_data)

        assert result["session_id"] is not None
        assert result["timestamp"] is not None
        assert isinstance(result["timestamp"], datetime)

    def test_parse_gps_coordinates(self, sample_torque_data):
        """Test parsing of GPS coordinates."""
        result = TorqueParser.parse(sample_torque_data)

        assert result["latitude"] == pytest.approx(37.7749, rel=1e-4)
        assert result["longitude"] == pytest.approx(-122.4194, rel=1e-4)

    def test_parse_speed(self, sample_torque_data):
        """Test parsing of speed."""
        result = TorqueParser.parse(sample_torque_data)

        assert result["speed_mph"] == pytest.approx(45.5, rel=1e-2)

    def test_parse_engine_rpm(self, sample_torque_data):
        """Test parsing of engine RPM."""
        result = TorqueParser.parse(sample_torque_data)

        assert result["engine_rpm"] == 0  # Electric mode

    def test_parse_engine_rpm_gas_mode(self, sample_torque_data):
        """Test parsing RPM when engine is running."""
        sample_torque_data["kc"] = "1500"
        result = TorqueParser.parse(sample_torque_data)

        assert result["engine_rpm"] == 1500

    def test_parse_fuel_level(self, sample_torque_data):
        """Test parsing of fuel level."""
        result = TorqueParser.parse(sample_torque_data)

        assert result["fuel_level_percent"] == pytest.approx(75.5, rel=1e-2)

    def test_parse_fuel_remaining_gallons(self, sample_torque_data):
        """Test calculation of fuel remaining in gallons."""
        result = TorqueParser.parse(sample_torque_data)

        # 75.5% of 9.3122 gallons = ~7.03 gallons
        expected = 75.5 / 100 * 9.3122
        assert result["fuel_remaining_gallons"] == pytest.approx(expected, rel=1e-2)

    def test_parse_state_of_charge(self, sample_torque_data):
        """Test parsing of battery SOC."""
        result = TorqueParser.parse(sample_torque_data)

        assert result["state_of_charge"] == pytest.approx(85.0, rel=1e-2)

    def test_parse_temperature_conversion(self, sample_torque_data):
        """Test Celsius to Fahrenheit conversion for temperatures."""
        result = TorqueParser.parse(sample_torque_data)

        # 21°C = 69.8°F
        assert result["coolant_temp_f"] == pytest.approx(69.8, abs=1)

        # 18°C = 64.4°F
        assert result["intake_air_temp_f"] == pytest.approx(64.4, abs=1)

        # 22°C = 71.6°F
        assert result["ambient_temp_f"] == pytest.approx(71.6, abs=1)

    def test_parse_odometer(self, sample_torque_data):
        """Test parsing of odometer."""
        result = TorqueParser.parse(sample_torque_data)

        assert result["odometer_miles"] == pytest.approx(50123.4, rel=1e-2)

    def test_parse_raw_data_preserved(self, sample_torque_data):
        """Test that raw data is preserved."""
        result = TorqueParser.parse(sample_torque_data)

        assert result["raw_data"] is not None
        assert "kff1006" in result["raw_data"]

    def test_parse_empty_values(self):
        """Test handling of empty/missing values."""
        data = {
            "session": str(uuid.uuid4()),
            "time": str(int(datetime.now(timezone.utc).timestamp() * 1000)),
        }
        result = TorqueParser.parse(data)

        assert result["latitude"] is None
        assert result["speed_mph"] is None
        assert result["engine_rpm"] is None

    def test_parse_invalid_values(self):
        """Test handling of invalid values."""
        data = {
            "session": str(uuid.uuid4()),
            "time": str(int(datetime.now(timezone.utc).timestamp() * 1000)),
            "kff1001": "invalid",
            "kc": "not_a_number",
        }
        result = TorqueParser.parse(data)

        assert result["speed_mph"] is None
        assert result["engine_rpm"] is None

    def test_parse_alternative_pid_formats(self):
        """Test parsing with alternative PID key formats."""
        data = {
            "session": str(uuid.uuid4()),
            "time": str(int(datetime.now(timezone.utc).timestamp() * 1000)),
            "k0c": "2000",  # Alternative RPM key
            "k05": "25.0",  # Alternative coolant temp key
        }
        result = TorqueParser.parse(data)

        assert result["engine_rpm"] == 2000
        # 25°C = 77°F
        assert result["coolant_temp_f"] == pytest.approx(77.0, abs=1)

    def test_parse_generates_session_id_if_missing(self):
        """Test that session ID is generated if not provided."""
        data = {
            "time": str(int(datetime.now(timezone.utc).timestamp() * 1000)),
        }
        result = TorqueParser.parse(data)

        assert result["session_id"] is not None
        assert isinstance(result["session_id"], uuid.UUID)

    def test_parse_generates_timestamp_if_missing(self):
        """Test that timestamp is generated if not provided."""
        data = {
            "session": str(uuid.uuid4()),
        }
        result = TorqueParser.parse(data)

        assert result["timestamp"] is not None
        # Should be close to now
        diff = utc_now() - result["timestamp"]
        assert diff.total_seconds() < 5

    def test_celsius_to_fahrenheit(self):
        """Test temperature conversion function."""
        assert TorqueParser._celsius_to_fahrenheit(0) == 32
        assert TorqueParser._celsius_to_fahrenheit(100) == 212
        assert TorqueParser._celsius_to_fahrenheit(-40) == -40
        assert TorqueParser._celsius_to_fahrenheit(20) == pytest.approx(68, abs=0.1)
