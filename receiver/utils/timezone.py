"""
Timezone utilities for consistent datetime handling.

The Problem:
- PostgreSQL stores timezone-aware datetimes (with tzinfo)
- SQLite stores naive datetimes (without tzinfo)
- Comparing aware and naive datetimes raises TypeError

The Solution:
- Use these helpers for all datetime operations
- normalize_datetime() strips timezone for safe comparisons
- utc_now() returns consistent naive UTC time
- ensure_utc() converts any datetime to naive UTC
"""

from datetime import datetime, timezone as tz
from typing import Optional


def utc_now() -> datetime:
    """
    Get current UTC time as a naive datetime.

    Returns a datetime without timezone info for database compatibility.
    Use this instead of datetime.utcnow() or datetime.now(timezone.utc).

    Returns:
        Current UTC time without tzinfo
    """
    return datetime.utcnow()


def normalize_datetime(dt: Optional[datetime]) -> Optional[datetime]:
    """
    Convert a datetime to naive UTC for safe comparisons.

    This handles the common case where you need to compare datetimes
    from different sources (PostgreSQL vs SQLite, user input, etc).

    Args:
        dt: A datetime that may or may not have timezone info

    Returns:
        Naive datetime in UTC, or None if input was None

    Examples:
        >>> aware = datetime(2024, 1, 1, 12, 0, tzinfo=timezone.utc)
        >>> normalize_datetime(aware)
        datetime.datetime(2024, 1, 1, 12, 0)

        >>> naive = datetime(2024, 1, 1, 12, 0)
        >>> normalize_datetime(naive)
        datetime.datetime(2024, 1, 1, 12, 0)
    """
    if dt is None:
        return None

    if dt.tzinfo is not None:
        # Convert to UTC first if it has a different timezone
        if dt.tzinfo != tz.utc:
            dt = dt.astimezone(tz.utc)
        # Strip the timezone info
        return dt.replace(tzinfo=None)

    return dt


def ensure_utc(dt: Optional[datetime]) -> Optional[datetime]:
    """
    Ensure a datetime is timezone-aware in UTC.

    Use this when you need a timezone-aware datetime (e.g., for APIs).

    Args:
        dt: A datetime that may or may not have timezone info

    Returns:
        Timezone-aware datetime in UTC, or None if input was None
    """
    if dt is None:
        return None

    if dt.tzinfo is None:
        # Assume naive datetimes are already UTC
        return dt.replace(tzinfo=tz.utc)

    # Convert to UTC if it has a different timezone
    return dt.astimezone(tz.utc)


def is_before(dt1: Optional[datetime], dt2: Optional[datetime]) -> bool:
    """
    Safely compare two datetimes, handling mixed timezone states.

    Args:
        dt1: First datetime
        dt2: Second datetime

    Returns:
        True if dt1 < dt2, False otherwise
        Returns False if either is None
    """
    if dt1 is None or dt2 is None:
        return False

    return normalize_datetime(dt1) < normalize_datetime(dt2)


def is_after(dt1: Optional[datetime], dt2: Optional[datetime]) -> bool:
    """
    Safely compare two datetimes, handling mixed timezone states.

    Args:
        dt1: First datetime
        dt2: Second datetime

    Returns:
        True if dt1 > dt2, False otherwise
        Returns False if either is None
    """
    if dt1 is None or dt2 is None:
        return False

    return normalize_datetime(dt1) > normalize_datetime(dt2)
