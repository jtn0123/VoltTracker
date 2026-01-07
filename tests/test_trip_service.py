"""
Tests for trip service module.

Tests the trip finalization logic including:
- Basic trip metric calculations
- Gas mode detection and processing
- Electric efficiency calculations
- Weather fetching
- Edge cases like empty telemetry
"""

import uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

from models import TelemetryRaw, Trip
from services.trip_service import (
    calculate_electric_efficiency,
    calculate_trip_basics,
    fetch_trip_weather,
    finalize_trip,
    process_gas_mode,
)


class TestCalculateTripBasics:
    """Tests for calculate_trip_basics function."""

    def test_sets_end_time_from_last_telemetry(self, app, db_session):
        """End time should be set from the last telemetry point."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(hours=1),
            start_odometer=50000.0,
        )
        db_session.add(trip)
        db_session.flush()

        telemetry = [
            TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=30),
                odometer_miles=50010.0,
                ambient_temp_f=70.0,
            ),
            TelemetryRaw(
                session_id=session_id,
                timestamp=now,
                odometer_miles=50025.0,
                ambient_temp_f=72.0,
            ),
        ]
        for t in telemetry:
            db_session.add(t)
        db_session.flush()

        calculate_trip_basics(trip, telemetry)

        assert trip.end_time == telemetry[-1].timestamp
        assert trip.end_odometer == 50025.0

    def test_calculates_distance_miles(self, app, db_session):
        """Distance should be calculated from odometer difference."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(hours=1),
            start_odometer=50000.0,
        )
        db_session.add(trip)
        db_session.flush()

        telemetry = [
            TelemetryRaw(
                session_id=session_id,
                timestamp=now,
                odometer_miles=50025.0,
                ambient_temp_f=70.0,
            ),
        ]
        db_session.add(telemetry[0])
        db_session.flush()

        calculate_trip_basics(trip, telemetry)

        assert trip.distance_miles == 25.0

    def test_handles_empty_telemetry_list(self, app, db_session):
        """Empty telemetry list should not crash - returns early."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(hours=1),
            start_odometer=50000.0,
        )
        db_session.add(trip)
        db_session.flush()

        # This should not raise IndexError - should return early
        calculate_trip_basics(trip, [])

        # Trip should be unchanged
        assert trip.end_time is None
        assert trip.end_odometer is None

    def test_calculates_average_temperature(self, app, db_session):
        """Average temperature should be calculated from telemetry."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(hours=1),
            start_odometer=50000.0,
        )
        db_session.add(trip)
        db_session.flush()

        telemetry = [
            TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=30),
                odometer_miles=50010.0,
                ambient_temp_f=60.0,
            ),
            TelemetryRaw(
                session_id=session_id,
                timestamp=now,
                odometer_miles=50020.0,
                ambient_temp_f=80.0,
            ),
        ]
        for t in telemetry:
            db_session.add(t)
        db_session.flush()

        calculate_trip_basics(trip, telemetry)

        assert trip.ambient_temp_avg_f == 70.0


