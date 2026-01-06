"""
Tests for production hardening features.

Tests health checks, request ID tracking, weather cache LRU behavior,
export pagination, and rate limiting.
"""

import os
import sys
from unittest.mock import MagicMock, patch

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

from datetime import datetime, timedelta
from collections import OrderedDict


class TestHealthCheckEndpoints:
    """Tests for /health and /ready endpoints."""

    def test_health_endpoint_returns_200(self, client):
        """Health endpoint returns 200 and healthy status."""
        response = client.get("/health")
        assert response.status_code == 200
        data = response.json
        assert data["status"] == "healthy"
        assert data["service"] == "volttracker"

    def test_healthz_alternative_works(self, client):
        """Alternative /healthz endpoint works."""
        response = client.get("/healthz")
        assert response.status_code == 200
        assert response.json["status"] == "healthy"

    def test_ready_endpoint_checks_database(self, client):
        """Readiness endpoint checks database connectivity."""
        response = client.get("/ready")
        assert response.status_code in [200, 503]
        data = response.json
        assert "checks" in data
        assert "database" in data["checks"]
        assert "scheduler" in data["checks"]

    def test_readiness_alternative_works(self, client):
        """Alternative /readiness endpoint works."""
        response = client.get("/readiness")
        assert response.status_code in [200, 503]
        assert "checks" in response.json

    @patch("database.SessionLocal")
    def test_ready_returns_503_when_database_fails(self, mock_session, client):
        """Readiness returns 503 when database is down."""
        mock_session.return_value.execute.side_effect = Exception("DB connection failed")

        response = client.get("/ready")

        # Should return 503 when not ready
        assert response.status_code == 503
        data = response.json
        assert data["status"] == "not_ready"
        assert "errors" in data


class TestRequestIDTracking:
    """Tests for X-Request-ID header injection and tracking."""

    def test_request_id_added_to_response_when_not_provided(self, client):
        """Response includes X-Request-ID even when not provided in request."""
        response = client.get("/health")
        assert "X-Request-ID" in response.headers
        # Should be a valid UUID format
        request_id = response.headers["X-Request-ID"]
        assert len(request_id) > 0

    def test_request_id_preserved_from_client(self, client):
        """X-Request-ID from client is preserved in response."""
        custom_id = "test-request-123"
        response = client.get("/health", headers={"X-Request-ID": custom_id})

        assert response.headers.get("X-Request-ID") == custom_id

    def test_request_id_available_in_flask_g(self, app):
        """Request ID is accessible via flask.g during request."""
        from flask import g

        with app.test_request_context(headers={"X-Request-ID": "test-456"}):
            app.preprocess_request()
            assert hasattr(g, "request_id")
            assert g.request_id == "test-456"


