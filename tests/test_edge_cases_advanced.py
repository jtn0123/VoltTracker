"""
Advanced Edge Case Tests for VoltTracker

Comprehensive tests for:
- Extreme boundary values
- Unicode and special characters
- Very large and very small numbers
- Date/time edge cases (leap years, DST, timezone boundaries)
- Concurrent operations
- Memory and performance limits
"""

import json
import uuid
from datetime import datetime, timedelta, timezone
from decimal import Decimal

import pytest

from tests.factories import (
    ChargingSessionFactory,
    FuelEventFactory,
    TelemetryFactory,
    TripFactory,
)


# ============================================================================
# Extreme Boundary Values
# ============================================================================


class TestExtremeBoundaryValues:
    """Test extreme numerical boundaries."""

    def test_trip_with_maximum_float_values(self, db_session):
        """Test trip with maximum reasonable float values."""
        trip = TripFactory.create(
            db_session=db_session,
            start_odometer=999999.9,
            end_odometer=1000000.0,
            distance_miles=999999.9,
            electric_kwh_used=999.9,
        )

        assert trip.id is not None
        assert trip.distance_miles == 999999.9

    def test_trip_with_minimum_positive_values(self, db_session):
        """Test trip with minimum positive values."""
        trip = TripFactory.create(
            db_session=db_session,
            distance_miles=0.001,  # Minimum meaningful distance
            electric_kwh_used=0.001,
        )

        assert trip.distance_miles == 0.001

    def test_soc_at_exactly_zero_percent(self, db_session):
        """SOC at exactly 0%."""
        telemetry = TelemetryFactory.create(
            db_session=db_session,
            state_of_charge=0.0,
        )

        assert telemetry.state_of_charge == 0.0

    def test_soc_at_exactly_100_percent(self, db_session):
        """SOC at exactly 100%."""
        telemetry = TelemetryFactory.create(
            db_session=db_session,
            state_of_charge=100.0,
        )

        assert telemetry.state_of_charge == 100.0

    def test_negative_temperature_extreme_cold(self, db_session):
        """Test with extreme cold temperatures."""
        telemetry = TelemetryFactory.create(
            db_session=db_session,
            ambient_temp_f=-50.0,  # Very cold
        )

        assert telemetry.ambient_temp_f == -50.0

    def test_extreme_hot_temperature(self, db_session):
        """Test with extreme hot temperatures."""
        telemetry = TelemetryFactory.create(
            db_session=db_session,
            ambient_temp_f=150.0,  # Desert heat
        )

        assert telemetry.ambient_temp_f == 150.0

    def test_charging_power_at_maximum(self, db_session):
        """Test charging at maximum DCFC power."""
        session = ChargingSessionFactory.create(
            db_session=db_session,
            charge_type="DCFC",
            peak_power_kw=150.0,  # Maximum DC fast charging
            avg_power_kw=125.0,
        )

        assert session.peak_power_kw == 150.0

    def test_odometer_rollover_boundary(self, db_session):
        """Test odometer near rollover point."""
        trip = TripFactory.create(
            db_session=db_session,
            start_odometer=999995.0,
            end_odometer=999999.9,  # Near max
            distance_miles=4.9,
        )

        assert trip.end_odometer == 999999.9

    def test_fuel_level_edge_boundaries(self, db_session):
        """Test fuel level at exact boundaries."""
        # Exactly empty
        t1 = TelemetryFactory.create(db_session=db_session, fuel_level_percent=0.0)
        assert t1.fuel_level_percent == 0.0

        # Exactly full
        t2 = TelemetryFactory.create(db_session=db_session, fuel_level_percent=100.0)
        assert t2.fuel_level_percent == 100.0

    def test_speed_at_zero(self, db_session):
        """Test speed at exactly zero (stopped)."""
        telemetry = TelemetryFactory.create(
            db_session=db_session,
            speed_mph=0.0,
        )

        assert telemetry.speed_mph == 0.0

    def test_speed_at_highway_maximum(self, db_session):
        """Test speed at highway maximum."""
        telemetry = TelemetryFactory.create(
            db_session=db_session,
            speed_mph=120.0,  # Unrealistic but possible data
        )

        assert telemetry.speed_mph == 120.0


# ============================================================================
# Unicode and Special Characters
# ============================================================================