class TestProcessGasMode:
    """Tests for process_gas_mode function."""

    def test_handles_empty_lists(self, app, db_session):
        """Empty telemetry/points lists should not crash."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now,
            start_odometer=50000.0,
        )
        db_session.add(trip)
        db_session.flush()

        # Should not raise any errors
        process_gas_mode(db_session, trip, [], [])

        # Trip should remain unchanged (gas_mode_entered defaults to False)
        assert trip.gas_mode_entered is False or trip.gas_mode_entered is None

    def test_detects_gas_mode_entry(self, app, db_session):
        """Gas mode entry should be detected when RPM > threshold and SOC < threshold."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(hours=1),
            start_odometer=50000.0,
        )
        db_session.add(trip)
        db_session.flush()

        # Create telemetry showing transition to gas mode
        telemetry = []
        points = []
        for i in range(10):
            t = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=(10 - i) * 5),
                odometer_miles=50000.0 + i * 2,
                state_of_charge=25.0 - i * 2 if i < 4 else 15.0,  # Drops to 15%
                engine_rpm=0 if i < 4 else 1200,  # Engine starts at point 4
                fuel_level_percent=80.0 - i * 0.5,
            )
            telemetry.append(t)
            points.append(t.to_dict())
            db_session.add(t)
        db_session.flush()

        process_gas_mode(db_session, trip, telemetry, points)

        assert trip.gas_mode_entered is True
        assert trip.soc_at_gas_transition is not None

    def test_all_electric_trip(self, app, db_session):
        """Trip without gas mode entry should have all electric miles."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(hours=1),
            start_odometer=50000.0,
            end_odometer=50020.0,
            distance_miles=20.0,
        )
        db_session.add(trip)
        db_session.flush()

        # All electric - no engine RPM, high SOC
        telemetry = []
        points = []
        for i in range(10):
            t = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=(10 - i) * 5),
                odometer_miles=50000.0 + i * 2,
                state_of_charge=80.0 - i * 2,  # Stays above threshold
                engine_rpm=0,
                fuel_level_percent=80.0,
            )
            telemetry.append(t)
            points.append(t.to_dict())
            db_session.add(t)
        db_session.flush()

        process_gas_mode(db_session, trip, telemetry, points)

        assert trip.gas_mode_entered is None or trip.gas_mode_entered is False
        assert trip.electric_miles == 20.0


class TestCalculateElectricEfficiency:
    """Tests for calculate_electric_efficiency function."""

    def test_calculates_kwh_for_electric_trip(self, app, db_session):
        """Should calculate kWh used for electric portion of trip."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(hours=1),
            electric_miles=15.0,
        )

        points = [
            {
                "timestamp": now - timedelta(hours=1),
                "state_of_charge": 80.0,
                "hv_battery_power_kw": 10.0,
            },
            {
                "timestamp": now,
                "state_of_charge": 50.0,
                "hv_battery_power_kw": 8.0,
            },
        ]

        calculate_electric_efficiency(trip, points)

        assert trip.electric_kwh_used is not None

    def test_skips_short_electric_segment(self, app, db_session):
        """Should not calculate efficiency for segments < 0.5 miles."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(minutes=5),
            electric_miles=0.3,  # Too short
        )

        points = [
            {"timestamp": now, "state_of_charge": 80.0},
        ]

        calculate_electric_efficiency(trip, points)

        assert trip.electric_kwh_used is None


class TestFetchTripWeather:
    """Tests for fetch_trip_weather function."""

    @patch("services.trip_service.get_weather_for_location")
    def test_fetches_weather_for_gps_point(self, mock_weather, app, db_session):
        """Should fetch weather for the first GPS point."""
        mock_weather.return_value = {
            "temperature_f": 72.0,
            "precipitation_in": 0.0,
            "wind_speed_mph": 10.0,
            "conditions": "Clear",
        }

        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)
        end_time = now + timedelta(minutes=10)

        trip = Trip(
            session_id=session_id,
            start_time=now,
            end_time=end_time,
        )

        points = [
            {"latitude": 37.7749, "longitude": -122.4194, "timestamp": now.isoformat()},
        ]

        fetch_trip_weather(trip, points)

        assert trip.weather_temp_f == 72.0
        assert trip.weather_conditions == "Clear"

    def test_handles_no_gps_points(self, app, db_session):
        """Should not crash if no GPS data available."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now,
        )

        points = [
            {"timestamp": now},  # No GPS
        ]

        # Should not raise
        fetch_trip_weather(trip, points)

        assert trip.weather_temp_f is None

    @patch("services.trip_service.get_weather_for_location")
    def test_handles_weather_api_error(self, mock_weather, app, db_session):
        """Should handle weather API errors gracefully."""
        mock_weather.side_effect = Exception("API Error")

        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now,
        )

        points = [
            {"latitude": 37.7749, "longitude": -122.4194, "timestamp": now},
        ]

        # Should not raise
        fetch_trip_weather(trip, points)

        assert trip.weather_temp_f is None


class TestFinalizeTrip:
    """Tests for finalize_trip function."""

    def test_handles_trip_with_no_telemetry(self, app, db_session):
        """Trip with no telemetry should be marked closed without crashing."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now,
            start_odometer=50000.0,
            is_closed=False,
        )
        db_session.add(trip)
        db_session.commit()

        finalize_trip(db_session, trip)

        assert trip.is_closed is True

    def test_processes_complete_trip(self, app, db_session):
        """Complete trip should have all metrics calculated."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(hours=1),
            start_odometer=50000.0,
            is_closed=False,
        )
        db_session.add(trip)
        db_session.flush()

        # Add telemetry
        for i in range(10):
            t = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=(10 - i) * 5),
                odometer_miles=50000.0 + i * 2.5,
                state_of_charge=80.0 - i * 2,
                engine_rpm=0,
                ambient_temp_f=70.0,
            )
            db_session.add(t)
        db_session.commit()

        finalize_trip(db_session, trip)

        assert trip.is_closed is True
        assert trip.end_time is not None
        assert trip.end_odometer is not None
        assert trip.distance_miles is not None


class TestZeroSOCEdgeCases:
    """Tests for edge cases involving zero SOC values."""

    def test_process_gas_mode_with_zero_soc_does_not_crash(self, app, db_session):
        """Gas mode processing should not crash when SOC is exactly 0."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(hours=1),
            start_odometer=50000.0,
        )
        db_session.add(trip)
        db_session.flush()

        # Telemetry with SOC at exactly 0
        telemetry = []
        points = []
        for i in range(5):
            t = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=(5 - i) * 10),
                odometer_miles=50000.0 + i * 5,
                state_of_charge=0.0,  # Zero SOC
                engine_rpm=1200,  # Gas mode
                fuel_level_percent=80.0 - i,
            )
            telemetry.append(t)
            points.append(t.to_dict())
            db_session.add(t)
        db_session.flush()

        # Should not crash - this is the key test
        # Note: Gas mode detection requires SOC to transition BELOW threshold,
        # so constant 0% SOC may not trigger gas mode detection
        process_gas_mode(db_session, trip, telemetry, points)

        # The function should complete without error - that's the main assertion
        # Gas detection depends on SOC transition, not just low SOC value
