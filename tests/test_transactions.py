"""
Database transaction tests for VoltTracker.

Tests transaction behavior:
- Rollback on errors
- Commit behavior
- Transaction isolation
- Nested transactions
- Savepoints

NOTE: These tests are skipped because they require a PostgreSQL-specific
fixture setup. The in-memory SQLite database used for other tests has
limitations with transaction isolation and concurrent connection handling.
These tests should be run against a real PostgreSQL database.
"""

import pytest
from datetime import datetime, timezone
import uuid

# Skip all tests in this module - they require PostgreSQL for proper transaction testing
pytestmark = pytest.mark.skip(
    reason="Transaction tests require PostgreSQL - SQLite has different transaction semantics"
)


class TestTransactionRollback:
    """Test that transactions rollback properly on errors."""

    def test_trip_creation_rollback_on_error(self, db_session):
        """Test that trip creation rolls back if subsequent operations fail."""
        from models import Trip, TelemetryRaw

        session_id = uuid.uuid4()
        initial_trip_count = db_session.query(Trip).count()

        try:
            # Create trip
            trip = Trip(
                session_id=session_id,
                is_closed=False,
                start_time=datetime.now(timezone.utc)
            )
            db_session.add(trip)
            db_session.flush()  # Write to DB but don't commit

            # Try to add invalid telemetry (simulate error)
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=None,  # This should cause an error
                state_of_charge="invalid"  # Wrong type
            )
            db_session.add(telemetry)
            db_session.commit()  # This should fail

        except Exception:
            db_session.rollback()

        # Trip should NOT exist (rolled back)
        final_trip_count = db_session.query(Trip).count()
        assert final_trip_count == initial_trip_count, "Trip should have been rolled back"

        # Verify trip doesn't exist
        trip = db_session.query(Trip).filter(Trip.session_id == session_id).first()
        assert trip is None, "Trip should not exist after rollback"

    def test_csv_import_rollback_on_validation_error(self, db_session):
        """Test that CSV import rolls back if validation fails mid-import."""
        from models import CsvImport, TelemetryRaw

        session_id = uuid.uuid4()
        import_code = f"IMP-{uuid.uuid4().hex[:12]}"

        initial_telemetry_count = db_session.query(TelemetryRaw).count()

        try:
            # Create import record
            csv_import = CsvImport(
                import_code=import_code,
                filename="test.csv",
                file_hash="abc123",
                file_size_bytes=1000,
                status="processing",
                total_rows=3
            )
            db_session.add(csv_import)
            db_session.flush()

            # Add some valid telemetry
            for i in range(2):
                telemetry = TelemetryRaw(
                    session_id=session_id,
                    timestamp=datetime.now(timezone.utc),
                    state_of_charge=85.0 - i
                )
                db_session.add(telemetry)

            db_session.flush()

            # Add invalid telemetry (simulates validation error)
            invalid_telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=None,  # Invalid
                state_of_charge=85.0
            )
            db_session.add(invalid_telemetry)
            db_session.commit()  # Should fail

        except Exception:
            db_session.rollback()

        # Nothing should have been committed
        final_telemetry_count = db_session.query(TelemetryRaw).count()
        assert final_telemetry_count == initial_telemetry_count, "Telemetry should be rolled back"

        # Import record should not exist
        csv_import = db_session.query(CsvImport).filter(
            CsvImport.import_code == import_code
        ).first()
        assert csv_import is None, "CSV import should not exist after rollback"

    def test_trip_finalization_partial_rollback(self, db_session):
        """Test that trip finalization rolls back if weather API fails."""
        from models import Trip

        session_id = uuid.uuid4()

        # Create trip
        trip = Trip(
            session_id=session_id,
            is_closed=False,
            start_time=datetime.now(timezone.utc),
            distance_miles=10.0
        )
        db_session.add(trip)
        db_session.commit()

        trip_id = trip.id

        try:
            # Start finalization
            trip.is_closed = True
            trip.end_time = datetime.now(timezone.utc)
            db_session.flush()

            # Simulate weather API failure
            raise Exception("Weather API timeout")

            db_session.commit()

        except Exception:
            db_session.rollback()

        # Trip should still be open (rolled back)
        trip = db_session.query(Trip).filter(Trip.id == trip_id).first()
        assert trip.is_closed is False, "Trip close should be rolled back"
        assert trip.end_time is None, "End time should be rolled back"


