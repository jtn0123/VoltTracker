"""Tests for CSV import functionality."""

import io

from receiver.utils.csv_importer import TorqueCSVImporter


class TestTorqueCSVImporter:
    """Tests for the TorqueCSVImporter class."""

    def test_parse_basic_csv(self):
        """Test parsing a basic Torque CSV with standard columns."""
        csv_content = """GPS Time,Latitude,Longitude,GPS Speed (Meters/second),Engine RPM(rpm),Fuel Level (%)
2024-01-15 10:30:00,37.7749,-122.4194,15.5,1200,75.5
2024-01-15 10:30:05,37.7750,-122.4195,16.2,1250,75.4
"""
        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert stats["total_rows"] == 2
        assert stats["parsed_rows"] == 2
        assert stats["skipped_rows"] == 0
        assert len(records) == 2

        # Check first record
        assert records[0]["latitude"] == 37.7749
        assert records[0]["longitude"] == -122.4194
        assert abs(records[0]["speed_mph"] - 34.67) < 0.1  # 15.5 m/s to mph
        assert records[0]["engine_rpm"] == 1200
        assert records[0]["fuel_level_percent"] == 75.5

    def test_parse_volt_specific_columns(self):
        """Test parsing Volt-specific columns like SOC."""
        csv_content = """GPS Time,State of Charge(%),HV Battery Power(kW)
2024-01-15 10:30:00,65.5,12.3
2024-01-15 10:30:05,65.0,-5.5
"""
        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 2
        assert records[0]["state_of_charge"] == 65.5
        assert records[0]["hv_battery_power_kw"] == 12.3
        assert records[1]["hv_battery_power_kw"] == -5.5

    def test_parse_alternative_timestamp_formats(self):
        """Test various timestamp formats."""
        csv_content = """GPS Time,Latitude
01-Jan-2024 10:30:45.123,37.7749
2024-01-15T10:30:45,37.7750
01/15/2024 10:30:45,37.7751
"""
        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 3
        assert all(r["timestamp"] is not None for r in records)

    def test_parse_european_date_formats(self):
        """Test European date formats (DD/MM/YYYY and DD.MM.YYYY)."""
        csv_content = """GPS Time,Latitude
15/01/2024 10:30:45,37.7749
15.01.2024 10:30:45,37.7750
15-01-2024 10:30:45,37.7751
"""
        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 3
        assert all(r["timestamp"] is not None for r in records)
        # All should parse to January 15, 2024
        for record in records:
            assert record["timestamp"].month == 1
            assert record["timestamp"].day == 15

    def test_parse_iso8601_with_timezone(self):
        """Test ISO 8601 with timezone offsets."""
        csv_content = """GPS Time,Latitude
2024-01-15T10:30:45Z,37.7749
2024-01-15T10:30:45+00:00,37.7750
2024-01-15T15:30:45+05:00,37.7751
2024-01-15T05:30:45-05:00,37.7752
"""
        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 4
        assert all(r["timestamp"] is not None for r in records)
        # All should be converted to UTC
        for record in records:
            # Should all be the same time in UTC (10:30:45)
            assert record["timestamp"].hour == 10
            assert record["timestamp"].minute == 30

    def test_parse_text_month_formats(self):
        """Test text month formats like 'Jan 15, 2024'."""
        csv_content = """GPS Time,Latitude
Jan 15, 2024 10:30:45,37.7749
January 15, 2024 10:30:45,37.7750
"""
        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 2
        assert all(r["timestamp"] is not None for r in records)

    def test_parse_date_only_formats(self):
        """Test date-only formats assume midnight UTC."""
        csv_content = """GPS Time,Latitude
2024-01-15,37.7749
01/15/2024,37.7750
"""
        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 2
        assert all(r["timestamp"] is not None for r in records)
        # Should default to midnight
        for record in records:
            assert record["timestamp"].hour == 0
            assert record["timestamp"].minute == 0

    def test_parse_epoch_timestamp(self):
        """Test parsing epoch timestamp in milliseconds."""
        csv_content = """GPS Time,Latitude
1705320600000,37.7749
"""
        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert records[0]["timestamp"] is not None
        assert records[0]["latitude"] == 37.7749

    def test_parse_speed_conversions(self):
        """Test speed unit conversions."""
        csv_content = """GPS Time,GPS Speed (km/h)
2024-01-15 10:30:00,100
"""
        records, _ = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert abs(records[0]["speed_mph"] - 62.14) < 0.1  # 100 km/h to mph

    def test_parse_handles_empty_values(self):
        """Test that empty values are handled gracefully."""
        csv_content = """GPS Time,Latitude,Longitude,Engine RPM(rpm)
2024-01-15 10:30:00,37.7749,,-
2024-01-15 10:30:05,,,-
"""
        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert stats["parsed_rows"] == 2
        assert records[0]["latitude"] == 37.7749
        assert records[0]["longitude"] is None
        assert records[0]["engine_rpm"] is None

    def test_parse_skips_rows_without_timestamp(self):
        """Test that rows without valid timestamps are skipped."""
        csv_content = """GPS Time,Latitude
2024-01-15 10:30:00,37.7749
invalid_time,37.7750
,37.7751
"""
        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert stats["parsed_rows"] == 1
        assert stats["skipped_rows"] == 2
        assert len(records) == 1

    def test_parse_fuel_remaining_calculation(self):
        """Test that fuel remaining gallons is calculated correctly."""
        csv_content = """GPS Time,Fuel Level (%)
2024-01-15 10:30:00,50.0
"""
        records, _ = TorqueCSVImporter.parse_csv(csv_content)

        # 50% of 9.3122 gallons = 4.6561 gallons
        assert abs(records[0]["fuel_remaining_gallons"] - 4.6561) < 0.001

    def test_parse_odometer_km_to_miles(self):
        """Test odometer conversion from km to miles."""
        csv_content = """GPS Time,Odometer(km)
2024-01-15 10:30:00,100
"""
        records, _ = TorqueCSVImporter.parse_csv(csv_content)

        assert abs(records[0]["odometer_miles"] - 62.14) < 0.1

    def test_parse_case_insensitive_columns(self):
        """Test that column matching is case-insensitive."""
        csv_content = """gps time,LATITUDE,Fuel Level (%)
2024-01-15 10:30:00,37.7749,75.0
"""
        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 1
        assert records[0]["latitude"] == 37.7749
        assert records[0]["fuel_level_percent"] == 75.0

    def test_parse_generates_session_id(self):
        """Test that a session ID is generated for the import."""
        csv_content = """GPS Time,Latitude
2024-01-15 10:30:00,37.7749
2024-01-15 10:30:05,37.7750
"""
        records, _ = TorqueCSVImporter.parse_csv(csv_content)

        assert records[0]["session_id"] is not None
        # All records should have the same session ID
        assert records[0]["session_id"] == records[1]["session_id"]

    def test_parse_raw_data_preserved(self):
        """Test that raw CSV data is preserved."""
        csv_content = """GPS Time,Latitude,Custom Column
2024-01-15 10:30:00,37.7749,custom_value
"""
        records, _ = TorqueCSVImporter.parse_csv(csv_content)

        # Only mapped columns should be in raw_data
        assert "Latitude" in records[0]["raw_data"]
        assert records[0]["raw_data"]["Latitude"] == "37.7749"

    def test_parse_temperature_columns(self):
        """Test parsing temperature columns."""
        csv_content = """GPS Time,Ambient Air Temp(°F),Engine Coolant Temp(°F)
2024-01-15 10:30:00,72.5,195.0
"""
        records, _ = TorqueCSVImporter.parse_csv(csv_content)

        assert records[0]["ambient_temp_f"] == 72.5
        assert records[0]["coolant_temp_f"] == 195.0

    def test_parse_empty_csv(self):
        """Test handling of empty CSV."""
        csv_content = ""
        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 0
        assert stats["total_rows"] == 0

    def test_parse_header_only_csv(self):
        """Test handling of CSV with only headers."""
        csv_content = """GPS Time,Latitude,Longitude
"""
        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        assert len(records) == 0
        assert stats["total_rows"] == 0


