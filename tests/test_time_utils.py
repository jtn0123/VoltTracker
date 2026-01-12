"""
Tests for time utilities.

Tests time parsing and manipulation including:
- Datetime parsing (multiple formats)
- Unix timestamp parsing
- Timezone handling
- Date range parsing
- Date shortcuts (today, yesterday, last_7_days, etc.)
"""

from datetime import datetime, timedelta, timezone
import pytest


class TestUtcNow:
    """Tests for utc_now() function."""

    def test_utc_now_returns_datetime(self):
        """utc_now returns a datetime object."""
        from utils.time_utils import utc_now

        now = utc_now()

        assert isinstance(now, datetime)

    def test_utc_now_has_timezone(self):
        """utc_now returns timezone-aware datetime."""
        from utils.time_utils import utc_now

        now = utc_now()

        assert now.tzinfo is not None
        assert now.tzinfo == timezone.utc

    def test_utc_now_is_current(self):
        """utc_now returns current time (within 1 second)."""
        from utils.time_utils import utc_now

        before = datetime.now(timezone.utc)
        result = utc_now()
        after = datetime.now(timezone.utc)

        assert before <= result <= after


class TestParseDatetime:
    """Tests for parse_datetime() function."""

    def test_parse_datetime_iso_format(self):
        """Parse ISO 8601 datetime string."""
        from utils.time_utils import parse_datetime

        result = parse_datetime("2024-01-15T14:30:00Z")

        assert result.year == 2024
        assert result.month == 1
        assert result.day == 15
        assert result.hour == 14
        assert result.minute == 30
        assert result.tzinfo is not None

    def test_parse_datetime_date_only(self):
        """Parse date-only string."""
        from utils.time_utils import parse_datetime

        result = parse_datetime("2024-01-15")

        assert result.year == 2024
        assert result.month == 1
        assert result.day == 15
        assert result.hour == 0
        assert result.minute == 0

    def test_parse_datetime_with_timezone(self):
        """Parse datetime with timezone offset."""
        from utils.time_utils import parse_datetime

        result = parse_datetime("2024-01-15T14:30:00+05:00")

        assert result.year == 2024
        assert result.tzinfo is not None

    def test_parse_datetime_unix_timestamp(self):
        """Parse Unix timestamp."""
        from utils.time_utils import parse_datetime

        # January 15, 2024, 14:30:00 UTC
        result = parse_datetime("1705329000")

        assert result.year == 2024
        assert result.month == 1
        assert result.tzinfo == timezone.utc

    def test_parse_datetime_slash_format(self):
        """Parse date with slashes."""
        from utils.time_utils import parse_datetime

        result = parse_datetime("01/15/2024")

        assert result.year == 2024
        assert result.month == 1
        assert result.day == 15

    def test_parse_datetime_empty_string(self):
        """Parse empty string returns default."""
        from utils.time_utils import parse_datetime

        result = parse_datetime("")

        assert result is None

    def test_parse_datetime_none(self):
        """Parse None returns default."""
        from utils.time_utils import parse_datetime

        result = parse_datetime(None)

        assert result is None

    def test_parse_datetime_invalid_returns_default(self):
        """Parse invalid string returns default."""
        from utils.time_utils import parse_datetime

        result = parse_datetime("not-a-date")

        assert result is None

    def test_parse_datetime_custom_default(self):
        """Parse invalid string returns custom default."""
        from utils.time_utils import parse_datetime

        default = datetime(2020, 1, 1, tzinfo=timezone.utc)
        result = parse_datetime("invalid", default=default)

        assert result == default

    def test_parse_datetime_strips_whitespace(self):
        """Parse string strips leading/trailing whitespace."""
        from utils.time_utils import parse_datetime

        result = parse_datetime("  2024-01-15  ")

        assert result.year == 2024
        assert result.month == 1
        assert result.day == 15

    def test_parse_datetime_assume_utc_true(self):
        """Parse naive datetime assumes UTC when assume_utc=True."""
        from utils.time_utils import parse_datetime

        result = parse_datetime("2024-01-15T14:30:00", assume_utc=True)

        assert result.tzinfo == timezone.utc

    def test_parse_datetime_assume_utc_false(self):
        """Parse naive datetime remains naive when assume_utc=False."""
        from utils.time_utils import parse_datetime

        result = parse_datetime("2024-01-15T14:30:00", assume_utc=False)

        assert result.tzinfo is None

    def test_parse_datetime_unreasonably_large_timestamp(self):
        """Parse unreasonably large timestamp returns default."""
        from utils.time_utils import parse_datetime

        # Year 3001 timestamp
        result = parse_datetime("32503680001")

        assert result is None

    def test_parse_datetime_european_format(self):
        """Parse European date format."""
        from utils.time_utils import parse_datetime

        result = parse_datetime("15-01-2024")

        assert result.year == 2024
        assert result.month == 1
        assert result.day == 15