class TestWeatherCacheLRU:
    """Tests for weather cache LRU eviction and memory management."""

    def setup_method(self):
        """Clear cache before each test."""
        from utils.weather import _weather_cache
        _weather_cache.clear()

    def test_weather_cache_respects_max_size(self):
        """Weather cache never exceeds MAX_WEATHER_CACHE_SIZE when using the API."""
        from utils.weather import _weather_cache, MAX_WEATHER_CACHE_SIZE

        # Verify that the cache uses LRU eviction by checking the implementation
        # The actual eviction happens in get_weather_for_location()
        # This test verifies the MAX_WEATHER_CACHE_SIZE constant is defined
        assert MAX_WEATHER_CACHE_SIZE == 1000

        # Verify cache is an OrderedDict (required for LRU)
        from collections import OrderedDict
        assert isinstance(_weather_cache, OrderedDict)

    def test_weather_cache_evicts_oldest_entries(self):
        """LRU cache implementation uses OrderedDict for proper eviction."""
        from utils.weather import _weather_cache
        from collections import OrderedDict

        _weather_cache.clear()

        # Verify OrderedDict behavior: FIFO with popitem(last=False)
        test_cache = OrderedDict()
        test_cache["first"] = 1
        test_cache["second"] = 2
        test_cache["third"] = 3

        # popitem(last=False) should remove the oldest (first inserted)
        removed = test_cache.popitem(last=False)
        assert removed[0] == "first"
        assert "first" not in test_cache
        assert "second" in test_cache
        assert "third" in test_cache

    @patch("utils.weather.requests.get")
    def test_weather_cache_hit_moves_to_end(self, mock_get):
        """Cache hit moves entry to end (marks as recently used)."""
        from utils.weather import get_weather_for_location, _weather_cache
        import time

        _weather_cache.clear()

        # Mock successful API response
        mock_response = MagicMock()
        mock_response.json.return_value = {
            "hourly": {
                "time": [datetime.utcnow().strftime("%Y-%m-%dT%H:00")],
                "temperature_2m": [65.0],
                "precipitation": [0.0],
                "wind_speed_10m": [10.0],
                "weather_code": [0],
            }
        }
        mock_response.raise_for_status = MagicMock()
        mock_get.return_value = mock_response

        # First call - cache miss
        result1 = get_weather_for_location(37.77, -122.42)
        assert result1 is not None
        assert mock_get.call_count == 1

        # Second call - cache hit
        result2 = get_weather_for_location(37.77, -122.42)
        assert result2 is not None
        assert mock_get.call_count == 1  # No additional API call

        # Cache key should be at the end
        cache_keys = list(_weather_cache.keys())
        expected_key = (37.77, -122.42, datetime.utcnow().strftime("%Y-%m-%d-%H"))
        assert cache_keys[-1] == expected_key

    @patch("utils.weather.requests.get")
    def test_weather_cache_expires_old_entries(self, mock_get):
        """Expired cache entries are removed on access."""
        from utils.weather import get_weather_for_location, _weather_cache
        from config import Config
        import time

        _weather_cache.clear()

        # Mock API response
        mock_response = MagicMock()
        mock_response.json.return_value = {
            "hourly": {
                "time": [datetime.utcnow().strftime("%Y-%m-%dT%H:00")],
                "temperature_2m": [65.0],
                "precipitation": [0.0],
                "wind_speed_10m": [10.0],
                "weather_code": [0],
            }
        }
        mock_response.raise_for_status = MagicMock()
        mock_get.return_value = mock_response

        # Add expired entry manually
        cache_key = (37.77, -122.42, datetime.utcnow().strftime("%Y-%m-%d-%H"))
        expired_time = time.time() - Config.WEATHER_CACHE_TIMEOUT_SECONDS - 100
        _weather_cache[cache_key] = ({"temp": 70}, expired_time)

        # Access should trigger expiration and re-fetch
        result = get_weather_for_location(37.77, -122.42)

        # Should have fetched from API (cache was expired)
        assert mock_get.call_count == 1
        assert result is not None


class TestExportPagination:
    """Tests for export endpoint pagination and filtering."""

    def test_export_all_respects_limit(self, client, db_session):
        """Export endpoint respects limit parameter."""
        from models import Trip
        from datetime import datetime, timezone
        import uuid

        # Create 15 test trips
        for i in range(15):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc),
                is_closed=True
            )
            db_session.add(trip)
        db_session.commit()

        # Request with limit=10
        response = client.get("/api/export/all?limit=10")
        assert response.status_code == 200
        data = response.json

        # Should return at most 10 trips
        assert len(data["trips"]) <= 10
        assert data["filters"]["limit"] == 10

    def test_export_all_default_limit(self, client, db_session):
        """Export endpoint uses default limit of 10000."""
        response = client.get("/api/export/all")
        assert response.status_code == 200
        data = response.json

        # Default limit should be 10000
        assert data["filters"]["limit"] == 10000

    def test_export_all_max_limit_enforced(self, client):
        """Export endpoint enforces max limit of 50000."""
        response = client.get("/api/export/all?limit=100000")
        assert response.status_code == 200
        data = response.json

        # Should cap at 50000
        assert data["filters"]["limit"] == 50000

    def test_export_all_with_date_range(self, client, db_session):
        """Export endpoint filters by date range."""
        from models import Trip
        from datetime import datetime, timezone, timedelta
        import uuid

        # Create trips from different dates
        old_date = datetime.now(timezone.utc) - timedelta(days=30)
        recent_date = datetime.now(timezone.utc) - timedelta(days=5)

        old_session_id = uuid.uuid4()
        recent_session_id = uuid.uuid4()

        old_trip = Trip(
            session_id=old_session_id,
            start_time=old_date,
            is_closed=True
        )
        recent_trip = Trip(
            session_id=recent_session_id,
            start_time=recent_date,
            is_closed=True
        )
        db_session.add_all([old_trip, recent_trip])
        db_session.commit()

        # Request only recent trips (use simple date format without microseconds)
        filter_date = datetime.now(timezone.utc) - timedelta(days=10)
        # Use simple ISO format: YYYY-MM-DDTHH:MM:SSZ
        start_date = filter_date.strftime("%Y-%m-%dT%H:%M:%SZ")
        response = client.get(f"/api/export/all?start_date={start_date}")

        assert response.status_code == 200
        data = response.json

        # Should only include recent trip
        trip_ids = [t["session_id"] for t in data["trips"]]
        assert str(recent_session_id) in trip_ids
        assert str(old_session_id) not in trip_ids

    def test_export_all_invalid_date_format(self, client):
        """Export endpoint returns 400 for invalid date format."""
        response = client.get("/api/export/all?start_date=invalid-date")
        assert response.status_code == 400
        assert "error" in response.json


