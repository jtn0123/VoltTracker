"""Regression tests for backend audit bug fixes in analytics/export routes.

Each test guards exactly one fixed bug in:
  receiver/routes/{map,statistics,settings,bulk_operations,export_routes,import_routes}.py

These tests must FAIL if the corresponding fix is reverted.
"""

import json
import os
import sys
import uuid
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

from models import CsvImport, SocTransition, TelemetryRaw, Trip


def _make_trip(db_session, **kw):
    """Create and commit a closed trip with sensible defaults."""
    defaults = dict(
        session_id=uuid.uuid4(),
        start_time=datetime.now(timezone.utc) - timedelta(days=1),
        end_time=datetime.now(timezone.utc) - timedelta(days=1) + timedelta(hours=1),
        start_odometer=50000.0,
        distance_miles=25.0,
        is_closed=True,
    )
    defaults.update(kw)
    trip = Trip(**defaults)
    db_session.add(trip)
    db_session.commit()
    return trip


# ---------------------------------------------------------------------------
# Bug 1: bulk_operations.py — permanent delete must purge the *fetched* trip
# ids, not the raw request input. Raw ids would orphan telemetry of
# already-soft-deleted trips (or fail to delete their soc_transitions).
# ---------------------------------------------------------------------------
def test_bulk_permanent_delete_uses_fetched_trip_ids_not_raw_input(client, db_session):
    """A soft-deleted trip's soc_transitions must NOT be purged by a permanent
    delete that targets a different (live) trip id."""
    live = _make_trip(db_session, session_id=uuid.uuid4())

    soft = _make_trip(
        db_session,
        session_id=uuid.uuid4(),
        deleted_at=datetime.now(timezone.utc),
    )
    # soc_transition belonging to the soft-deleted trip
    db_session.add(SocTransition(
        trip_id=soft.id,
        soc_at_transition=17.0,
        timestamp=datetime.now(timezone.utc),
    ))
    db_session.commit()
    soft_id = soft.id

    # Request a permanent delete of the LIVE trip *and* the already
    # soft-deleted trip id. The route only fetches non-deleted trips, so the
    # fetched list is [live]. The buggy code deleted soc_transitions by the
    # *raw* request ids ([live, soft]) -> wrongly purging the soft trip's rows.
    resp = client.post(
        "/api/bulk/trips/delete",
        json={"trip_ids": [live.id, soft_id], "permanent": True},
    )
    assert resp.status_code == 200

    # The soft-deleted trip and its soc_transition must be untouched.
    assert db_session.query(Trip).filter(Trip.id == soft_id).first() is not None
    remaining = db_session.query(SocTransition).filter(
        SocTransition.trip_id == soft_id
    ).count()
    assert remaining == 1, "soc_transition of an unrelated trip was wrongly purged"


# ---------------------------------------------------------------------------
# Bug 2: bulk_operations.py — bulk_export_trips must exclude soft-deleted trips.
# ---------------------------------------------------------------------------
def test_bulk_export_excludes_soft_deleted_trips(client, db_session):
    live = _make_trip(db_session)
    deleted = _make_trip(db_session, deleted_at=datetime.now(timezone.utc))

    resp = client.post(
        "/api/bulk/trips/export",
        json={"trip_ids": [live.id, deleted.id], "format": "json"},
    )
    assert resp.status_code == 200
    data = resp.get_json()
    ids = {row["id"] for row in data}
    assert live.id in ids
    assert deleted.id not in ids, "soft-deleted trip leaked into bulk export"


# ---------------------------------------------------------------------------
# Bug 3: bulk_operations.py — bulk_trip_stats must exclude soft-deleted trips.
# ---------------------------------------------------------------------------
def test_bulk_stats_excludes_soft_deleted_trips(client, db_session):
    live = _make_trip(db_session, distance_miles=30.0)
    deleted = _make_trip(
        db_session, distance_miles=100.0, deleted_at=datetime.now(timezone.utc)
    )

    resp = client.post(
        "/api/bulk/trips/stats",
        json={"trip_ids": [live.id, deleted.id]},
    )
    assert resp.status_code == 200
    data = resp.get_json()
    assert data["trip_count"] == 1, "soft-deleted trip counted in bulk stats"
    assert data["total_distance_miles"] == 30.0, (
        "soft-deleted trip distance leaked into bulk stats total"
    )


# ---------------------------------------------------------------------------
# Bug 4: export_routes.py — a date-only end_date must include the whole day,
# not resolve to midnight (which excludes every trip on that day).
# ---------------------------------------------------------------------------
def test_export_date_only_end_date_includes_last_day(client, db_session):
    # Trip on a fixed day, late afternoon.
    day = datetime(2024, 6, 15, 16, 30, 0, tzinfo=timezone.utc)
    _make_trip(db_session, start_time=day, end_time=day + timedelta(hours=1))

    resp = client.get(
        "/api/export/trips?format=json&stream=false"
        "&start_date=2024-06-01&end_date=2024-06-15"
    )
    assert resp.status_code == 200
    data = resp.get_json()
    assert len(data) == 1, (
        "trip on the last day was excluded — date-only end_date off-by-one"
    )


