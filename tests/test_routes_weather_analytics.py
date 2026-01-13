"""
Tests for weather analytics routes in VoltTracker.

Tests weather-efficiency correlation analysis endpoints.
"""

import json
import os
import sys
import uuid
from datetime import datetime, timezone, timedelta
from unittest.mock import patch

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))


# ============================================================================
# Fixtures
# ============================================================================


@pytest.fixture
def trips_with_weather(db_session):
    """Create trips with varied weather conditions for testing."""
    from models import Trip

    trips = []
    now = datetime.now(timezone.utc)

    # Different temperature conditions
    weather_conditions = [
        {"temp": 20.0, "wind": 5.0, "precip": 0.0, "conditions": "Clear"},  # Freezing
        {"temp": 40.0, "wind": 10.0, "precip": 0.0, "conditions": "Cloudy"},  # Cold
        {"temp": 65.0, "wind": 8.0, "precip": 0.0, "conditions": "Clear"},  # Ideal
        {"temp": 65.0, "wind": 5.0, "precip": 0.0, "conditions": "Clear"},  # Ideal
        {"temp": 85.0, "wind": 15.0, "precip": 0.0, "conditions": "Clear"},  # Hot
        {"temp": 55.0, "wind": 5.0, "precip": 0.15, "conditions": "Rain"},  # Moderate rain
        {"temp": 50.0, "wind": 30.0, "precip": 0.0, "conditions": "Cloudy"},  # Strong wind
        {"temp": 72.0, "wind": 3.0, "precip": 0.0, "conditions": "Clear"},  # Perfect
    ]

    for i, weather in enumerate(weather_conditions):
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=i * 2),
            end_time=now - timedelta(days=i * 2, hours=-1),
            start_odometer=50000.0 + i * 30,
            end_odometer=50000.0 + i * 30 + 25.0,
            distance_miles=25.0,
            electric_miles=20.0 + (i % 5),
            kwh_per_mile=0.28 + (abs(weather["temp"] - 65) * 0.002),  # Efficiency varies with temp
            weather_temp_f=weather["temp"],
            weather_wind_mph=weather["wind"],
            weather_precipitation_in=weather["precip"],
            weather_conditions=weather["conditions"],
            extreme_weather=(weather["temp"] < 32 or weather["temp"] > 95 or weather["wind"] > 25),
            is_closed=True,
        )
        trips.append(trip)
        db_session.add(trip)

    db_session.commit()
    return trips


# ============================================================================
# Efficiency Correlation Tests
# ============================================================================


class TestEfficiencyCorrelation:
    """Tests for GET /api/analytics/weather/efficiency-correlation."""

    def test_correlation_no_data(self, client):
        """Test correlation with no trip data."""
        response = client.get("/api/analytics/weather/efficiency-correlation")

        assert response.status_code == 200
        data = json.loads(response.data)
        # Should return empty or default analysis
        assert isinstance(data, dict)

    def test_correlation_with_data(self, client, trips_with_weather):
        """Test correlation with weather data."""
        response = client.get("/api/analytics/weather/efficiency-correlation")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_correlation_date_filters(self, client, trips_with_weather):
        """Test correlation with date filters."""
        start = (datetime.now(timezone.utc) - timedelta(days=10)).strftime("%Y-%m-%d")
        end = datetime.now(timezone.utc).strftime("%Y-%m-%d")

        response = client.get(f"/api/analytics/weather/efficiency-correlation?start_date={start}&end_date={end}")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)


# ============================================================================
# Temperature Bands Tests
# ============================================================================


class TestTemperatureBands:
    """Tests for GET /api/analytics/weather/temperature-bands."""

    def test_temp_bands_no_data(self, client):
        """Test temperature bands with no data."""
        response = client.get("/api/analytics/weather/temperature-bands")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_temp_bands_with_data(self, client, trips_with_weather):
        """Test temperature bands with varied weather."""
        response = client.get("/api/analytics/weather/temperature-bands")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_temp_bands_date_filters(self, client, trips_with_weather):
        """Test temperature bands with date filters."""
        start = (datetime.now(timezone.utc) - timedelta(days=30)).strftime("%Y-%m-%d")

        response = client.get(f"/api/analytics/weather/temperature-bands?start_date={start}")

        assert response.status_code == 200


# ============================================================================
# Precipitation Impact Tests
# ============================================================================


class TestPrecipitationImpact:
    """Tests for GET /api/analytics/weather/precipitation-impact."""

    def test_precip_impact_no_data(self, client):
        """Test precipitation impact with no data."""
        response = client.get("/api/analytics/weather/precipitation-impact")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_precip_impact_with_data(self, client, trips_with_weather):
        """Test precipitation impact with trip data."""
        response = client.get("/api/analytics/weather/precipitation-impact")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)


