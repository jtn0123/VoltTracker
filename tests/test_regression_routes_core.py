"""
Regression tests for backend audit bug fixes in core route files.

Each test guards exactly one fix made during the backend audit in:
    receiver/routes/telemetry.py
    receiver/routes/trips.py
    receiver/routes/battery.py
    receiver/routes/system.py

If a fix is reverted, the corresponding test below must fail.
"""

import json
import os
import sys
import uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))


@pytest.fixture
def battery_cell_readings(db_session):
    """Create sample battery cell readings for testing."""
    from models import BatteryCellReading

    readings = []
    now = datetime.now(timezone.utc)
    for i in range(5):
        cell_voltages = [3.72 + (j % 10) * 0.003 for j in range(96)]
        reading = BatteryCellReading.from_cell_voltages(
            timestamp=now - timedelta(days=i),
            cell_voltages=cell_voltages,
            state_of_charge=80.0 - i * 5,
        )
        if reading:
            readings.append(reading)
            db_session.add(reading)
    db_session.commit()
    return readings


# ============================================================================
# telemetry.py
# ============================================================================


class TestTelemetryRegressions:
    """Regressions for receiver/routes/telemetry.py."""

    def test_opt_float_preserves_zero_value(self, client, db_session):
        """BUG: _opt_float used `float(val) if val else None`, so a real
        telemetry value of 0 was returned as null. Fix uses `is not None`.

        A speed of exactly 0 (vehicle stopped) must serialize as 0.0, not None.
        """
        from models import TelemetryRaw

        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)
        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=now,
            speed_mph=0.0,          # stopped vehicle - a real zero reading
            engine_rpm=0,           # electric mode - a real zero reading
            state_of_charge=55.0,
            odometer_miles=50000.0,
        )
        db_session.add(telemetry)
        db_session.commit()

        response = client.get("/api/telemetry/latest")
        assert response.status_code == 200
        result = json.loads(response.data)
        assert result["active"] is True

        data = result["data"]
        # Without the fix these would be None instead of 0.0.
        assert data["speed_mph"] == 0.0
        assert data["speed_mph"] is not None
        assert data["engine_rpm"] == 0.0
        assert data["engine_rpm"] is not None

    def test_engine_running_is_boolean_not_rpm(self, client, db_session):
        """BUG: when engine_running was null, the fallback returned
        `engine_rpm and engine_rpm > THRESHOLD`, which yields the raw RPM
        integer (or None) instead of a bool. Fix wraps it in bool(...).

        With engine_running unset and engine_rpm of 0, the response field
        must be the boolean False, not 0 / None.
        """
        from models import TelemetryRaw

        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)
        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=now,
            engine_rpm=0,           # falsy int -> old code returned 0, not False
            engine_running=None,    # forces the fallback computation
            state_of_charge=60.0,
            odometer_miles=50000.0,
        )
        db_session.add(telemetry)
        db_session.commit()

        response = client.get("/api/telemetry/latest")
        assert response.status_code == 200
        result = json.loads(response.data)

        engine_running = result["data"]["engine_running"]
        # Must be a real boolean, not the raw rpm value.
        assert isinstance(engine_running, bool)
        assert engine_running is False


# ============================================================================
# trips.py
# ============================================================================


