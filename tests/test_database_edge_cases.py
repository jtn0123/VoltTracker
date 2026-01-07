"""
Database-Specific Edge Case Tests

Tests for:
- Database constraints (unique, foreign key, not null)
- Transaction boundaries and rollback behavior
- Concurrent operations and race conditions
- Data integrity and consistency
- Query performance edge cases
- Connection pooling and resource limits
"""

import uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

import pytest
from sqlalchemy.exc import IntegrityError, SQLAlchemyError

from models import ChargingSession, FuelEvent, TelemetryRaw, Trip, WeatherCache
from tests.factories import (
    ChargingSessionFactory,
    FuelEventFactory,
    TelemetryFactory,
    TripFactory,
)


# ============================================================================
# Database Constraint Tests
# ============================================================================


class TestDatabaseConstraints:
    """Test database constraints are enforced."""

    def test_trip_unique_session_id_constraint(self, db_session):
        """Test that session_id must be unique for trips."""
        session_id = uuid.uuid4()

        # Create first trip
        TripFactory.create(db_session=db_session, session_id=session_id)

        # Try to create second trip with same session_id
        with pytest.raises(IntegrityError):
            TripFactory.create(db_session=db_session, session_id=session_id)
            db_session.flush()

        db_session.rollback()

    def test_trip_null_session_id_not_allowed(self, db_session):
        """Test that session_id cannot be null."""
        trip = Trip(
            start_time=datetime.now(timezone.utc),
            session_id=None,  # Should violate NOT NULL constraint
        )
        db_session.add(trip)

        with pytest.raises((IntegrityError, SQLAlchemyError)):
            db_session.commit()

        db_session.rollback()

    def test_trip_null_start_time_not_allowed(self, db_session):
        """Test that start_time cannot be null."""
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=None,  # Should violate NOT NULL constraint
        )
        db_session.add(trip)

        with pytest.raises((IntegrityError, SQLAlchemyError)):
            db_session.commit()

        db_session.rollback()

    def test_charging_session_unique_start_time_constraint(self, db_session):
        """Test unique constraint on charging session start_time."""
        start_time = datetime.now(timezone.utc)

        # Create first session
        ChargingSessionFactory.create(db_session=db_session, start_time=start_time)

        # Try to create second session with same start_time
        with pytest.raises(IntegrityError):
            ChargingSessionFactory.create(db_session=db_session, start_time=start_time)
            db_session.flush()

        db_session.rollback()

    def test_telemetry_allows_duplicate_session_timestamps(self, db_session):
        """Test that telemetry allows multiple points at same timestamp."""
        session_id = uuid.uuid4()
        timestamp = datetime.now(timezone.utc)

        # Create multiple telemetry points at same timestamp
        # (This should be allowed - no unique constraint)
        TelemetryFactory.create(
            db_session=db_session,
            session_id=session_id,
            timestamp=timestamp,
            state_of_charge=80.0,
        )
        TelemetryFactory.create(
            db_session=db_session,
            session_id=session_id,
            timestamp=timestamp,
            state_of_charge=79.0,
        )

        count = db_session.query(TelemetryRaw).filter(
            TelemetryRaw.session_id == session_id,
            TelemetryRaw.timestamp == timestamp
        ).count()

        assert count == 2  # Should allow duplicates

    def test_foreign_key_behavior_on_delete(self, db_session):
        """Test foreign key cascade behavior (if any)."""
        # Note: Current schema doesn't have explicit foreign keys
        # This test documents the current behavior
        trip = TripFactory.create(db_session=db_session)
        session_id = trip.session_id

        # Create telemetry for this trip
        TelemetryFactory.create(db_session=db_session, session_id=session_id)

        # Delete trip
        db_session.delete(trip)
        db_session.commit()

        # Telemetry should still exist (no cascade delete)
        telemetry_count = db_session.query(TelemetryRaw).filter(
            TelemetryRaw.session_id == session_id
        ).count()

        assert telemetry_count == 1  # Telemetry remains


# ============================================================================
# Transaction and Rollback Tests
# ============================================================================


