"""
Integration tests for external API interactions.

Tests interactions with:
- Open-Meteo weather API
- Open-Elevation API
- Retry logic and error handling
- Rate limiting and caching

Uses `responses` library to mock HTTP requests without hitting real APIs.
"""

import pytest
import responses
import json
from datetime import datetime, timezone
from unittest.mock import patch


# ============================================================================
# Weather API Integration Tests
# ============================================================================

class TestWeatherAPIIntegration:
    """Test weather API interactions with mocked responses."""

    @responses.activate
    def test_weather_api_success(self):
        """Test successful weather API call."""
        # Mock Open-Meteo API response
        responses.add(
            responses.GET,
            "https://api.open-meteo.com/v1/forecast",
            json={
                "latitude": 37.7749,
                "longitude": -122.4194,
                "hourly": {
                    "time": ["2024-01-15T14:00"],
                    "temperature_2m": [15.5],
                    "relative_humidity_2m": [65],
                    "wind_speed_10m": [12.5],
                    "precipitation": [0.0]
                }
            },
            status=200
        )

        # Test weather fetching (would need to import actual service)
        # This is a template - adjust imports based on actual code
        from services import weather_service

        result = weather_service.fetch_weather(37.7749, -122.4194, datetime.now(timezone.utc))

        assert result is not None
        # Verify API was called once
        assert len(responses.calls) == 1

    @responses.activate
    def test_weather_api_timeout_retry(self):
        """Test weather API timeout with retry logic."""
        # First call times out
        responses.add(
            responses.GET,
            "https://api.open-meteo.com/v1/forecast",
            body=Exception("Connection timeout"),
            status=408
        )

        # Second call succeeds
        responses.add(
            responses.GET,
            "https://api.open-meteo.com/v1/forecast",
            json={
                "latitude": 37.7749,
                "longitude": -122.4194,
                "hourly": {
                    "time": ["2024-01-15T14:00"],
                    "temperature_2m": [15.5]
                }
            },
            status=200
        )

        from services import weather_service

        # Should retry and eventually succeed
        result = weather_service.fetch_weather_with_retry(
            37.7749, -122.4194,
            datetime.now(timezone.utc),
            max_retries=2
        )

        assert result is not None
        # Should have made 2 calls (1 fail + 1 success)
        assert len(responses.calls) == 2

    @responses.activate
    def test_weather_api_rate_limiting(self):
        """Test weather API rate limiting (429 response)."""
        # Return 429 Too Many Requests
        responses.add(
            responses.GET,
            "https://api.open-meteo.com/v1/forecast",
            json={"error": "Too many requests"},
            status=429,
            headers={"Retry-After": "60"}
        )

        from services import weather_service
        from exceptions import WeatherAPIError

        # Should handle rate limiting gracefully
        with pytest.raises(WeatherAPIError) as exc_info:
            weather_service.fetch_weather(37.7749, -122.4194, datetime.now(timezone.utc))

        assert "rate limit" in str(exc_info.value).lower() or "429" in str(exc_info.value)

    @responses.activate
    def test_weather_api_invalid_coordinates(self):
        """Test weather API with invalid coordinates."""
        responses.add(
            responses.GET,
            "https://api.open-meteo.com/v1/forecast",
            json={"error": "Invalid coordinates"},
            status=400
        )

        from services import weather_service
        from exceptions import WeatherAPIError

        with pytest.raises(WeatherAPIError):
            # Invalid latitude (>90)
            weather_service.fetch_weather(100.0, -122.4194, datetime.now(timezone.utc))

    @responses.activate
    def test_weather_api_caching(self):
        """Test that weather API results are cached."""
        # Setup mock response
        responses.add(
            responses.GET,
            "https://api.open-meteo.com/v1/forecast",
            json={
                "latitude": 37.7749,
                "longitude": -122.4194,
                "hourly": {
                    "time": ["2024-01-15T14:00"],
                    "temperature_2m": [15.5]
                }
            },
            status=200
        )

        from services import weather_service

        # First call - should hit API
        result1 = weather_service.fetch_weather_cached(
            37.7749, -122.4194,
            datetime.now(timezone.utc)
        )

        # Second call with same params - should use cache
        result2 = weather_service.fetch_weather_cached(
            37.7749, -122.4194,
            datetime.now(timezone.utc)
        )

        assert result1 == result2
        # Should only have made 1 API call (second was cached)
        assert len(responses.calls) == 1


