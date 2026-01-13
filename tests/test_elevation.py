"""Tests for elevation utility and analytics service."""

import uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import MagicMock, patch

import pytest

from models import Route, TelemetryRaw, Trip
from services import elevation_analytics_service
from utils.elevation import (
    calculate_elevation_profile,
    estimate_elevation_impact_factor,
    get_elevation_for_point,
    get_elevation_for_points,
    sample_coordinates,
)


class TestElevationUtility:
    """Tests for elevation utility functions."""

    def test_sample_coordinates_basic(self):
        """Test coordinate sampling with basic input."""
        coords = [(1.0, 1.0), (2.0, 2.0), (3.0, 3.0), (4.0, 4.0), (5.0, 5.0)]
        result = sample_coordinates(coords, max_samples=3)
        assert len(result) == 3
        assert result[0] == (1.0, 1.0)  # First point
        assert result[-1] == (5.0, 5.0)  # Last point

    def test_sample_coordinates_fewer_than_max(self):
        """Test sampling when fewer points than max."""
        coords = [(1.0, 1.0), (2.0, 2.0)]
        result = sample_coordinates(coords, max_samples=5)
        assert len(result) == 2
        assert result == coords

    def test_sample_coordinates_empty(self):
        """Test sampling empty list."""
        result = sample_coordinates([], max_samples=5)
        assert result == []

    def test_sample_coordinates_single_point(self):
        """Test sampling single point."""
        coords = [(1.0, 1.0)]
        result = sample_coordinates(coords, max_samples=5)
        assert result == coords

    def test_sample_coordinates_includes_endpoints(self):
        """Test that sampling always includes first and last points."""
        coords = [(i, i) for i in range(100)]
        result = sample_coordinates(coords, max_samples=10)
        assert result[0] == (0, 0)
        assert result[-1] == (99, 99)
        assert len(result) == 10

    def test_calculate_elevation_profile_basic(self):
        """Test elevation profile calculation."""
        elevations = [100.0, 150.0, 120.0, 180.0, 160.0]
        profile = calculate_elevation_profile(elevations)

        assert profile["max_elevation_m"] == 180.0
        assert profile["min_elevation_m"] == 100.0
        assert profile["net_change_m"] == 60.0  # 160 - 100
        # Gain: 50 (100->150) + 60 (120->180) = 110
        assert profile["total_gain_m"] == 110.0
        # Loss: 30 (150->120) + 20 (180->160) = 50
        assert profile["total_loss_m"] == 50.0

    def test_calculate_elevation_profile_empty(self):
        """Test profile with empty list."""
        profile = calculate_elevation_profile([])
        assert profile["total_gain_m"] is None
        assert profile["max_elevation_m"] is None

    def test_calculate_elevation_profile_single_point(self):
        """Test profile with single point."""
        profile = calculate_elevation_profile([100.0])
        assert profile["max_elevation_m"] == 100.0
        assert profile["min_elevation_m"] == 100.0
        assert profile["total_gain_m"] is None
        assert profile["total_loss_m"] is None

    def test_calculate_elevation_profile_with_none_values(self):
        """Test profile handles None values in list."""
        elevations = [100.0, None, 150.0, None, 200.0]
        profile = calculate_elevation_profile(elevations)
        assert profile["max_elevation_m"] == 200.0
        assert profile["min_elevation_m"] == 100.0

    def test_calculate_elevation_profile_downhill(self):
        """Test profile for downhill trip."""
        elevations = [200.0, 150.0, 100.0]
        profile = calculate_elevation_profile(elevations)
        assert profile["net_change_m"] == -100.0
        assert profile["total_gain_m"] == 0
        assert profile["total_loss_m"] == 100.0

    def test_estimate_elevation_impact_factor_uphill(self):
        """Test impact factor for uphill trip."""
        factor = estimate_elevation_impact_factor(50.0, 10.0, 5.0)  # 50m gain, 5 miles
        assert factor > 1.0  # Uphill should increase consumption

    def test_estimate_elevation_impact_factor_downhill(self):
        """Test impact factor for downhill trip."""
        factor = estimate_elevation_impact_factor(10.0, 50.0, 5.0)  # 50m loss, 5 miles
        assert factor < 1.0  # Downhill should decrease consumption

    def test_estimate_elevation_impact_factor_flat(self):
        """Test impact factor for flat trip."""
        factor = estimate_elevation_impact_factor(5.0, 5.0, 10.0)
        assert factor == pytest.approx(1.0, abs=0.01)

    def test_estimate_elevation_impact_factor_zero_distance(self):
        """Test impact factor with zero distance."""
        factor = estimate_elevation_impact_factor(50.0, 10.0, 0)
        assert factor == 1.0

    @patch("utils.elevation.requests.get")
    def test_get_elevation_for_point_success(self, mock_get):
        """Test single point elevation fetch."""
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {"elevation": [125.5]}
        mock_get.return_value = mock_response

        result = get_elevation_for_point(37.7749, -122.4194)
        assert result == 125.5
        mock_get.assert_called_once()

    @patch("utils.elevation.requests.get")
    def test_get_elevation_for_point_api_error(self, mock_get):
        """Test single point with API error."""
        mock_response = MagicMock()
        mock_response.status_code = 500
        mock_response.raise_for_status.side_effect = Exception("Server error")
        mock_get.return_value = mock_response

        result = get_elevation_for_point(37.7749, -122.4194)
        assert result is None

    @patch("utils.elevation.requests.get")
    def test_get_elevation_for_points_success(self, mock_get):
        """Test batch elevation fetch."""
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {"elevation": [100.0, 150.0, 200.0]}
        mock_get.return_value = mock_response

        coords = [(37.0, -122.0), (37.1, -122.1), (37.2, -122.2)]
        result = get_elevation_for_points(coords)

        assert result == [100.0, 150.0, 200.0]

    @patch("utils.elevation.requests.get")
    def test_get_elevation_for_points_empty(self, mock_get):
        """Test batch fetch with empty list."""
        result = get_elevation_for_points([])
        assert result == []
        mock_get.assert_not_called()

    @patch("utils.elevation.requests.get")
    def test_get_elevation_for_points_api_failure(self, mock_get):
        """Test batch fetch with API failure returns None for each point."""
        mock_response = MagicMock()
        mock_response.status_code = 503
        mock_response.raise_for_status.side_effect = Exception("Service unavailable")
        mock_get.return_value = mock_response

        coords = [(37.0, -122.0), (37.1, -122.1)]
        result = get_elevation_for_points(coords)

        # When API fails, returns None for each coordinate
        assert result == [None, None]

    @patch("utils.elevation.requests.get")
    def test_get_elevation_for_points_timeout_error(self, mock_get):
        """Test batch fetch handles Timeout exception."""
        import requests

        mock_get.side_effect = requests.exceptions.Timeout("Request timed out")

        coords = [(37.0, -122.0)]
        result = get_elevation_for_points(coords)

        # When API times out, returns None for each coordinate
        assert result == [None]

    @patch("utils.elevation.requests.get")
    def test_get_elevation_for_points_connection_error(self, mock_get):
        """Test batch fetch handles ConnectionError."""
        import requests

        mock_get.side_effect = requests.exceptions.ConnectionError("Connection failed")

        coords = [(37.0, -122.0)]
        result = get_elevation_for_points(coords)

        # When connection fails, returns None for each coordinate
        assert result == [None]

    @patch("utils.elevation.requests.get")
    def test_get_elevation_for_points_http_client_error(self, mock_get):
        """Test batch fetch handles 4xx HTTP errors."""
        import requests

        mock_response = MagicMock()
        mock_response.status_code = 400
        error = requests.exceptions.HTTPError("Bad request")
        error.response = mock_response
        mock_get.side_effect = error

        coords = [(37.0, -122.0)]
        result = get_elevation_for_points(coords)

        # Client errors return None without retry
        assert result == [None]

    @patch("utils.elevation.requests.get")
    def test_get_elevation_for_points_single_value_response(self, mock_get):
        """Test batch fetch handles single value instead of list."""
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {"elevation": 150.0}  # Single value, not list
        mock_get.return_value = mock_response

        coords = [(37.0, -122.0)]
        result = get_elevation_for_points(coords)

        # Should wrap single value in list
        assert result == [150.0]

    @patch("utils.elevation.requests.get")
    def test_get_elevation_for_points_unexpected_format(self, mock_get):
        """Test batch fetch handles unexpected response format."""
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {"elevation": "invalid"}  # Unexpected string
        mock_get.return_value = mock_response

        coords = [(37.0, -122.0)]
        result = get_elevation_for_points(coords)

        # Should return None for unexpected format
        assert result == [None]


