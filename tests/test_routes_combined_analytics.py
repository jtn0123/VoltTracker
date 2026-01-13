"""
Tests for combined analytics routes in VoltTracker.

Tests multi-factor efficiency analysis endpoints.
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
def trips_with_combined_data(db_session):
    """Create trips with weather, elevation, and efficiency data for testing."""
    from models import Trip

    trips = []
    now = datetime.now(timezone.utc)

    # Varied conditions for multi-factor analysis
    conditions = [
        # Good conditions: mild temp, flat, no rain
        {"temp": 65.0, "elev_net": 5, "precip": 0.0, "kwh": 0.26},
        # Hot with elevation gain
        {"temp": 95.0, "elev_net": 100, "precip": 0.0, "kwh": 0.35},
        # Cold with rain
        {"temp": 32.0, "elev_net": -20, "precip": 0.2, "kwh": 0.38},
        # Ideal conditions
        {"temp": 70.0, "elev_net": 0, "precip": 0.0, "kwh": 0.25},
        # Mixed: moderate temp, downhill
        {"temp": 55.0, "elev_net": -50, "precip": 0.0, "kwh": 0.22},
        # Mixed: mild temp, uphill, light rain
        {"temp": 60.0, "elev_net": 30, "precip": 0.05, "kwh": 0.30},
    ]

    for i, cond in enumerate(conditions):
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=i * 7),
            end_time=now - timedelta(days=i * 7, hours=-1),
            start_odometer=50000.0 + i * 50,
            end_odometer=50000.0 + i * 50 + 30.0,
            distance_miles=30.0,
            electric_miles=25.0,
            kwh_per_mile=cond["kwh"],
            weather_temp_f=cond["temp"],
            weather_precipitation_in=cond["precip"],
            weather_conditions="Clear" if cond["precip"] == 0 else "Rain",
            elevation_net_change_m=cond["elev_net"],
            elevation_gain_m=max(0, cond["elev_net"]),
            elevation_loss_m=abs(min(0, cond["elev_net"])),
            is_closed=True,
        )
        trips.append(trip)
        db_session.add(trip)

    db_session.commit()
    return trips


# ============================================================================
# Multi-Factor Analysis Tests
# ============================================================================


class TestMultiFactorAnalysis:
    """Tests for GET /api/analytics/efficiency/multi-factor."""

    def test_multi_factor_no_data(self, client):
        """Test multi-factor analysis with no data."""
        response = client.get("/api/analytics/efficiency/multi-factor")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_multi_factor_with_data(self, client, trips_with_combined_data):
        """Test multi-factor analysis with trip data."""
        response = client.get("/api/analytics/efficiency/multi-factor")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_multi_factor_date_filters(self, client, trips_with_combined_data):
        """Test multi-factor analysis with date filters."""
        start = (datetime.now(timezone.utc) - timedelta(days=30)).strftime("%Y-%m-%d")
        end = datetime.now(timezone.utc).strftime("%Y-%m-%d")

        response = client.get(f"/api/analytics/efficiency/multi-factor?start_date={start}&end_date={end}")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)


# ============================================================================
# Efficiency Predictions Tests
# ============================================================================


class TestEfficiencyPredictions:
    """Tests for GET /api/analytics/efficiency/predictions."""

    def test_predictions_no_params(self, client):
        """Test predictions with no parameters."""
        response = client.get("/api/analytics/efficiency/predictions")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_predictions_with_temperature(self, client, trips_with_combined_data):
        """Test predictions with temperature parameter."""
        response = client.get("/api/analytics/efficiency/predictions?temperature_f=70")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_predictions_with_elevation(self, client, trips_with_combined_data):
        """Test predictions with elevation parameter."""
        response = client.get("/api/analytics/efficiency/predictions?elevation_change_m=50")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_predictions_with_rain(self, client, trips_with_combined_data):
        """Test predictions with rain parameter."""
        response = client.get("/api/analytics/efficiency/predictions?is_raining=true")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_predictions_all_params(self, client, trips_with_combined_data):
        """Test predictions with all parameters."""
        response = client.get(
            "/api/analytics/efficiency/predictions"
            "?temperature_f=65&elevation_change_m=-20&is_raining=false"
        )

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_predictions_invalid_temperature(self, client):
        """Test predictions with invalid temperature returns error."""
        response = client.get("/api/analytics/efficiency/predictions?temperature_f=not_a_number")

        assert response.status_code == 400
        data = json.loads(response.data)
        assert "error" in data

    def test_predictions_invalid_elevation(self, client):
        """Test predictions with invalid elevation returns error."""
        response = client.get("/api/analytics/efficiency/predictions?elevation_change_m=invalid")

        assert response.status_code == 400
        data = json.loads(response.data)
        assert "error" in data


# ============================================================================
# Efficiency Time Series Tests
# ============================================================================


class TestEfficiencyTimeSeries:
    """Tests for GET /api/analytics/efficiency/time-series."""

    def test_time_series_defaults(self, client):
        """Test time series with default parameters."""
        response = client.get("/api/analytics/efficiency/time-series")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_time_series_with_days(self, client, trips_with_combined_data):
        """Test time series with custom days parameter."""
        response = client.get("/api/analytics/efficiency/time-series?days=30")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_time_series_group_by_day(self, client, trips_with_combined_data):
        """Test time series grouped by day."""
        response = client.get("/api/analytics/efficiency/time-series?group_by=day")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_time_series_group_by_week(self, client, trips_with_combined_data):
        """Test time series grouped by week."""
        response = client.get("/api/analytics/efficiency/time-series?group_by=week")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_time_series_group_by_month(self, client, trips_with_combined_data):
        """Test time series grouped by month."""
        response = client.get("/api/analytics/efficiency/time-series?group_by=month")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_time_series_invalid_group_by_uses_default(self, client):
        """Test time series falls back to default group_by."""
        response = client.get("/api/analytics/efficiency/time-series?group_by=invalid")

        assert response.status_code == 200
        # Should fallback to week

    def test_time_series_days_clamped_min(self, client):
        """Test time series clamps days to minimum 7."""
        response = client.get("/api/analytics/efficiency/time-series?days=1")

        assert response.status_code == 200
        # Should use 7 as minimum

    def test_time_series_days_clamped_max(self, client):
        """Test time series clamps days to maximum 365."""
        response = client.get("/api/analytics/efficiency/time-series?days=1000")

        assert response.status_code == 200
        # Should use 365 as maximum

    def test_time_series_invalid_days_uses_default(self, client):
        """Test time series uses default for invalid days."""
        response = client.get("/api/analytics/efficiency/time-series?days=not_a_number")

        assert response.status_code == 200
        # Should use default 90


# ============================================================================
# Optimal Conditions Tests
# ============================================================================


class TestOptimalConditions:
    """Tests for GET /api/analytics/efficiency/optimal-conditions."""

    def test_optimal_conditions_no_data(self, client):
        """Test optimal conditions with no data."""
        response = client.get("/api/analytics/efficiency/optimal-conditions")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_optimal_conditions_with_data(self, client, trips_with_combined_data):
        """Test optimal conditions with trip data."""
        response = client.get("/api/analytics/efficiency/optimal-conditions")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)


# ============================================================================
# Error Handling Tests
# ============================================================================


class TestCombinedAnalyticsErrors:
    """Tests for error handling in combined analytics routes."""

    def test_multi_factor_invalid_date_format(self, client):
        """Test multi-factor with invalid date format."""
        # Invalid date should be ignored (parsed as None)
        response = client.get("/api/analytics/efficiency/multi-factor?start_date=invalid")
        assert response.status_code == 200

    def test_multi_factor_service_exception(self, client):
        """Test multi-factor handles service exceptions."""
        with patch("routes.combined_analytics.combined_analytics_service.get_multi_factor_analysis") as mock:
            mock.side_effect = Exception("Test error")
            response = client.get("/api/analytics/efficiency/multi-factor")

            assert response.status_code == 500
            data = json.loads(response.data)
            assert "error" in data

    def test_predictions_service_exception(self, client):
        """Test predictions handles service exceptions."""
        with patch("routes.combined_analytics.combined_analytics_service.get_efficiency_predictions") as mock:
            mock.side_effect = Exception("Test error")
            response = client.get("/api/analytics/efficiency/predictions")

            assert response.status_code == 500
            data = json.loads(response.data)
            assert "error" in data

    def test_time_series_service_exception(self, client):
        """Test time series handles service exceptions."""
        with patch("routes.combined_analytics.combined_analytics_service.get_efficiency_time_series") as mock:
            mock.side_effect = Exception("Test error")
            response = client.get("/api/analytics/efficiency/time-series")

            assert response.status_code == 500
            data = json.loads(response.data)
            assert "error" in data

    def test_optimal_conditions_service_exception(self, client):
        """Test optimal conditions handles service exceptions."""
        with patch("routes.combined_analytics.combined_analytics_service.get_best_driving_conditions_combined") as mock:
            mock.side_effect = Exception("Test error")
            response = client.get("/api/analytics/efficiency/optimal-conditions")

            assert response.status_code == 500
            data = json.loads(response.data)
            assert "error" in data