class TestTripsRegressions:
    """Regressions for receiver/routes/trips.py."""

    def test_invalid_date_shortcut_does_not_drop_explicit_dates(self, client, db_session):
        """BUG: _apply_date_filters returned the query early whenever a
        `date_range` param was present, even if it was invalid - silently
        ignoring any explicit start_date/end_date filters. Fix only returns
        early for a *valid* shortcut and otherwise falls through.

        With an invalid date_range plus a start_date that excludes an old
        trip, that trip must NOT appear.
        """
        from models import Trip

        now = datetime.now(timezone.utc)
        old_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=300),
            start_odometer=50000.0,
            distance_miles=25.0,
            is_closed=True,
        )
        recent_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=2),
            start_odometer=50100.0,
            distance_miles=15.0,
            is_closed=True,
        )
        db_session.add_all([old_trip, recent_trip])
        db_session.commit()
        old_iso = old_trip.start_time.isoformat()
        recent_iso = recent_trip.start_time.isoformat()

        start_date = (now - timedelta(days=10)).strftime("%Y-%m-%d")
        response = client.get(
            f"/api/trips?date_range=not_a_real_shortcut&start_date={start_date}"
        )
        assert response.status_code == 200
        data = response.get_json()

        start_times = [t["start_time"] for t in data["trips"]]
        # The explicit start_date filter must still apply: old trip excluded.
        assert old_iso not in start_times
        assert recent_iso in start_times

    def test_restore_trip_invalidates_query_cache(self, client, db_session):
        """BUG: restore_trip committed the un-delete but never called
        invalidate_query_cache, so stale cached trip/stats lists survived.
        Fix adds invalidate_query_cache('trip') and ('stats').
        """
        from models import Trip

        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            distance_miles=20.0,
            is_closed=True,
            deleted_at=datetime.now(timezone.utc),
        )
        db_session.add(trip)
        db_session.commit()
        trip_id = trip.id

        with patch("routes.trips.invalidate_query_cache") as mock_invalidate:
            response = client.post(f"/api/trips/{trip_id}/restore")
            assert response.status_code == 200
            # Cache must be invalidated for both trip and stats namespaces.
            called_args = [c.args[0] for c in mock_invalidate.call_args_list]
            assert "trip" in called_args
            assert "stats" in called_args

    def test_mpg_trend_honors_standalone_end_date(self, client, db_session):
        """BUG: get_mpg_trend only applied an end_date filter when a
        start_date was *also* supplied (end_date nested inside the
        `if start_date_param` block). Fix handles either bound independently.

        Supplying only end_date must exclude a trip after that date.
        """
        from models import Trip

        now = datetime.now(timezone.utc)
        before_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=20),
            start_odometer=50000.0,
            distance_miles=30.0,
            gas_mode_entered=True,
            gas_mpg=42.0,
            is_closed=True,
        )
        after_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=1),
            start_odometer=50100.0,
            distance_miles=30.0,
            gas_mode_entered=True,
            gas_mpg=48.0,
            is_closed=True,
        )
        db_session.add_all([before_trip, after_trip])
        db_session.commit()
        before_iso = before_trip.start_time.isoformat()
        after_iso = after_trip.start_time.isoformat()

        end_date = (now - timedelta(days=10)).strftime("%Y-%m-%d")
        response = client.get(f"/api/mpg/trend?end_date={end_date}")
        assert response.status_code == 200
        data = response.get_json()

        dates = [row["date"] for row in data]
        # Only the trip before end_date should be present.
        assert before_iso in dates
        assert after_iso not in dates

    def test_mpg_trend_invalid_date_returns_400(self, client, db_session):
        """BUG: get_mpg_trend called dt.fromisoformat() with no error
        handling, so a malformed date param raised an unhandled ValueError
        (HTTP 500). Fix wraps parsing in try/except returning 400.
        """
        response = client.get("/api/mpg/trend?start_date=not-a-date")
        assert response.status_code == 400
        data = json.loads(response.data)
        assert "error" in data


# ============================================================================
# battery.py
# ============================================================================


class TestBatteryRegressions:
    """Regressions for receiver/routes/battery.py."""

    def test_cell_readings_negative_limit_is_clamped(self, client, battery_cell_readings):
        """BUG: limit was only capped at the top (min(limit, 100)); a
        negative limit reached the query and a negative SQL LIMIT errors on
        PostgreSQL. Fix clamps with max(1, min(limit, 100)).

        A negative limit must not error and must return at least one row.
        """
        response = client.get("/api/battery/cells?limit=-5")
        assert response.status_code == 200
        result = json.loads(response.data)
        # Clamped to >= 1, so we get exactly one reading back.
        assert result["count"] == 1
        assert len(result["readings"]) == 1

    def test_cell_readings_negative_days_is_ignored(self, client, db_session):
        """BUG: `if days:` accepted negative day counts, producing a future
        cutoff that filtered out ALL readings. Fix requires `days > 0`.

        A negative `days` must be ignored, returning existing readings.
        """
        from models import BatteryCellReading

        now = datetime.now(timezone.utc)
        cell_voltages = [3.72 + (j % 10) * 0.003 for j in range(96)]
        reading = BatteryCellReading.from_cell_voltages(
            timestamp=now - timedelta(days=2),
            cell_voltages=cell_voltages,
            state_of_charge=80.0,
        )
        assert reading is not None
        db_session.add(reading)
        db_session.commit()

        response = client.get("/api/battery/cells?days=-30")
        assert response.status_code == 200
        result = json.loads(response.data)
        # Negative days must be ignored, not used as a future cutoff.
        assert result["count"] >= 1

    def test_add_cell_reading_non_string_timestamp_returns_400(self, client):
        """BUG: add_cell_reading called timestamp_str.replace('Z', ...)
        without checking the type, so a non-string timestamp (e.g. int)
        raised an unhandled AttributeError (HTTP 500). Fix type-checks and
        returns 400.
        """
        voltages = [3.72] * 96
        data = {"cell_voltages": voltages, "timestamp": 1234567890}  # int, not str

        response = client.post(
            "/api/battery/cells/add",
            data=json.dumps(data),
            content_type="application/json",
        )
        assert response.status_code == 400
        result = json.loads(response.data)
        assert "timestamp" in result["error"].lower()


# ============================================================================
# system.py
# ============================================================================


class TestSystemRegressions:
    """Regressions for receiver/routes/system.py."""

    def test_readiness_check_closes_db_session_on_failure(self, client):
        """BUG: readiness_check called db.close() inside the try block,
        after db.execute(). If execute() raised, close() was skipped and the
        session leaked. Fix moves close() into a finally block.

        Simulate a failing SELECT 1 and assert the created session is still
        closed.
        """
        from unittest.mock import MagicMock

        mock_db = MagicMock()
        mock_db.execute.side_effect = Exception("connection refused")

        with patch("database.SessionLocal", return_value=mock_db):
            response = client.get("/ready")

        # Database check should report failure...
        assert response.status_code == 503
        data = json.loads(response.data)
        assert data["checks"]["database"] is False
        # ...but the session must still have been closed (finally block).
        mock_db.close.assert_called_once()
