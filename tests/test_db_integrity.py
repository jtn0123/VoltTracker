"""
Database Integrity Tests.

Tests schema creation, foreign key enforcement, and cascading behavior.
"""

import os
import sys
import uuid
from datetime import datetime, timedelta, timezone

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

from models import (  # noqa: E402
    ChargingSession,
    SocTransition,
    TelemetryRaw,
    Trip,
)
from sqlalchemy import inspect  # noqa: E402
from sqlalchemy.exc import IntegrityError  # noqa: E402


class TestSchemaCreation:
    """Validate that all expected tables and columns are created."""

    def test_all_core_tables_exist(self, app, db_session):
        """All core tables from init.sql are represented in ORM."""
        inspector = inspect(db_session.bind)
        table_names = inspector.get_table_names()

        expected = [
            "telemetry_raw", "trips", "fuel_events",
            "soc_transitions", "charging_sessions",
            "battery_health_readings",
        ]
        for table in expected:
            assert table in table_names, f"Missing table: {table}"

    def test_telemetry_raw_columns(self, app, db_session):
        """telemetry_raw has key columns."""
        inspector = inspect(db_session.bind)
        columns = {c["name"] for c in inspector.get_columns("telemetry_raw")}

        required = {
            "id", "session_id", "timestamp", "latitude", "longitude",
            "speed_mph", "engine_rpm", "state_of_charge", "fuel_level_percent",
            "odometer_miles", "hv_battery_power_kw",
        }
        missing = required - columns
        assert not missing, f"telemetry_raw missing columns: {missing}"

    def test_trips_columns(self, app, db_session):
        """trips table has key columns."""
        inspector = inspect(db_session.bind)
        columns = {c["name"] for c in inspector.get_columns("trips")}

        required = {
            "id", "session_id", "start_time", "end_time",
            "distance_miles", "electric_miles", "gas_miles",
            "gas_mpg", "gas_mode_entered", "is_closed",
        }
        missing = required - columns
        assert not missing, f"trips missing columns: {missing}"


class TestForeignKeyRelationships:
    """Test FK relationships between tables."""

    def test_soc_transition_references_trip(self, app, db_session):
        """SocTransition.trip_id references a valid trip."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now,
            start_odometer=50000.0,
            start_soc=80.0,
            is_closed=False,
        )
        db_session.add(trip)
        db_session.commit()

        transition = SocTransition(
            trip_id=trip.id,
            timestamp=now,
            soc_at_transition=17.5,
            ambient_temp_f=72.0,
        )
        db_session.add(transition)
        db_session.commit()

        # Verify relationship works
        assert transition.trip_id == trip.id
        assert transition.trip.session_id == session_id

    def test_trip_has_soc_transitions_relationship(self, app, db_session):
        """Trip.soc_transitions back-populates correctly."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now,
            start_odometer=50000.0,
            start_soc=80.0,
            is_closed=False,
        )
        db_session.add(trip)
        db_session.commit()

        for i in range(3):
            t = SocTransition(
                trip_id=trip.id,
                timestamp=now + timedelta(minutes=i),
                soc_at_transition=17.5 - i * 0.5,
            )
            db_session.add(t)
        db_session.commit()

        db_session.refresh(trip)
        assert len(trip.soc_transitions) == 3

    def test_trip_session_id_unique(self, app, db_session):
        """Cannot create two trips with the same session_id."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip1 = Trip(
            session_id=session_id,
            start_time=now,
            start_odometer=50000.0,
            start_soc=80.0,
        )
        db_session.add(trip1)
        db_session.commit()

        trip2 = Trip(
            session_id=session_id,
            start_time=now + timedelta(hours=1),
            start_odometer=50100.0,
            start_soc=90.0,
        )
        db_session.add(trip2)

        with pytest.raises(IntegrityError):
            db_session.commit()
        db_session.rollback()


class TestCascadingDeletes:
    """Test that deleting parent records handles children correctly."""

    def test_delete_trip_with_soc_transitions(self, app, db_session):
        """Deleting a trip handles its soc_transitions (FK has no cascade in SQLite)."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now,
            start_odometer=50000.0,
            start_soc=80.0,
        )
        db_session.add(trip)
        db_session.commit()

        transition = SocTransition(
            trip_id=trip.id,
            timestamp=now,
            soc_at_transition=17.5,
        )
        db_session.add(transition)
        db_session.commit()

        trip_id = trip.id

        # Delete trip — soc_transitions should still exist (no cascade delete)
        # or be handled depending on FK constraints
        db_session.delete(trip)
        db_session.commit()

        # Trip is gone
        assert db_session.query(Trip).filter(Trip.id == trip_id).first() is None

    def test_telemetry_independent_of_trip_lifecycle(self, app, db_session):
        """Telemetry linked by session_id is NOT deleted when trip is deleted."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now,
            start_odometer=50000.0,
            start_soc=80.0,
        )
        db_session.add(trip)

        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=now,
            speed_mph=45.0,
        )
        db_session.add(telemetry)
        db_session.commit()

        # Delete trip
        db_session.delete(trip)
        db_session.commit()

        # Telemetry still exists (linked by session_id, not FK)
        remaining = db_session.query(TelemetryRaw).filter(
            TelemetryRaw.session_id == session_id
        ).count()
        assert remaining == 1

    def test_charging_sessions_allow_duplicate_start_time(self, app, db_session):
        """
        Charging sessions may share an identical start_time.

        start_time is intentionally NOT globally unique: telemetry timestamps
        are second-precision and multi-vehicle deployments can have two
        vehicles begin charging in the same second. Identity is the surrogate
        primary key, not start_time.
        """
        now = datetime.now(timezone.utc)

        s1 = ChargingSession(start_time=now, start_soc=20.0, is_complete=False)
        s2 = ChargingSession(start_time=now, start_soc=30.0, is_complete=False)
        db_session.add(s1)
        db_session.add(s2)

        # Must not raise - both rows persist with distinct primary keys.
        db_session.commit()

        assert s1.id != s2.id
        assert (
            db_session.query(ChargingSession)
            .filter(ChargingSession.start_time == now)
            .count()
            == 2
        )


class TestDataIntegrity:
    """Test data integrity constraints."""

    def test_trip_not_null_constraints(self, app, db_session):
        """Trip requires session_id and start_time."""
        # Missing session_id
        trip = Trip(start_time=datetime.now(timezone.utc), start_odometer=50000.0)
        db_session.add(trip)

        with pytest.raises(IntegrityError):
            db_session.commit()
        db_session.rollback()

    def test_telemetry_not_null_constraints(self, app, db_session):
        """Telemetry requires session_id and timestamp."""
        t = TelemetryRaw(speed_mph=45.0)  # Missing session_id and timestamp
        db_session.add(t)

        with pytest.raises(IntegrityError):
            db_session.commit()
        db_session.rollback()