class TestCSVImportEndpoint:
    """Tests for the CSV import API endpoint."""

    def test_import_csv_no_file(self, client):
        """Test import with no file provided."""
        response = client.post("/api/import/csv")
        assert response.status_code == 400
        assert b"No file provided" in response.data

    def test_import_csv_empty_filename(self, client):
        """Test import with empty filename."""
        data = {"file": (io.BytesIO(b""), "")}
        response = client.post("/api/import/csv", data=data, content_type="multipart/form-data")
        assert response.status_code == 400
        assert b"No file selected" in response.data

    def test_import_csv_wrong_extension(self, client):
        """Test import with wrong file extension."""
        data = {"file": (io.BytesIO(b"test content"), "test.txt")}
        response = client.post("/api/import/csv", data=data, content_type="multipart/form-data")
        assert response.status_code == 400
        assert b"must be a CSV" in response.data

    def test_import_csv_valid_file(self, client, db_session):
        """Test successful CSV import."""
        csv_content = b"""GPS Time,Latitude,Longitude,GPS Speed (Meters/second),Fuel Level (%)
2024-01-15 10:30:00,37.7749,-122.4194,15.5,75.5
2024-01-15 10:30:05,37.7750,-122.4195,16.2,75.4
"""
        data = {"file": (io.BytesIO(csv_content), "test.csv")}
        response = client.post("/api/import/csv", data=data, content_type="multipart/form-data")

        assert response.status_code == 200
        json_data = response.get_json()
        assert "Successfully imported" in json_data["message"]
        assert json_data["stats"]["parsed_rows"] == 2

    def test_import_csv_creates_trip(self, client, db_session):
        """Test that CSV import creates a trip record."""
        csv_content = b"""GPS Time,Latitude,Fuel Level (%),State of Charge(%)
2024-01-15 10:30:00,37.7749,75.5,80.0
2024-01-15 10:35:00,37.7750,75.0,75.0
"""
        data = {"file": (io.BytesIO(csv_content), "test.csv")}
        response = client.post("/api/import/csv", data=data, content_type="multipart/form-data")

        assert response.status_code == 200
        json_data = response.get_json()
        # trip_id is at top level in new response format
        assert "trip_id" in json_data or "trip_id" in json_data.get("stats", {})

    def test_import_csv_invalid_content(self, client, db_session):
        """Test import with no valid records."""
        csv_content = b"""GPS Time,Latitude
invalid_timestamp,37.7749
"""
        data = {"file": (io.BytesIO(csv_content), "test.csv")}
        response = client.post("/api/import/csv", data=data, content_type="multipart/form-data")

        assert response.status_code == 400
        assert b"No valid records" in response.data