# ---------------------------------------------------------------------------
# Bug 5: export_routes.py — export_all must clamp a negative limit to >= 1.
# (A negative LIMIT errors on PostgreSQL; on SQLite it means "unlimited".)
# ---------------------------------------------------------------------------
def test_export_all_clamps_negative_limit(client, db_session):
    for _ in range(3):
        _make_trip(db_session)

    resp = client.get("/api/export/all?limit=-5")
    assert resp.status_code == 200
    body = json.loads(resp.get_data(as_text=True))
    # Fix clamps limit to 1; buggy negative limit returns all 3 trips.
    assert body["filters"]["limit"] == 1, "negative limit was not clamped to 1"
    assert body["summary"]["total_trips"] == 1, (
        "negative limit was not honored as a 1-row clamp"
    )


# ---------------------------------------------------------------------------
# Bug 6: import_routes.py — get_import_history must clamp a negative limit.
# ---------------------------------------------------------------------------
def test_import_history_clamps_negative_limit(client, db_session):
    for i in range(3):
        db_session.add(CsvImport(
            import_code=f"IMP{i:03d}",
            filename=f"f{i}.csv",
            file_hash=uuid.uuid4().hex,
            file_size_bytes=100,
            status="success",
            created_at=datetime.now(timezone.utc) - timedelta(minutes=i),
        ))
    db_session.commit()

    resp = client.get("/api/imports?limit=-1")
    assert resp.status_code == 200
    data = resp.get_json()
    # Fix clamps to 1; buggy negative limit on SQLite returns all 3.
    assert len(data) == 1, "negative limit was not clamped in import history"


# ---------------------------------------------------------------------------
# Bug 7: map.py — GPX <time> must be a valid ISO-8601 UTC stamp. Naively
# appending 'Z' to a tz-aware isoformat() yields an invalid '...+00:00Z'.
# ---------------------------------------------------------------------------
def test_gpx_time_has_no_double_timezone():
    """generate_gpx must emit valid GPX timestamps for tz-aware datetimes.

    Telemetry/trip timestamps are DateTime(timezone=True); appending 'Z' to a
    tz-aware isoformat() produces an invalid '...+00:00Z'. Exercised at the
    function level so the tz-aware datetime survives (SQLite drops tzinfo).
    """
    import re

    from routes.map import generate_gpx

    ts = datetime(2024, 3, 1, 12, 0, 0, tzinfo=timezone.utc)
    trip = Trip(
        session_id=uuid.uuid4(),
        start_time=ts,
        distance_miles=10.0,
        is_closed=True,
    )
    telemetry = [
        TelemetryRaw(
            session_id=trip.session_id,
            timestamp=ts,
            latitude=37.7749,
            longitude=-122.4194,
            speed_mph=45.0,
        )
    ]

    body = generate_gpx(trip, telemetry)

    assert "+00:00Z" not in body, "GPX emitted invalid double-timezone timestamp"

    # Every <time> value must round-trip through ISO-8601 parsing.
    times = re.findall(r"<time>(.*?)</time>", body)
    assert times, "no <time> elements emitted"
    for value in times:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        assert parsed.tzinfo is not None


# ---------------------------------------------------------------------------
# Bug 8: statistics.py — the distance confidence interval must be computed
# AFTER metric conversion so its units match total/mean/median.
# ---------------------------------------------------------------------------
def test_detailed_stats_distance_ci_matches_metric_units(client, db_session):
    base = datetime.now(timezone.utc) - timedelta(days=2)
    for i, miles in enumerate((20.0, 30.0, 40.0, 50.0, 60.0)):
        _make_trip(
            db_session,
            session_id=uuid.uuid4(),
            start_time=base + timedelta(hours=i),
            distance_miles=miles,
        )

    resp = client.get("/api/stats/detailed?metric=distance&units=metric&include_ci=true")
    assert resp.status_code == 200
    data = resp.get_json()
    analysis = data["distance_analysis"]
    assert analysis["unit"] == "km"

    ci = analysis["confidence_interval"]
    assert ci is not None
    # The CI mean must agree with the (km) mean — if the CI were computed on
    # raw miles it would be ~1.609x smaller.
    ci_mean = ci.get("mean", ci.get("point_estimate"))
    assert ci_mean is not None
    assert abs(ci_mean - analysis["mean"]) < 1.0, (
        "confidence interval was computed before metric conversion"
    )
