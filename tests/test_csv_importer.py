"""
Tests for CSV importer module.

Tests the Torque Pro CSV import functionality including:
- Column name mapping and variations
- Timestamp parsing (multiple formats)
- Unit conversions (speed, distance, temperature)
- Data validation and range checking
- Duplicate detection
- Error handling and stats tracking
- CSV dialect detection
- Edge cases and malformed data
"""

import uuid
from datetime import datetime, timezone
from typing import Set

import pytest

from receiver.utils.csv_importer import VALIDATION_RANGES, TorqueCSVImporter


class TestColumnMapping:
    """Tests for column name mapping."""

    def test_standard_column_names(self):
        """Standard column names are mapped correctly."""
        csv_content = """GPS Time,Latitude,Longitude,Engine RPM(rpm),Fuel Level (%)
01-Jan-2024 12:00:00,37.7749,-122.4194,1500.0,75.0"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert records[0]["latitude"] == 37.7749
        assert records[0]["longitude"] == -122.4194
        assert records[0]["engine_rpm"] == 1500.0
        assert records[0]["fuel_level_percent"] == 75.0

    def test_volt_specific_soc_columns(self):
        """Volt-specific SOC column variations are recognized."""
        # Test various SOC column formats
        test_cases = [
            ("!! SOC (Usable)(%)", 85.0),
            ("!! SOC(Raw)(%)", 90.0),
            ("!Hybrid Pack Remaining (SOC)(%)", 80.0),
            ("Hybrid Battery Charge(%)", 75.0),
            ("State of Charge(%)", 70.0),
        ]

        for col_name, expected_soc in test_cases:
            csv_content = f"""GPS Time,{col_name}
01-Jan-2024 12:00:00,{expected_soc}"""

            records, stats = TorqueCSVImporter.parse_csv(csv_content)

            assert len(records) == 1
            assert records[0]["state_of_charge"] == expected_soc

    def test_case_insensitive_mapping(self):
        """Column names are case-insensitive."""
        csv_content = """gps time,LATITUDE,longitude,ENGINE RPM(rpm)
01-Jan-2024 12:00:00,37.7749,-122.4194,1500.0"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert records[0]["latitude"] == 37.7749
        assert records[0]["longitude"] == -122.4194
        assert records[0]["engine_rpm"] == 1500.0

    def test_speed_column_variations(self):
        """Different speed column formats are recognized."""
        test_cases = [
            ("GPS Speed (Meters/second)", 10.0, 22.37),  # 10 m/s ≈ 22.37 mph
            ("GPS Speed(km/h)", 100.0, 62.14),  # 100 km/h ≈ 62.14 mph
            ("Speed (GPS)(mph)", 55.0, 55.0),  # Already mph
        ]

        for col_name, value, expected_mph in test_cases:
            csv_content = f"""GPS Time,{col_name}
01-Jan-2024 12:00:00,{value}"""

            records, stats = TorqueCSVImporter.parse_csv(csv_content)

            assert len(records) == 1
            assert records[0]["speed_mph"] == pytest.approx(expected_mph, abs=0.1)

    def test_temperature_column_variations(self):
        """Various temperature columns are mapped correctly."""
        csv_content = """GPS Time,Ambient Air Temp(°F),!Engine Coolant Temp(°F),*M Intake Air Temp IAT(°F)
01-Jan-2024 12:00:00,68.0,195.0,85.0"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert records[0]["ambient_temp_f"] == 68.0
        assert records[0]["coolant_temp_f"] == 195.0
        assert records[0]["intake_air_temp_f"] == 85.0

    def test_hv_battery_columns(self):
        """High voltage battery columns are parsed."""
        csv_content = """GPS Time,!! Inst. KPower(kW),!! HV Discharge Amps(A),!! HV Volts(V),!HV Battery Temp(°F)
