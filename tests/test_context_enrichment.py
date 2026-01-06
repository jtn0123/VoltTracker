"""
Tests for context enrichment utilities.

Tests vehicle statistics, usage tier classification, battery health metrics,
and event enrichment following loggingsucks.com patterns.
"""

import uuid
from datetime import datetime, timedelta, timezone

import pytest
from models import Trip
from utils.context_enrichment import (
    classify_usage_tier,
    enrich_event_with_vehicle_context,
    get_battery_health_metrics,
    get_current_trip_context,
    get_vehicle_statistics,
)


class TestClassifyUsageTier:
    """Tests for classify_usage_tier function."""

    def test_new_user_zero_trips(self):
        """Zero trips classifies as new user."""
        assert classify_usage_tier(0) == "new"

    def test_light_user_boundary(self):
        """1-19 trips classifies as light user."""
        assert classify_usage_tier(1) == "light"
        assert classify_usage_tier(10) == "light"
        assert classify_usage_tier(19) == "light"

    def test_moderate_user_boundary(self):
        """20-99 trips classifies as moderate user."""
        assert classify_usage_tier(20) == "moderate"
        assert classify_usage_tier(50) == "moderate"
        assert classify_usage_tier(99) == "moderate"

    def test_heavy_user_boundary(self):
        """100+ trips classifies as heavy user."""
        assert classify_usage_tier(100) == "heavy"
        assert classify_usage_tier(500) == "heavy"


class TestGetVehicleStatistics:
    """Tests for get_vehicle_statistics function."""

    def test_no_trips_returns_defaults(self, app, db_session):
        """No trips returns zero defaults with 'new' tier."""
        stats = get_vehicle_statistics(db_session)

        assert stats["total_trips"] == 0
        assert stats["total_miles"] == 0.0
        assert stats["account_age_days"] == 0
        assert stats["usage_tier"] == "new"
        assert stats["avg_kwh_per_mile"] is None
        assert stats["avg_gas_mpg"] is None

    def test_with_closed_trips(self, app, db_session):
        """Calculates statistics from closed trips."""
        now = datetime.now(timezone.utc)

        # Add 5 closed trips over 10 days
        for i in range(5):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=now - timedelta(days=10 - i),
                end_time=now - timedelta(days=10 - i) + timedelta(hours=1),
                distance_miles=25.0,
                electric_miles=20.0,
                kwh_per_mile=0.25,
                gas_mpg=40.0,
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        stats = get_vehicle_statistics(db_session)

        assert stats["total_trips"] == 5
        assert stats["total_miles"] == 125.0  # 5 * 25
        assert stats["account_age_days"] >= 10
        assert stats["usage_tier"] == "light"
        assert stats["avg_kwh_per_mile"] == 0.25
        assert stats["avg_gas_mpg"] == 40.0

    def test_excludes_open_trips(self, app, db_session):
        """Open trips are excluded from statistics."""
        now = datetime.now(timezone.utc)

        # Add one closed trip
        closed_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=5),
            distance_miles=30.0,
            is_closed=True,
        )
        db_session.add(closed_trip)

        # Add one open trip
        open_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(hours=1),
            distance_miles=15.0,
            is_closed=False,
        )
        db_session.add(open_trip)
        db_session.commit()

        stats = get_vehicle_statistics(db_session)

        assert stats["total_trips"] == 1
        assert stats["total_miles"] == 30.0


class TestGetBatteryHealthMetrics:
    """Tests for get_battery_health_metrics function."""

    def test_no_trips_returns_empty(self, app, db_session):
        """No trips returns empty dict."""
        metrics = get_battery_health_metrics(db_session)
        assert metrics == {}

    def test_calculates_metrics_from_recent_trips(self, app, db_session):
        """Calculates battery health metrics from recent trips."""
        now = datetime.now(timezone.utc)

        # Add 10 closed trips with electric miles
        for i in range(10):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=now - timedelta(days=i),
                end_time=now - timedelta(days=i) + timedelta(hours=1),
                electric_miles=20.0 + i,
                kwh_per_mile=0.25,
                start_soc=80.0,
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        metrics = get_battery_health_metrics(db_session)

        assert "recent_avg_electric_miles" in metrics
        assert metrics["recent_avg_electric_miles"] > 0
        assert "recent_avg_efficiency_kwh_per_mile" in metrics
        assert metrics["recent_avg_efficiency_kwh_per_mile"] == 0.25
        assert metrics["sample_size_trips"] == 10

    def test_handles_missing_soc_data(self, app, db_session):
        """Handles trips without SOC data gracefully."""
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=1),
            end_time=now,
            electric_miles=25.0,
            kwh_per_mile=0.3,
            start_soc=None,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        metrics = get_battery_health_metrics(db_session)

        # SOC drop is None when start_soc is missing
        assert metrics.get("recent_avg_soc_drop_percent") is None

    def test_handles_missing_efficiency_data(self, app, db_session):
        """Handles trips without efficiency data gracefully."""
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=1),
            end_time=now,
            electric_miles=25.0,
            kwh_per_mile=None,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        metrics = get_battery_health_metrics(db_session)

        assert metrics["recent_avg_efficiency_kwh_per_mile"] is None


class TestGetCurrentTripContext:
    """Tests for get_current_trip_context function."""

    def test_trip_not_found(self, app, db_session):
        """Returns trip_found=False when trip doesn't exist."""
        context = get_current_trip_context(db_session, str(uuid.uuid4()))

        assert context["trip_found"] is False

    def test_returns_trip_context(self, app, db_session):
        """Returns full context for existing trip."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(minutes=30),
            start_soc=85.0,
            start_odometer=50000.0,
            is_closed=False,
        )
        db_session.add(trip)
        db_session.commit()

        context = get_current_trip_context(db_session, str(session_id))

        assert context["trip_found"] is True
        assert context["trip_id"] == trip.id
        assert context["is_closed"] is False
        assert context["trip_duration_seconds"] >= 1800  # ~30 minutes
        assert context["start_soc"] == 85.0
        assert context["start_odometer"] == 50000.0

    def test_handles_closed_trip(self, app, db_session):
        """Returns context for closed trip."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(hours=1),
            end_time=now,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        context = get_current_trip_context(db_session, str(session_id))

        assert context["trip_found"] is True
        assert context["is_closed"] is True


class TestEnrichEventWithVehicleContext:
    """Tests for enrich_event_with_vehicle_context function."""

    def test_enriches_event_with_stats(self, app, db_session):
        """Enriches event with vehicle statistics."""
        from utils.wide_events import WideEvent

        now = datetime.now(timezone.utc)

        # Add some trips
        for i in range(3):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=now - timedelta(days=i),
                distance_miles=20.0,
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        event = WideEvent(operation="test_operation")
        enrich_event_with_vehicle_context(event, db_session)

        # Vehicle context is nested under 'vehicle_context' key
        assert "vehicle_context" in event.context
        assert event.context["vehicle_context"]["total_trips"] == 3

    def test_enriches_with_battery_health(self, app, db_session):
        """Enriches event with battery health when requested."""
        from utils.wide_events import WideEvent

        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=1),
            electric_miles=25.0,
            kwh_per_mile=0.3,
            start_soc=80.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        event = WideEvent(operation="test_operation")
        enrich_event_with_vehicle_context(event, db_session, include_battery_health=True)

        # Vehicle context is nested under 'vehicle_context' key
        assert "vehicle_context" in event.context
        assert "recent_avg_electric_miles" in event.context["vehicle_context"]