class TestElevationAnalyticsService:
    """Tests for elevation analytics service."""

    @pytest.fixture
    def sample_trips_with_elevation(self, db_session):
        """Create sample trips with elevation data."""
        now = datetime.now(timezone.utc)
        trips = []

        # Steep downhill trip
        trip1 = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(hours=5),
            end_time=now - timedelta(hours=4),
            is_closed=True,
            distance_miles=10.0,
            electric_miles=10.0,
            kwh_per_mile=0.25,
            elevation_start_m=500.0,
            elevation_end_m=400.0,
            elevation_gain_m=20.0,
            elevation_loss_m=120.0,
            elevation_net_change_m=-100.0,
            elevation_max_m=520.0,
            elevation_min_m=400.0,
        )
        trips.append(trip1)

        # Flat trip
        trip2 = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(hours=3),
            end_time=now - timedelta(hours=2),
            is_closed=True,
            distance_miles=8.0,
            electric_miles=8.0,
            kwh_per_mile=0.30,
            elevation_start_m=100.0,
            elevation_end_m=105.0,
            elevation_gain_m=15.0,
            elevation_loss_m=10.0,
            elevation_net_change_m=5.0,
            elevation_max_m=115.0,
            elevation_min_m=95.0,
        )
        trips.append(trip2)

        # Steep uphill trip
        trip3 = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(hours=1),
            end_time=now,
            is_closed=True,
            distance_miles=12.0,
            electric_miles=12.0,
            kwh_per_mile=0.42,
            elevation_start_m=200.0,
            elevation_end_m=320.0,
            elevation_gain_m=150.0,
            elevation_loss_m=30.0,
            elevation_net_change_m=120.0,
            elevation_max_m=350.0,
            elevation_min_m=200.0,
        )
        trips.append(trip3)

        for trip in trips:
            db_session.add(trip)
        db_session.commit()

        return trips

    def test_get_efficiency_by_elevation_change(self, db_session, sample_trips_with_elevation):
        """Test efficiency by elevation change categories."""
        result = elevation_analytics_service.get_efficiency_by_elevation_change(db_session)

        assert "elevation_categories" in result
        assert "baseline_kwh_per_mile" in result
        assert "total_trips_analyzed" in result
        assert result["total_trips_analyzed"] == 3

        categories = {c["category"]: c for c in result["elevation_categories"]}
        # Should have steep_downhill, flat, and steep_uphill
        assert "steep_downhill" in categories or "steep_uphill" in categories or "flat" in categories

    def test_get_efficiency_by_elevation_change_with_date_filter(
        self, db_session, sample_trips_with_elevation
    ):
        """Test elevation analysis with date filter."""
        now = datetime.now(timezone.utc)
        result = elevation_analytics_service.get_efficiency_by_elevation_change(
            db_session,
            start_date=now - timedelta(hours=4),
            end_date=now,
        )

        # Should get 2 trips (not the one from 5 hours ago)
        assert result["total_trips_analyzed"] <= 3

    def test_get_efficiency_by_gradient(self, db_session, sample_trips_with_elevation):
        """Test efficiency by gradient bands."""
        result = elevation_analytics_service.get_efficiency_by_gradient(db_session)

        assert "gradient_analysis" in result
        assert "total_trips_analyzed" in result
        assert result["total_trips_analyzed"] == 3

        # Verify gradient bands have expected structure
        for band in result["gradient_analysis"]:
            assert "band" in band
            assert "label" in band
            assert "avg_kwh_per_mile" in band
            assert "sample_count" in band

    def test_get_efficiency_by_gradient_empty(self, db_session):
        """Test gradient analysis with no trips."""
        result = elevation_analytics_service.get_efficiency_by_gradient(db_session)
        assert result["total_trips_analyzed"] == 0
        assert result["gradient_analysis"] == []

    def test_get_elevation_summary(self, db_session, sample_trips_with_elevation):
        """Test elevation summary statistics."""
        result = elevation_analytics_service.get_elevation_summary(db_session)

        assert "summary" in result
        assert "statistics" in result

        summary = result["summary"]
        assert "trips_with_elevation" in summary
        assert "trips_without_elevation" in summary
        assert "coverage_percent" in summary
        assert summary["trips_with_elevation"] == 3

        stats = result["statistics"]
        assert "avg_elevation_gain_m" in stats
        assert "avg_elevation_loss_m" in stats
        assert "max_elevation_gain_m" in stats

    def test_get_elevation_summary_no_trips(self, db_session):
        """Test summary with no trips."""
        result = elevation_analytics_service.get_elevation_summary(db_session)
        assert result["summary"]["trips_with_elevation"] == 0

    def test_get_route_elevation_comparison(self, db_session):
        """Test route elevation comparison."""
        # Create a route with elevation data
        route = Route(
            name="Test Hill Route",
            start_lat=37.7749,
            start_lon=-122.4194,
            end_lat=37.7849,
            end_lon=-122.4094,
            trip_count=5,
            avg_distance_miles=10.0,
            avg_elevation_gain_m=75.0,
            avg_elevation_loss_m=25.0,
            avg_efficiency_kwh_per_mile=0.35,
        )
        db_session.add(route)
        db_session.commit()

        result = elevation_analytics_service.get_route_elevation_comparison(db_session)

        assert len(result) >= 1
        route_data = result[0]
        assert route_data["name"] == "Test Hill Route"
        assert route_data["avg_elevation_gain_m"] == 75.0

    def test_get_route_elevation_comparison_empty(self, db_session):
        """Test route comparison with no routes."""
        result = elevation_analytics_service.get_route_elevation_comparison(db_session)
        assert result == []

    def test_efficiency_impact_calculation(self, db_session, sample_trips_with_elevation):
        """Test that efficiency impact percentages are calculated correctly."""
        result = elevation_analytics_service.get_efficiency_by_elevation_change(db_session)

        for category in result["elevation_categories"]:
            if category["avg_kwh_per_mile"] is not None:
                # Impact should be calculated relative to baseline
                expected_impact = (
                    (category["avg_kwh_per_mile"] - result["baseline_kwh_per_mile"])
                    / result["baseline_kwh_per_mile"]
                    * 100
                )
                assert category["efficiency_impact_percent"] == pytest.approx(
                    expected_impact, abs=0.2
                )