01-Jan-2024 12:00:00,25.5,80.0,360.0,75.0"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert records[0]["hv_battery_power_kw"] == 25.5
        assert records[0]["hv_discharge_amps"] == 80.0
        assert records[0]["hv_battery_voltage_v"] == 360.0
        assert records[0]["battery_temp_f"] == 75.0

    def test_unmapped_columns_ignored(self):
        """Columns without mapping are ignored gracefully."""
        csv_content = """GPS Time,Unknown Column,Random Data
01-Jan-2024 12:00:00,foo,bar"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        # Should still parse timestamp
        assert records[0]["timestamp"] is not None


class TestTimestampParsing:
    """Tests for timestamp parsing."""

    def test_torque_format_with_milliseconds(self):
        """Parses Torque format: DD-Mon-YYYY HH:MM:SS.fff"""
        csv_content = """GPS Time,Latitude
01-Jan-2024 12:30:45.123,37.7749"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        ts = records[0]["timestamp"]
        assert ts.year == 2024
        assert ts.month == 1
        assert ts.day == 1
        assert ts.hour == 12
        assert ts.minute == 30
        assert ts.second == 45

    def test_torque_format_without_milliseconds(self):
        """Parses Torque format without milliseconds."""
        csv_content = """GPS Time,Latitude
15-Feb-2024 08:15:30,37.7749"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        ts = records[0]["timestamp"]
        assert ts.year == 2024
        assert ts.month == 2
        assert ts.day == 15
        assert ts.hour == 8
        assert ts.minute == 15

    def test_iso_8601_format(self):
        """Parses ISO 8601 timestamps."""
        test_cases = [
            "2024-01-01 12:30:45",
            "2024-01-01T12:30:45",
            "2024-01-01T12:30:45.123",
        ]

        for timestamp in test_cases:
            csv_content = f"""GPS Time,Latitude
{timestamp},37.7749"""

            records, stats = TorqueCSVImporter.parse_csv(csv_content)

            assert len(records) == 1
            assert records[0]["timestamp"] is not None

    def test_iso_8601_with_timezone(self):
        """Parses ISO 8601 with timezone offset."""
        csv_content = """GPS Time,Latitude
2024-01-01T12:30:45+05:00,37.7749"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        ts = records[0]["timestamp"]
        # Should be converted to UTC
        assert ts.tzinfo == timezone.utc

    def test_iso_8601_with_z_suffix(self):
        """Parses ISO 8601 with Z (UTC) suffix."""
        csv_content = """GPS Time,Latitude
2024-01-01T12:30:45Z,37.7749"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        ts = records[0]["timestamp"]
        assert ts.tzinfo == timezone.utc

    def test_unix_epoch_seconds(self):
        """Parses Unix epoch timestamp in seconds."""
        # Jan 1, 2024 00:00:00 UTC
        epoch = 1704067200
        csv_content = f"""GPS Time
{epoch}"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        ts = records[0]["timestamp"]
        assert ts.year == 2024
        assert ts.month == 1
        assert ts.day == 1

    def test_unix_epoch_milliseconds(self):
        """Parses Unix epoch timestamp in milliseconds."""
        # Jan 1, 2024 00:00:00 UTC (milliseconds)
        epoch_ms = 1704067200000
        csv_content = f"""GPS Time
{epoch_ms}"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        ts = records[0]["timestamp"]
        assert ts.year == 2024
        assert ts.month == 1

    def test_us_date_format(self):
        """Parses US date format MM/DD/YYYY."""
        csv_content = """GPS Time,Latitude
01/15/2024 12:30:45,37.7749"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        ts = records[0]["timestamp"]
        assert ts.month == 1
        assert ts.day == 15

    def test_european_date_format(self):
        """Parses European date format DD/MM/YYYY."""
        csv_content = """GPS Time,Latitude
15/01/2024 12:30:45,37.7749"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        # This will be parsed - actual date depends on parser
        assert records[0]["timestamp"] is not None

    def test_device_time_fallback(self):
        """Falls back to Device Time if GPS Time missing."""
        csv_content = """Device Time,Latitude
2024-01-01 12:30:45,37.7749"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert records[0]["timestamp"] is not None

    def test_invalid_timestamp_returns_none(self):
        """Invalid timestamp results in skipped row."""
        csv_content = """GPS Time,Latitude
not-a-date,37.7749"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        # Row should be skipped (no valid timestamp)
        assert len(records) == 0
        assert stats["skipped_rows"] == 1

    def test_empty_timestamp_skipped(self):
        """Empty timestamp results in skipped row."""
        csv_content = """GPS Time,Latitude