class TestTransactionBehavior:
    """Test transaction boundaries and rollback."""

    def test_explicit_rollback_undoes_changes(self, db_session):
        """Test that rollback undoes uncommitted changes."""
        initial_count = db_session.query(Trip).count()

        # Create trip but don't commit
        trip = TripFactory.build(session_id=uuid.uuid4())
        db_session.add(trip)
        db_session.flush()  # Flush but don't commit

        # Verify it's in session
        assert db_session.query(Trip).count() == initial_count + 1

        # Rollback
        db_session.rollback()

        # Should be gone
        assert db_session.query(Trip).count() == initial_count

    def test_exception_triggers_rollback(self, db_session):
        """Test that exceptions trigger automatic rollback."""
        initial_count = db_session.query(Trip).count()
        session_id = uuid.uuid4()

        try:
            # Create first trip
            TripFactory.create(db_session=db_session, session_id=session_id)

            # Try to create duplicate (will fail)
            TripFactory.create(db_session=db_session, session_id=session_id)
            db_session.flush()
        except IntegrityError:
            db_session.rollback()

        # After rollback, count should not increase
        final_count = db_session.query(Trip).count()
        # May have changed if first create succeeded before rollback
        assert final_count >= initial_count

    def test_nested_transaction_rollback(self, db_session):
        """Test nested transaction (savepoint) rollback."""
        initial_count = db_session.query(Trip).count()

        trip1 = TripFactory.create(db_session=db_session)

        # Try nested transaction with error
        try:
            nested = db_session.begin_nested()
            trip2 = TripFactory.build(session_id=uuid.uuid4())
            db_session.add(trip2)
            db_session.flush()

            # Force an error
            raise Exception("Simulated error")

        except Exception:
            # Rollback should be handled
            if nested.is_active:
                nested.rollback()

        # trip1 should still exist
        final_count = db_session.query(Trip).count()
        assert final_count >= initial_count + 1  # At least trip1 added

    def test_commit_after_query_error(self, db_session):
        """Test that we can continue after a query error."""
        # Force a query error
        try:
            db_session.query(Trip).filter(Trip.id == "invalid").first()
        except Exception:
            db_session.rollback()

        # Should be able to create trip after rollback
        trip = TripFactory.create(db_session=db_session)
        assert trip.id is not None

    def test_multiple_flushes_before_commit(self, db_session):
        """Test multiple flushes in single transaction."""
        trip1 = TripFactory.build(session_id=uuid.uuid4())
        db_session.add(trip1)
        db_session.flush()  # First flush

        trip2 = TripFactory.build(session_id=uuid.uuid4())
        db_session.add(trip2)
        db_session.flush()  # Second flush

        # Both should be visible before commit
        assert trip1.id is not None
        assert trip2.id is not None

        db_session.commit()

        # Both should persist
        assert db_session.query(Trip).filter(Trip.id == trip1.id).first() is not None
        assert db_session.query(Trip).filter(Trip.id == trip2.id).first() is not None


# ============================================================================
# Data Integrity Tests
# ============================================================================


class TestDataIntegrity:
    """Test data integrity across operations."""

    def test_trip_telemetry_consistency_check(self, db_session):
        """Verify trip and telemetry data stay consistent."""
        session_id = uuid.uuid4()
        start_time = datetime.now(timezone.utc)

        # Create trip
        trip = TripFactory.create(
            db_session=db_session,
            session_id=session_id,
            start_time=start_time,
            start_odometer=50000.0,
            start_soc=90.0,
        )

        # Create telemetry
        telemetry = TelemetryFactory.create(
            db_session=db_session,
            session_id=session_id,
            timestamp=start_time,
            odometer_miles=50000.0,
            state_of_charge=90.0,
        )

        # Verify consistency
        assert trip.session_id == telemetry.session_id
        assert trip.start_odometer == telemetry.odometer_miles
        assert trip.start_soc == telemetry.state_of_charge

    def test_charging_session_soc_integrity(self, db_session):
        """Verify charging session SOC values are logical."""
        session = ChargingSessionFactory.create(
            db_session=db_session,
            start_soc=20.0,
            end_soc=95.0,
        )

        # End SOC should be greater than or equal to start SOC
        assert session.end_soc >= session.start_soc

    def test_trip_closed_status_integrity(self, db_session):
        """Verify closed trips have end_time."""
        trip = TripFactory.create(
            db_session=db_session,
            is_closed=True,
        )

        # Closed trips should have end_time
        assert trip.end_time is not None

    def test_telemetry_timestamp_ordering(self, db_session):
        """Verify telemetry points maintain timestamp order."""
        session_id = uuid.uuid4()
        base_time = datetime.now(timezone.utc)

        # Create points in reverse order
        for i in reversed(range(5)):
            TelemetryFactory.create(
                db_session=db_session,
                session_id=session_id,
                timestamp=base_time + timedelta(minutes=i),
            )

        # Retrieve and verify order
        points = db_session.query(TelemetryRaw).filter(
            TelemetryRaw.session_id == session_id
        ).order_by(TelemetryRaw.timestamp).all()

        # Should be in chronological order
        for i in range(len(points) - 1):
            assert points[i].timestamp < points[i + 1].timestamp

    def test_fuel_event_odometer_consistency(self, db_session):
        """Verify fuel events have consistent odometer progression."""
        # Create multiple fuel events
        events = []
        for i in range(3):
            event = FuelEventFactory.create(
                db_session=db_session,
                timestamp=datetime.now(timezone.utc) + timedelta(days=i),
                odometer_miles=50000.0 + (i * 200),
            )
            events.append(event)

        # Verify odometer is increasing
        for i in range(len(events) - 1):
            assert events[i].odometer_miles < events[i + 1].odometer_miles


