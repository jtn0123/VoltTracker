"""
Regression tests for backend audit bug fixes in receiver/utils/*.

One test per bug fix. Each test exercises the exact buggy code path so that
reverting the corresponding fix makes the test fail.

Files covered:
- time_utils.py        - Unix timestamp wrongly treated as date-only
- wide_events.py       - set_duration() overwrote an explicit duration_ms
- csv_importer.py      - duplicate detection ignored non-UTC timezones
- route_clustering.py  - similarity score was not symmetric
- query_cache.py       - DB Session args poisoned the cache key
- weather.py           - _parse_weather_response crashed/mis-flagged on null precip
- weather.py           - _check_db_cache crashed on tz-aware fetched_at
- calculations.py      - analyze_soc_floor dropped a 0.0% SOC reading
- torque_parser.py     - timestamp returned tz-aware instead of naive UTC

Note: the cache_utils.py _UNINITIALIZED / connection-failure fix is already
covered by tests/test_cache_utils.py (TestGetRedisCache). This file covers the
separate cache_utils Flask-Response encoding fix is intentionally NOT included
because it requires an app context; the remaining cache_utils change tested
here is via query_cache's key_func.
"""

import os
import sys
from datetime import datetime, timedelta, timezone
from unittest.mock import MagicMock

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))


# ---------------------------------------------------------------------------
# time_utils.py - parse_date_range: 10-digit Unix timestamp is NOT date-only
# ---------------------------------------------------------------------------
def test_parse_date_range_unix_timestamp_keeps_time_component():
    """A 10-digit Unix epoch string carries a time and must not be snapped
    to start/end of day. Bug: a bare ``len(s) <= 10`` check treated the
    10-char epoch as date-only and clobbered hour/minute/second."""
    from utils.time_utils import parse_date_range

    # 1705329000 -> 2024-01-15 14:30:00 UTC (14:30, not 00:00 / 23:59)
    start, end = parse_date_range("1705329000", "1705329000")

    # If treated as date-only, start would be 00:00:00 and end 23:59:59.
    assert (start.hour, start.minute, start.second) == (14, 30, 0)
    assert (end.hour, end.minute, end.second) == (14, 30, 0)


# ---------------------------------------------------------------------------
# wide_events.py - set_duration() must not overwrite an explicit duration_ms
# ---------------------------------------------------------------------------
def test_wide_event_set_duration_preserves_explicit_value():
    """If duration_ms was set explicitly by the caller, set_duration() must
    keep it. Bug: it unconditionally recomputed from wall-clock start_time."""
    from utils.wide_events import WideEvent

    event = WideEvent("telemetry_upload")
    event.add_context(duration_ms=42.5)  # caller-measured value

    event.set_duration()

    assert event.context["duration_ms"] == 42.5
    # start_time is still stripped from the final context.
    assert "start_time" not in event.context


# ---------------------------------------------------------------------------
# csv_importer.py - duplicate detection normalizes tz-aware timestamps to UTC
# ---------------------------------------------------------------------------
def test_csv_importer_dedup_handles_non_utc_timezone():
    """A DB timestamp stored in a non-UTC zone must still match an equivalent
    naive-UTC record. Bug: replace(tzinfo=None) kept the local wall-clock
    time, so the same instant in a non-UTC zone missed the duplicate."""
    from utils.csv_importer import TorqueCSVImporter

    # 2024-01-15 14:30 UTC, expressed in a UTC-5 zone (09:30 local).
    est = timezone(timedelta(hours=-5))
    existing = {datetime(2024, 1, 15, 9, 30, 0, tzinfo=est)}

    # Incoming CSV record at the same instant, naive UTC.
    records = [{"timestamp": datetime(2024, 1, 15, 14, 30, 0)}]

    unique, dup_count = TorqueCSVImporter._find_duplicates(records, existing)

    assert dup_count == 1
    assert unique == []


# ---------------------------------------------------------------------------
# route_clustering.py - similarity score is symmetric
# ---------------------------------------------------------------------------
def test_route_similarity_is_symmetric():
    """Similarity must not depend on argument order. Bug: dividing by
    len(sampled1) instead of the zip-truncated pair count gave different
    averages when routes had different lengths."""
    from utils.route_clustering import calculate_route_similarity

    # Routes must differ at the overlapping (zip-truncated) pairs, otherwise
    # total_distance is 0 and the bug is masked. The long route is offset in
    # longitude so each compared pair has a nonzero haversine distance.
    long_route = [(37.0 + i * 0.001, -122.05) for i in range(20)]
    short_route = [(37.0 + i * 0.001, -122.0) for i in range(5)]

    score_ab = calculate_route_similarity(long_route, short_route)
    score_ba = calculate_route_similarity(short_route, long_route)

    assert score_ab == pytest.approx(score_ba)