# ============================================================================
# Wind Impact Tests
# ============================================================================


class TestWindImpact:
    """Tests for GET /api/analytics/weather/wind-impact."""

    def test_wind_impact_no_data(self, client):
        """Test wind impact with no data."""
        response = client.get("/api/analytics/weather/wind-impact")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_wind_impact_with_data(self, client, trips_with_weather):
        """Test wind impact with varied wind conditions."""
        response = client.get("/api/analytics/weather/wind-impact")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)


# ============================================================================
# Seasonal Trends Tests
# ============================================================================


class TestSeasonalTrends:
    """Tests for GET /api/analytics/weather/seasonal-trends."""

    def test_seasonal_trends_no_data(self, client):
        """Test seasonal trends with no data."""
        response = client.get("/api/analytics/weather/seasonal-trends")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_seasonal_trends_with_data(self, client, trips_with_weather):
        """Test seasonal trends with trip data."""
        response = client.get("/api/analytics/weather/seasonal-trends")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_seasonal_trends_months_param(self, client, trips_with_weather):
        """Test seasonal trends with months parameter."""
        response = client.get("/api/analytics/weather/seasonal-trends?months=12")

        assert response.status_code == 200

    def test_seasonal_trends_max_months(self, client, trips_with_weather):
        """Test seasonal trends respects max months limit."""
        # Try to request more than 60 months
        response = client.get("/api/analytics/weather/seasonal-trends?months=100")

        assert response.status_code == 200


# ============================================================================
# Best Conditions Tests
# ============================================================================


class TestBestConditions:
    """Tests for GET /api/analytics/weather/best-conditions."""

    def test_best_conditions_no_data(self, client):
        """Test best conditions with no data."""
        response = client.get("/api/analytics/weather/best-conditions")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_best_conditions_with_data(self, client, trips_with_weather):
        """Test best conditions identifies optimal weather."""
        response = client.get("/api/analytics/weather/best-conditions")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)


# ============================================================================
# Error Handling Tests
# ============================================================================


class TestWeatherAnalyticsErrors:
    """Tests for error handling in weather analytics routes."""

    def test_correlation_invalid_date_format(self, client):
        """Test correlation with invalid date format."""
        # Invalid date should be ignored (parsed as None)
        response = client.get("/api/analytics/weather/efficiency-correlation?start_date=invalid")
        assert response.status_code == 200

    def test_correlation_service_exception(self, client):
        """Test correlation handles service exceptions."""
        with patch("routes.weather_analytics.weather_analytics_service.get_weather_efficiency_correlation") as mock:
            mock.side_effect = Exception("Test error")
            response = client.get("/api/analytics/weather/efficiency-correlation")

            assert response.status_code == 500
            data = json.loads(response.data)
            assert "error" in data

    def test_temp_bands_service_exception(self, client):
        """Test temperature bands handles service exceptions."""
        with patch("routes.weather_analytics.weather_analytics_service.get_efficiency_by_temperature_bands") as mock:
            mock.side_effect = Exception("Test error")
            response = client.get("/api/analytics/weather/temperature-bands")

            assert response.status_code == 500
            data = json.loads(response.data)
            assert "error" in data

    def test_precip_impact_service_exception(self, client):
        """Test precipitation impact handles service exceptions."""
        with patch("routes.weather_analytics.weather_analytics_service.get_efficiency_by_precipitation") as mock:
            mock.side_effect = Exception("Test error")
            response = client.get("/api/analytics/weather/precipitation-impact")

            assert response.status_code == 500
            data = json.loads(response.data)
            assert "error" in data

    def test_wind_impact_service_exception(self, client):
        """Test wind impact handles service exceptions."""
        with patch("routes.weather_analytics.weather_analytics_service.get_efficiency_by_wind") as mock:
            mock.side_effect = Exception("Test error")
            response = client.get("/api/analytics/weather/wind-impact")

            assert response.status_code == 500
            data = json.loads(response.data)
            assert "error" in data

    def test_seasonal_trends_service_exception(self, client):
        """Test seasonal trends handles service exceptions."""
        with patch("routes.weather_analytics.weather_analytics_service.get_seasonal_trends") as mock:
            mock.side_effect = Exception("Test error")
            response = client.get("/api/analytics/weather/seasonal-trends")

            assert response.status_code == 500
            data = json.loads(response.data)
            assert "error" in data

    def test_best_conditions_service_exception(self, client):
        """Test best conditions handles service exceptions."""
        with patch("routes.weather_analytics.weather_analytics_service.get_best_driving_conditions") as mock:
            mock.side_effect = Exception("Test error")
            response = client.get("/api/analytics/weather/best-conditions")

            assert response.status_code == 500
            data = json.loads(response.data)
            assert "error" in data