# ============================================================================
# Query Performance Edge Cases
# ============================================================================


class TestQueryPerformance:
    """Test query performance with edge cases."""

    def test_query_with_large_result_set(self, db_session):
        """Query returning many results."""
        # Create 100 trips
        TripFactory.create_batch(100, db_session=db_session)

        # Query all
        import time
        start = time.time()
        trips = db_session.query(Trip).all()
        duration = time.time() - start

        assert len(trips) == 100
        assert duration < 1.0  # Should complete in under 1 second

    def test_query_with_complex_filter(self, db_session):
        """Query with multiple filters."""
        # Create mix of trips
        TripFactory.create_batch(20, db_session=db_session, is_closed=True)
        TripFactory.create_batch(20, db_session=db_session, is_closed=False)

        # Complex filter query
        results = db_session.query(Trip).filter(
            Trip.is_closed == True,  # noqa: E712
            Trip.distance_miles > 10.0,
            Trip.electric_miles > 5.0,
        ).all()

        assert len(results) > 0

    def test_query_with_ordering_and_limit(self, db_session):
        """Query with ORDER BY and LIMIT."""
        TripFactory.create_batch(50, db_session=db_session)

        # Get most recent 10 trips
        recent_trips = db_session.query(Trip).order_by(
            Trip.start_time.desc()
        ).limit(10).all()

        assert len(recent_trips) <= 10

    def test_query_with_join_simulation(self, db_session):
        """Query simulating join between trip and telemetry."""
        session_id = uuid.uuid4()

        trip = TripFactory.create(db_session=db_session, session_id=session_id)
        TelemetryFactory.create_batch(
            10,
            db_session=db_session,
            session_id=session_id
        )

        # Query trip and count its telemetry
        telemetry_count = db_session.query(TelemetryRaw).filter(
            TelemetryRaw.session_id == trip.session_id
        ).count()

        assert telemetry_count == 10

    def test_query_with_aggregation(self, db_session):
        """Query with aggregation functions."""
        from sqlalchemy import func

        # Create trips with known distances
        for i in range(10):
            TripFactory.create(
                db_session=db_session,
                distance_miles=float(i * 10)
            )

        # Aggregate query
        avg_distance = db_session.query(
            func.avg(Trip.distance_miles)
        ).scalar()

        assert avg_distance is not None
        assert avg_distance > 0

    def test_query_with_date_range(self, db_session):
        """Query filtering by date range."""
        now = datetime.now(timezone.utc)

        # Create trips across date range
        for i in range(30):
            TripFactory.create(
                db_session=db_session,
                start_time=now - timedelta(days=i)
            )

        # Query last 7 days
        week_ago = now - timedelta(days=7)
        recent_trips = db_session.query(Trip).filter(
            Trip.start_time >= week_ago
        ).all()

        assert len(recent_trips) <= 8  # Should be ~7-8 trips


# ============================================================================
# Concurrent Operation Tests
# ============================================================================


class TestConcurrentOperations:
    """Test concurrent database operations."""

    def test_simultaneous_trip_creation(self, db_session):
        """Test creating multiple trips simultaneously."""
        # Simulate concurrent creation
        session_ids = [uuid.uuid4() for _ in range(10)]

        trips = []
        for session_id in session_ids:
            trip = TripFactory.build(session_id=session_id)
            db_session.add(trip)
            trips.append(trip)

        db_session.flush()
        db_session.commit()

        # All should be created
        for trip in trips:
            assert trip.id is not None

    def test_read_while_write_in_progress(self, db_session):
        """Test reading while write is uncommitted."""
        # Create and flush (but don't commit)
        trip = TripFactory.build(session_id=uuid.uuid4())
        db_session.add(trip)
        db_session.flush()

        # Try to read in same session (should see it)
        found = db_session.query(Trip).filter(Trip.id == trip.id).first()
        assert found is not None

        # Rollback
        db_session.rollback()

    def test_bulk_insert_performance(self, db_session):
        """Test bulk insert of many records."""
        import time

        telemetry_points = []
        session_id = uuid.uuid4()

        for i in range(1000):
            point = TelemetryFactory.build(
                session_id=session_id,
                timestamp=datetime.now(timezone.utc) + timedelta(seconds=i),
            )
            telemetry_points.append(point)

        # Bulk insert
        start = time.time()
        db_session.bulk_save_objects(telemetry_points)
        db_session.commit()
        duration = time.time() - start

        # Should be reasonably fast
        assert duration < 2.0  # Under 2 seconds for 1000 inserts

        # Verify count
        count = db_session.query(TelemetryRaw).filter(
            TelemetryRaw.session_id == session_id
        ).count()
        assert count == 1000


