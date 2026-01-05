"""
Tests for timezone utility module.

Tests the timezone handling functions including:
- utc_now() for getting current UTC time
- normalize_datetime() for stripping timezone info
- ensure_utc() for adding UTC timezone info
- is_before() and is_after() for safe datetime comparisons
"""

from datetime import datetime, timedelta, timezone  # noqa: F401
from zoneinfo import ZoneInfo

import pytest  # noqa: F401
from utils.timezone import ensure_utc, is_after, is_before, normalize_datetime, utc_now


class TestUtcNow:
    """Tests for utc_now function."""

    def test_returns_naive_datetime(self):
        """utc_now should return datetime without timezone info."""
        result = utc_now()
        assert result.tzinfo is None

    def test_returns_utc_time(self):
        """utc_now should return time close to current UTC."""
        before = datetime.utcnow()
        result = utc_now()
        after = datetime.utcnow()

        assert before <= result <= after

    def test_returns_datetime_type(self):
        """utc_now should return a datetime object."""
        result = utc_now()
        assert isinstance(result, datetime)


class TestNormalizeDatetime:
    """Tests for normalize_datetime function."""

    def test_none_input_returns_none(self):
        """None input should return None."""
        result = normalize_datetime(None)
        assert result is None

    def test_naive_datetime_unchanged(self):
        """Naive datetime should be returned unchanged."""
        dt = datetime(2024, 6, 15, 12, 30, 0)
        result = normalize_datetime(dt)

        assert result == dt
        assert result.tzinfo is None

    def test_utc_aware_datetime_strips_tzinfo(self):
        """UTC-aware datetime should have tzinfo stripped."""
        dt = datetime(2024, 6, 15, 12, 30, 0, tzinfo=timezone.utc)
        result = normalize_datetime(dt)

        assert result == datetime(2024, 6, 15, 12, 30, 0)
        assert result.tzinfo is None

    def test_non_utc_aware_converts_to_utc_then_strips(self):
        """Non-UTC timezone should be converted to UTC then stripped."""
        # US Eastern time (UTC-5)
        eastern = ZoneInfo("America/New_York")
        dt = datetime(2024, 6, 15, 8, 30, 0, tzinfo=eastern)

        result = normalize_datetime(dt)

        # 8:30 AM Eastern = 12:30 PM UTC in summer (EDT)
        assert result.hour == 12
        assert result.minute == 30
        assert result.tzinfo is None

    def test_preserves_date_components(self):
        """Date components should be preserved."""
        dt = datetime(2024, 6, 15, 12, 30, 45, 123456, tzinfo=timezone.utc)
        result = normalize_datetime(dt)

        assert result.year == 2024
        assert result.month == 6
        assert result.day == 15
        assert result.hour == 12
        assert result.minute == 30
        assert result.second == 45
        assert result.microsecond == 123456


class TestEnsureUtc:
    """Tests for ensure_utc function."""

    def test_none_input_returns_none(self):
        """None input should return None."""
        result = ensure_utc(None)
        assert result is None

    def test_naive_datetime_adds_utc_tzinfo(self):
        """Naive datetime should have UTC tzinfo added."""
        dt = datetime(2024, 6, 15, 12, 30, 0)
        result = ensure_utc(dt)

        assert result.tzinfo == timezone.utc
        assert result.hour == 12  # Time unchanged

    def test_utc_aware_datetime_unchanged(self):
        """UTC-aware datetime should be returned as-is."""
        dt = datetime(2024, 6, 15, 12, 30, 0, tzinfo=timezone.utc)
        result = ensure_utc(dt)

        assert result == dt
        assert result.tzinfo == timezone.utc

    def test_non_utc_aware_converts_to_utc(self):
        """Non-UTC timezone should be converted to UTC."""
        eastern = ZoneInfo("America/New_York")
        dt = datetime(2024, 6, 15, 8, 30, 0, tzinfo=eastern)

        result = ensure_utc(dt)

        # 8:30 AM Eastern = 12:30 PM UTC in summer (EDT)
        assert result.hour == 12
        assert result.minute == 30
        assert result.tzinfo == timezone.utc


