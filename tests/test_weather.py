"""
Tests for weather API integration.

Tests Open-Meteo API calls, response parsing, and weather impact calculations.
All tests mock HTTP requests to avoid actual API calls.
"""

import os
import sys
from datetime import datetime, timedelta
from unittest.mock import MagicMock, patch

import pytest
import requests

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

from utils.weather import (  # noqa: E402
    OPEN_METEO_HISTORICAL_URL,
    OPEN_METEO_URL,
    _parse_weather_response,
    _weather_code_to_description,
    get_weather_for_location,
    get_weather_impact_factor,
)


class TestGetWeatherForLocation:
    """Tests for the main weather fetching function."""

    @patch("utils.weather.requests.get")
    def test_get_weather_returns_dict_for_valid_coordinates(self, mock_get):
        """Weather fetch with valid coordinates returns a dict."""
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

        result = get_weather_for_location(37.7749, -122.4194)

        assert result is not None
        assert isinstance(result, dict)
        assert "temperature_f" in result
        assert "conditions" in result

    @patch("utils.weather.requests.get")
    def test_get_weather_with_current_timestamp_uses_forecast_api(self, mock_get):
        """Current timestamps use the forecast API endpoint."""
        mock_response = MagicMock()
        mock_response.json.return_value = {
            "hourly": {
                "time": [datetime.utcnow().strftime("%Y-%m-%dT%H:00")],
                "temperature_2m": [70.0],
                "precipitation": [0.0],
                "wind_speed_10m": [5.0],
                "weather_code": [1],
            }
        }
        mock_response.raise_for_status = MagicMock()
        mock_get.return_value = mock_response

        get_weather_for_location(37.7749, -122.4194)

        call_url = mock_get.call_args[0][0]
        assert OPEN_METEO_URL in call_url

    @patch("utils.weather.requests.get")
    def test_get_weather_with_old_timestamp_uses_historical_api(self, mock_get):
        """Timestamps more than 5 days ago use historical API."""
        mock_response = MagicMock()
        old_date = datetime.utcnow() - timedelta(days=10)
        mock_response.json.return_value = {
            "hourly": {
                "time": [old_date.strftime("%Y-%m-%dT%H:00")],
                "temperature_2m": [55.0],
                "precipitation": [0.1],
                "wind_speed_10m": [15.0],
                "weather_code": [61],
            }
        }
        mock_response.raise_for_status = MagicMock()
        mock_get.return_value = mock_response

        get_weather_for_location(37.7749, -122.4194, old_date)

        call_url = mock_get.call_args[0][0]
        assert OPEN_METEO_HISTORICAL_URL in call_url

    @patch("utils.weather.requests.get")
    def test_get_weather_threshold_exactly_5_days_uses_forecast(self, mock_get):
        """Timestamp exactly 5 days ago uses forecast API."""
        mock_response = MagicMock()
        five_days_ago = datetime.utcnow() - timedelta(days=5)
        mock_response.json.return_value = {
            "hourly": {
                "time": [five_days_ago.strftime("%Y-%m-%dT%H:00")],
                "temperature_2m": [60.0],
                "precipitation": [0.0],
                "wind_speed_10m": [8.0],
                "weather_code": [2],
            }
        }
        mock_response.raise_for_status = MagicMock()
        mock_get.return_value = mock_response

        get_weather_for_location(37.7749, -122.4194, five_days_ago)

        call_url = mock_get.call_args[0][0]
        assert OPEN_METEO_URL in call_url

    @patch("utils.weather.requests.get")
    def test_get_weather_threshold_6_days_ago_uses_historical(self, mock_get):
        """Timestamp 6 days ago uses historical API."""
        mock_response = MagicMock()
        six_days_ago = datetime.utcnow() - timedelta(days=6)
        mock_response.json.return_value = {
            "hourly": {
                "time": [six_days_ago.strftime("%Y-%m-%dT%H:00")],
                "temperature_2m": [58.0],
                "precipitation": [0.0],
                "wind_speed_10m": [12.0],
                "weather_code": [3],
            }
        }
        mock_response.raise_for_status = MagicMock()
        mock_get.return_value = mock_response

        get_weather_for_location(37.7749, -122.4194, six_days_ago)

        call_url = mock_get.call_args[0][0]
        assert OPEN_METEO_HISTORICAL_URL in call_url

    @patch("utils.weather.requests.get")
    def test_get_weather_returns_none_on_network_error(self, mock_get):
        """Network connection error returns None."""
        mock_get.side_effect = requests.exceptions.ConnectionError()

        result = get_weather_for_location(37.7749, -122.4194)

        assert result is None

    @patch("utils.weather.requests.get")
    def test_get_weather_returns_none_on_api_timeout(self, mock_get):
        """API timeout returns None."""
        mock_get.side_effect = requests.exceptions.Timeout()

        result = get_weather_for_location(37.7749, -122.4194)

        assert result is None

    @patch("utils.weather.requests.get")
    def test_get_weather_returns_none_on_invalid_json(self, mock_get):
        """Invalid JSON response returns None."""
        mock_response = MagicMock()
        mock_response.json.side_effect = ValueError("Invalid JSON")
        mock_response.raise_for_status = MagicMock()
        mock_get.return_value = mock_response

        result = get_weather_for_location(37.7749, -122.4194)

        assert result is None

    @patch("utils.weather.requests.get")
    def test_get_weather_handles_api_rate_limit(self, mock_get):
        """HTTP 429 rate limit error returns None."""
        mock_response = MagicMock()
        mock_response.raise_for_status.side_effect = requests.exceptions.HTTPError(response=MagicMock(status_code=429))
        mock_get.return_value = mock_response

        result = get_weather_for_location(37.7749, -122.4194)

        assert result is None


