"""
Tests for trips and MPG trend API endpoints.
"""

import pytest
from datetime import datetime, timedelta
from unittest.mock import MagicMock, patch

import sys
import os

# Add parent directory to path for imports
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import create_app
from models import Trip


@pytest.fixture
def app():
    """Create test application."""
    app = create_app()
    app.config["TESTING"] = True
    return app


@pytest.fixture
def client(app):
    """Create test client."""
    return app.test_client()


class TestTripsAPI:
    """Tests for /api/trips endpoint."""

    def test_get_trips_returns_list(self, client):
        """Test that trips endpoint returns a list of trips."""
        response = client.get("/api/trips")
        assert response.status_code == 200
        data = response.get_json()
        assert "trips" in data
        assert "pagination" in data
        assert isinstance(data["trips"], list)

    def test_get_trips_pagination(self, client):
        """Test trips endpoint pagination."""
        response = client.get("/api/trips?limit=5&page=1")
        assert response.status_code == 200
        data = response.get_json()
        assert data["pagination"]["per_page"] == 5
        assert data["pagination"]["page"] == 1

    def test_get_trips_has_required_fields(self, client):
        """Test that trip objects have required fields."""
        response = client.get("/api/trips?limit=1")
        assert response.status_code == 200
        data = response.get_json()

        if data["trips"]:
            trip = data["trips"][0]
            # Check required fields exist
            assert "id" in trip
            assert "start_time" in trip
            assert "is_closed" in trip
            assert "distance_miles" in trip


class TestMPGTrendAPI:
    """Tests for /api/mpg/trend endpoint."""

    def test_get_mpg_trend_returns_list(self, client):
        """Test that MPG trend endpoint returns a list."""
        response = client.get("/api/mpg/trend")
        assert response.status_code == 200
        data = response.get_json()
        assert isinstance(data, list)

    def test_get_mpg_trend_with_days_param(self, client):
        """Test MPG trend with days parameter."""
        response = client.get("/api/mpg/trend?days=30")
        assert response.status_code == 200
        data = response.get_json()
        assert isinstance(data, list)

    def test_get_mpg_trend_with_all_time(self, client):
        """Test MPG trend with large days parameter (all time)."""
        response = client.get("/api/mpg/trend?days=365")
        assert response.status_code == 200
        data = response.get_json()
        assert isinstance(data, list)

    def test_mpg_trend_has_required_fields(self, client):
        """Test that MPG trend data points have required fields."""
        response = client.get("/api/mpg/trend?days=90")
        assert response.status_code == 200
        data = response.get_json()

        if data:
            point = data[0]
            assert "date" in point
            assert "mpg" in point
            assert "gas_miles" in point

    def test_mpg_trend_dates_are_iso_format(self, client):
        """Test that dates are in ISO format."""
        response = client.get("/api/mpg/trend?days=90")
        assert response.status_code == 200
        data = response.get_json()

        if data:
            date_str = data[0]["date"]
            # Should be parseable as ISO datetime
            try:
                datetime.fromisoformat(date_str.replace("Z", "+00:00"))
            except ValueError:
                pytest.fail(f"Date '{date_str}' is not valid ISO format")

    def test_mpg_trend_only_includes_gas_trips(self, client):
        """Test that MPG trend only includes trips with valid MPG."""
        response = client.get("/api/mpg/trend?days=90")
        assert response.status_code == 200
        data = response.get_json()

        for point in data:
            assert point["mpg"] is not None
            assert point["mpg"] > 0

    def test_mpg_trend_ordered_by_date(self, client):
        """Test that MPG trend data is ordered by date."""
        response = client.get("/api/mpg/trend?days=90")
        assert response.status_code == 200
        data = response.get_json()

        if len(data) > 1:
            dates = [datetime.fromisoformat(p["date"].replace("Z", "+00:00")) for p in data]
            assert dates == sorted(dates), "Dates should be in ascending order"


class TestTripDetailsAPI:
    """Tests for /api/trips/<id> endpoint."""

    def test_get_trip_details_not_found(self, client):
        """Test getting non-existent trip returns 404."""
        response = client.get("/api/trips/999999")
        assert response.status_code == 404

    def test_get_trip_details_success(self, client):
        """Test getting existing trip details."""
        # First get a trip ID from the list
        list_response = client.get("/api/trips?limit=1")
        data = list_response.get_json()

        if data["trips"]:
            trip_id = data["trips"][0]["id"]
            response = client.get(f"/api/trips/{trip_id}")
            assert response.status_code == 200
            trip_data = response.get_json()
            assert trip_data["id"] == trip_id


class TestEfficiencySummaryAPI:
    """Tests for /api/efficiency/summary endpoint."""

    def test_get_efficiency_summary(self, client):
        """Test efficiency summary endpoint."""
        response = client.get("/api/efficiency/summary")
        assert response.status_code == 200
        data = response.get_json()

        # Check required fields
        assert "lifetime_gas_mpg" in data
        assert "total_miles_tracked" in data
        assert "ev_ratio" in data

    def test_efficiency_summary_values_are_valid(self, client):
        """Test that efficiency values are valid numbers or None."""
        response = client.get("/api/efficiency/summary")
        assert response.status_code == 200
        data = response.get_json()

        # MPG should be None or positive
        if data["lifetime_gas_mpg"] is not None:
            assert data["lifetime_gas_mpg"] > 0

        # Total miles should be >= 0
        if data["total_miles_tracked"] is not None:
            assert data["total_miles_tracked"] >= 0

        # EV ratio should be 0-100
        if data["ev_ratio"] is not None:
            assert 0 <= data["ev_ratio"] <= 100


class TestSOCAnalysisAPI:
    """Tests for /api/soc/analysis endpoint."""

    def test_get_soc_analysis(self, client):
        """Test SOC analysis endpoint."""
        response = client.get("/api/soc/analysis")
        assert response.status_code == 200
        data = response.get_json()

        # Check structure
        assert "transitions" in data or "avg_soc" in data or "count" in data