class TestUnicodeAndSpecialCharacters:
    """Test handling of unicode and special characters."""

    def test_fuel_event_notes_with_unicode(self, db_session):
        """Notes with unicode characters."""
        event = FuelEventFactory.create(
            db_session=db_session,
            notes="café ☕ Station — Route 66 • Main St.",
        )

        assert "café" in event.notes
        assert "☕" in event.notes

    def test_charging_session_notes_with_emojis(self, db_session):
        """Notes with emoji characters."""
        session = ChargingSessionFactory.create(
            db_session=db_session,
            notes="⚡ Fast charging 🔋 at mall 🛒",
        )

        assert "⚡" in session.notes
        assert "🔋" in session.notes

    def test_special_characters_in_location_name(self, db_session):
        """Special characters and punctuation."""
        session = ChargingSessionFactory.create(
            db_session=db_session,
            location_name="Bob's Garage & Auto (Main St.) <Downtown>",
        )

        assert "Bob's" in session.location_name
        assert "&" in session.location_name

    def test_multiline_text_in_notes(self, db_session):
        """Multiline text with newlines."""
        session = ChargingSessionFactory.create(
            db_session=db_session,
            notes="Line 1\nLine 2\nLine 3\n\nLine 5",
        )

        assert "\n" in session.notes
        assert session.notes.count("\n") == 4

    def test_very_long_text_in_notes(self, db_session):
        """Very long text strings."""
        long_text = "A" * 5000  # 5KB of text
        session = ChargingSessionFactory.create(
            db_session=db_session,
            notes=long_text,
        )

        assert len(session.notes) == 5000

    def test_json_special_characters_in_text(self, db_session):
        """Text containing JSON special characters."""
        session = ChargingSessionFactory.create(
            db_session=db_session,
            notes='{"status": "ok"}, [1,2,3], "quoted", \\backslash',
        )

        assert '{"status"' in session.notes


# ============================================================================
# Floating Point Precision
# ============================================================================


class TestFloatingPointPrecision:
    """Test floating point arithmetic edge cases."""

    def test_very_small_distance_calculation(self, db_session):
        """Very small distance values and precision."""
        trip = TripFactory.create(
            db_session=db_session,
            start_odometer=50000.0,
            end_odometer=50000.001,  # 0.001 miles
            distance_miles=0.001,
        )

        assert trip.distance_miles < 0.01

    def test_kwh_per_mile_high_precision(self, db_session):
        """High precision kWh/mile calculation."""
        trip = TripFactory.create(
            db_session=db_session,
            electric_kwh_used=4.567890,
            electric_miles=20.123456,
            kwh_per_mile=0.227,  # Calculated value
        )

        assert trip.kwh_per_mile is not None
        assert isinstance(trip.kwh_per_mile, float)

    def test_cumulative_rounding_errors(self, db_session):
        """Test that cumulative rounding doesn't cause issues."""
        # Create multiple telemetry points with slightly different values
        session_id = uuid.uuid4()
        soc_values = [100.0, 99.1, 98.2, 97.3, 96.4, 95.5]  # 0.9% drops

        for i, soc in enumerate(soc_values):
            TelemetryFactory.create(
                db_session=db_session,
                session_id=session_id,
                state_of_charge=soc,
                timestamp=datetime.now(timezone.utc) + timedelta(minutes=i),
            )

        # Verify all created successfully
        from models import TelemetryRaw
        count = db_session.query(TelemetryRaw).filter(
            TelemetryRaw.session_id == session_id
        ).count()

        assert count == len(soc_values)

    def test_division_result_precision(self):
        """Test division precision in calculations."""
        from utils.calculations import calculate_kwh_per_mile

        # Division that doesn't divide evenly
        result = calculate_kwh_per_mile(kwh_used=10.0, electric_miles=3.0)

        # Should be 3.333...
        assert result is not None
        assert 3.33 < result < 3.34

    def test_percentage_calculation_precision(self):
        """Test percentage calculations."""
        from utils.calculations import analyze_soc_floor

        transitions = [
            {"soc_at_transition": 17.555, "ambient_temp_f": 70.0},
            {"soc_at_transition": 17.545, "ambient_temp_f": 70.0},
        ]

        result = analyze_soc_floor(transitions)

        # Average SOC should handle precision correctly
        assert result["average_soc"] is not None


# ============================================================================
# Date and Time Edge Cases
# ============================================================================


