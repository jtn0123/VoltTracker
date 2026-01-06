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

    def test_parse_odometer_km_conversion(self):
        """Test odometer conversion from km to miles."""
        data = {
            "k21": "50000",  # Odometer km
            "session": "test-session",
            "time": "1609459200000",
        }
        result = TorqueParser.parse(data)
        # 50000 km * 0.621371 = 31068.55 miles
        assert result["odometer_miles"] == pytest.approx(31068.55, abs=0.1)

    def test_parse_invalid_timestamp(self):
        """Test handling of invalid timestamp."""
        data = {
            "kff1006": "37.7749",  # GPS Latitude
            "session": "test-session",
            "time": "invalid",  # Invalid timestamp
        }
        result = TorqueParser.parse(data)
        # Should use current time
        assert result["timestamp"] is not None

    def test_parse_hv_battery_fields(self):
        """Test parsing HV battery related PIDs."""
        data = {
            "k22000b": "15.5",  # HV battery power kW
            "k22000a": "45.2",  # HV battery current A
            "k220009": "350.5",  # HV battery voltage V
            "k222414": "50.0",  # HV discharge amps
            "k22434f": "25.0",  # Battery temp C
            "k220038": "22.0",  # Battery coolant temp C
            "session": "test-session",
            "time": "1609459200000",
        }
        result = TorqueParser.parse(data)
        assert result["hv_battery_power_kw"] == 15.5
        assert result["hv_battery_current_a"] == 45.2
        assert result["hv_battery_voltage_v"] == 350.5
        assert result["hv_discharge_amps"] == 50.0
        assert result["battery_temp_f"] == pytest.approx(77.0, abs=0.1)
        assert result["battery_coolant_temp_f"] == pytest.approx(71.6, abs=0.1)

    def test_parse_charger_fields(self):
        """Test parsing charger related PIDs."""
        data = {
            "k220057": "1",  # Charger status
            "k22006e": "3.3",  # Charger power kW
            "k224368": "240",  # Charger AC voltage
            "k224369": "15",  # Charger AC current
            "k22436b": "360",  # Charger HV voltage
            "k22436c": "10",  # Charger HV current
            "k22437d": "5500",  # Last charge Wh
            "session": "test-session",
            "time": "1609459200000",
        }
        result = TorqueParser.parse(data)
        assert result["charger_status"] == 1
        assert result["charger_connected"] is True
        assert result["charger_power_kw"] == 3.3
        assert result["charger_ac_power_kw"] == 3.3
        assert result["charger_ac_voltage"] == 240
        assert result["charger_ac_current"] == 15
        assert result["charger_hv_voltage"] == 360
        assert result["charger_hv_current"] == 10
        assert result["last_charge_wh"] == 5500

    def test_parse_charger_power_watts(self):
        """Test charger power in watts converts to kW."""
        data = {
            "k224373": "3300",  # Charger power W
            "session": "test-session",
            "time": "1609459200000",
        }
        result = TorqueParser.parse(data)
        assert result["charger_power_w"] == 3300
        assert result["charger_power_kw"] == 3.3
        assert result["charger_ac_power_kw"] == 3.3

    def test_parse_motor_generator_rpms(self):
        """Test parsing motor and generator RPMs."""
        data = {
            "k220051": "2500",  # Motor A RPM
            "k220052": "2300",  # Motor B RPM
            "k220053": "1800",  # Generator RPM
            "session": "test-session",
            "time": "1609459200000",
        }
        result = TorqueParser.parse(data)
        assert result["motor_a_rpm"] == 2500
        assert result["motor_b_rpm"] == 2300
        assert result["generator_rpm"] == 1800

    def test_parse_motor_temperatures(self):
        """Test parsing motor temperatures and finding max."""
        data = {
            "k221570": "55",  # Motor temp 1 C
            "k221571": "60",  # Motor temp 2 C
            "k221572": "58",  # Motor temp 3 C
            "k221573": "62",  # Motor temp 4 C
            "session": "test-session",
            "time": "1609459200000",
        }
        result = TorqueParser.parse(data)
        # Max is 62C = 143.6F
        assert result["motor_temp_max_f"] == pytest.approx(143.6, abs=0.1)

    def test_parse_engine_details(self):
        """Test parsing engine related PIDs."""
        data = {
            "k221154": "95",  # Engine oil temp C
            "k22203f": "150",  # Engine torque Nm
            "k221930": "1",  # Engine running
            "k220049": "88",  # Engine coolant temp C (Volt-specific)
            "k220047": "75",  # Transmission temp C
            "session": "test-session",
            "time": "1609459200000",
        }
        result = TorqueParser.parse(data)
        assert result["engine_oil_temp_f"] == pytest.approx(203, abs=1)
        assert result["engine_torque_nm"] == 150
        assert result["engine_running"] is True
        assert result["coolant_temp_f"] == pytest.approx(190.4, abs=1)
        assert result["transmission_temp_f"] == pytest.approx(167, abs=1)

    def test_parse_battery_capacity(self):
        """Test parsing battery capacity kWh."""
        data = {
            "k2241a3": "18.4",  # Battery capacity kWh
            "session": "test-session",
            "time": "1609459200000",
        }
        result = TorqueParser.parse(data)
        assert result["battery_capacity_kwh"] == 18.4

    def test_parse_lifetime_counters(self):
        """Test parsing lifetime counter PIDs."""
        data = {
            "k224322": "25000",  # Lifetime EV miles
            "k224323": "15000",  # Lifetime gas miles
            "k224324": "450.5",  # Lifetime fuel gallons
            "k224325": "5500",  # Lifetime kWh
            "session": "test-session",
            "time": "1609459200000",
        }
        result = TorqueParser.parse(data)
        assert result["lifetime_ev_miles"] == 25000
        assert result["lifetime_gas_miles"] == 15000
        assert result["lifetime_fuel_gal"] == 450.5
        assert result["lifetime_kwh"] == 5500

    def test_parse_dte_fields(self):
        """Test parsing distance to empty fields."""
        data = {
            "k22430a": "35",  # DTE electric miles
            "k22430c": "250",  # DTE gas miles
            "session": "test-session",
            "time": "1609459200000",
        }
        result = TorqueParser.parse(data)
        assert result["dte_electric_miles"] == 35
        assert result["dte_gas_miles"] == 250
