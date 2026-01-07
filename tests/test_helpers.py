"""
Test Helpers and Utilities for VoltTracker

Provides helper functions and utilities to make testing easier:
- API test helpers
- Mock builders for external services
- Database transaction helpers
- Time manipulation utilities
- Assertion helpers

Usage:
    # Make API request with automatic JSON parsing
    response_data = make_api_request(client, '/api/trips')

    # Mock external API responses
    with mock_weather_api(temperature=75.0):
        ...
"""

import contextlib
import json
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional
from unittest import mock

from flask.testing import FlaskClient


# ============================================================================
# API Testing Helpers
# ============================================================================


class APITestHelper:
    """Helper for making API requests in tests."""

    def __init__(self, client: FlaskClient):
        self.client = client
        self.last_response = None

    def get(self, url: str, **kwargs) -> Dict[str, Any]:
        """Make GET request and return JSON response."""
        response = self.client.get(url, **kwargs)
        self.last_response = response
        assert response.status_code < 400, (
            f"GET {url} failed with {response.status_code}: "
            f"{response.get_data(as_text=True)}"
        )
        return response.get_json()

    def post(self, url: str, data: Dict[str, Any] = None, **kwargs) -> Dict[str, Any]:
        """Make POST request with JSON data."""
        response = self.client.post(url, json=data, **kwargs)
        self.last_response = response
        assert response.status_code < 400, (
            f"POST {url} failed with {response.status_code}: "
            f"{response.get_data(as_text=True)}"
        )
        return response.get_json()

    def patch(self, url: str, data: Dict[str, Any] = None, **kwargs) -> Dict[str, Any]:
        """Make PATCH request with JSON data."""
        response = self.client.patch(url, json=data, **kwargs)
        self.last_response = response
        assert response.status_code < 400, (
            f"PATCH {url} failed with {response.status_code}: "
            f"{response.get_data(as_text=True)}"
        )
        return response.get_json()

    def delete(self, url: str, **kwargs) -> Optional[Dict[str, Any]]:
        """Make DELETE request."""
        response = self.client.delete(url, **kwargs)
        self.last_response = response
        assert response.status_code < 400, (
            f"DELETE {url} failed with {response.status_code}: "
            f"{response.get_data(as_text=True)}"
        )
        if response.get_data():
            return response.get_json()
        return None

    def expect_error(self, method: str, url: str, expected_status: int, **kwargs):
        """Make request expecting an error status code."""
        method_func = getattr(self.client, method.lower())
        response = method_func(url, **kwargs)
        self.last_response = response
        assert response.status_code == expected_status, (
            f"{method} {url} returned {response.status_code}, "
            f"expected {expected_status}"
        )
        return response


def make_api_request(
    client: FlaskClient,
    url: str,
    method: str = "GET",
    data: Dict[str, Any] = None,
    expected_status: int = 200,
) -> Dict[str, Any]:
    """
    Make an API request and assert success.

    Returns parsed JSON response.
    """
    kwargs = {}
    if data:
        kwargs["json"] = data

    method_func = getattr(client, method.lower())
    response = method_func(url, **kwargs)

    assert response.status_code == expected_status, (
        f"{method} {url} returned {response.status_code}, "
        f"expected {expected_status}. "
        f"Response: {response.get_data(as_text=True)}"
    )

    if response.get_data():
        return response.get_json()
    return {}


def assert_api_success(response, expected_status: int = 200):
    """Assert that API response is successful."""
    assert response.status_code == expected_status, (
        f"Expected {expected_status}, got {response.status_code}: "
        f"{response.get_data(as_text=True)}"
    )


def assert_api_error(response, expected_status: int, error_keyword: str = None):
    """Assert that API response is an error with expected status."""
    assert response.status_code == expected_status, (
        f"Expected error {expected_status}, got {response.status_code}"
    )

    if error_keyword:
        data = response.get_data(as_text=True)
        assert error_keyword.lower() in data.lower(), (
            f"Expected error message to contain '{error_keyword}', "
            f"got: {data}"
        )


# ============================================================================
# External API Mocking
# ============================================================================