class TestDateTimeEdgeCases:
    """Test date/time boundary conditions."""

    def test_trip_on_leap_day(self, db_session):
        """Trip on February 29th (leap day)."""
        leap_day = datetime(2024, 2, 29, 12, 0, 0, tzinfo=timezone.utc)

        trip = TripFactory.create(
            db_session=db_session,
            start_time=leap_day,
            end_time=leap_day + timedelta(hours=1),
        )

        assert trip.start_time.month == 2
        assert trip.start_time.day == 29

    def test_trip_spanning_midnight(self, db_session):
        """Trip that starts before midnight and ends after."""
        start = datetime(2024, 1, 1, 23, 30, 0, tzinfo=timezone.utc)
        end = datetime(2024, 1, 2, 0, 30, 0, tzinfo=timezone.utc)

        trip = TripFactory.create(
            db_session=db_session,
            start_time=start,
            end_time=end,
        )

        assert trip.start_time.day == 1
        assert trip.end_time.day == 2

    def test_trip_spanning_year_boundary(self, db_session):
        """Trip that spans New Year's Eve."""
        start = datetime(2023, 12, 31, 23, 0, 0, tzinfo=timezone.utc)
        end = datetime(2024, 1, 1, 1, 0, 0, tzinfo=timezone.utc)

        trip = TripFactory.create(
            db_session=db_session,
            start_time=start,
            end_time=end,
        )

        assert trip.start_time.year == 2023
        assert trip.end_time.year == 2024

    def test_very_short_trip_duration(self, db_session):
        """Trip lasting only 1 second."""
        start = datetime.now(timezone.utc)
        end = start + timedelta(seconds=1)

        trip = TripFactory.create(
            db_session=db_session,
            start_time=start,
            end_time=end,
        )

        duration = (trip.end_time - trip.start_time).total_seconds()
        assert duration == 1.0

    def test_very_long_trip_duration(self, db_session):
        """Trip lasting 24 hours."""
        start = datetime.now(timezone.utc)
        end = start + timedelta(hours=24)

        trip = TripFactory.create(
            db_session=db_session,
            start_time=start,
            end_time=end,
        )

        duration = (trip.end_time - trip.start_time).total_seconds()
        assert duration == 86400.0  # 24 hours

    def test_timestamp_microsecond_precision(self, db_session):
        """Timestamps with microsecond precision."""
        timestamp = datetime(2024, 1, 1, 12, 0, 0, 123456, tzinfo=timezone.utc)

        telemetry = TelemetryFactory.create(
            db_session=db_session,
            timestamp=timestamp,
        )

        assert telemetry.timestamp.microsecond == 123456

    def test_historical_date_very_old(self, db_session):
        """Test with very old historical date."""
        old_date = datetime(2010, 1, 1, 12, 0, 0, tzinfo=timezone.utc)

        trip = TripFactory.create(
            db_session=db_session,
            start_time=old_date,
            end_time=old_date + timedelta(hours=1),
        )

        assert trip.start_time.year == 2010

    def test_future_date(self, db_session):
        """Test with future date (e.g., scheduled charging)."""
        future = datetime(2030, 1, 1, 12, 0, 0, tzinfo=timezone.utc)

        session = ChargingSessionFactory.create(
            db_session=db_session,
            start_time=future,
        )

        assert session.start_time.year == 2030


# ============================================================================
# GPS Coordinate Edge Cases
# ============================================================================


class TestGPSCoordinateEdgeCases:
    """Test GPS coordinate boundaries."""

    def test_latitude_at_north_pole(self, db_session):
        """Latitude at North Pole (90°)."""
        telemetry = TelemetryFactory.create(
            db_session=db_session,
            latitude=90.0,
            longitude=0.0,
        )

        assert telemetry.latitude == 90.0

    def test_latitude_at_south_pole(self, db_session):
        """Latitude at South Pole (-90°)."""
        telemetry = TelemetryFactory.create(
            db_session=db_session,
            latitude=-90.0,
            longitude=0.0,
        )

        assert telemetry.latitude == -90.0

    def test_longitude_at_international_date_line(self, db_session):
        """Longitude at International Date Line (±180°)."""
        telemetry = TelemetryFactory.create(
            db_session=db_session,
            latitude=0.0,
            longitude=180.0,
        )

        assert telemetry.longitude == 180.0

    def test_coordinates_at_equator_prime_meridian(self, db_session):
        """Coordinates at Null Island (0°, 0°)."""
        telemetry = TelemetryFactory.create(
            db_session=db_session,
            latitude=0.0,
            longitude=0.0,
        )

        assert telemetry.latitude == 0.0
        assert telemetry.longitude == 0.0

    def test_coordinates_with_high_precision(self, db_session):
        """GPS coordinates with many decimal places."""
        telemetry = TelemetryFactory.create(
            db_session=db_session,
            latitude=37.7749295,  # 7 decimal places (~1cm precision)
            longitude=-122.4194155,
        )

        assert abs(telemetry.latitude - 37.7749295) < 0.0000001


# ============================================================================
# API Request Edge Cases
# ============================================================================