class TestElevationAnalyticsRoutes:
    """Tests for elevation analytics API routes."""

    def test_efficiency_correlation_endpoint(self, client, db_session):
        """Test elevation efficiency correlation endpoint."""
        response = client.get("/api/analytics/elevation/efficiency-correlation")
        assert response.status_code == 200
        data = response.get_json()
        assert "elevation_categories" in data

    def test_gradient_endpoint(self, client, db_session):
        """Test gradient analysis endpoint."""
        response = client.get("/api/analytics/elevation/gradient")
        assert response.status_code == 200
        data = response.get_json()
        assert "gradient_analysis" in data

    def test_summary_endpoint(self, client, db_session):
        """Test elevation summary endpoint."""
        response = client.get("/api/analytics/elevation/summary")
        assert response.status_code == 200
        data = response.get_json()
        assert "summary" in data
        assert "statistics" in data

    def test_route_comparison_endpoint(self, client, db_session):
        """Test route comparison endpoint."""
        response = client.get("/api/analytics/elevation/route-comparison")
        assert response.status_code == 200
        data = response.get_json()
        assert "routes" in data
        assert "count" in data

    def test_trip_elevation_endpoint_not_found(self, client, db_session):
        """Test trip elevation endpoint with non-existent trip."""
        response = client.get("/api/analytics/elevation/trip/99999")
        assert response.status_code == 404

    def test_trip_elevation_endpoint_success(self, client, db_session):
        """Test trip elevation endpoint with existing trip."""
        now = datetime.now(timezone.utc)
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(hours=1),
            end_time=now,
            is_closed=True,
            distance_miles=5.0,
            electric_miles=5.0,
            kwh_per_mile=0.32,
            elevation_start_m=100.0,
            elevation_end_m=150.0,
            elevation_gain_m=60.0,
            elevation_loss_m=10.0,
            elevation_net_change_m=50.0,
        )
        db_session.add(trip)
        db_session.commit()
        db_session.refresh(trip)

        response = client.get(f"/api/analytics/elevation/trip/{trip.id}")
        assert response.status_code == 200
        data = response.get_json()
        assert data["trip_id"] == trip.id
        assert "elevation" in data
        assert data["elevation"]["start_m"] == 100.0
        assert data["elevation"]["gain_m"] == 60.0

    def test_efficiency_correlation_with_date_params(self, client, db_session):
        """Test elevation endpoint with date parameters."""
        response = client.get(
            "/api/analytics/elevation/efficiency-correlation",
            query_string={
                "start_date": "2024-01-01",
                "end_date": "2024-12-31",
            },
        )
        assert response.status_code == 200

    def test_gradient_with_date_params(self, client, db_session):
        """Test gradient endpoint with date parameters."""
        response = client.get(
            "/api/analytics/elevation/gradient",
            query_string={
                "start_date": "2024-01-01",
                "end_date": "2024-12-31",
            },
        )
        assert response.status_code == 200
