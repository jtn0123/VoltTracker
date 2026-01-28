"""
Tests for weather background jobs.

Note: These jobs use local imports inside functions, which makes testing
more complex. We use sys.modules manipulation to properly mock dependencies.
"""

import pytest
from unittest.mock import MagicMock, patch, PropertyMock
from datetime import datetime, timezone
import uuid
import sys


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

        # Mock the weather service module before importing
        mock_weather_service = MagicMock()
        mock_weather_service.fetch_and_store_weather = MagicMock(return_value=[{"temp": 72}])

        with patch.dict(sys.modules, {"services.weather_service": mock_weather_service}):
            # Re-import to get fresh module with mocked dependencies
            import importlib
            import jobs.weather_jobs as weather_jobs

            importlib.reload(weather_jobs)

            result = weather_jobs.fetch_weather_for_trip(trip_id, db_session)

        assert result["status"] == "success"
        assert result["trip_id"] == trip_id
        assert result["weather_points"] == 1

    def test_fetch_weather_for_trip_not_found(self, app, db_session):
        """Test weather fetch for non-existent trip."""
        mock_weather_service = MagicMock()

        with patch.dict(sys.modules, {"services.weather_service": mock_weather_service}):
            import importlib
            import jobs.weather_jobs as weather_jobs

            importlib.reload(weather_jobs)

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

        mock_weather_service = MagicMock()
        mock_weather_service.fetch_and_store_weather = MagicMock(side_effect=Exception("Weather API error"))

        with patch.dict(sys.modules, {"services.weather_service": mock_weather_service}):
            import importlib
            import jobs.weather_jobs as weather_jobs

            importlib.reload(weather_jobs)

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

        mock_weather_service = MagicMock()
        mock_weather_service.fetch_and_store_weather = MagicMock(return_value=None)

        with patch.dict(sys.modules, {"services.weather_service": mock_weather_service}):
            import importlib
            import jobs.weather_jobs as weather_jobs

            importlib.reload(weather_jobs)

            result = weather_jobs.fetch_weather_for_trip(trip_id, db_session)

        assert result["status"] == "success"
        assert result["weather_points"] == 0


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

        mock_weather_service = MagicMock()
        mock_weather_service.fetch_and_store_weather = MagicMock(return_value=[{"temp": 72}])

        with patch.dict(sys.modules, {"services.weather_service": mock_weather_service}):
            import importlib
            import jobs.weather_jobs as weather_jobs

            importlib.reload(weather_jobs)

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

        mock_weather_service = MagicMock()
        mock_weather_service.fetch_and_store_weather = MagicMock(return_value=[{"temp": 72}])

        with patch.dict(sys.modules, {"services.weather_service": mock_weather_service}):
            import importlib
            import jobs.weather_jobs as weather_jobs

            importlib.reload(weather_jobs)

            # Include a non-existent trip ID
            result = weather_jobs.batch_fetch_weather([trips[0].id, 99999, trips[1].id])

        assert result["success"] == 2
        assert result["failed"] == 1

    def test_batch_fetch_weather_empty_list(self, app, db_session):
        """Test batch fetch with empty trip list."""
        mock_weather_service = MagicMock()

        with patch.dict(sys.modules, {"services.weather_service": mock_weather_service}):
            import importlib
            import jobs.weather_jobs as weather_jobs

            importlib.reload(weather_jobs)

            result = weather_jobs.batch_fetch_weather([])

        assert result["success"] == 0
        assert result["failed"] == 0
        assert len(result["trip_results"]) == 0


class TestFetchElevationForTrip:
    """Tests for fetch_elevation_for_trip function."""

    def test_fetch_elevation_for_trip_not_found(self, app, db_session):
        """Test elevation fetch for non-existent trip."""
        mock_weather_service = MagicMock()
        mock_elevation_service = MagicMock()
        mock_elevation_service.fetch_and_update_elevations = MagicMock(return_value=0)

        with patch.dict(
            sys.modules,
            {"services.weather_service": mock_weather_service, "services.elevation_service": mock_elevation_service},
        ):
            import importlib
            import jobs.weather_jobs as weather_jobs

            importlib.reload(weather_jobs)

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

        mock_weather_service = MagicMock()
        mock_elevation_service = MagicMock()
        mock_elevation_service.fetch_and_update_elevations = MagicMock(return_value=0)

        with patch.dict(
            sys.modules,
            {"services.weather_service": mock_weather_service, "services.elevation_service": mock_elevation_service},
        ):
            import importlib
            import jobs.weather_jobs as weather_jobs

            importlib.reload(weather_jobs)

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

        mock_weather_service = MagicMock()
        mock_elevation_service = MagicMock()
        mock_elevation_service.fetch_and_update_elevations = MagicMock(return_value=5)

        with patch.dict(
            sys.modules,
            {"services.weather_service": mock_weather_service, "services.elevation_service": mock_elevation_service},
        ):
            import importlib
            import jobs.weather_jobs as weather_jobs

            importlib.reload(weather_jobs)

            result = weather_jobs.fetch_elevation_for_trip(trip_id, db_session)

        assert result["status"] == "success"
        assert result["trip_id"] == trip_id
        assert result["elevation_points"] == 5


class TestBatchFetchWeatherAndElevation:
    """Tests for batch_fetch_weather_and_elevation function - integration tests."""

    def test_batch_fetch_empty_list(self, app, db_session):
        """Test batch fetch with empty list."""
        mock_weather_service = MagicMock()
        mock_elevation_service = MagicMock()

        with patch.dict(
            sys.modules,
            {"services.weather_service": mock_weather_service, "services.elevation_service": mock_elevation_service},
        ):
            import importlib
            import jobs.weather_jobs as weather_jobs

            importlib.reload(weather_jobs)

            result = weather_jobs.batch_fetch_weather_and_elevation([])

        assert result["weather_success"] == 0
        assert result["weather_failed"] == 0
        assert result["elevation_success"] == 0
        assert result["elevation_failed"] == 0
        assert len(result["trip_results"]) == 0