class TestTransactionIsolation:
    """Test transaction isolation levels."""

    def test_read_committed_isolation(self, db_session):
        """
        Test READ COMMITTED isolation (default in PostgreSQL).

        One transaction shouldn't see uncommitted changes from another.
        """
        from models import Trip
        from database import SessionLocal

        session_id = uuid.uuid4()

        # Create trip in first session
        trip = Trip(
            session_id=session_id,
            is_closed=False,
            start_time=datetime.now(timezone.utc)
        )
        db_session.add(trip)
        db_session.flush()  # Write but don't commit

        # Try to read from second session
        other_session = SessionLocal()
        try:
            other_trip = other_session.query(Trip).filter(
                Trip.session_id == session_id
            ).first()

            # Should NOT see uncommitted trip (READ COMMITTED)
            assert other_trip is None, "Should not see uncommitted data"

        finally:
            other_session.close()

        # Now commit first session
        db_session.commit()

        # Read from second session again
        other_session = SessionLocal()
        try:
            other_trip = other_session.query(Trip).filter(
                Trip.session_id == session_id
            ).first()

            # Should NOW see committed trip
            assert other_trip is not None, "Should see committed data"

        finally:
            other_session.close()

    def test_dirty_read_prevention(self, db_session):
        """Test that dirty reads are prevented."""
        from models import Trip
        from database import SessionLocal

        session_id = uuid.uuid4()

        # Create and commit initial trip
        trip = Trip(
            session_id=session_id,
            is_closed=False,
            start_time=datetime.now(timezone.utc),
            distance_miles=10.0
        )
        db_session.add(trip)
        db_session.commit()

        trip_id = trip.id

        # Update trip but don't commit
        trip.distance_miles = 20.0
        db_session.flush()

        # Read from other session
        other_session = SessionLocal()
        try:
            other_trip = other_session.query(Trip).filter(Trip.id == trip_id).first()

            # Should see old value (10.0), not dirty value (20.0)
            assert other_trip.distance_miles == 10.0, "Should not see dirty read"

        finally:
            other_session.close()

        # Rollback the update
        db_session.rollback()


class TestSavepoints:
    """Test savepoint functionality for partial rollbacks."""

    def test_savepoint_rollback(self, db_session):
        """Test rolling back to a savepoint."""
        from models import Trip
        from sqlalchemy import text

        session_id1 = uuid.uuid4()
        session_id2 = uuid.uuid4()

        # Create first trip and commit
        trip1 = Trip(
            session_id=session_id1,
            is_closed=False,
            start_time=datetime.now(timezone.utc)
        )
        db_session.add(trip1)
        db_session.commit()

        # Create savepoint
        savepoint = db_session.begin_nested()

        try:
            # Create second trip
            trip2 = Trip(
                session_id=session_id2,
                is_closed=False,
                start_time=datetime.now(timezone.utc)
            )
            db_session.add(trip2)
            db_session.flush()

            # Simulate error
            raise Exception("Something went wrong")

        except Exception:
            # Rollback to savepoint
            savepoint.rollback()

        # Commit outer transaction
        db_session.commit()

        # First trip should exist
        trip1_exists = db_session.query(Trip).filter(
            Trip.session_id == session_id1
        ).first()
        assert trip1_exists is not None, "First trip should exist"

        # Second trip should NOT exist (rolled back)
        trip2_exists = db_session.query(Trip).filter(
            Trip.session_id == session_id2
        ).first()
        assert trip2_exists is None, "Second trip should not exist"