# ============================================================================
# Elevation API Integration Tests
# ============================================================================

class TestElevationAPIIntegration:
    """Test elevation API interactions."""

    @responses.activate
    def test_elevation_api_success(self):
        """Test successful elevation API call."""
        responses.add(
            responses.POST,
            "https://api.open-elevation.com/api/v1/lookup",
            json={
                "results": [
                    {"latitude": 37.7749, "longitude": -122.4194, "elevation": 52}
                ]
            },
            status=200
        )

        from services import elevation_service

        elevation = elevation_service.fetch_elevation(37.7749, -122.4194)

        assert elevation == 52
        assert len(responses.calls) == 1

    @responses.activate
    def test_elevation_api_batch_request(self):
        """Test batch elevation API request."""
        # Mock batch response
        responses.add(
            responses.POST,
            "https://api.open-elevation.com/api/v1/lookup",
            json={
                "results": [
                    {"latitude": 37.7749, "longitude": -122.4194, "elevation": 52},
                    {"latitude": 37.7750, "longitude": -122.4195, "elevation": 53},
                    {"latitude": 37.7751, "longitude": -122.4196, "elevation": 54}
                ]
            },
            status=200
        )

        from services import elevation_service

        coordinates = [
            (37.7749, -122.4194),
            (37.7750, -122.4195),
            (37.7751, -122.4196)
        ]

        elevations = elevation_service.fetch_elevations_batch(coordinates)

        assert len(elevations) == 3
        assert elevations[0] == 52
        assert elevations[2] == 54

        # Should make only 1 API call for batch
        assert len(responses.calls) == 1

    @responses.activate
    def test_elevation_api_timeout(self):
        """Test elevation API timeout handling."""
        responses.add(
            responses.POST,
            "https://api.open-elevation.com/api/v1/lookup",
            body=Exception("Connection timeout")
        )

        from services import elevation_service

        # Should handle timeout gracefully
        elevation = elevation_service.fetch_elevation_safe(37.7749, -122.4194, default=None)

        assert elevation is None

    @responses.activate
    def test_elevation_api_sampling(self):
        """Test elevation API with coordinate sampling (reduce API calls)."""
        # Mock response for sampled coordinates
        responses.add(
            responses.POST,
            "https://api.open-elevation.com/api/v1/lookup",
            json={
                "results": [
                    {"latitude": 37.7749, "longitude": -122.4194, "elevation": 52},
                    {"latitude": 37.7755, "longitude": -122.4200, "elevation": 55}
                ]
            },
            status=200
        )

        from services import elevation_service

        # Large list of coordinates
        all_coordinates = [(37.7749 + i*0.0001, -122.4194 + i*0.0001) for i in range(100)]

        # Sample and fetch (e.g., every 10th point)
        elevations = elevation_service.fetch_elevations_sampled(
            all_coordinates,
            sample_rate=0.1  # 10% sampling
        )

        # Should have sampled ~10 points but API called with fewer (batched)
        assert len(responses.calls) >= 1


# ============================================================================
# Combined Integration Tests
# ============================================================================