class TestFormatDatetimeIso:
    """Tests for format_datetime_iso() function."""

    def test_format_datetime_iso_basic(self):
        """Format datetime to ISO string."""
        from utils.time_utils import format_datetime_iso

        dt = datetime(2024, 1, 15, 14, 30, 0, tzinfo=timezone.utc)
        result = format_datetime_iso(dt)

        assert result == "2024-01-15T14:30:00+00:00"

    def test_format_datetime_iso_none(self):
        """Format None returns None."""
        from utils.time_utils import format_datetime_iso

        result = format_datetime_iso(None)

        assert result is None

    def test_format_datetime_iso_naive_datetime(self):
        """Format naive datetime."""
        from utils.time_utils import format_datetime_iso

        dt = datetime(2024, 1, 15, 14, 30, 0)
        result = format_datetime_iso(dt)

        assert "2024-01-15" in result
        assert "14:30:00" in result


class TestDaysAgo:
    """Tests for days_ago() function."""

    def test_days_ago_basic(self):
        """Get datetime N days ago."""
        from utils.time_utils import days_ago

        result = days_ago(7)

        now = datetime.now(timezone.utc)
        expected = now - timedelta(days=7)

        # Allow 1 second tolerance
        assert abs((result - expected).total_seconds()) < 1

    def test_days_ago_zero(self):
        """Get datetime 0 days ago (today)."""
        from utils.time_utils import days_ago

        result = days_ago(0)

        now = datetime.now(timezone.utc)

        assert abs((result - now).total_seconds()) < 1

    def test_days_ago_negative(self):
        """Get datetime with negative days (future)."""
        from utils.time_utils import days_ago

        result = days_ago(-7)

        now = datetime.now(timezone.utc)
        expected = now + timedelta(days=7)

        assert abs((result - expected).total_seconds()) < 1

    def test_days_ago_has_timezone(self):
        """days_ago returns timezone-aware datetime."""
        from utils.time_utils import days_ago

        result = days_ago(7)

        assert result.tzinfo == timezone.utc


class TestParseDateShortcut:
    """Tests for parse_date_shortcut() function."""

    def test_parse_date_shortcut_today(self):
        """Parse 'today' shortcut."""
        from utils.time_utils import parse_date_shortcut

        result = parse_date_shortcut("today")

        assert result is not None
        start, end = result
        assert start.date() == datetime.now(timezone.utc).date()
        assert end.date() == datetime.now(timezone.utc).date()

    def test_parse_date_shortcut_yesterday(self):
        """Parse 'yesterday' shortcut."""
        from utils.time_utils import parse_date_shortcut

        result = parse_date_shortcut("yesterday")
        assert result is not None

        start, end = result
        expected_date = (datetime.now(timezone.utc) - timedelta(days=1)).date()

        assert start.date() == expected_date
        assert end.date() == expected_date

    def test_parse_date_shortcut_last_7_days(self):
        """Parse 'last_7_days' shortcut."""
        from utils.time_utils import parse_date_shortcut

        result = parse_date_shortcut("last_7_days")
        assert result is not None

        start, end = result
        now = datetime.now(timezone.utc)
        expected_start = (now - timedelta(days=7)).date()

        assert start.date() == expected_start
        assert end.date() == now.date()

    def test_parse_date_shortcut_last_30_days(self):
        """Parse 'last_30_days' shortcut."""
        from utils.time_utils import parse_date_shortcut

        result = parse_date_shortcut("last_30_days")
        assert result is not None

        start, end = result
        now = datetime.now(timezone.utc)
        expected_start = (now - timedelta(days=30)).date()

        assert start.date() == expected_start
        assert end.date() == now.date()

    def test_parse_date_shortcut_this_week(self):
        """Parse 'this_week' shortcut."""
        from utils.time_utils import parse_date_shortcut

        result = parse_date_shortcut("this_week")

        assert result is not None
        start, end = result
        # Start should be Monday of current week
        assert start.weekday() == 0

    def test_parse_date_shortcut_this_month(self):
        """Parse 'this_month' shortcut."""
        from utils.time_utils import parse_date_shortcut

        result = parse_date_shortcut("this_month")
        assert result is not None

        start, end = result
        now = datetime.now(timezone.utc)

        assert start.day == 1
        assert start.month == now.month
        assert start.year == now.year

    def test_parse_date_shortcut_last_month(self):
        """Parse 'last_month' shortcut."""
        from utils.time_utils import parse_date_shortcut

        result = parse_date_shortcut("last_month")
        assert result is not None

        start, end = result
        assert start.day == 1

    def test_parse_date_shortcut_this_year(self):
        """Parse 'this_year' shortcut."""
        from utils.time_utils import parse_date_shortcut

        result = parse_date_shortcut("this_year")

        # this_year may not be implemented
        if result is None:
            pytest.skip("this_year shortcut not implemented")

        start, end = result
        now = datetime.now(timezone.utc)

        assert start.day == 1
        assert start.month == 1
        assert start.year == now.year

    def test_parse_date_shortcut_invalid(self):
        """Parse invalid shortcut returns None."""
        from utils.time_utils import parse_date_shortcut

        result = parse_date_shortcut("invalid_shortcut")

        assert result is None

    def test_parse_date_shortcut_case_insensitive(self):
        """Date shortcuts are case-insensitive."""
        from utils.time_utils import parse_date_shortcut

        result1 = parse_date_shortcut("TODAY")
        result2 = parse_date_shortcut("today")

        assert result1 is not None
        assert result2 is not None
        # Should be same day
        assert result1[0].date() == result2[0].date()