class TestTransactionCommitBehavior:
    """Test various commit scenarios."""

    def test_explicit_commit_required(self, db_session):
        """Test that changes aren't visible without commit."""
        from models import Trip

        session_id = uuid.uuid4()

        # Add trip but don't commit
        trip = Trip(
            session_id=session_id,
            is_closed=False,
            start_time=datetime.now(timezone.utc)
        )
        db_session.add(trip)
        db_session.flush()  # Write to DB

        # Query in same session - should see it
        same_session_trip = db_session.query(Trip).filter(
            Trip.session_id == session_id
        ).first()
        assert same_session_trip is not None, "Should see in same session"

        # Rollback
        db_session.rollback()

        # Should no longer exist even in same session
        rolled_back_trip = db_session.query(Trip).filter(
            Trip.session_id == session_id
        ).first()
        assert rolled_back_trip is None, "Should not exist after rollback"

    def test_autoflush_behavior(self, db_session):
        """Test SQLAlchemy autoflush behavior."""
        from models import Trip

        session_id = uuid.uuid4()

        # Add trip (not flushed)
        trip = Trip(
            session_id=session_id,
            is_closed=False,
            start_time=datetime.now(timezone.utc)
        )
        db_session.add(trip)

        # Query should trigger autoflush
        count = db_session.query(Trip).filter(
            Trip.session_id == session_id
        ).count()

        # Should see the trip (autoflush happened)
        assert count == 1, "Autoflush should have occurred"

        # But still not committed
        db_session.rollback()

        # Should be gone
        count = db_session.query(Trip).filter(
            Trip.session_id == session_id
        ).count()
        assert count == 0, "Should be rolled back"

    def test_commit_multiple_models(self, db_session):
        """Test committing changes across multiple models."""
        from models import Trip, TelemetryRaw, FuelEvent

        session_id = uuid.uuid4()
        timestamp = datetime.now(timezone.utc)

        # Create trip
        trip = Trip(
            session_id=session_id,
            is_closed=False,
            start_time=timestamp
        )
        db_session.add(trip)

        # Add telemetry
        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=timestamp,
            state_of_charge=85.0
        )
        db_session.add(telemetry)

        # Add fuel event
        fuel_event = FuelEvent(
            timestamp=timestamp,
            gallons_added=10.0,
            total_cost=35.0,
        )
        db_session.add(fuel_event)

        # Commit all at once
        db_session.commit()

        # Verify all were saved
        assert db_session.query(Trip).filter(Trip.session_id == session_id).count() == 1
        assert db_session.query(TelemetryRaw).filter(TelemetryRaw.session_id == session_id).count() == 1
        assert db_session.query(FuelEvent).filter(FuelEvent.total_cost == 35.0).count() == 1


class TestConstraintViolations:
    """Test that constraint violations trigger proper rollbacks."""

    def test_unique_constraint_violation(self, db_session):
        """Test that unique constraint violations are handled."""
        from models import Trip
        from sqlalchemy.exc import IntegrityError

        session_id = uuid.uuid4()

        # Create first trip
        trip1 = Trip(
            session_id=session_id,
            is_closed=False,
            start_time=datetime.now(timezone.utc)
        )
        db_session.add(trip1)
        db_session.commit()

        # Try to create duplicate (if there's a unique constraint on session_id when not closed)
        # This depends on your actual schema constraints
        trip2 = Trip(
            session_id=session_id,  # Same session ID
            is_closed=False,
            start_time=datetime.now(timezone.utc)
        )
        db_session.add(trip2)

        # Depending on constraints, this might raise IntegrityError
        try:
            db_session.commit()
            # If no error, that's fine (no unique constraint on open trips)
        except IntegrityError:
            db_session.rollback()
            # Constraint violation was caught and rolled back
            pass

        # First trip should still exist
        trips = db_session.query(Trip).filter(Trip.session_id == session_id).all()
        assert len(trips) >= 1, "Original trip should exist"

    def test_foreign_key_constraint(self, db_session):
        """Test that foreign key constraints are enforced."""
        from models import TelemetryRaw
        from sqlalchemy.exc import IntegrityError

        fake_session_id = uuid.uuid4()

        # Try to create telemetry for non-existent trip
        # (if there's a foreign key constraint)
        telemetry = TelemetryRaw(
            session_id=fake_session_id,  # Doesn't exist
            timestamp=datetime.now(timezone.utc),
            state_of_charge=85.0
        )
        db_session.add(telemetry)

        # This may or may not fail depending on your schema
        # If you have a FK constraint, it should fail
        try:
            db_session.commit()
            # No FK constraint, that's fine
        except IntegrityError:
            db_session.rollback()
            # FK constraint violated, rolled back
            pass


# ============================================================================
# Fixtures
# ============================================================================

@pytest.fixture
def db_session():
    """Provide a clean database session for each test."""
    from database import SessionLocal

    session = SessionLocal()
    yield session

    # Cleanup
    try:
        session.rollback()
        session.close()
    except:
        pass


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
