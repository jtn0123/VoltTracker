"""Tests for trip finalization calculations."""

import os
import sys
import pytest
from datetime import datetime, timezone
from unittest.mock import MagicMock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

from services.trip_service import calculate_trip_basics
from models import Trip, TelemetryRaw


class TestCalculateTripBasics:
    """Tests for calculate_trip_basics function."""

    def test_empty_telemetry(self):
        """Should handle empty telemetry gracefully."""
        trip = MagicMock(spec=Trip)
        trip.id = 1
        calculate_trip_basics(trip, [])
        # Should not crash

    def test_sets_end_time(self):
        """Should set end_time from last telemetry point."""
        trip = MagicMock(spec=Trip)
        trip.id = 1
        trip.start_odometer = 50000.0

        t1 = MagicMock(spec=TelemetryRaw)
        t1.timestamp = datetime(2026, 1, 1, 10, 0, tzinfo=timezone.utc)
        t1.odometer_miles = 50010.0
        t1.to_dict.return_value = {"ambient_temp_f": 70.0}

        t2 = MagicMock(spec=TelemetryRaw)
        t2.timestamp = datetime(2026, 1, 1, 11, 0, tzinfo=timezone.utc)
        t2.odometer_miles = 50025.0
        t2.to_dict.return_value = {"ambient_temp_f": 72.0}

        calculate_trip_basics(trip, [t1, t2])
        assert trip.end_time == t2.timestamp
        assert trip.end_odometer == pytest.approx(50025.0)

    def test_calculates_distance(self):
        """Should calculate distance_miles from odometer difference."""
        trip = MagicMock(spec=Trip)
        trip.id = 1
        trip.start_odometer = 50000.0

        t1 = MagicMock(spec=TelemetryRaw)
        t1.timestamp = datetime(2026, 1, 1, 10, 0, tzinfo=timezone.utc)
        t1.odometer_miles = 50000.0
        t1.to_dict.return_value = {"ambient_temp_f": 70.0}

        t2 = MagicMock(spec=TelemetryRaw)
        t2.timestamp = datetime(2026, 1, 1, 11, 0, tzinfo=timezone.utc)
        t2.odometer_miles = 50030.0
        t2.to_dict.return_value = {"ambient_temp_f": 70.0}

        calculate_trip_basics(trip, [t1, t2])
        assert trip.distance_miles == pytest.approx(30.0)


class TestTimeUtils:
    """Tests for time utility functions."""

    def test_utc_now(self):
        from utils.time_utils import utc_now
        now = utc_now()
        assert now.tzinfo is not None

    def test_parse_datetime_iso(self):
        from utils.time_utils import parse_datetime
        result = parse_datetime("2026-01-15")
        assert result is not None
        assert result.year == 2026
        assert result.month == 1
        assert result.day == 15

    def test_parse_datetime_with_time(self):
        from utils.time_utils import parse_datetime
        result = parse_datetime("2026-01-15T14:30:00Z")
        assert result is not None
        assert result.hour == 14
        assert result.minute == 30

    def test_parse_datetime_invalid(self):
        from utils.time_utils import parse_datetime
        result = parse_datetime("not-a-date")
        # Should return default (None)
        assert result is None

    def test_parse_datetime_with_default(self):
        from utils.time_utils import parse_datetime
        default = datetime(2026, 6, 1, tzinfo=timezone.utc)
        result = parse_datetime("garbage", default=default)
        assert result == default
