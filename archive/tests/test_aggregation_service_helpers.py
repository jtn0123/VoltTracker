"""
Direct tests for the helpers in
``receiver/services/aggregation_service.py`` that PR #45 extracted to
reduce cognitive complexity. The most load-bearing helper is
``_populate_monthly_summary``, which the existing test suite never
exercised — coverage was 5% before this file was added. The reset-on-
empty branches added in this PR (see the CodeRabbit Major review) are
specifically tested below so a future regression that drops the
``else`` clauses can't slip through CI.
"""

from datetime import datetime, timezone
from unittest.mock import MagicMock

from services.aggregation_service import _populate_monthly_summary


def _mk_trip(*, distance=10.0, electric=8.0, gas=2.0, kwh_per_mile=0.3, mpg=40.0, kwh_used=2.4, gallons=0.05):
    """Build a Trip-shaped MagicMock with the fields _populate_monthly_summary reads."""
    t = MagicMock()
    t.distance_miles = distance
    t.electric_miles = electric
    t.gas_miles = gas
    t.kwh_per_mile = kwh_per_mile
    t.gas_mpg = mpg
    t.electric_kwh_used = kwh_used
    t.fuel_used_gallons = gallons
    return t


def _empty_summary():
    """Build a MagicMock that behaves like a fresh MonthlySummary row."""
    s = MagicMock()
    s.total_trips = None
    s.total_distance_miles = None
    s.total_electric_miles = None
    s.total_gas_miles = None
    s.electric_percentage = "stale"
    s.avg_kwh_per_mile = "stale"
    s.avg_mpg = "stale"
    s.total_kwh_used = None
    s.total_gallons_used = None
    s.total_charging_sessions = None
    s.total_kwh_charged = None
    return s


def _empty_db():
    """A MagicMock db whose ChargingSession query returns []."""
    db = MagicMock()
    db.query.return_value.filter.return_value.all.return_value = []
    return db


class TestPopulateMonthlySummary:
    def test_populates_basic_totals_from_trips(self):
        summary = _empty_summary()
        trips = [_mk_trip(distance=10, electric=8, gas=2),
                 _mk_trip(distance=20, electric=15, gas=5)]
        start = datetime(2026, 1, 1, tzinfo=timezone.utc)
        end = datetime(2026, 2, 1, tzinfo=timezone.utc)

        _populate_monthly_summary(_empty_db(), summary, trips, start, end)

        assert summary.total_trips == 2
        assert summary.total_distance_miles == 30
        assert summary.total_electric_miles == 23
        assert summary.total_gas_miles == 7
        # 23 / 30 * 100
        assert abs(summary.electric_percentage - 76.6666) < 0.01

    def test_resets_derived_fields_when_no_trips(self):
        """Regression guard: an emptied month must not leak previous run's averages."""
        summary = _empty_summary()
        # Pretend a previous aggregation populated stale values.
        summary.electric_percentage = 99.9
        summary.avg_kwh_per_mile = 0.42
        summary.avg_mpg = 38.0

        _populate_monthly_summary(
            _empty_db(),
            summary,
            [],
            datetime(2026, 1, 1, tzinfo=timezone.utc),
            datetime(2026, 2, 1, tzinfo=timezone.utc),
        )

        assert summary.total_trips == 0
        assert summary.total_distance_miles == 0
        # The Major CodeRabbit fix: these MUST be reset, not left stale.
        assert summary.electric_percentage is None
        assert summary.avg_kwh_per_mile is None
        assert summary.avg_mpg is None

    def test_resets_avg_kwh_per_mile_when_only_gas_trips(self):
        summary = _empty_summary()
        summary.avg_kwh_per_mile = 0.30  # stale from previous month

        # All trips are gas-only — kwh_per_mile is None on every one
        gas_trips = [_mk_trip(distance=20, electric=0, gas=20, kwh_per_mile=None)]

        _populate_monthly_summary(
            _empty_db(),
            summary,
            gas_trips,
            datetime(2026, 1, 1, tzinfo=timezone.utc),
            datetime(2026, 2, 1, tzinfo=timezone.utc),
        )

        assert summary.avg_kwh_per_mile is None

    def test_resets_avg_mpg_when_only_electric_trips(self):
        summary = _empty_summary()
        summary.avg_mpg = 42.0  # stale

        ev_trips = [_mk_trip(distance=20, electric=20, gas=0, mpg=None)]

        _populate_monthly_summary(
            _empty_db(),
            summary,
            ev_trips,
            datetime(2026, 1, 1, tzinfo=timezone.utc),
            datetime(2026, 2, 1, tzinfo=timezone.utc),
        )

        assert summary.avg_mpg is None

    def test_computes_averages_when_data_present(self):
        summary = _empty_summary()
        trips = [
            _mk_trip(kwh_per_mile=0.3, mpg=40.0),
            _mk_trip(kwh_per_mile=0.4, mpg=50.0),
            _mk_trip(kwh_per_mile=0.2, mpg=30.0),
        ]
        _populate_monthly_summary(
            _empty_db(),
            summary,
            trips,
            datetime(2026, 1, 1, tzinfo=timezone.utc),
            datetime(2026, 2, 1, tzinfo=timezone.utc),
        )

        assert abs(summary.avg_kwh_per_mile - 0.3) < 0.001
        assert abs(summary.avg_mpg - 40.0) < 0.001

    def test_charging_session_aggregation_via_db_query(self):
        summary = _empty_summary()

        cs1, cs2 = MagicMock(kwh_added=10.0), MagicMock(kwh_added=15.0)
        db = MagicMock()
        db.query.return_value.filter.return_value.all.return_value = [cs1, cs2]

        _populate_monthly_summary(
            db,
            summary,
            [],
            datetime(2026, 1, 1, tzinfo=timezone.utc),
            datetime(2026, 2, 1, tzinfo=timezone.utc),
        )

        assert summary.total_charging_sessions == 2
        assert summary.total_kwh_charged == 25.0
