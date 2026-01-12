"""
Time parsing and manipulation utilities for VoltTracker.

Provides consistent date/time parsing across all routes with:
- Multiple format support (ISO, YYYY-MM-DD, Unix timestamp)
- Timezone handling (UTC normalization)
- Query parameter parsing
- Date range validation
- Common date shortcuts (today, yesterday, last_7_days, etc.)
"""

import logging
from datetime import datetime, timedelta, timezone
from typing import Optional, Tuple, Dict, Any
from dateutil import parser as date_parser

logger = logging.getLogger(__name__)


# Common date format patterns
DATETIME_FORMATS = [
    "%Y-%m-%d",  # 2024-01-15
    "%Y-%m-%dT%H:%M:%S",  # 2024-01-15T14:30:00
    "%Y-%m-%dT%H:%M:%SZ",  # 2024-01-15T14:30:00Z
    "%Y-%m-%dT%H:%M:%S%z",  # 2024-01-15T14:30:00+00:00
    "%Y/%m/%d",  # 2024/01/15
    "%m/%d/%Y",  # 01/15/2024
    "%d-%m-%Y",  # 15-01-2024
]


def utc_now() -> datetime:
    """
    Get current UTC time with timezone info.

    Returns:
        datetime: Current time in UTC timezone

    Example:
        >>> now = utc_now()
        >>> print(now.tzinfo)
        UTC
    """
    return datetime.now(timezone.utc)


def parse_datetime(
    date_string: str,
    default: Optional[datetime] = None,
    assume_utc: bool = True
) -> Optional[datetime]:
    """
    Parse a date/time string into a datetime object.

    Supports multiple formats:
    - ISO 8601: "2024-01-15T14:30:00Z"
    - Date only: "2024-01-15"
    - Unix timestamp: "1705329000"
    - Common formats: "01/15/2024", "15-01-2024"

    Args:
        date_string: The date/time string to parse
        default: Value to return if parsing fails (default: None)
        assume_utc: If True and no timezone in string, assume UTC (default: True)

    Returns:
        datetime object or default value if parsing fails

    Example:
        >>> dt = parse_datetime("2024-01-15")
        >>> print(dt)
        2024-01-15 00:00:00+00:00

        >>> dt = parse_datetime("invalid")
        >>> print(dt)
        None
    """
    if not date_string:
        return default

    date_string = date_string.strip()

    # Try Unix timestamp first
    if date_string.isdigit():
        try:
            timestamp = int(date_string)
            return datetime.fromtimestamp(timestamp, tz=timezone.utc)
        except (ValueError, OSError):
            pass

    # Try dateutil parser (handles most formats)
    try:
        dt = date_parser.parse(date_string)
        # Add UTC timezone if naive and assume_utc is True
        if assume_utc and dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt
    except (ValueError, TypeError):
        pass

    # Try explicit formats
    for fmt in DATETIME_FORMATS:
        try:
            dt = datetime.strptime(date_string, fmt)
            if assume_utc and dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            return dt
        except ValueError:
            continue

    logger.warning(f"Failed to parse datetime string: {date_string}")
    return default


def parse_date_range(
    start_str: Optional[str] = None,
    end_str: Optional[str] = None,
    default_days: int = 30
) -> Tuple[datetime, datetime]:
    """
    Parse start and end date strings into a date range.

    If neither provided, returns range from (now - default_days) to now.
    If only start provided, end defaults to now.
    If only end provided, start defaults to (end - default_days).

    Args:
        start_str: Start date string (optional)
        end_str: End date string (optional)
        default_days: Days to look back if dates not provided (default: 30)

    Returns:
        Tuple of (start_datetime, end_datetime) in UTC

    Example:
        >>> start, end = parse_date_range("2024-01-01", "2024-01-31")
        >>> print(start, end)
        2024-01-01 00:00:00+00:00 2024-01-31 23:59:59+00:00
    """
    now = utc_now()

    # Parse end date
    if end_str:
        end_date = parse_datetime(end_str, default=now)
        # Set to end of day if only date provided (no time)
        if len(end_str) <= 10:  # Date only, no time component
            end_date = end_date.replace(hour=23, minute=59, second=59)
    else:
        end_date = now

    # Parse start date
    if start_str:
        start_date = parse_datetime(start_str, default=now - timedelta(days=default_days))
        # Set to start of day if only date provided
        if len(start_str) <= 10:
            start_date = start_date.replace(hour=0, minute=0, second=0)
    else:
        start_date = end_date - timedelta(days=default_days)

    # Ensure start is before end
    if start_date > end_date:
        start_date, end_date = end_date, start_date
        logger.warning(f"Start date after end date, swapped: {start_date} <-> {end_date}")

    return start_date, end_date


def parse_query_date_range(
    args: Dict[str, Any],
    start_param: str = "start_date",
    end_param: str = "end_date",
    default_days: int = 30
) -> Tuple[datetime, datetime]:
    """
    Parse date range from Flask request.args or dict.

    Args:
        args: Request args dict (e.g., request.args)
        start_param: Name of start date parameter (default: "start_date")
        end_param: Name of end date parameter (default: "end_date")
        default_days: Days to look back if not provided (default: 30)

    Returns:
        Tuple of (start_datetime, end_datetime)

    Example:
        >>> from flask import request
        >>> start, end = parse_query_date_range(request.args)
    """
    start_str = args.get(start_param)
    end_str = args.get(end_param)
    return parse_date_range(start_str, end_str, default_days)