# ---------------------------------------------------------------------------
# query_cache.py - DB Session args are dropped from the cache key
# ---------------------------------------------------------------------------
def test_cached_query_key_ignores_db_session_arg():
    """A SQLAlchemy Session's repr embeds its memory address, so including it
    in the cache key would make the key non-deterministic and the cache would
    never hit. Through the real cached_query decorator, two distinct Session
    objects with identical real arguments must produce the SAME Redis key."""
    from unittest.mock import patch

    from utils.query_cache import cached_query

    def make_session():
        # Duck-typed SQLAlchemy Session: has query/commit/rollback.
        s = MagicMock()
        s.query = MagicMock()
        s.commit = MagicMock()
        s.rollback = MagicMock()
        return s

    @cached_query(ttl=60, key_prefix="trips")
    def get_data(db, days):  # noqa: ARG001
        return days

    captured_keys = []
    mock_redis = MagicMock()
    mock_redis.get.return_value = None  # always a cache miss

    def capture_setex(key, ttl, value):  # noqa: ARG001
        captured_keys.append(key)

    mock_redis.setex.side_effect = capture_setex

    with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
        get_data(make_session(), 30)
        get_data(make_session(), 30)  # different Session, same real args
        get_data(make_session(), 99)  # different real arg

    # Two different Session objects but identical days -> identical key.
    assert captured_keys[0] == captured_keys[1]
    # A genuinely different argument still changes the key.
    assert captured_keys[2] != captured_keys[0]


# ---------------------------------------------------------------------------
# weather.py - _parse_weather_response: null precipitation is handled
# ---------------------------------------------------------------------------
def test_parse_weather_response_handles_null_precipitation():
    """Open-Meteo can return null for an hourly precipitation value. Bug:
    ``precip[idx] > 0`` raised TypeError when the value was None. The fix
    guards with ``is not None``."""
    from utils.weather import _parse_weather_response

    ts = datetime(2024, 1, 15, 14, 0, 0, tzinfo=timezone.utc)
    data = {
        "hourly": {
            "time": ["2024-01-15T14:00"],
            "temperature_2m": [60.0],
            "precipitation": [None],  # null hourly value
            "wind_speed_10m": [5.0],
            "weather_code": [0],
        }
    }

    result = _parse_weather_response(data, ts)

    assert result is not None
    assert result["precipitation_in"] is None
    assert result["is_raining"] is False


# ---------------------------------------------------------------------------
# weather.py - _check_db_cache: tz-aware fetched_at does not crash
# ---------------------------------------------------------------------------
def test_check_db_cache_handles_tz_aware_fetched_at():
    """fetched_at is a DateTime(timezone=True) column; Postgres returns it
    tz-aware while utc_now() is naive. Bug: the subtraction raised TypeError
    ('can't subtract offset-naive and offset-aware datetimes'), silently
    disabling the persistent weather cache. The fix wraps fetched_at in
    normalize_datetime()."""
    from utils.weather import _check_db_cache

    db_cache = MagicMock()
    db_cache.fetched_at = datetime.now(timezone.utc)  # tz-aware, fresh
    db_cache.temperature_f = 70.0
    db_cache.precipitation_in = 0.0
    db_cache.wind_speed_mph = 5.0
    db_cache.weather_code = 0
    db_cache.is_raining = False
    db_cache.conditions = "Clear"

    db_session = MagicMock()
    db_session.query.return_value.filter.return_value.first.return_value = db_cache

    # Must not raise TypeError; a fresh entry returns cached data (not None).
    result = _check_db_cache(db_session, 37.77, -122.42, "2024-01-15T14", 37.77, -122.42)

    assert result is not None


# ---------------------------------------------------------------------------
# calculations.py - analyze_soc_floor keeps a 0.0% SOC reading
# ---------------------------------------------------------------------------
def test_analyze_soc_floor_keeps_zero_percent_reading():
    """A SOC of exactly 0% is a valid, important battery-floor data point.
    Bug: a truthiness filter (``if t.get("soc_at_transition")``) dropped the
    falsy 0.0 value. The fix uses ``is not None``."""
    from utils.calculations import analyze_soc_floor

    transitions = [
        {"soc_at_transition": 0.0, "ambient_temp_f": 70.0},
        {"soc_at_transition": 10.0, "ambient_temp_f": 70.0},
    ]

    result = analyze_soc_floor(transitions)

    # Both readings counted; 0.0 must not be dropped.
    assert result["count"] == 2
    assert result["min_soc"] == 0.0
    assert result["average_soc"] == pytest.approx(5.0)


# ---------------------------------------------------------------------------
# torque_parser.py - timestamp is naive UTC, consistent with utc_now()
# ---------------------------------------------------------------------------
def test_torque_parser_timestamp_is_naive_utc():
    """Torque epoch-ms time must parse to a NAIVE UTC datetime, matching the
    utc_now() fallback. Bug: returning a tz-aware datetime caused
    'can't subtract offset-naive and offset-aware datetimes' downstream."""
    from utils.torque_parser import TorqueParser

    # 1705329000000 ms -> 2024-01-15 14:30:00 UTC
    result = TorqueParser.parse({"time": "1705329000000"})

    ts = result["timestamp"]
    assert isinstance(ts, datetime)
    assert ts.tzinfo is None  # naive, not offset-aware
    assert (ts.year, ts.month, ts.day, ts.hour, ts.minute) == (2024, 1, 15, 14, 30)