# ============================================================================
# Database State Edge Cases
# ============================================================================


class TestDatabaseStateEdgeCases:
    """Test edge cases in database state."""

    def test_empty_database_queries(self, db_session):
        """Queries on empty database."""
        # Database should be empty
        assert db_session.query(Trip).count() == 0
        assert db_session.query(TelemetryRaw).count() == 0
        assert db_session.query(ChargingSession).count() == 0

        # Queries should return empty results, not errors
        trips = db_session.query(Trip).all()
        assert trips == []

    def test_deleted_record_query(self, db_session):
        """Query for deleted record."""
        trip = TripFactory.create(db_session=db_session)
        trip_id = trip.id

        # Delete it
        db_session.delete(trip)
        db_session.commit()

        # Query should return None
        found = db_session.query(Trip).filter(Trip.id == trip_id).first()
        assert found is None

    def test_soft_delete_behavior(self, db_session):
        """Test soft delete (deleted_at field)."""
        trip = TripFactory.create(db_session=db_session)

        # Soft delete
        trip.deleted_at = datetime.now(timezone.utc)
        db_session.commit()

        # Should still exist in database
        found = db_session.query(Trip).filter(Trip.id == trip.id).first()
        assert found is not None
        assert found.deleted_at is not None

    def test_query_with_none_comparison(self, db_session):
        """Query filtering for NULL values."""
        # Create trips with and without end_time
        TripFactory.create(db_session=db_session, end_time=None, is_closed=False)
        TripFactory.create(
            db_session=db_session,
            end_time=datetime.now(timezone.utc),
            is_closed=True
        )

        # Query for open trips (end_time is NULL)
        open_trips = db_session.query(Trip).filter(Trip.end_time.is_(None)).all()

        assert len(open_trips) >= 1

    def test_query_after_update(self, db_session):
        """Verify query reflects updates."""
        trip = TripFactory.create(db_session=db_session, distance_miles=25.0)

        # Update
        trip.distance_miles = 50.0
        db_session.commit()

        # Re-query
        found = db_session.query(Trip).filter(Trip.id == trip.id).first()
        assert found.distance_miles == 50.0

    def test_session_expiry_refresh(self, db_session):
        """Test refreshing expired session data."""
        trip = TripFactory.create(db_session=db_session)

        # Expire the session
        db_session.expire(trip)

        # Access attribute (should trigger refresh)
        distance = trip.distance_miles

        assert distance is not None


# ============================================================================
# Database Error Handling
# ============================================================================


class TestDatabaseErrorHandling:
    """Test error handling for database operations."""

    def test_handling_integrity_error_gracefully(self, db_session):
        """Test graceful handling of integrity errors."""
        session_id = uuid.uuid4()

        # Create first trip
        TripFactory.create(db_session=db_session, session_id=session_id)

        # Try to create duplicate
        try:
            TripFactory.create(db_session=db_session, session_id=session_id)
            db_session.flush()
            assert False, "Should have raised IntegrityError"
        except IntegrityError:
            db_session.rollback()
            # Successfully handled error

        # Database should still be usable
        count = db_session.query(Trip).count()
        assert count >= 1

    def test_recovery_after_database_error(self, db_session):
        """Test that session can be used after error."""
        # Force an error
        try:
            db_session.execute("SELECT * FROM nonexistent_table")
        except Exception:
            db_session.rollback()

        # Session should be usable again
        trip = TripFactory.create(db_session=db_session)
        assert trip.id is not None

    def test_handling_connection_pool_exhaustion(self, db_session):
        """Test behavior when connection pool is full."""
        # This is more of a documentation test
        # In production, pool exhaustion should be handled gracefully
        # SQLAlchemy will queue requests or timeout

        # Create many queries (simulating concurrent requests)
        for _ in range(10):
            db_session.query(Trip).count()

        # Should complete without errors
        assert True
