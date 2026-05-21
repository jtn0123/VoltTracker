"""
Tests for elevation analytics routes in VoltTracker.

Tests elevation-efficiency correlation analysis endpoints.
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
def trips_with_elevation(db_session):
    """Create trips with varied elevation data for testing."""
    from models import Trip

    trips = []
    now = datetime.now(timezone.utc)

    # Different elevation conditions
    elevation_conditions = [
        # Steep downhill
        {"start": 500, "end": 300, "gain": 20, "loss": 220, "net": -200, "max": 520, "min": 280},
        # Moderate downhill
        {"start": 400, "end": 370, "gain": 10, "loss": 40, "net": -30, "max": 410, "min": 360},
        # Flat
        {"start": 300, "end": 305, "gain": 15, "loss": 10, "net": 5, "max": 310, "min": 295},
        # Moderate uphill
        {"start": 200, "end": 230, "gain": 40, "loss": 10, "net": 30, "max": 235, "min": 195},
        # Steep uphill
        {"start": 100, "end": 250, "gain": 180, "loss": 30, "net": 150, "max": 260, "min": 95},
        # Another flat trip
        {"start": 350, "end": 352, "gain": 8, "loss": 6, "net": 2, "max": 355, "min": 348},
    ]

    for i, elev in enumerate(elevation_conditions):
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=i * 2),
            end_time=now - timedelta(days=i * 2, hours=-1),
            start_odometer=50000.0 + i * 30,
            end_odometer=50000.0 + i * 30 + 25.0,
            distance_miles=25.0,
            electric_miles=20.0 + (i % 5),
            kwh_per_mile=0.28 + (elev["net"] * 0.0001),  # Efficiency varies with elevation
            elevation_start_m=elev["start"],
            elevation_end_m=elev["end"],
            elevation_gain_m=elev["gain"],
            elevation_loss_m=elev["loss"],
            elevation_net_change_m=elev["net"],
            elevation_max_m=elev["max"],
            elevation_min_m=elev["min"],
            is_closed=True,
        )
        trips.append(trip)
        db_session.add(trip)

    db_session.commit()
    return trips


# ============================================================================
# Efficiency by Elevation Tests
# ============================================================================


class TestEfficiencyByElevation:
    """Tests for GET /api/analytics/elevation/efficiency-correlation."""

    def test_correlation_no_data(self, client):
        """Test correlation with no trip data."""
        response = client.get("/api/analytics/elevation/efficiency-correlation")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_correlation_with_data(self, client, trips_with_elevation):
        """Test correlation with elevation data."""
        response = client.get("/api/analytics/elevation/efficiency-correlation")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_correlation_date_filters(self, client, trips_with_elevation):
        """Test correlation with date filters."""
        start = (datetime.now(timezone.utc) - timedelta(days=10)).strftime("%Y-%m-%d")
        end = datetime.now(timezone.utc).strftime("%Y-%m-%d")

        response = client.get(f"/api/analytics/elevation/efficiency-correlation?start_date={start}&end_date={end}")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)


# ============================================================================
# Efficiency by Gradient Tests
# ============================================================================


class TestEfficiencyByGradient:
    """Tests for GET /api/analytics/elevation/gradient."""

    def test_gradient_no_data(self, client):
        """Test gradient analysis with no data."""
        response = client.get("/api/analytics/elevation/gradient")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_gradient_with_data(self, client, trips_with_elevation):
        """Test gradient analysis with elevation data."""
        response = client.get("/api/analytics/elevation/gradient")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_gradient_date_filters(self, client, trips_with_elevation):
        """Test gradient analysis with date filters."""
        start = (datetime.now(timezone.utc) - timedelta(days=30)).strftime("%Y-%m-%d")

        response = client.get(f"/api/analytics/elevation/gradient?start_date={start}")

        assert response.status_code == 200


# ============================================================================
# Elevation Summary Tests
# ============================================================================


class TestElevationSummary:
    """Tests for GET /api/analytics/elevation/summary."""

    def test_summary_no_data(self, client):
        """Test elevation summary with no data."""
        response = client.get("/api/analytics/elevation/summary")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)

    def test_summary_with_data(self, client, trips_with_elevation):
        """Test elevation summary with trip data."""
        response = client.get("/api/analytics/elevation/summary")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, dict)


# ============================================================================
# Route Comparison Tests
# ============================================================================


class TestRouteComparison:
    """Tests for GET /api/analytics/elevation/route-comparison."""

    def test_route_comparison_no_data(self, client):
        """Test route comparison with no data."""
        response = client.get("/api/analytics/elevation/route-comparison")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "routes" in data
        assert "count" in data
        assert isinstance(data["routes"], list)

    def test_route_comparison_with_data(self, client, trips_with_elevation):
        """Test route comparison with elevation data."""
        response = client.get("/api/analytics/elevation/route-comparison")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "routes" in data
        assert "count" in data


# ============================================================================
# Trip Elevation Tests
# ============================================================================


class TestTripElevation:
    """Tests for GET /api/analytics/elevation/trip/<id>."""

    def test_trip_elevation_success(self, client, trips_with_elevation):
        """Test getting elevation data for a specific trip."""
        trip_id = trips_with_elevation[0].id

        response = client.get(f"/api/analytics/elevation/trip/{trip_id}")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data["trip_id"] == trip_id
        assert "elevation" in data
        assert "efficiency" in data
        assert "distance_miles" in data

        # Check elevation fields
        elev = data["elevation"]
        assert "start_m" in elev
        assert "end_m" in elev
        assert "gain_m" in elev
        assert "loss_m" in elev
        assert "net_change_m" in elev
        assert "max_m" in elev
        assert "min_m" in elev

    def test_trip_elevation_not_found(self, client):
        """Test getting elevation data for non-existent trip."""
        response = client.get("/api/analytics/elevation/trip/99999")

        assert response.status_code == 404
        data = json.loads(response.data)
        assert "error" in data
        assert "not found" in data["error"].lower()

    def test_trip_elevation_includes_efficiency(self, client, trips_with_elevation):
        """Test trip elevation response includes efficiency data."""
        trip_id = trips_with_elevation[0].id

        response = client.get(f"/api/analytics/elevation/trip/{trip_id}")

        assert response.status_code == 200
        data = json.loads(response.data)
        efficiency = data["efficiency"]
        assert "kwh_per_mile" in efficiency
        assert "electric_miles" in efficiency


# ============================================================================
# Error Handling Tests
# ============================================================================


class TestElevationAnalyticsErrors:
    """Tests for error handling in elevation analytics routes."""

    def test_correlation_invalid_date_format(self, client):
        """Test correlation with invalid date format."""
        # Invalid date should be ignored (parsed as None)
        response = client.get("/api/analytics/elevation/efficiency-correlation?start_date=invalid")
        assert response.status_code == 200

    def test_correlation_service_exception(self, client):
        """Test correlation handles service exceptions."""
        with patch("routes.elevation_analytics.elevation_analytics_service.get_efficiency_by_elevation_change") as mock:
            mock.side_effect = Exception("Test error")
            response = client.get("/api/analytics/elevation/efficiency-correlation")

            assert response.status_code == 500
            data = json.loads(response.data)
            assert "error" in data

    def test_gradient_service_exception(self, client):
        """Test gradient handles service exceptions."""
        with patch("routes.elevation_analytics.elevation_analytics_service.get_efficiency_by_gradient") as mock:
            mock.side_effect = Exception("Test error")
            response = client.get("/api/analytics/elevation/gradient")

            assert response.status_code == 500
            data = json.loads(response.data)
            assert "error" in data

    def test_summary_service_exception(self, client):
        """Test summary handles service exceptions."""
        with patch("routes.elevation_analytics.elevation_analytics_service.get_elevation_summary") as mock:
            mock.side_effect = Exception("Test error")
            response = client.get("/api/analytics/elevation/summary")

            assert response.status_code == 500
            data = json.loads(response.data)
            assert "error" in data

    def test_route_comparison_service_exception(self, client):
        """Test route comparison handles service exceptions."""
        with patch("routes.elevation_analytics.elevation_analytics_service.get_route_elevation_comparison") as mock:
            mock.side_effect = Exception("Test error")
            response = client.get("/api/analytics/elevation/route-comparison")

            assert response.status_code == 500
            data = json.loads(response.data)
            assert "error" in data

    def test_trip_elevation_service_exception(self, client, trips_with_elevation):
        """Test trip elevation handles query exceptions."""
        trip_id = trips_with_elevation[0].id
        with patch("routes.elevation_analytics.get_db") as mock:
            mock.return_value.query.side_effect = Exception("Test error")
            response = client.get(f"/api/analytics/elevation/trip/{trip_id}")

            assert response.status_code == 500
            data = json.loads(response.data)
            assert "error" in data
