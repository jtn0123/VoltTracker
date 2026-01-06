"""
Tests for trip weather sampling and averaging.

Tests the improved weather integration that samples weather every 15 minutes
during a trip and averages the results for more accurate trip weather data.
"""

import os
import sys
from datetime import datetime, timedelta, timezone
from unittest.mock import MagicMock, patch

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

from models import Trip  # noqa: E402
from services.trip_service import fetch_trip_weather  # noqa: E402


class TestWeatherTripSampling:
    """Tests for 15-minute weather sampling during trips."""

    @patch("services.trip_service.get_weather_for_location")
    @patch("services.trip_service.get_weather_impact_factor")
    def test_short_trip_samples_start_and_end(self, mock_impact, mock_get_weather):
        """Short trips (<15 min) sample weather at start and end only."""
        # Setup: 10-minute trip
        start_time = datetime.now(timezone.utc)
        end_time = start_time + timedelta(minutes=10)

        trip = Trip(
            id=1,
            start_time=start_time,
            end_time=end_time
        )

        # Create telemetry points with GPS
        points = [
            {
                "timestamp": start_time.isoformat(),
                "latitude": 37.7749,
                "longitude": -122.4194
            },
            {
                "timestamp": (start_time + timedelta(minutes=5)).isoformat(),
                "latitude": 37.7750,
                "longitude": -122.4195
            },
            {
                "timestamp": end_time.isoformat(),
                "latitude": 37.7751,
                "longitude": -122.4196
            }
        ]

        # Mock weather responses
        mock_get_weather.return_value = {
            "temperature_f": 70.0,
            "precipitation_in": 0.0,
            "wind_speed_mph": 10.0,
            "conditions": "Clear"
        }
        mock_impact.return_value = 1.0

        # Execute
        fetch_trip_weather(trip, points)

        # Verify: Should call weather API twice (start + end)
        assert mock_get_weather.call_count == 2
        assert trip.weather_temp_f == 70.0
        assert trip.weather_conditions == "Clear"

    @patch("services.trip_service.get_weather_for_location")
    @patch("services.trip_service.get_weather_impact_factor")
    def test_long_trip_samples_every_15_minutes(self, mock_impact, mock_get_weather):
        """Long trips sample weather every 15 minutes."""
        # Setup: 60-minute trip
        start_time = datetime.now(timezone.utc)
        end_time = start_time + timedelta(minutes=60)

        trip = Trip(
            id=1,
            start_time=start_time,
            end_time=end_time
        )

        # Create telemetry points every 5 minutes with GPS
        points = []
        for i in range(13):  # 0, 5, 10, ... 60 minutes
            points.append({
                "timestamp": (start_time + timedelta(minutes=i * 5)).isoformat(),
                "latitude": 37.7749 + (i * 0.001),
                "longitude": -122.4194 + (i * 0.001)
            })

        # Mock weather responses
        mock_get_weather.return_value = {
            "temperature_f": 70.0,
            "precipitation_in": 0.0,
            "wind_speed_mph": 10.0,
            "conditions": "Clear"
        }
        mock_impact.return_value = 1.0

        # Execute
        fetch_trip_weather(trip, points)

        # Verify: Should call weather API 5 times (0, 15, 30, 45, 60 minutes)
        assert mock_get_weather.call_count == 5
        assert trip.weather_temp_f == 70.0

    @patch("services.trip_service.get_weather_for_location")
    @patch("services.trip_service.get_weather_impact_factor")
    def test_averages_temperature_across_samples(self, mock_impact, mock_get_weather):
        """Temperature is averaged across all samples."""
        # Setup: 30-minute trip
        start_time = datetime.now(timezone.utc)
        end_time = start_time + timedelta(minutes=30)

        trip = Trip(
            id=1,
            start_time=start_time,
            end_time=end_time
        )

        # Create telemetry points
        points = []
        for i in range(7):  # 0, 5, 10, 15, 20, 25, 30 minutes
            points.append({
                "timestamp": (start_time + timedelta(minutes=i * 5)).isoformat(),
                "latitude": 37.7749,
                "longitude": -122.4194
            })

        # Mock weather responses with varying temperatures
        weather_responses = [
            {"temperature_f": 65.0, "precipitation_in": 0.0, "wind_speed_mph": 10.0, "conditions": "Clear"},
            {"temperature_f": 70.0, "precipitation_in": 0.0, "wind_speed_mph": 10.0, "conditions": "Clear"},
            {"temperature_f": 75.0, "precipitation_in": 0.0, "wind_speed_mph": 10.0, "conditions": "Clear"},
        ]
        mock_get_weather.side_effect = weather_responses
        mock_impact.return_value = 1.0

        # Execute
        fetch_trip_weather(trip, points)

        # Verify: Should average 65, 70, 75 = 70.0
        assert trip.weather_temp_f == 70.0

    @patch("services.trip_service.get_weather_for_location")
    @patch("services.trip_service.get_weather_impact_factor")
    def test_averages_wind_speed_across_samples(self, mock_impact, mock_get_weather):
        """Wind speed is averaged across all samples."""
        # Setup: 30-minute trip
        start_time = datetime.now(timezone.utc)
        end_time = start_time + timedelta(minutes=30)

        trip = Trip(
            id=1,
            start_time=start_time,
            end_time=end_time
        )

        # Create telemetry points
        points = []
        for i in range(7):
            points.append({
                "timestamp": (start_time + timedelta(minutes=i * 5)).isoformat(),
                "latitude": 37.7749,
                "longitude": -122.4194
            })

        # Mock weather responses with varying wind speeds
        weather_responses = [
            {"temperature_f": 70.0, "precipitation_in": 0.0, "wind_speed_mph": 5.0, "conditions": "Clear"},
            {"temperature_f": 70.0, "precipitation_in": 0.0, "wind_speed_mph": 10.0, "conditions": "Clear"},
            {"temperature_f": 70.0, "precipitation_in": 0.0, "wind_speed_mph": 15.0, "conditions": "Clear"},
        ]
        mock_get_weather.side_effect = weather_responses
        mock_impact.return_value = 1.0

        # Execute
        fetch_trip_weather(trip, points)

        # Verify: Should average 5, 10, 15 = 10.0
        assert trip.weather_wind_mph == 10.0

    @patch("services.trip_service.get_weather_for_location")
    @patch("services.trip_service.get_weather_impact_factor")
    def test_uses_most_common_weather_condition(self, mock_impact, mock_get_weather):
        """Most common weather condition is used."""
        # Setup: 30-minute trip
        start_time = datetime.now(timezone.utc)
        end_time = start_time + timedelta(minutes=30)

        trip = Trip(
            id=1,
            start_time=start_time,
            end_time=end_time
        )

        # Create telemetry points
        points = []
        for i in range(7):
            points.append({
                "timestamp": (start_time + timedelta(minutes=i * 5)).isoformat(),
                "latitude": 37.7749,
                "longitude": -122.4194
            })

        # Mock weather responses with varying conditions
        weather_responses = [
            {"temperature_f": 70.0, "precipitation_in": 0.0, "wind_speed_mph": 10.0, "conditions": "Clear"},
            {"temperature_f": 70.0, "precipitation_in": 0.1, "wind_speed_mph": 10.0, "conditions": "Light Rain"},
            {"temperature_f": 70.0, "precipitation_in": 0.0, "wind_speed_mph": 10.0, "conditions": "Clear"},
        ]
        mock_get_weather.side_effect = weather_responses
        mock_impact.return_value = 1.0

        # Execute
        fetch_trip_weather(trip, points)

        # Verify: "Clear" appears 2x, "Light Rain" appears 1x, so "Clear" should win
        assert trip.weather_conditions == "Clear"

    @patch("services.trip_service.get_weather_for_location")
    @patch("services.trip_service.get_weather_impact_factor")
    def test_uses_worst_condition_on_tie(self, mock_impact, mock_get_weather):
        """Worst weather condition is used when counts are tied."""
        # Setup: 45-minute trip to get 4 samples (0, 15, 30, 45)
        start_time = datetime.now(timezone.utc)
        end_time = start_time + timedelta(minutes=45)

        trip = Trip(
            id=1,
            start_time=start_time,
            end_time=end_time
        )

        # Create telemetry points every 5 minutes
        points = []
        for i in range(10):
            points.append({
                "timestamp": (start_time + timedelta(minutes=i * 5)).isoformat(),
                "latitude": 37.7749,
                "longitude": -122.4194
            })

        # Mock weather responses with tied conditions (2 Clear, 2 Rain)
        weather_responses = [
            {"temperature_f": 70.0, "precipitation_in": 0.0, "wind_speed_mph": 10.0, "conditions": "Clear"},
            {"temperature_f": 70.0, "precipitation_in": 0.2, "wind_speed_mph": 10.0, "conditions": "Rain"},
            {"temperature_f": 70.0, "precipitation_in": 0.0, "wind_speed_mph": 10.0, "conditions": "Clear"},
            {"temperature_f": 70.0, "precipitation_in": 0.2, "wind_speed_mph": 10.0, "conditions": "Rain"},
        ]
        mock_get_weather.side_effect = weather_responses
        mock_impact.return_value = 1.0

        # Execute
        fetch_trip_weather(trip, points)

        # Verify: Both "Clear" and "Rain" appear 2x, but "Rain" is worse so it wins
        assert trip.weather_conditions == "Rain"

    @patch("services.trip_service.get_weather_for_location")
    @patch("services.trip_service.get_weather_impact_factor")
    def test_averages_impact_factor(self, mock_impact, mock_get_weather):
        """Weather impact factor is averaged across all samples."""
        # Setup: 30-minute trip
        start_time = datetime.now(timezone.utc)
        end_time = start_time + timedelta(minutes=30)

        trip = Trip(
            id=1,
            start_time=start_time,
            end_time=end_time
        )

        # Create telemetry points
        points = []
        for i in range(7):
            points.append({
                "timestamp": (start_time + timedelta(minutes=i * 5)).isoformat(),
                "latitude": 37.7749,
                "longitude": -122.4194
            })

        # Mock weather responses
        mock_get_weather.return_value = {
            "temperature_f": 70.0,
            "precipitation_in": 0.0,
            "wind_speed_mph": 10.0,
            "conditions": "Clear"
        }

        # Mock impact factors: 1.0, 1.1, 1.2
        mock_impact.side_effect = [1.0, 1.1, 1.2]

        # Execute
        fetch_trip_weather(trip, points)

        # Verify: Should average 1.0, 1.1, 1.2 = 1.1
        assert trip.weather_impact_factor == 1.1

    @patch("services.trip_service.get_weather_for_location")
    def test_handles_no_gps_points_gracefully(self, mock_get_weather):
        """Handles trips without GPS data gracefully."""
        # Setup: Trip with no GPS points
        start_time = datetime.now(timezone.utc)
        end_time = start_time + timedelta(minutes=30)

        trip = Trip(
            id=1,
            start_time=start_time,
            end_time=end_time
        )

        # No GPS points
        points = []

        # Execute
        fetch_trip_weather(trip, points)

        # Verify: Should not crash, no weather data set
        assert trip.weather_temp_f is None
        assert trip.weather_conditions is None
        assert mock_get_weather.call_count == 0

    @patch("services.trip_service.get_weather_for_location")
    def test_uses_actual_timestamp_from_telemetry(self, mock_get_weather):
        """Uses actual telemetry timestamps, not just start/end times."""
        # Setup: Trip with timestamps
        start_time = datetime(2025, 1, 1, 10, 0, 0, tzinfo=timezone.utc)
        end_time = datetime(2025, 1, 1, 10, 30, 0, tzinfo=timezone.utc)

        trip = Trip(
            id=1,
            start_time=start_time,
            end_time=end_time
        )

        # Create telemetry points at specific times
        points = [
            {
                "timestamp": datetime(2025, 1, 1, 10, 0, 0, tzinfo=timezone.utc).isoformat(),
                "latitude": 37.7749,
                "longitude": -122.4194
            },
            {
                "timestamp": datetime(2025, 1, 1, 10, 15, 0, tzinfo=timezone.utc).isoformat(),
                "latitude": 37.7750,
                "longitude": -122.4195
            },
            {
                "timestamp": datetime(2025, 1, 1, 10, 30, 0, tzinfo=timezone.utc).isoformat(),
                "latitude": 37.7751,
                "longitude": -122.4196
            }
        ]

        # Mock weather response
        mock_get_weather.return_value = {
            "temperature_f": 70.0,
            "precipitation_in": 0.0,
            "wind_speed_mph": 10.0,
            "conditions": "Clear"
        }

        # Execute
        fetch_trip_weather(trip, points)

        # Verify: Should have called with the actual timestamps
        assert mock_get_weather.call_count == 3

        # Check that the sample times match our expected times (0, 15, 30 minutes)
        call_args = mock_get_weather.call_args_list
        sample_times = [call[0][2] for call in call_args]  # Third argument is the timestamp

        assert sample_times[0] == start_time
        assert sample_times[1] == datetime(2025, 1, 1, 10, 15, 0, tzinfo=timezone.utc)
        assert sample_times[2] == end_time