class TestIsBefore:
    """Tests for is_before function."""

    def test_none_first_returns_false(self):
        """None as first arg should return False."""
        dt = datetime(2024, 6, 15, 12, 30, 0)
        result = is_before(None, dt)
        assert result is False

    def test_none_second_returns_false(self):
        """None as second arg should return False."""
        dt = datetime(2024, 6, 15, 12, 30, 0)
        result = is_before(dt, None)
        assert result is False

    def test_both_none_returns_false(self):
        """Both None should return False."""
        result = is_before(None, None)
        assert result is False

    def test_both_naive_compares_correctly(self):
        """Two naive datetimes should compare correctly."""
        dt1 = datetime(2024, 6, 15, 12, 0, 0)
        dt2 = datetime(2024, 6, 15, 14, 0, 0)

        assert is_before(dt1, dt2) is True
        assert is_before(dt2, dt1) is False

    def test_equal_datetimes_returns_false(self):
        """Equal datetimes should return False for is_before."""
        dt1 = datetime(2024, 6, 15, 12, 0, 0)
        dt2 = datetime(2024, 6, 15, 12, 0, 0)

        assert is_before(dt1, dt2) is False

    def test_mixed_timezone_compares_correctly(self):
        """Mixed naive and aware datetimes should compare correctly."""
        naive = datetime(2024, 6, 15, 12, 0, 0)
        aware = datetime(2024, 6, 15, 14, 0, 0, tzinfo=timezone.utc)

        assert is_before(naive, aware) is True
        assert is_before(aware, naive) is False

    def test_different_timezones_compare_correctly(self):
        """Different timezones should compare correctly."""
        eastern = ZoneInfo("America/New_York")
        utc = timezone.utc

        # 8:00 AM Eastern = 12:00 PM UTC in summer
        dt1 = datetime(2024, 6, 15, 8, 0, 0, tzinfo=eastern)
        # 14:00 UTC is after 12:00 UTC
        dt2 = datetime(2024, 6, 15, 14, 0, 0, tzinfo=utc)

        assert is_before(dt1, dt2) is True


class TestIsAfter:
    """Tests for is_after function."""

    def test_none_first_returns_false(self):
        """None as first arg should return False."""
        dt = datetime(2024, 6, 15, 12, 30, 0)
        result = is_after(None, dt)
        assert result is False

    def test_none_second_returns_false(self):
        """None as second arg should return False."""
        dt = datetime(2024, 6, 15, 12, 30, 0)
        result = is_after(dt, None)
        assert result is False

    def test_both_none_returns_false(self):
        """Both None should return False."""
        result = is_after(None, None)
        assert result is False

    def test_both_naive_compares_correctly(self):
        """Two naive datetimes should compare correctly."""
        dt1 = datetime(2024, 6, 15, 14, 0, 0)
        dt2 = datetime(2024, 6, 15, 12, 0, 0)

        assert is_after(dt1, dt2) is True
        assert is_after(dt2, dt1) is False

    def test_equal_datetimes_returns_false(self):
        """Equal datetimes should return False for is_after."""
        dt1 = datetime(2024, 6, 15, 12, 0, 0)
        dt2 = datetime(2024, 6, 15, 12, 0, 0)

        assert is_after(dt1, dt2) is False

    def test_mixed_timezone_compares_correctly(self):
        """Mixed naive and aware datetimes should compare correctly."""
        naive = datetime(2024, 6, 15, 14, 0, 0)
        aware = datetime(2024, 6, 15, 12, 0, 0, tzinfo=timezone.utc)

        assert is_after(naive, aware) is True
        assert is_after(aware, naive) is False


class TestTimezoneEdgeCases:
    """Test edge cases and real-world scenarios."""

    def test_dst_transition(self):
        """Test datetime during DST transition."""
        eastern = ZoneInfo("America/New_York")

        # March 10, 2024 - DST begins in US (clocks spring forward)
        # 2:00 AM EST becomes 3:00 AM EDT
        before_dst = datetime(2024, 3, 10, 1, 0, 0, tzinfo=eastern)
        after_dst = datetime(2024, 3, 10, 3, 30, 0, tzinfo=eastern)

        # Both should normalize without error
        norm_before = normalize_datetime(before_dst)
        norm_after = normalize_datetime(after_dst)

        assert norm_before is not None
        assert norm_after is not None
        assert is_before(before_dst, after_dst)

    def test_year_boundary(self):
        """Test datetime at year boundary."""
        dt1 = datetime(2023, 12, 31, 23, 59, 59, tzinfo=timezone.utc)
        dt2 = datetime(2024, 1, 1, 0, 0, 0, tzinfo=timezone.utc)

        assert is_before(dt1, dt2)
        assert is_after(dt2, dt1)

    def test_microsecond_precision(self):
        """Test microsecond precision is preserved."""
        dt = datetime(2024, 6, 15, 12, 30, 45, 999999, tzinfo=timezone.utc)
        result = normalize_datetime(dt)

        assert result.microsecond == 999999

    def test_chained_operations(self):
        """Test chaining normalize and ensure operations."""
        original = datetime(2024, 6, 15, 12, 30, 0, tzinfo=timezone.utc)

        # Normalize then ensure UTC
        normalized = normalize_datetime(original)
        ensured = ensure_utc(normalized)

        # Should be equivalent to original (with tzinfo)
        assert ensured.year == original.year
        assert ensured.month == original.month
        assert ensured.day == original.day
        assert ensured.hour == original.hour
        assert ensured.minute == original.minute
        assert ensured.tzinfo == timezone.utc