class TestParseWeatherResponse:
    """Tests for _parse_weather_response function."""

    def test_parse_returns_none_for_missing_hourly(self):
        """Missing hourly data returns None."""
        data = {"latitude": 37.7749, "longitude": -122.4194}
        result = _parse_weather_response(data, datetime.utcnow())
        assert result is None

    def test_parse_extracts_temperature_correctly(self):
        """Temperature is correctly extracted from response."""
        now = datetime.utcnow()
        data = {
            "hourly": {
                "time": [now.strftime("%Y-%m-%dT%H:00")],
                "temperature_2m": [72.5],
                "precipitation": [0.0],
                "wind_speed_10m": [10.0],
                "weather_code": [0],
            }
        }
        result = _parse_weather_response(data, now)
        assert result["temperature_f"] == 72.5

    def test_parse_finds_exact_hour_match(self):
        """Parser finds exact hour match in time array."""
        now = datetime.utcnow().replace(minute=0, second=0, microsecond=0)
        target_str = now.strftime("%Y-%m-%dT%H:00")

        data = {
            "hourly": {
                "time": [
                    (now - timedelta(hours=1)).strftime("%Y-%m-%dT%H:00"),
                    target_str,
                    (now + timedelta(hours=1)).strftime("%Y-%m-%dT%H:00"),
                ],
                "temperature_2m": [60.0, 65.0, 70.0],
                "precipitation": [0.0, 0.0, 0.0],
                "wind_speed_10m": [5.0, 8.0, 10.0],
                "weather_code": [0, 1, 2],
            }
        }
        result = _parse_weather_response(data, now)
        assert result["temperature_f"] == 65.0

    def test_parse_uses_fallback_index_when_no_match(self):
        """Parser uses hour as index when exact match not found."""
        now = datetime(2024, 1, 15, 10, 30, 0)  # 10:30 AM
        data = {
            "hourly": {
                "time": ["2024-01-14T00:00"] * 24,  # Wrong day, won't match
                "temperature_2m": [50.0 + i for i in range(24)],
                "precipitation": [0.0] * 24,
                "wind_speed_10m": [10.0] * 24,
                "weather_code": [0] * 24,
            }
        }
        result = _parse_weather_response(data, now)
        # Should use index 10 (the hour)
        assert result["temperature_f"] == 60.0  # 50.0 + 10

    def test_parse_handles_empty_time_array(self):
        """Empty time array returns None."""
        data = {
            "hourly": {"time": [], "temperature_2m": [], "precipitation": [], "wind_speed_10m": [], "weather_code": []}
        }
        result = _parse_weather_response(data, datetime.utcnow())
        assert result is None

    def test_parse_sets_is_raining_flag(self):
        """is_raining flag is set correctly based on precipitation."""
        now = datetime.utcnow()
        data = {
            "hourly": {
                "time": [now.strftime("%Y-%m-%dT%H:00")],
                "temperature_2m": [55.0],
                "precipitation": [0.15],
                "wind_speed_10m": [20.0],
                "weather_code": [61],
            }
        }
        result = _parse_weather_response(data, now)
        assert result["is_raining"] is True

    def test_parse_sets_is_raining_false_when_no_precipitation(self):
        """is_raining is False when precipitation is 0."""
        now = datetime.utcnow()
        data = {
            "hourly": {
                "time": [now.strftime("%Y-%m-%dT%H:00")],
                "temperature_2m": [70.0],
                "precipitation": [0.0],
                "wind_speed_10m": [5.0],
                "weather_code": [0],
            }
        }
        result = _parse_weather_response(data, now)
        assert result["is_raining"] is False