# Date shortcut constants
DATE_SHORTCUTS = {
    "today": lambda: (
        utc_now().replace(hour=0, minute=0, second=0),
        utc_now()
    ),
    "yesterday": lambda: (
        (utc_now() - timedelta(days=1)).replace(hour=0, minute=0, second=0),
        (utc_now() - timedelta(days=1)).replace(hour=23, minute=59, second=59)
    ),
    "last_7_days": lambda: (
        utc_now() - timedelta(days=7),
        utc_now()
    ),
    "last_30_days": lambda: (
        utc_now() - timedelta(days=30),
        utc_now()
    ),
    "last_90_days": lambda: (
        utc_now() - timedelta(days=90),
        utc_now()
    ),
    "this_week": lambda: (
        utc_now() - timedelta(days=utc_now().weekday()),
        utc_now()
    ),
    "this_month": lambda: (
        utc_now().replace(day=1, hour=0, minute=0, second=0),
        utc_now()
    ),
    "last_month": lambda: (
        (utc_now().replace(day=1) - timedelta(days=1)).replace(day=1, hour=0, minute=0, second=0),
        (utc_now().replace(day=1) - timedelta(days=1)).replace(hour=23, minute=59, second=59)
    ),
}


def parse_date_shortcut(shortcut: str) -> Optional[Tuple[datetime, datetime]]:
    """
    Parse a date shortcut into a date range.

    Supported shortcuts:
    - "today": Today 00:00 to now
    - "yesterday": Yesterday 00:00 to 23:59
    - "last_7_days": 7 days ago to now
    - "last_30_days": 30 days ago to now
    - "last_90_days": 90 days ago to now
    - "this_week": Start of week to now
    - "this_month": Start of month to now
    - "last_month": Entire previous month

    Args:
        shortcut: The shortcut string

    Returns:
        Tuple of (start, end) or None if invalid shortcut

    Example:
        >>> start, end = parse_date_shortcut("last_7_days")
        >>> print(start, end)
        2024-01-08 14:30:00+00:00 2024-01-15 14:30:00+00:00
    """
    shortcut_lower = shortcut.lower()
    if shortcut_lower in DATE_SHORTCUTS:
        return DATE_SHORTCUTS[shortcut_lower]()
    return None


def format_datetime_iso(dt: Optional[datetime]) -> Optional[str]:
    """
    Format datetime as ISO 8601 string.

    Args:
        dt: datetime object

    Returns:
        ISO formatted string or None

    Example:
        >>> dt = utc_now()
        >>> print(format_datetime_iso(dt))
        2024-01-15T14:30:00+00:00
    """
    if dt is None:
        return None
    return dt.isoformat()


def format_datetime_readable(dt: Optional[datetime]) -> Optional[str]:
    """
    Format datetime as human-readable string.

    Args:
        dt: datetime object

    Returns:
        Formatted string like "Jan 15, 2024 2:30 PM" or None

    Example:
        >>> dt = utc_now()
        >>> print(format_datetime_readable(dt))
        Jan 15, 2024 2:30 PM
    """
    if dt is None:
        return None
    return dt.strftime("%b %d, %Y %I:%M %p")


def get_time_range_description(start: datetime, end: datetime) -> str:
    """
    Get human-readable description of a time range.

    Args:
        start: Start datetime
        end: End datetime

    Returns:
        Description like "Last 7 days", "Jan 1 - Jan 31, 2024", etc.

    Example:
        >>> now = utc_now()
        >>> week_ago = now - timedelta(days=7)
        >>> desc = get_time_range_description(week_ago, now)
        >>> print(desc)
        Last 7 days
    """
    now = utc_now()
    delta = end - start

    # Check for common shortcuts
    if abs((end - now).total_seconds()) < 3600:  # Within 1 hour of now
        if delta.days == 7:
            return "Last 7 days"
        elif delta.days == 30:
            return "Last 30 days"
        elif delta.days == 90:
            return "Last 90 days"
        elif delta.days < 1:
            return "Today"

    # Format as date range
    if start.year == end.year:
        if start.month == end.month:
            return f"{start.strftime('%b %d')} - {end.strftime('%d, %Y')}"
        else:
            return f"{start.strftime('%b %d')} - {end.strftime('%b %d, %Y')}"
    else:
        return f"{start.strftime('%b %d, %Y')} - {end.strftime('%b %d, %Y')}"


def days_ago(days: int) -> datetime:
    """
    Get datetime for N days ago from now.

    Args:
        days: Number of days ago

    Returns:
        datetime N days ago

    Example:
        >>> seven_days_ago = days_ago(7)
    """
    return utc_now() - timedelta(days=days)


def hours_ago(hours: int) -> datetime:
    """
    Get datetime for N hours ago from now.

    Args:
        hours: Number of hours ago

    Returns:
        datetime N hours ago

    Example:
        >>> two_hours_ago = hours_ago(2)
    """
    return utc_now() - timedelta(hours=hours)