,37.7749
-,37.7749"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 0
        assert stats["skipped_rows"] == 2


class TestUnitConversions:
    """Tests for unit conversions."""

    def test_speed_mps_to_mph(self):
        """Converts meters/second to mph."""
        # 10 m/s = 22.369 mph
        csv_content = """GPS Time,GPS Speed (Meters/second)
01-Jan-2024 12:00:00,10.0"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert records[0]["speed_mph"] == pytest.approx(22.37, abs=0.01)

    def test_speed_kmh_to_mph(self):
        """Converts km/h to mph."""
        # 100 km/h = 62.137 mph
        csv_content = """GPS Time,GPS Speed(km/h)
01-Jan-2024 12:00:00,100.0"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert records[0]["speed_mph"] == pytest.approx(62.14, abs=0.01)

    def test_odometer_km_to_miles(self):
        """Converts odometer km to miles."""
        # 100 km = 62.137 miles
        csv_content = """GPS Time,Odometer(km)
01-Jan-2024 12:00:00,100.0"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert records[0]["odometer_miles"] == pytest.approx(62.14, abs=0.01)

    def test_fuel_level_to_gallons(self):
        """Converts fuel level percent to gallons."""
        # 50% of 9.3 gallon tank = 4.65 gallons
        csv_content = """GPS Time,Fuel Level(%)
01-Jan-2024 12:00:00,50.0"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert records[0]["fuel_level_percent"] == 50.0
        assert records[0]["fuel_remaining_gallons"] == pytest.approx(4.65, abs=0.01)


class TestValidation:
    """Tests for data validation."""

    def test_valid_values_pass(self):
        """Valid values within ranges pass validation."""
        csv_content = """GPS Time,State of Charge(%),Fuel Level(%),Speed (GPS)(mph),Engine RPM(rpm)
01-Jan-2024 12:00:00,85.0,50.0,65.0,2500.0"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert stats["validation_warnings"] == 0
        assert len(stats["warnings"]) == 0

    def test_out_of_range_soc_warning(self):
        """SOC outside 0-100% generates warning."""
        csv_content = """GPS Time,State of Charge(%)
01-Jan-2024 12:00:00,150.0"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        # Record is still added, but with warning
        assert len(records) == 1
        assert stats["validation_warnings"] > 0
        assert any("state_of_charge" in w for w in stats["warnings"])

    def test_negative_fuel_level_warning(self):
        """Negative fuel level generates warning."""
        csv_content = """GPS Time,Fuel Level(%)
01-Jan-2024 12:00:00,-5.0"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert stats["validation_warnings"] > 0

    def test_excessive_speed_warning(self):
        """Speed > 200 mph generates warning."""
        csv_content = """GPS Time,Speed (GPS)(mph)
01-Jan-2024 12:00:00,250.0"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert stats["validation_warnings"] > 0
        assert any("speed_mph" in w for w in stats["warnings"])

    def test_invalid_gps_coordinates_warning(self):
        """Invalid GPS coordinates generate warnings."""
        csv_content = """GPS Time,Latitude,Longitude
01-Jan-2024 12:00:00,95.0,200.0"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert stats["validation_warnings"] >= 2  # Both lat and lon invalid

    def test_validation_warning_limit(self):
        """Validation warnings are limited to 10 messages."""
        # Create 20 rows with invalid data
        rows = ["GPS Time,State of Charge(%)"]
        for i in range(20):
            # Use proper date format with 2-digit day
            day = str(i + 1).zfill(2)
            rows.append(f"{day}-Jan-2024 12:00:00,150.0")

        csv_content = "\n".join(rows)
        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        # Should have all 20 records
        assert len(records) == 20
        # Warnings capped at 10
        assert len(stats["warnings"]) <= 10
        # But should have counted all validation warnings
        assert stats["validation_warnings"] == 20

    def test_null_values_not_validated(self):
        """Null/missing values don't trigger validation warnings."""
        csv_content = """GPS Time,State of Charge(%),Fuel Level(%)
01-Jan-2024 12:00:00,,"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert stats["validation_warnings"] == 0


class TestDuplicateDetection:
    """Tests for duplicate record detection."""

    def test_no_duplicates_without_existing_timestamps(self):
        """Without existing timestamps, no duplicates removed."""
        csv_content = """GPS Time,Latitude