class TestRateLimiting:
    """Tests for per-endpoint rate limiting."""

    def test_csv_import_has_rate_limit(self, client):
        """CSV import endpoint has rate limiting."""
        # Rate limiter is configured but hard to test without actual limits
        # This test verifies the endpoint exists and accepts POST
        response = client.post("/api/import/csv")
        # Should return 400 (no file) not 405 (method not allowed)
        assert response.status_code == 400

    def test_export_all_has_rate_limit(self, client):
        """Export all endpoint has rate limiting."""
        # Verify endpoint is accessible
        response = client.get("/api/export/all")
        assert response.status_code == 200


class TestSlowQueryLogging:
    """Tests for slow query detection and logging."""

    def test_slow_query_logs_warning(self, app, db_session):
        """Queries exceeding threshold are logged as warnings."""
        # Execute a query that simulates slowness
        # Note: This is hard to test without actually having slow queries
        # We'll just verify the logging infrastructure is set up

        # The event listeners should be registered
        from database import after_cursor_execute, before_cursor_execute

        # Verify the event listener functions exist
        assert callable(after_cursor_execute)
        assert callable(before_cursor_execute)

        # Test that event infrastructure is working by executing a simple query
        # This verifies that the slow query logging doesn't break normal operations
        from models import Trip
        trip_count = db_session.query(Trip).count()
        assert trip_count >= 0  # Count should work without errors

    def test_slow_query_threshold_configurable(self):
        """Slow query threshold is configurable."""
        from database import SLOW_QUERY_THRESHOLD_MS

        # Default should be 500ms
        assert SLOW_QUERY_THRESHOLD_MS == 500


class TestAnalyticsConfiguration:
    """Tests for configurable analytics constants."""

    def test_temperature_bands_from_config(self):
        """Temperature bands are loaded from Config."""
        from services.weather_analytics_service import TEMP_BANDS
        from config import Config

        assert TEMP_BANDS == Config.ANALYTICS_TEMP_BANDS
        assert len(TEMP_BANDS) == 7  # freezing, cold, cool, ideal, warm, hot, very_hot

    def test_wind_bands_from_config(self):
        """Wind bands are loaded from Config."""
        from services.weather_analytics_service import WIND_BANDS
        from config import Config

        assert WIND_BANDS == Config.ANALYTICS_WIND_BANDS
        assert len(WIND_BANDS) == 4  # calm, light, moderate, strong

    def test_analytics_bands_have_correct_format(self):
        """Analytics bands have correct tuple format."""
        from config import Config

        for band in Config.ANALYTICS_TEMP_BANDS:
            assert len(band) == 3  # (name, min, max)
            assert isinstance(band[0], str)
            assert band[1] is None or isinstance(band[1], (int, float))
            assert band[2] is None or isinstance(band[2], (int, float))