class MockWeatherAPI:
    """Mock for weather API responses."""

    @staticmethod
    @contextlib.contextmanager
    def mock_response(
        temperature: float = 70.0,
        precipitation: float = 0.0,
        wind_speed: float = 10.0,
        weather_code: int = 0,
    ):
        """Mock a successful weather API response."""
        now = datetime.now(timezone.utc)
        hours = [(now - timedelta(hours=i)).strftime("%Y-%m-%dT%H:00") for i in range(24)]
        hours.reverse()

        mock_data = {
            "hourly": {
                "time": hours,
                "temperature_2m": [temperature] * 24,
                "precipitation": [precipitation] * 24,
                "wind_speed_10m": [wind_speed] * 24,
                "weather_code": [weather_code] * 24,
            }
        }

        with mock.patch("utils.weather.fetch_weather_data") as mock_fetch:
            mock_fetch.return_value = mock_data
            yield mock_fetch

    @staticmethod
    @contextlib.contextmanager
    def mock_failure():
        """Mock a weather API failure."""
        with mock.patch("utils.weather.fetch_weather_data") as mock_fetch:
            mock_fetch.side_effect = Exception("Weather API unavailable")
            yield mock_fetch


class MockElevationAPI:
    """Mock for elevation API responses."""

    @staticmethod
    @contextlib.contextmanager
    def mock_response(elevation_meters: float = 100.0):
        """Mock a successful elevation API response."""
        mock_data = {
            "results": [{"elevation": elevation_meters}]
        }

        with mock.patch("utils.elevation.fetch_elevation") as mock_fetch:
            mock_fetch.return_value = elevation_meters
            yield mock_fetch

    @staticmethod
    @contextlib.contextmanager
    def mock_failure():
        """Mock an elevation API failure."""
        with mock.patch("utils.elevation.fetch_elevation") as mock_fetch:
            mock_fetch.return_value = None
            yield mock_fetch


# ============================================================================
# Database Transaction Helpers
# ============================================================================


@contextlib.contextmanager
def db_transaction(db_session, rollback=False):
    """
    Context manager for database transactions.

    If rollback=True, automatically rolls back at the end (useful for tests
    that shouldn't persist changes).
    """
    try:
        yield db_session
        if rollback:
            db_session.rollback()
        else:
            db_session.commit()
    except Exception:
        db_session.rollback()
        raise


def clean_database(db_session):
    """Delete all data from all tables."""
    from models import (
        BatteryHealthReading,
        ChargingSession,
        FuelEvent,
        TelemetryRaw,
        Trip,
        WeatherCache,
    )

    # Delete in correct order (respecting foreign keys)
    db_session.query(TelemetryRaw).delete()
    db_session.query(FuelEvent).delete()
    db_session.query(ChargingSession).delete()
    db_session.query(Trip).delete()
    db_session.query(BatteryHealthReading).delete()
    db_session.query(WeatherCache).delete()

    db_session.commit()


def count_queries(db_session) -> int:
    """Count number of queries executed in current transaction."""
    # This would require query logging to be enabled
    # See debug_utils.py for query counting
    pass


# ============================================================================
# Time Manipulation
# ============================================================================


class FreezeTime:
    """
    Helper for time manipulation in tests.

    Wraps freezegun for easier use.
    """

    @staticmethod
    @contextlib.contextmanager
    def freeze(time_to_freeze: datetime):
        """Freeze time at a specific datetime."""
        from freezegun import freeze_time

        with freeze_time(time_to_freeze):
            yield

    @staticmethod
    @contextlib.contextmanager
    def freeze_now():
        """Freeze time at current moment."""
        from freezegun import freeze_time

        with freeze_time(datetime.now(timezone.utc)):
            yield

    @staticmethod
    def create_time_range(
        start: datetime,
        end: datetime,
        interval: timedelta,
    ) -> List[datetime]:
        """Create a list of datetimes between start and end."""
        times = []
        current = start

        while current <= end:
            times.append(current)
            current += interval

        return times


# ============================================================================
# Data Validation Helpers
# ============================================================================


def validate_trip_dict(trip_data: Dict[str, Any]):
    """Validate that a trip dict has required fields."""
    required_fields = [
        "id",
        "start_time",
        "distance_miles",
        "is_closed",
    ]

    for field in required_fields:
        assert field in trip_data, f"Trip missing required field: {field}"


def validate_charging_session_dict(session_data: Dict[str, Any]):
    """Validate that a charging session dict has required fields."""
    required_fields = [
        "id",
        "start_time",
        "start_soc",
        "charge_type",
    ]

    for field in required_fields:
        assert field in session_data, f"Charging session missing required field: {field}"


def validate_telemetry_dict(telemetry_data: Dict[str, Any]):
    """Validate that a telemetry dict has required fields."""
    required_fields = [
        "timestamp",
        "state_of_charge",
    ]

    for field in required_fields:
        assert field in telemetry_data, f"Telemetry missing required field: {field}"


# ============================================================================
# Torque Data Builders
# ============================================================================


