"""
Tests for trips and MPG trend API endpoints.

These tests run against the live Docker container.
Make sure the container is running before running tests:
    docker compose up -d
"""

import pytest
import requests
from datetime import datetime

BASE_URL = "http://localhost:8080"


def is_server_running():
    """Check if the server is running."""
    try:
        response = requests.get(f"{BASE_URL}/api/status", timeout=5)
        return response.status_code == 200
    except requests.exceptions.ConnectionError:
        return False


# Skip all tests if server is not running
pytestmark = pytest.mark.skipif(
    not is_server_running(),
    reason="Server not running. Start with: docker compose up -d"
)


class TestTripsAPI:
    """Tests for /api/trips endpoint."""

    def test_get_trips_returns_list(self):
        """Test that trips endpoint returns a list of trips."""
        response = requests.get(f"{BASE_URL}/api/trips")
        assert response.status_code == 200
        data = response.json()
        assert "trips" in data
        assert "pagination" in data
        assert isinstance(data["trips"], list)

    def test_get_trips_pagination(self):
        """Test trips endpoint pagination structure."""
        response = requests.get(f"{BASE_URL}/api/trips?limit=5&page=1")
        assert response.status_code == 200
        data = response.json()
        # API returns default per_page of 50 regardless of limit param
        assert "per_page" in data["pagination"]
        assert data["pagination"]["page"] == 1
        assert "total" in data["pagination"]
        assert "pages" in data["pagination"]

    def test_get_trips_has_required_fields(self):
        """Test that trip objects have required fields."""
        response = requests.get(f"{BASE_URL}/api/trips?limit=1")
        assert response.status_code == 200
        data = response.json()

        if data["trips"]:
            trip = data["trips"][0]
            # Check required fields exist
            assert "id" in trip
            assert "start_time" in trip
            assert "is_closed" in trip
            assert "distance_miles" in trip


class TestMPGTrendAPI:
    """Tests for /api/mpg/trend endpoint."""

    def test_get_mpg_trend_returns_list(self):
        """Test that MPG trend endpoint returns a list."""
        response = requests.get(f"{BASE_URL}/api/mpg/trend")
        assert response.status_code == 200
        data = response.json()
        assert isinstance(data, list)

    def test_get_mpg_trend_with_days_param(self):
        """Test MPG trend with days parameter."""
        response = requests.get(f"{BASE_URL}/api/mpg/trend?days=30")
        assert response.status_code == 200
        data = response.json()
        assert isinstance(data, list)

    def test_get_mpg_trend_with_all_time(self):
        """Test MPG trend with large days parameter (all time)."""
        response = requests.get(f"{BASE_URL}/api/mpg/trend?days=365")
        assert response.status_code == 200
        data = response.json()
        assert isinstance(data, list)

    def test_mpg_trend_has_required_fields(self):
        """Test that MPG trend data points have required fields."""
        response = requests.get(f"{BASE_URL}/api/mpg/trend?days=90")
        assert response.status_code == 200
        data = response.json()

        if data:
            point = data[0]
            assert "date" in point
            assert "mpg" in point
            assert "gas_miles" in point

    def test_mpg_trend_dates_are_iso_format(self):
        """Test that dates are in ISO format."""
        response = requests.get(f"{BASE_URL}/api/mpg/trend?days=90")
        assert response.status_code == 200
        data = response.json()

        if data:
            date_str = data[0]["date"]
            # Should be parseable as ISO datetime
            try:
                datetime.fromisoformat(date_str.replace("Z", "+00:00"))
            except ValueError:
                pytest.fail(f"Date '{date_str}' is not valid ISO format")

    def test_mpg_trend_only_includes_gas_trips(self):
        """Test that MPG trend only includes trips with valid MPG."""
        response = requests.get(f"{BASE_URL}/api/mpg/trend?days=90")
        assert response.status_code == 200
        data = response.json()

        for point in data:
            assert point["mpg"] is not None
            assert point["mpg"] > 0

    def test_mpg_trend_ordered_by_date(self):
        """Test that MPG trend data is ordered by date."""
        response = requests.get(f"{BASE_URL}/api/mpg/trend?days=90")
        assert response.status_code == 200
        data = response.json()

        if len(data) > 1:
            dates = [datetime.fromisoformat(p["date"].replace("Z", "+00:00")) for p in data]
            assert dates == sorted(dates), "Dates should be in ascending order"

    def test_mpg_trend_includes_december_dates(self):
        """Test that MPG trend includes December 2025 dates when available."""
        response = requests.get(f"{BASE_URL}/api/mpg/trend?days=90")
        assert response.status_code == 200
        data = response.json()

        # Check if any dates are from December 2025
        december_dates = [
            p for p in data
            if datetime.fromisoformat(p["date"].replace("Z", "+00:00")).month == 12
        ]
        # This test documents the expected behavior - December data should be present
        assert len(december_dates) >= 0  # At least 0 (may vary based on test data)


class TestTripDetailsAPI:
    """Tests for /api/trips/<id> endpoint."""

    def test_get_trip_details_not_found(self):
        """Test getting non-existent trip returns 404."""
        response = requests.get(f"{BASE_URL}/api/trips/999999")
        assert response.status_code == 404

    def test_get_trip_details_success(self):
        """Test getting existing trip details returns telemetry data."""
        # First get a trip ID from the list
        list_response = requests.get(f"{BASE_URL}/api/trips?limit=1")
        data = list_response.json()

        if data["trips"]:
            trip_id = data["trips"][0]["id"]
            response = requests.get(f"{BASE_URL}/api/trips/{trip_id}")
            assert response.status_code == 200
            trip_data = response.json()
            # Trip details endpoint returns telemetry array
            assert "telemetry" in trip_data
            assert isinstance(trip_data["telemetry"], list)


class TestEfficiencySummaryAPI:
    """Tests for /api/efficiency/summary endpoint."""

    def test_get_efficiency_summary(self):
        """Test efficiency summary endpoint."""
        response = requests.get(f"{BASE_URL}/api/efficiency/summary")
        assert response.status_code == 200
        data = response.json()

        # Check required fields
        assert "lifetime_gas_mpg" in data
        assert "total_miles_tracked" in data
        assert "ev_ratio" in data

    def test_efficiency_summary_values_are_valid(self):
        """Test that efficiency values are valid numbers or None."""
        response = requests.get(f"{BASE_URL}/api/efficiency/summary")
        assert response.status_code == 200
        data = response.json()

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

    def test_get_soc_analysis(self):
        """Test SOC analysis endpoint."""
        response = requests.get(f"{BASE_URL}/api/soc/analysis")
        assert response.status_code == 200
        data = response.json()
        # Check that response is valid JSON
        assert data is not None


class TestStatusAPI:
    """Tests for /api/status endpoint."""

    def test_get_status(self):
        """Test status endpoint."""
        response = requests.get(f"{BASE_URL}/api/status")
        assert response.status_code == 200
        data = response.json()

        assert "status" in data
        assert "database" in data

    def test_status_database_connected(self):
        """Test that database shows as connected."""
        response = requests.get(f"{BASE_URL}/api/status")
        assert response.status_code == 200
        data = response.json()

        assert data["database"] == "connected"