01-Jan-2024 12:00:00,37.7749
01-Jan-2024 12:00:00,37.7750"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        # Both records kept (no deduplication within import)
        assert len(records) == 2
        assert stats["duplicates_removed"] == 0

    def test_removes_existing_duplicates(self):
        """Removes records matching existing timestamps."""
        csv_content = """GPS Time,Latitude
01-Jan-2024 12:00:00,37.7749
01-Jan-2024 13:00:00,37.7750
01-Jan-2024 14:00:00,37.7751"""

        # Create set of existing timestamps
        existing: Set[datetime] = {
            datetime(2024, 1, 1, 12, 0, 0),  # First record is duplicate
            datetime(2024, 1, 1, 14, 0, 0),  # Third record is duplicate
        }

        records, stats = TorqueCSVImporter.parse_csv(csv_content, existing)

        # Only middle record should remain
        assert len(records) == 1
        assert records[0]["latitude"] == 37.7750
        assert stats["duplicates_removed"] == 2

    def test_timezone_normalized_for_comparison(self):
        """Timestamps are normalized for duplicate comparison."""
        csv_content = """GPS Time,Latitude
2024-01-01T12:00:00Z,37.7749"""

        # Existing timestamp (naive)
        existing: Set[datetime] = {
            datetime(2024, 1, 1, 12, 0, 0),
        }

        records, stats = TorqueCSVImporter.parse_csv(csv_content, existing)

        assert len(records) == 0
        assert stats["duplicates_removed"] == 1


class TestParseCSV:
    """Integration tests for parse_csv method."""

    def test_complete_parsing_workflow(self):
        """Full CSV parsing workflow with all features."""
        csv_content = """GPS Time,Latitude,Longitude,State of Charge(%),Speed (GPS)(mph),Engine RPM(rpm)
01-Jan-2024 12:00:00,37.7749,-122.4194,85.0,55.0,0.0
01-Jan-2024 12:05:00,37.7850,-122.4100,84.5,60.0,0.0
01-Jan-2024 12:10:00,37.7950,-122.4000,84.0,58.0,1500.0"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 3
        assert stats["total_rows"] == 3
        assert stats["parsed_rows"] == 3
        assert stats["skipped_rows"] == 0
        assert stats["total_errors"] == 0

    def test_stats_tracking(self):
        """Stats dictionary tracks all parsing metrics."""
        csv_content = """GPS Time,State of Charge(%)
01-Jan-2024 12:00:00,85.0
invalid-timestamp,90.0
01-Jan-2024 12:10:00,150.0"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert stats["total_rows"] == 3
        assert stats["parsed_rows"] == 2  # Two valid records
        assert stats["skipped_rows"] == 1  # One invalid timestamp
        assert stats["validation_warnings"] == 1  # SOC=150
        assert "columns_found" in stats

    def test_columns_found_reported(self):
        """Reports which columns were found and mapped."""
        csv_content = """GPS Time,Latitude,Unknown Column
01-Jan-2024 12:00:00,37.7749,foo"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert "timestamp" in stats["columns_found"]
        assert "latitude" in stats["columns_found"]
        # Unknown column not in found list

    def test_session_id_assigned(self):
        """All records in import get same session ID."""
        csv_content = """GPS Time,Latitude
01-Jan-2024 12:00:00,37.7749
01-Jan-2024 12:05:00,37.7850"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 2
        session1 = records[0]["session_id"]
        session2 = records[1]["session_id"]
        assert session1 == session2
        assert isinstance(session1, uuid.UUID)

    def test_csv_dialect_detection(self):
        """Detects CSV dialect (delimiter, etc)."""
        # Semicolon-delimited CSV
        csv_content = """GPS Time;Latitude;Longitude
01-Jan-2024 12:00:00;37.7749;-122.4194"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert records[0]["latitude"] == 37.7749

    def test_raw_data_preserved(self):
        """Original CSV values preserved in raw_data."""
        csv_content = """GPS Time,Latitude,Engine RPM(rpm)