class TestWeatherCodeToDescription:
    """Tests for _weather_code_to_description function."""

    def test_code_0_returns_clear(self):
        """Code 0 returns 'Clear'."""
        assert _weather_code_to_description(0) == "Clear"

    def test_code_3_returns_overcast(self):
        """Code 3 returns 'Overcast'."""
        assert _weather_code_to_description(3) == "Overcast"

    def test_code_61_returns_light_rain(self):
        """Code 61 returns 'Light Rain'."""
        assert _weather_code_to_description(61) == "Light Rain"

    def test_code_95_returns_thunderstorm(self):
        """Code 95 returns 'Thunderstorm'."""
        assert _weather_code_to_description(95) == "Thunderstorm"

    def test_unknown_code_returns_code_number(self):
        """Unknown code returns 'Code X' format."""
        result = _weather_code_to_description(999)
        assert result == "Code 999"

    def test_none_code_returns_unknown(self):
        """None code returns 'Unknown'."""
        assert _weather_code_to_description(None) == "Unknown"


class TestGetWeatherImpactFactor:
    """Tests for get_weather_impact_factor function."""

    def test_ideal_temp_returns_1_0(self):
        """Ideal temperature (65-75°F) returns factor of 1.0."""
        weather = {"temperature_f": 70.0, "is_raining": False, "wind_speed_mph": 5.0}
        assert get_weather_impact_factor(weather) == 1.0

    def test_freezing_temp_adds_0_20(self):
        """Freezing temperature (<32°F) adds 0.20 penalty."""
        weather = {"temperature_f": 25.0, "is_raining": False, "wind_speed_mph": 5.0}
        assert get_weather_impact_factor(weather) == 1.20

    def test_cold_temp_45_to_32_adds_0_10(self):
        """Cold temperature (32-45°F) adds 0.10 penalty."""
        weather = {"temperature_f": 40.0, "is_raining": False, "wind_speed_mph": 5.0}
        assert get_weather_impact_factor(weather) == 1.10

    def test_cool_temp_55_to_45_adds_0_05(self):
        """Cool temperature (45-55°F) adds 0.05 penalty."""
        weather = {"temperature_f": 50.0, "is_raining": False, "wind_speed_mph": 5.0}
        assert get_weather_impact_factor(weather) == 1.05

    def test_hot_temp_over_95_adds_0_10(self):
        """Hot temperature (>95°F) adds 0.10 penalty."""
        weather = {"temperature_f": 100.0, "is_raining": False, "wind_speed_mph": 5.0}
        assert get_weather_impact_factor(weather) == 1.10

    def test_warm_temp_85_to_95_adds_0_05(self):
        """Warm temperature (85-95°F) adds 0.05 penalty."""
        weather = {"temperature_f": 90.0, "is_raining": False, "wind_speed_mph": 5.0}
        assert get_weather_impact_factor(weather) == 1.05

    def test_heavy_rain_adds_0_10(self):
        """Heavy rain (>0.25 in) adds 0.10 penalty."""
        weather = {"temperature_f": 70.0, "is_raining": True, "precipitation_in": 0.5, "wind_speed_mph": 5.0}
        assert get_weather_impact_factor(weather) == 1.10

    def test_light_rain_adds_0_05(self):
        """Light rain (<=0.25 in) adds 0.05 penalty."""
        weather = {"temperature_f": 70.0, "is_raining": True, "precipitation_in": 0.1, "wind_speed_mph": 5.0}
        assert get_weather_impact_factor(weather) == 1.05

    def test_no_rain_adds_nothing(self):
        """No rain adds no penalty."""
        weather = {"temperature_f": 70.0, "is_raining": False, "wind_speed_mph": 5.0}
        assert get_weather_impact_factor(weather) == 1.0

    def test_strong_wind_over_25_adds_0_10(self):
        """Strong wind (>25 mph) adds 0.10 penalty."""
        weather = {"temperature_f": 70.0, "is_raining": False, "wind_speed_mph": 30.0}
        assert get_weather_impact_factor(weather) == 1.10

    def test_moderate_wind_15_to_25_adds_0_05(self):
        """Moderate wind (15-25 mph) adds 0.05 penalty."""
        weather = {"temperature_f": 70.0, "is_raining": False, "wind_speed_mph": 20.0}
        assert get_weather_impact_factor(weather) == 1.05

    def test_light_wind_adds_nothing(self):
        """Light wind (<15 mph) adds no penalty."""
        weather = {"temperature_f": 70.0, "is_raining": False, "wind_speed_mph": 10.0}
        assert get_weather_impact_factor(weather) == 1.0

    def test_combined_bad_weather_stacks_factors(self):
        """Multiple bad conditions stack their penalties."""
        weather = {
            "temperature_f": 25.0,  # +0.20 (freezing)
            "is_raining": True,
            "precipitation_in": 0.5,  # +0.10 (heavy rain)
            "wind_speed_mph": 30.0,  # +0.10 (strong wind)
        }
        # 1.0 + 0.20 + 0.10 + 0.10 = 1.40
        assert get_weather_impact_factor(weather) == pytest.approx(1.40)

    def test_empty_weather_dict_returns_1_0(self):
        """Empty weather dict returns factor of 1.0."""
        assert get_weather_impact_factor({}) == 1.0

    def test_none_weather_returns_1_0(self):
        """None weather returns factor of 1.0."""
        assert get_weather_impact_factor(None) == 1.0

    def test_missing_temperature_only_checks_other_factors(self):
        """Missing temperature still checks rain and wind."""
        weather = {"is_raining": True, "precipitation_in": 0.5, "wind_speed_mph": 30.0}
        # 1.0 + 0.10 (rain) + 0.10 (wind) = 1.20
        assert get_weather_impact_factor(weather) == pytest.approx(1.20)

    def test_missing_wind_only_checks_other_factors(self):
        """Missing wind still checks temperature and rain."""
        weather = {"temperature_f": 25.0, "is_raining": True, "precipitation_in": 0.5}
        # 1.0 + 0.20 (freezing) + 0.10 (rain) = 1.30
        assert get_weather_impact_factor(weather) == pytest.approx(1.30)