class TestCombinedAPIIntegration:
    """Test scenarios involving multiple API calls."""

    @responses.activate
    def test_trip_finalization_with_apis(self, mock_db):
        """Test trip finalization that calls both weather and elevation APIs."""
        # Mock weather API
        responses.add(
            responses.GET,
            "https://api.open-meteo.com/v1/forecast",
            json={
                "latitude": 37.7749,
                "longitude": -122.4194,
                "hourly": {
                    "time": ["2024-01-15T14:00"],
                    "temperature_2m": [15.5],
                    "wind_speed_10m": [10]
                }
            },
            status=200
        )

        # Mock elevation API
        responses.add(
            responses.POST,
            "https://api.open-elevation.com/api/v1/lookup",
            json={
                "results": [
                    {"latitude": 37.7749, "longitude": -122.4194, "elevation": 52}
                ]
            },
            status=200
        )

        from services.trip_service import finalize_trip

        # Create test trip with GPS data
        trip = create_test_trip_with_gps(mock_db)

        # Finalize trip - should fetch weather and elevation
        result = finalize_trip(mock_db, trip)

        assert result is not None
        # Should have called both APIs
        assert len(responses.calls) == 2

    @responses.activate
    def test_graceful_degradation_on_api_failures(self, mock_db):
        """Test that trip finalization succeeds even if external APIs fail."""
        # Mock weather API failure
        responses.add(
            responses.GET,
            "https://api.open-meteo.com/v1/forecast",
            json={"error": "Service unavailable"},
            status=503
        )

        # Mock elevation API failure
        responses.add(
            responses.POST,
            "https://api.open-elevation.com/api/v1/lookup",
            json={"error": "Service unavailable"},
            status=503
        )

        from services.trip_service import finalize_trip

        trip = create_test_trip_with_gps(mock_db)

        # Should still finalize trip successfully (without weather/elevation data)
        result = finalize_trip(mock_db, trip, fail_on_api_error=False)

        assert result is not None
        # Trip should be marked as closed even without external data
        assert trip.is_closed is True


# ============================================================================
# API Retry Logic Tests
# ============================================================================

class TestAPIRetryLogic:
    """Test retry logic for API calls."""

    @responses.activate
    def test_exponential_backoff_retry(self):
        """Test exponential backoff retry strategy."""
        # First 2 calls fail
        responses.add(responses.GET, "https://api.example.com/data", json={"error": "Timeout"}, status=408)
        responses.add(responses.GET, "https://api.example.com/data", json={"error": "Timeout"}, status=408)
        # Third call succeeds
        responses.add(responses.GET, "https://api.example.com/data", json={"data": "success"}, status=200)

        from utils.retry_utils import retry_with_exponential_backoff
        import requests

        @retry_with_exponential_backoff(max_retries=3, base_delay=0.1)
        def fetch_data():
            resp = requests.get("https://api.example.com/data")
            resp.raise_for_status()
            return resp.json()

        result = fetch_data()

        assert result["data"] == "success"
        assert len(responses.calls) == 3

    def test_max_retries_exceeded(self):
        """Test that max retries is enforced."""
        from utils.retry_utils import retry_with_exponential_backoff, MaxRetriesExceeded

        @retry_with_exponential_backoff(max_retries=2, base_delay=0.01)
        def always_fails():
            raise Exception("Always fails")

        with pytest.raises(MaxRetriesExceeded):
            always_fails()


# ============================================================================
# Helper Functions
# ============================================================================

def create_test_trip_with_gps(db):
    """Helper to create a test trip with GPS data."""
    from models import Trip, TelemetryRaw
    from datetime import datetime, timedelta
    import uuid

    session_id = uuid.uuid4()
    trip = Trip(
        session_id=session_id,
        start_time=datetime.now(timezone.utc) - timedelta(hours=1),
        is_closed=False
    )
    db.add(trip)

    # Add telemetry with GPS
    telemetry = TelemetryRaw(
        session_id=session_id,
        timestamp=datetime.now(timezone.utc),
        latitude=37.7749,
        longitude=-122.4194,
        state_of_charge=85.0
    )
    db.add(telemetry)
    db.commit()

    return trip


# ============================================================================
# Fixtures
# ============================================================================

@pytest.fixture
def mock_db():
    """Mock database session for testing."""
    from database import SessionLocal

    db = SessionLocal()
    yield db
    db.rollback()
    db.close()


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
