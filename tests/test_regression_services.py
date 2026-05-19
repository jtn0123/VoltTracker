"""
Regression tests for the backend audit bug fixes.

Each test in this file guards exactly one bug fix made during the backend
audit across the receiver service layer. Each test exercises the precise
buggy code path so the bug cannot silently return.

Bugs guarded (one test each):
  1. battery_degradation_service.simple_linear_regression - division by zero
     when all x values are identical.
  2. combined_analytics_service.get_efficiency_time_series - string-keyed
     month grouping sorted lexically ("2024-9" after "2024-11").
  3. elevation_analytics_service.get_elevation_summary - coverage_percent
     numerator/denominator inconsistency.
  4. route_service.detect_routes - open trips (end_time=None) crashing the
     duration calculation.
  5. scheduler.check_refuel_events - None odometer crashing the log format.
  6. scheduler._handle_active_charging - peak_power_kw clobbered instead of
     kept as a running maximum.
  7. scheduler.check_charging_sessions - newest-first telemetry passed to
     detect_charging_session, inverting start/end SOC.
  8. trip_service.calculate_trip_basics - None odometer on the final
     telemetry point wiping out a valid end_odometer.
"""

import uuid
from datetime import datetime, timedelta, timezone

import pytest

from models import ChargingSession, FuelEvent, TelemetryRaw, Trip
from services.battery_degradation_service import simple_linear_regression
from services.combined_analytics_service import get_efficiency_time_series
from services.elevation_analytics_service import get_elevation_summary
from services.route_service import detect_routes
from services.scheduler import check_charging_sessions, check_refuel_events
from services.trip_service import calculate_trip_basics


# ---------------------------------------------------------------------------
# Bug 1: battery_degradation_service.simple_linear_regression
# ---------------------------------------------------------------------------
def test_linear_regression_identical_x_does_not_divide_by_zero():
    """Identical x values must return a value, not crash with ZeroDivisionError.

    Multiple battery readings at the same odometer make
    (n*sum_xx - sum_x*sum_x) == 0. The buggy code divided by that.
    """
    data = [(50000.0, 95.0), (50000.0, 93.0), (50000.0, 97.0)]

    slope, intercept = simple_linear_regression(data)

    # No mileage trend -> zero slope, intercept is the mean of y.
    assert slope == 0
    assert intercept == pytest.approx(95.0)


# ---------------------------------------------------------------------------
# Bug 2: combined_analytics_service.get_efficiency_time_series
# ---------------------------------------------------------------------------
def test_efficiency_time_series_months_sort_numerically(app, db_session):
    """Monthly periods must order chronologically, not lexically.

    A string key like "2024-9" sorts AFTER "2024-11". With numeric grouping
    February 2024 must appear before November 2024.
    """
    # Two trips: one in Feb 2024, one in Nov 2024 (Feb must come first).
    for month, day in [(2, 15), (11, 15)]:
        session_id = uuid.uuid4()
        start = datetime(2024, month, day, 12, 0, 0, tzinfo=timezone.utc)
        trip = Trip(
            session_id=session_id,
            start_time=start,
            end_time=start + timedelta(hours=1),
            start_odometer=50000.0,
            end_odometer=50020.0,
            distance_miles=20.0,
            electric_miles=20.0,
            kwh_per_mile=0.25,
            is_closed=True,
        )
        db_session.add(trip)
    db_session.commit()

    # days large enough to span back to Feb 2024 from the (mocked-free) call.
    days = (datetime.now(timezone.utc) - datetime(2024, 2, 1, tzinfo=timezone.utc)).days + 5
    result = get_efficiency_time_series(db_session, days=days, group_by="month")

    periods = [row["period"] for row in result["time_series"]]
    assert "2024-02" in periods
    assert "2024-11" in periods
    # The buggy string key produced "2024-11" before "2024-2".
    assert periods.index("2024-02") < periods.index("2024-11")


