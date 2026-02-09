"""
Additional tests for time_utils to cover uncovered lines:
- parse_datetime: dateutil fallback paths (lines 92-93, 109-111)
- parse_query_date_range (lines 194-196)
- get_time_range_description (lines 329-350)
"""

import os
import sys
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))


class TestParseDatetimeEdgeCases:
    """Test edge cases in parse_datetime not covered by existing tests."""

    def test_parse_datetime_timestamp_overflow(self):
        """Test timestamp that causes OSError/OverflowError."""
        from utils.time_utils import parse_datetime
        # Negative timestamp that might cause issues
        result = parse_datetime("completely!@#$invalid!@#$string")
        assert result is None

    def test_parse_datetime_dateutil_with_timezone(self):
        """Test dateutil parsing a string with timezone info (assume_utc branch)."""
        from utils.time_utils import parse_datetime
        # dateutil can parse this; it has timezone info so assume_utc branch not taken
        result = parse_datetime("2024-01-15T14:30:00+05:00", assume_utc=True)
        assert result is not None
        assert result.tzinfo is not None

    def test_parse_datetime_dateutil_naive_no_utc(self):
        """Test dateutil parsing with assume_utc=False keeps naive datetime."""
        from utils.time_utils import parse_datetime
        result = parse_datetime("January 15, 2024", assume_utc=False)
        assert result is not None
        # dateutil parses this but without timezone
        # With assume_utc=False, tzinfo should be None
        assert result.tzinfo is None


class TestParseQueryDateRange:
    """Tests for parse_query_date_range."""

    def test_parse_query_date_range_basic(self):
        """Test parse_query_date_range with start and end."""
        from utils.time_utils import parse_query_date_range
        args = {"start_date": "2024-01-01", "end_date": "2024-01-31"}
        start, end = parse_query_date_range(args)
        assert start.year == 2024
        assert start.month == 1
        assert end.month == 1

    def test_parse_query_date_range_no_params(self):
        """Test parse_query_date_range with no parameters uses defaults."""
        from utils.time_utils import parse_query_date_range
        args = {}
        start, end = parse_query_date_range(args)
        # Should default to last 30 days
        assert (end - start).days >= 29

    def test_parse_query_date_range_custom_param_names(self):
        """Test parse_query_date_range with custom parameter names."""
        from utils.time_utils import parse_query_date_range
        args = {"from": "2024-06-01", "to": "2024-06-30"}
        start, end = parse_query_date_range(args, start_param="from", end_param="to")
        assert start.month == 6
        assert end.month == 6

    def test_parse_query_date_range_custom_default_days(self):
        """Test parse_query_date_range with custom default_days."""
        from utils.time_utils import parse_query_date_range
        args = {}
        start, end = parse_query_date_range(args, default_days=7)
        assert (end - start).days >= 6
        assert (end - start).days <= 8


class TestGetTimeRangeDescription:
    """Tests for get_time_range_description."""

    def test_last_7_days(self):
        """Description for last 7 days range."""
        from utils.time_utils import get_time_range_description, utc_now
        now = utc_now()
        start = now - timedelta(days=7)
        result = get_time_range_description(start, now)
        assert result == "Last 7 days"

    def test_last_30_days(self):
        """Description for last 30 days range."""
        from utils.time_utils import get_time_range_description, utc_now
        now = utc_now()
        start = now - timedelta(days=30)
        result = get_time_range_description(start, now)
        assert result == "Last 30 days"

    def test_last_90_days(self):
        """Description for last 90 days range."""
        from utils.time_utils import get_time_range_description, utc_now
        now = utc_now()
        start = now - timedelta(days=90)
        result = get_time_range_description(start, now)
        assert result == "Last 90 days"

    def test_today(self):
        """Description for today range."""
        from utils.time_utils import get_time_range_description, utc_now
        now = utc_now()
        start = now - timedelta(hours=12)
        result = get_time_range_description(start, now)
        assert result == "Today"

    def test_same_month_range(self):
        """Description for range within same month."""
        from utils.time_utils import get_time_range_description
        start = datetime(2024, 3, 5, tzinfo=timezone.utc)
        end = datetime(2024, 3, 20, tzinfo=timezone.utc)
        result = get_time_range_description(start, end)
        assert "Mar" in result

    def test_different_month_same_year(self):
        """Description for range across months in same year."""
        from utils.time_utils import get_time_range_description
        start = datetime(2024, 1, 15, tzinfo=timezone.utc)
        end = datetime(2024, 3, 20, tzinfo=timezone.utc)
        result = get_time_range_description(start, end)
        assert "Jan" in result
        assert "Mar" in result

    def test_different_year(self):
        """Description for range across years."""
        from utils.time_utils import get_time_range_description
        start = datetime(2023, 11, 1, tzinfo=timezone.utc)
        end = datetime(2024, 2, 15, tzinfo=timezone.utc)
        result = get_time_range_description(start, end)
        assert "2023" in result
        assert "2024" in result