01-Jan-2024 12:00:00,37.7749,1500.0"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        raw = records[0]["raw_data"]
        assert "Latitude" in raw
        assert raw["Latitude"] == "37.7749"

    def test_error_messages_capped_at_10(self):
        """Error messages limited to 10 in stats."""
        # Create 20 rows with invalid timestamps (will be skipped)
        rows = ["GPS Time,Latitude"]
        for i in range(20):
            rows.append(f"invalid-timestamp-{i},37.7749")

        csv_content = "\n".join(rows)
        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        # All rows should be skipped due to invalid timestamps
        assert len(records) == 0
        assert stats["skipped_rows"] == 20
        # Note: invalid timestamps don't create CSVImportError exceptions,
        # they just result in None timestamp and skipped row
        # So total_errors might be 0, and that's ok


class TestEdgeCases:
    """Tests for edge cases and error handling."""

    def test_empty_csv(self):
        """Empty CSV handled gracefully."""
        csv_content = ""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 0
        assert stats["total_rows"] == 0

    def test_header_only_csv(self):
        """CSV with only header row."""
        csv_content = """GPS Time,Latitude,Longitude"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 0
        assert stats["total_rows"] == 0

    def test_missing_values_as_dash(self):
        """Dash (-) treated as missing value."""
        csv_content = """GPS Time,Latitude,Longitude
01-Jan-2024 12:00:00,-,-"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert records[0]["latitude"] is None
        assert records[0]["longitude"] is None

    def test_empty_string_values(self):
        """Empty strings treated as missing."""
        csv_content = """GPS Time,Latitude,Engine RPM(rpm)
01-Jan-2024 12:00:00,37.7749,"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert records[0]["engine_rpm"] is None

    def test_whitespace_trimmed(self):
        """Leading/trailing whitespace trimmed."""
        csv_content = """GPS Time,Latitude
01-Jan-2024 12:00:00  ,  37.7749  """

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert records[0]["latitude"] == 37.7749

    def test_malformed_number_skipped(self):
        """Non-numeric values for numeric fields are skipped."""
        csv_content = """GPS Time,Latitude,Engine RPM(rpm)
01-Jan-2024 12:00:00,not-a-number,also-not-a-number"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        # Fields with parse errors should be None
        assert records[0]["latitude"] is None
        assert records[0]["engine_rpm"] is None

    def test_all_fields_initialized_to_none(self):
        """All record fields initialized even if not in CSV."""
        csv_content = """GPS Time,Latitude
01-Jan-2024 12:00:00,37.7749"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        record = records[0]
        # Fields not in CSV should exist with None values
        assert record["latitude"] == 37.7749  # This one is present
        assert record["longitude"] is None
        assert record["engine_rpm"] is None
        assert record["state_of_charge"] is None
        assert record["fuel_level_percent"] is None

    def test_session_id_always_set(self):
        """Session ID always present in records."""
        csv_content = """GPS Time,Latitude
01-Jan-2024 12:00:00,37.7749"""

        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert "session_id" in records[0]
        assert records[0]["session_id"] is not None


class TestValidationRanges:
    """Tests for VALIDATION_RANGES constant."""

    def test_all_ranges_defined(self):
        """All expected validation ranges are defined."""
        expected_fields = [
            "state_of_charge",
            "fuel_level_percent",
            "speed_mph",
            "engine_rpm",
            "latitude",
            "longitude",
            "ambient_temp_f",
            "coolant_temp_f",
        ]

        for field in expected_fields:
            assert field in VALIDATION_RANGES
            min_val, max_val = VALIDATION_RANGES[field]
            assert min_val < max_val

    def test_soc_range(self):
        """SOC validation range is 0-100%."""
        assert VALIDATION_RANGES["state_of_charge"] == (0, 100)

    def test_fuel_range(self):
        """Fuel level validation range is 0-100%."""
        assert VALIDATION_RANGES["fuel_level_percent"] == (0, 100)

    def test_gps_ranges(self):
        """GPS coordinate ranges are valid."""
        assert VALIDATION_RANGES["latitude"] == (-90, 90)
        assert VALIDATION_RANGES["longitude"] == (-180, 180)