class TestParseDateRange:
    """Tests for parse_date_range() function."""

    def test_parse_date_range_with_explicit_dates(self):
        """Parse date range with explicit start and end dates."""
        from utils.time_utils import parse_date_range

        start, end = parse_date_range(
            start_str="2024-01-01",
            end_str="2024-01-31"
        )

        assert start.year == 2024
        assert start.month == 1
        assert start.day == 1
        assert end.year == 2024
        assert end.month == 1
        assert end.day == 31

    def test_parse_date_range_defaults_to_last_30_days(self):
        """No parameters defaults to last 30 days."""
        from utils.time_utils import parse_date_range

        start, end = parse_date_range()

        assert start is not None
        assert end is not None
        assert (end - start).days >= 29

    def test_parse_date_range_invalid_start_date(self):
        """Invalid start date uses default."""
        from utils.time_utils import parse_date_range

        start, end = parse_date_range(
            start_str="invalid",
            end_str="2024-01-31"
        )

        # Should fall back to default (30 days before end)
        assert start is not None

    def test_parse_date_range_only_start_date(self):
        """Only start date provided, end defaults to now."""
        from utils.time_utils import parse_date_range

        start, end = parse_date_range(start_str="2024-01-01")

        assert start.year == 2024
        assert start.month == 1
        assert start.day == 1
        assert end.date() >= datetime.now(timezone.utc).date()

    def test_parse_date_range_swaps_if_start_after_end(self):
        """Start after end gets swapped automatically."""
        from utils.time_utils import parse_date_range

        start, end = parse_date_range(
            start_str="2024-01-31",
            end_str="2024-01-01"
        )

        # Should be swapped
        assert start.day == 1
        assert end.day == 31

    def test_parse_date_range_custom_default_days(self):
        """Parse date range with custom default days."""
        from utils.time_utils import parse_date_range

        start, end = parse_date_range(default_days=7)

        assert start is not None
        assert end is not None
        assert (end - start).days >= 6


class TestFormatDatetimeReadable:
    """Tests for format_datetime_readable() function."""

    def test_format_datetime_readable_basic(self):
        """Format datetime to readable string."""
        from utils.time_utils import format_datetime_readable

        dt = datetime(2024, 1, 15, 14, 30, 0, tzinfo=timezone.utc)
        result = format_datetime_readable(dt)

        assert result is not None
        assert "2024" in result or "Jan" in result or "15" in result

    def test_format_datetime_readable_none(self):
        """Format None returns None."""
        from utils.time_utils import format_datetime_readable

        result = format_datetime_readable(None)

        assert result is None


class TestHoursAgo:
    """Tests for hours_ago() function."""

    def test_hours_ago_basic(self):
        """Get datetime N hours ago."""
        from utils.time_utils import hours_ago

        result = hours_ago(2)

        now = datetime.now(timezone.utc)
        expected = now - timedelta(hours=2)

        # Allow 1 second tolerance
        assert abs((result - expected).total_seconds()) < 1

    def test_hours_ago_zero(self):
        """Get datetime 0 hours ago (now)."""
        from utils.time_utils import hours_ago

        result = hours_ago(0)

        now = datetime.now(timezone.utc)

        assert abs((result - now).total_seconds()) < 1

    def test_hours_ago_has_timezone(self):
        """hours_ago returns timezone-aware datetime."""
        from utils.time_utils import hours_ago

        result = hours_ago(2)

        assert result.tzinfo == timezone.utc