class TorqueDataBuilder:
    """Builder for Torque Pro POST data."""

    def __init__(self):
        self.data = {
            "eml": "test@example.com",
            "v": "1.0",
            "session": "test-session",
            "id": "test-device",
            "time": str(int(datetime.now(timezone.utc).timestamp() * 1000)),
        }

    def with_location(self, lat: float, lon: float):
        """Add GPS location."""
        self.data["kff1006"] = str(lat)
        self.data["kff1005"] = str(lon)
        return self

    def with_speed(self, speed_mph: float):
        """Add speed."""
        self.data["kff1001"] = str(speed_mph)
        return self

    def with_soc(self, soc_percent: float):
        """Add state of charge."""
        self.data["k22005b"] = str(soc_percent)
        return self

    def with_rpm(self, rpm: int):
        """Add engine RPM."""
        self.data["kc"] = str(rpm)
        return self

    def with_fuel_level(self, percent: float):
        """Add fuel level."""
        self.data["k22002f"] = str(percent)
        return self

    def with_odometer(self, miles: float):
        """Add odometer."""
        self.data["kff1271"] = str(miles)
        return self

    def with_charging(self, power_kw: float = 6.6):
        """Add charging data."""
        self.data["charger_connected"] = "1"
        self.data["charger_power_kw"] = str(power_kw)
        return self

    def electric_mode(self):
        """Configure for electric mode."""
        self.data["kc"] = "0"  # No RPM
        self.data["k22005b"] = "80.0"  # High SOC
        return self

    def gas_mode(self):
        """Configure for gas mode."""
        self.data["kc"] = "1200"  # Engine running
        self.data["k22005b"] = "15.0"  # Low SOC
        return self

    def build(self) -> Dict[str, str]:
        """Build the Torque data dict."""
        return self.data.copy()


# ============================================================================
# Response Validators
# ============================================================================


def assert_response_has_pagination(response_data: Dict[str, Any]):
    """Assert that response has pagination metadata."""
    assert "page" in response_data, "Response missing 'page'"
    assert "per_page" in response_data, "Response missing 'per_page'"
    assert "total" in response_data, "Response missing 'total'"
    assert "pages" in response_data, "Response missing 'pages'"


def assert_response_has_data_list(response_data: Dict[str, Any], key: str = "data"):
    """Assert that response has a data list."""
    assert key in response_data, f"Response missing '{key}'"
    assert isinstance(response_data[key], list), f"'{key}' is not a list"


def assert_response_has_keys(response_data: Dict[str, Any], required_keys: List[str]):
    """Assert that response has all required keys."""
    for key in required_keys:
        assert key in response_data, f"Response missing required key: {key}"


# ============================================================================
# Test Data Generators
# ============================================================================


def generate_coordinate_path(
    start_lat: float,
    start_lon: float,
    points: int,
    distance_degrees: float = 0.1,
) -> List[tuple[float, float]]:
    """
    Generate a path of GPS coordinates.

    Useful for simulating a trip route.
    """
    coords = []
    for i in range(points):
        lat = start_lat + (i * distance_degrees / points)
        lon = start_lon + (i * distance_degrees / points)
        coords.append((lat, lon))
    return coords


def generate_soc_drain_curve(
    start_soc: float,
    end_soc: float,
    points: int,
) -> List[float]:
    """
    Generate realistic SOC drain curve.

    Drains faster at higher speeds/power levels (simplified).
    """
    soc_values = []
    total_drain = start_soc - end_soc

    for i in range(points):
        # Simple linear drain (could make more realistic)
        soc = start_soc - (total_drain * i / (points - 1))
        soc_values.append(max(end_soc, soc))

    return soc_values


def generate_charging_curve(
    start_soc: float,
    end_soc: float,
    points: int,
    charge_type: str = "L2",
) -> List[Dict[str, float]]:
    """
    Generate realistic charging curve with SOC and power.

    Charging slows down as battery approaches full.
    """
    curve = []
    total_charge = end_soc - start_soc

    # Power levels by charge type
    max_power = {
        "L1": 1.4,
        "L2": 6.6,
        "DCFC": 50.0,
    }[charge_type]

    for i in range(points):
        progress = i / (points - 1)
        soc = start_soc + (total_charge * progress)

        # Taper power as SOC increases (realistic for DCFC, simplified for L2)
        if soc > 80:
            power = max_power * (100 - soc) / 20  # Taper above 80%
        else:
            power = max_power

        curve.append({
            "soc": min(end_soc, soc),
            "power_kw": max(0.1, power),
        })

    return curve


# ============================================================================
# Fixture Helpers
# ============================================================================


def create_test_app_context(app):
    """Create app context for tests that need it."""
    return app.app_context()


def create_request_context(app, path="/", method="GET", **kwargs):
    """Create request context for tests."""
    return app.test_request_context(path, method=method, **kwargs)