class TestAPIRequestEdgeCases:
    """Test API with edge case inputs."""

    def test_api_trips_with_zero_results(self, client):
        """GET /api/trips with no trips in database."""
        response = client.get("/api/trips")

        assert response.status_code == 200
        data = response.get_json()
        assert data["trips"] == []

    def test_api_pagination_page_beyond_max(self, client, db_session):
        """Request page number beyond available pages."""
        # Create only 5 trips
        TripFactory.create_batch(5, db_session=db_session)

        # Request page 100 (way beyond available)
        response = client.get("/api/trips?page=100&per_page=10")

        assert response.status_code == 200
        data = response.get_json()
        assert data["trips"] == []  # No trips on that page

    def test_api_with_negative_page_number(self, client):
        """Request with negative page number."""
        response = client.get("/api/trips?page=-1")

        # Should default to page 1 or return error
        assert response.status_code in [200, 400]

    def test_api_with_very_large_per_page(self, client, db_session):
        """Request with excessively large per_page value."""
        TripFactory.create_batch(10, db_session=db_session)

        response = client.get("/api/trips?per_page=999999")

        assert response.status_code in [200, 400]
        if response.status_code == 200:
            data = response.get_json()
            # Should be capped at reasonable limit
            assert len(data["trips"]) <= 100

    def test_api_with_invalid_date_format(self, client):
        """Request with malformed date parameter."""
        response = client.get("/api/trips?start_date=invalid-date")

        # Should return error or ignore invalid param
        assert response.status_code in [200, 400]

    def test_api_with_special_characters_in_query(self, client):
        """Query parameters with special characters."""
        response = client.get("/api/trips?filter=<script>alert('xss')</script>")

        # Should handle safely
        assert response.status_code in [200, 400]


# ============================================================================
# Torque Data Edge Cases
# ============================================================================


class TestTorqueDataEdgeCases:
    """Test Torque Pro data parsing edge cases."""

    def test_torque_upload_with_empty_strings(self, client):
        """Torque data with empty string values."""
        data = {
            "eml": "",
            "v": "",
            "session": "",
            "id": "",
            "time": str(int(datetime.now(timezone.utc).timestamp() * 1000)),
        }

        response = client.post("/torque/upload", data=data)

        # Should handle gracefully
        assert response.status_code in [200, 400]

    def test_torque_upload_with_invalid_numeric_strings(self, client):
        """Torque data with invalid numeric values."""
        data = {
            "eml": "test@example.com",
            "v": "1.0",
            "session": str(uuid.uuid4()),
            "id": "test-device",
            "time": str(int(datetime.now(timezone.utc).timestamp() * 1000)),
            "kff1006": "not-a-number",  # Invalid latitude
            "kff1001": "abc",  # Invalid speed
            "k22005b": "xyz",  # Invalid SOC
        }

        response = client.post("/torque/upload", data=data)

        # Should handle parsing errors gracefully
        assert response.status_code in [200, 400]

    def test_torque_upload_with_extreme_timestamp(self, client):
        """Torque data with timestamp far in the future."""
        future_timestamp = int((datetime.now(timezone.utc) + timedelta(days=365*10)).timestamp() * 1000)

        data = {
            "eml": "test@example.com",
            "v": "1.0",
            "session": str(uuid.uuid4()),
            "id": "test-device",
            "time": str(future_timestamp),
        }

        response = client.post("/torque/upload", data=data)

        assert response.status_code in [200, 400]


# ============================================================================
# Calculation Edge Cases
# ============================================================================


class TestCalculationEdgeCases:
    """Test calculation functions with edge cases."""

    def test_efficiency_with_zero_kwh(self):
        """Calculate efficiency with zero kWh used."""
        from utils.calculations import calculate_kwh_per_mile

        result = calculate_kwh_per_mile(kwh_used=0.0, electric_miles=25.0)

        assert result is not None
        assert result == 0.0

    def test_mpg_with_fuel_level_increase(self):
        """MPG calculation when fuel level increases (refuel during trip)."""
        from utils.calculations import calculate_gas_mpg

        result = calculate_gas_mpg(
            start_odometer=1000.0,
            end_odometer=1100.0,
            start_fuel_level=30.0,
            end_fuel_level=90.0,  # Refueled!
        )

        # Should detect refuel and return None
        assert result is None

    def test_smooth_fuel_level_with_single_value(self):
        """Smooth fuel level with only one reading."""
        from utils.calculations import smooth_fuel_level

        result = smooth_fuel_level([75.0])

        assert result == 75.0

    def test_smooth_fuel_level_with_all_identical(self):
        """Smooth fuel level with all identical values."""
        from utils.calculations import smooth_fuel_level

        result = smooth_fuel_level([50.0, 50.0, 50.0, 50.0, 50.0])

        assert result == 50.0

    def test_detect_gas_mode_with_alternating_rpm(self):
        """Gas mode detection with alternating RPM values."""
        from utils.calculations import detect_gas_mode_entry

        points = []
        for i in range(20):
            points.append({
                "engine_rpm": 1500 if i % 2 == 0 else 0,  # Alternating
                "state_of_charge": 15.0,
                "odometer_miles": 50000 + i,
            })

        result = detect_gas_mode_entry(points)

        # Should require sustained RPM, not alternating
        assert result is None or result.get("rpm_sustained", False) is False