# ---------------------------------------------------------------------------
# Bug 3: elevation_analytics_service.get_elevation_summary
# ---------------------------------------------------------------------------
def test_elevation_summary_coverage_counts_consistently(app, db_session):
    """coverage_percent numerator and denominator must use the same trip set.

    `trips_without` counts ALL closed trips lacking elevation. The buggy
    `trips_with_elevation` used the efficiency-filtered base query, so a
    closed trip with elevation but no/invalid kwh_per_mile was dropped from
    the numerator -> coverage_percent understated.
    """
    now = datetime.now(timezone.utc)

    # Closed trip WITH elevation data but no kwh_per_mile (excluded by the
    # efficiency base filter, but is a valid "trip with elevation").
    trip_with = Trip(
        session_id=uuid.uuid4(),
        start_time=now - timedelta(hours=2),
        end_time=now - timedelta(hours=1),
        is_closed=True,
        elevation_gain_m=120.0,
        elevation_loss_m=80.0,
        kwh_per_mile=None,
    )
    # Closed trip WITHOUT elevation data.
    trip_without = Trip(
        session_id=uuid.uuid4(),
        start_time=now - timedelta(hours=4),
        end_time=now - timedelta(hours=3),
        is_closed=True,
        elevation_gain_m=None,
    )
    db_session.add(trip_with)
    db_session.add(trip_without)
    db_session.commit()

    summary = get_elevation_summary(db_session)["summary"]

    assert summary["trips_with_elevation"] == 1
    assert summary["trips_without_elevation"] == 1
    # 1 of 2 -> 50%. Buggy code counted 0 with elevation -> 0%.
    assert summary["coverage_percent"] == pytest.approx(50.0)


# ---------------------------------------------------------------------------
# Bug 4: route_service.detect_routes
# ---------------------------------------------------------------------------
def test_detect_routes_ignores_open_trips_without_end_time(app, db_session):
    """Open trips (end_time=None) must not reach the duration calculation.

    detect_routes feeds trips into _create_new_route, which computes
    (end_time - start_time). An open trip has end_time=None and the buggy
    query did not filter it out -> TypeError on the subtraction.
    """
    session_id = uuid.uuid4()
    now = datetime.now(timezone.utc)

    # Open trip with GPS telemetry, distance/electric miles set but NO end_time.
    trip = Trip(
        session_id=session_id,
        start_time=now - timedelta(minutes=30),
        end_time=None,
        distance_miles=10.0,
        electric_miles=10.0,
        kwh_per_mile=0.2,
        is_closed=False,
    )
    db_session.add(trip)
    db_session.flush()

    for i, (lat, lon) in enumerate([(37.7749, -122.4194), (37.8044, -122.2712)]):
        db_session.add(
            TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=30 - i * 15),
                latitude=lat,
                longitude=lon,
            )
        )
    db_session.commit()

    # Must not raise TypeError from None end_time.
    routes = detect_routes(db_session, min_trips=1)

    # The open trip must not have produced a route.
    assert routes == []


# ---------------------------------------------------------------------------
# Bug 5: scheduler.check_refuel_events
# ---------------------------------------------------------------------------
def test_check_refuel_events_handles_none_odometer(app, db_session):
    """A refuel with a None odometer must still be recorded, not crash logging.

    The log line formatted odometer_miles with `:.1f`; a None value
    (sensor dropout) raised a formatting error that rolled back a valid
    FuelEvent.
    """
    session_id = uuid.uuid4()
    now = datetime.now(timezone.utc)

    db_session.add(
        TelemetryRaw(
            session_id=session_id,
            timestamp=now - timedelta(hours=1),
            fuel_level_percent=25.0,
            odometer_miles=None,
        )
    )
    db_session.add(
        TelemetryRaw(
            session_id=session_id,
            timestamp=now,
            fuel_level_percent=85.0,  # 60% jump -> refuel
            odometer_miles=None,
        )
    )
    db_session.commit()

    # Must not raise from the `:.1f` format on a None odometer.
    check_refuel_events()

    fuel_events = db_session.query(FuelEvent).all()
    assert len(fuel_events) == 1


