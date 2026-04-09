"""
Tests for weather background jobs.

Imports are now plain module-level — the module fetches weather via
``utils.weather.get_weather_for_location`` and elevation via
``services.elevation_service.fetch_and_update_elevations``, both of which
exist in the codebase. We patch the *names re-exported into
jobs.weather_jobs* with ``mock.patch`` instead of the previous
``sys.modules`` + ``importlib.reload`` workaround that was masking
JTN-453's ImportError.
"""

from unittest.mock import patch
from datetime import datetime, timezone
import uuid

import jobs.weather_jobs as weather_jobs


class TestFetchWeatherForTrip:
    """Tests for fetch_weather_for_trip function."""

    def test_fetch_weather_for_trip_success(self, app, db_session):
        """Test successful weather fetch for a trip."""
        from models import Trip

        # Create a test trip
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            start_soc=80.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()
        trip_id = trip.id

        with patch.object(
            weather_jobs,
            "_fetch_weather_for_trip_data",
            return_value=[{"temperature_f": 72}],
        ):
            result = weather_jobs.fetch_weather_for_trip(trip_id, db_session)

        assert result["status"] == "success"
        assert result["trip_id"] == trip_id
        assert result["weather_points"] == 1

    def test_fetch_weather_for_trip_not_found(self, app, db_session):
        """Test weather fetch for non-existent trip."""
        result = weather_jobs.fetch_weather_for_trip(99999, db_session)

        assert result["status"] == "failed"
        assert result["reason"] == "trip_not_found"

    def test_fetch_weather_for_trip_handles_exception(self, app, db_session):
        """Test that exceptions are handled gracefully."""
        from models import Trip

        # Create a test trip
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            start_soc=80.0,
        )
        db_session.add(trip)
        db_session.commit()
        trip_id = trip.id

        with patch.object(
            weather_jobs,
            "_fetch_weather_for_trip_data",
            side_effect=Exception("Weather API error"),
        ):
            result = weather_jobs.fetch_weather_for_trip(trip_id, db_session)

        assert result["status"] == "failed"
        assert "error" in result
        assert "Weather API error" in result["error"]

    def test_fetch_weather_for_trip_no_weather_data(self, app, db_session):
        """Test handling when no weather data is returned."""
        from models import Trip

        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            start_soc=80.0,
        )
        db_session.add(trip)
        db_session.commit()
        trip_id = trip.id

        with patch.object(weather_jobs, "_fetch_weather_for_trip_data", return_value=None):
            result = weather_jobs.fetch_weather_for_trip(trip_id, db_session)

        assert result["status"] == "success"
        assert result["weather_points"] == 0


class TestFetchWeatherForTripDataHelper:
    """Tests for the inline _fetch_weather_for_trip_data helper.

    Covers the path that the previous tests skipped entirely by mocking the
    helper out — verifies the helper actually queries telemetry and persists
    weather columns onto the trip.
    """

    def test_helper_returns_empty_when_no_gps(self, app, db_session):
        from models import Trip

        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            start_soc=80.0,
        )
        db_session.add(trip)
        db_session.commit()

        with patch("jobs.weather_jobs.get_weather_for_location") as mock_get:
            result = weather_jobs._fetch_weather_for_trip_data(db_session, trip)

        assert result == []
        mock_get.assert_not_called()

    def test_helper_persists_weather_columns(self, app, db_session):
        from models import Trip, TelemetryRaw

        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            start_soc=80.0,
        )
        db_session.add(trip)
        db_session.flush()

        db_session.add(
            TelemetryRaw(
                session_id=session_id,
                timestamp=datetime.now(timezone.utc),
                latitude=37.7749,
                longitude=-122.4194,
                speed_mph=45.0,
            )
        )
        db_session.commit()

        sample = {
            "temperature_f": 68.0,
            "precipitation_in": 0.0,
            "wind_speed_mph": 5.0,
            "conditions": "Clear",
        }
        with patch("jobs.weather_jobs.get_weather_for_location", return_value=sample):
            result = weather_jobs._fetch_weather_for_trip_data(db_session, trip)

        assert result == [sample]
        assert trip.weather_temp_f == 68.0
        assert trip.weather_precipitation_in == 0.0
        assert trip.weather_wind_mph == 5.0
        assert trip.weather_conditions == "Clear"

    def test_helper_returns_empty_when_api_returns_none(self, app, db_session):
        from models import Trip, TelemetryRaw

        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            start_soc=80.0,
        )
        db_session.add(trip)
        db_session.flush()
        db_session.add(
            TelemetryRaw(
                session_id=session_id,
                timestamp=datetime.now(timezone.utc),
                latitude=37.7749,
                longitude=-122.4194,
            )
        )
        db_session.commit()

        with patch("jobs.weather_jobs.get_weather_for_location", return_value=None):
            result = weather_jobs._fetch_weather_for_trip_data(db_session, trip)

        assert result == []
        assert trip.weather_temp_f is None


class TestBatchFetchWeather:
    """Tests for batch_fetch_weather function."""

    def test_batch_fetch_weather_all_success(self, app, db_session):
        """Test batch fetch when all trips succeed."""
        from models import Trip

        # Create test trips
        trips = []
        for i in range(3):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc),
                start_odometer=50000.0 + i * 100,
                start_soc=80.0,
            )
            db_session.add(trip)
            trips.append(trip)
        db_session.commit()

        with patch.object(
            weather_jobs,
            "_fetch_weather_for_trip_data",
            return_value=[{"temperature_f": 72}],
        ):
            result = weather_jobs.batch_fetch_weather([t.id for t in trips])

        assert result["success"] == 3
        assert result["failed"] == 0
        assert len(result["trip_results"]) == 3

    def test_batch_fetch_weather_partial_failure(self, app, db_session):
        """Test batch fetch with some failures."""
        from models import Trip

        # Create 2 trips, but we'll pass 3 IDs (one non-existent)
        trips = []
        for i in range(2):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc),
                start_odometer=50000.0 + i * 100,
                start_soc=80.0,
            )
            db_session.add(trip)
            trips.append(trip)
        db_session.commit()

        with patch.object(
            weather_jobs,
            "_fetch_weather_for_trip_data",
            return_value=[{"temperature_f": 72}],
        ):
            # Include a non-existent trip ID
            result = weather_jobs.batch_fetch_weather([trips[0].id, 99999, trips[1].id])

        assert result["success"] == 2
        assert result["failed"] == 1

    def test_batch_fetch_weather_empty_list(self, app, db_session):
        """Test batch fetch with empty trip list."""
        result = weather_jobs.batch_fetch_weather([])

        assert result["success"] == 0
        assert result["failed"] == 0
        assert len(result["trip_results"]) == 0


class TestFetchElevationForTrip:
    """Tests for fetch_elevation_for_trip function."""

    def test_fetch_elevation_for_trip_not_found(self, app, db_session):
        """Test elevation fetch for non-existent trip."""
        result = weather_jobs.fetch_elevation_for_trip(99999, db_session)

        assert result["status"] == "failed"
        assert result["reason"] == "trip_not_found"

    def test_fetch_elevation_for_trip_no_gps_data(self, app, db_session):
        """Test elevation fetch when trip has no GPS data."""
        from models import Trip

        # Create a trip without GPS telemetry
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            start_soc=80.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()
        trip_id = trip.id

        result = weather_jobs.fetch_elevation_for_trip(trip_id, db_session)

        assert result["status"] == "skipped"
        assert result["reason"] == "no_gps_data"

    def test_fetch_elevation_for_trip_success(self, app, db_session):
        """Test successful elevation fetch for a trip with GPS data."""
        from models import Trip, TelemetryRaw

        # Create a trip
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            start_soc=80.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.flush()

        # Add telemetry with GPS coordinates
        for i in range(5):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=datetime.now(timezone.utc),
                latitude=37.7749 + i * 0.001,
                longitude=-122.4194 + i * 0.001,
                speed_mph=45.0,
            )
            db_session.add(telemetry)
        db_session.commit()
        trip_id = trip.id

        with patch.object(weather_jobs, "fetch_and_update_elevations", return_value=5):
            result = weather_jobs.fetch_elevation_for_trip(trip_id, db_session)

        assert result["status"] == "success"
        assert result["trip_id"] == trip_id
        assert result["elevation_points"] == 5


class TestBatchFetchWeatherAndElevation:
    """Tests for batch_fetch_weather_and_elevation function - integration tests."""

    def test_batch_fetch_empty_list(self, app, db_session):
        """Test batch fetch with empty list."""
        result = weather_jobs.batch_fetch_weather_and_elevation([])

        assert result["weather_success"] == 0
        assert result["weather_failed"] == 0
        assert result["elevation_success"] == 0
        assert result["elevation_failed"] == 0
        assert len(result["trip_results"]) == 0