# ---------------------------------------------------------------------------
# Bug 6: scheduler._handle_active_charging
# ---------------------------------------------------------------------------
def test_charging_session_peak_power_is_running_maximum(app, db_session, mocker):
    """peak_power_kw must keep the highest peak seen, not the latest window's.

    detect_charging_session only reports the peak of the recent ~50-point
    window. The buggy code overwrote active_session.peak_power_kw with that,
    losing an earlier higher peak.
    """
    now = datetime.now(timezone.utc)

    # Active session that already recorded a high peak earlier.
    session = ChargingSession(
        start_time=now - timedelta(hours=2),
        start_soc=30.0,
        peak_power_kw=9.5,  # earlier, higher peak
        is_complete=False,
    )
    db_session.add(session)

    telem_session_id = uuid.uuid4()
    db_session.add(
        TelemetryRaw(
            session_id=telem_session_id,
            timestamp=now,
            charger_connected=True,
            charger_ac_power_kw=6.6,
            state_of_charge=60.0,
        )
    )
    db_session.commit()
    session_id = session.id

    # Latest window only sees a 6.6 kW peak (lower than the stored 9.5).
    mocker.patch(
        "services.scheduler.detect_charging_session",
        return_value={
            "is_charging": True,
            "charge_type": "L2",
            "start_soc": 30.0,
            "current_soc": 60.0,
            "peak_power_kw": 6.6,
            "avg_power_kw": 6.0,
        },
    )

    check_charging_sessions()

    updated = db_session.query(ChargingSession).filter(ChargingSession.id == session_id).first()
    # Buggy code clobbered 9.5 with 6.6.
    assert updated.peak_power_kw == pytest.approx(9.5)


# ---------------------------------------------------------------------------
# Bug 7: scheduler.check_charging_sessions
# ---------------------------------------------------------------------------
def test_charging_session_start_soc_from_chronological_order(app, db_session):
    """Telemetry must be reversed to oldest-first before detection.

    `recent` is queried newest-first. detect_charging_session takes
    start_soc from the FIRST point and current_soc from the LAST. Without
    the reverse, start_soc would be the high (latest) SOC and current_soc
    the low (earliest) SOC -> a negative SOC gain.
    """
    session_id = uuid.uuid4()
    now = datetime.now(timezone.utc)

    # Chronologically: SOC rises 30 -> 40 -> 50 -> 60 while charging.
    for i in range(4):
        db_session.add(
            TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=(4 - i) * 5),
                charger_connected=True,
                charger_ac_power_kw=6.6,
                state_of_charge=30.0 + i * 10,
            )
        )
    db_session.commit()

    check_charging_sessions()

    session = db_session.query(ChargingSession).filter(ChargingSession.is_complete.is_(False)).first()
    assert session is not None
    # start_soc must be the earliest (lowest) reading, end_soc the latest.
    assert session.start_soc == pytest.approx(30.0)
    assert session.end_soc == pytest.approx(60.0)
    # SOC gain must be positive given chronologically-ordered charging.
    assert session.end_soc - session.start_soc > 0


# ---------------------------------------------------------------------------
# Bug 8: trip_service.calculate_trip_basics
# ---------------------------------------------------------------------------
def test_calculate_trip_basics_uses_last_valid_odometer(app, db_session):
    """A None odometer on the final point must not wipe out end_odometer.

    The buggy code used telemetry[-1].odometer_miles unconditionally; a
    sensor dropout on the final point set end_odometer=None and destroyed
    the distance calculation.
    """
    session_id = uuid.uuid4()
    now = datetime.now(timezone.utc)

    trip = Trip(
        session_id=session_id,
        start_time=now - timedelta(hours=1),
        start_odometer=50000.0,
    )
    db_session.add(trip)
    db_session.flush()

    telemetry = [
        TelemetryRaw(
            session_id=session_id,
            timestamp=now - timedelta(minutes=30),
            odometer_miles=50025.0,  # last VALID reading
        ),
        TelemetryRaw(
            session_id=session_id,
            timestamp=now,
            odometer_miles=None,  # sensor dropout at trip end
        ),
    ]
    for t in telemetry:
        db_session.add(t)
    db_session.flush()

    calculate_trip_basics(trip, telemetry)

    # Must fall back to the last point with a real odometer reading.
    assert trip.end_odometer == pytest.approx(50025.0)
    assert trip.distance_miles == pytest.approx(25.0)
