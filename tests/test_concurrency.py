"""
Race condition and concurrency tests for VoltTracker.

Tests concurrent operations to ensure data integrity:
- Concurrent trip creation
- Concurrent telemetry uploads
- Concurrent CSV imports
- Concurrent charging session updates

NOTE: These tests require PostgreSQL for proper concurrency testing. They are
automatically skipped on SQLite (which doesn't support the concurrent access
patterns these tests validate) and run on the `test-postgres` CI job. To run
locally, set DATABASE_URL=postgresql://... before invoking pytest.
"""

import os
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone

import pytest

# Only run these tests when DATABASE_URL points at PostgreSQL. SQLite's
# locking model can't exercise these race conditions.
pytestmark = pytest.mark.skipif(
    not os.environ.get("DATABASE_URL", "").startswith("postgresql"),
    reason="Requires PostgreSQL (set DATABASE_URL=postgresql://...)",
)


def _get_or_create_trip(db, session_id):
    """Find or atomically create a trip for the given session_id.

    Inline helper so concurrency tests don't depend on private route helpers.
    Mirrors the race-condition handling in receiver/routes/telemetry.py.
    """
    from models import Trip
    from sqlalchemy.exc import IntegrityError

    trip = db.query(Trip).filter(Trip.session_id == session_id).first()
    if trip:
        return trip

    try:
        trip = Trip(
            session_id=session_id,
            is_closed=False,
            start_time=datetime.now(timezone.utc),
        )
        db.add(trip)
        db.commit()
        return trip
    except IntegrityError:
        db.rollback()
        # Another worker won the race — fetch their row.
        return db.query(Trip).filter(Trip.session_id == session_id).first()


class TestConcurrentTripCreation:
    """Test concurrent trip creation for the same session."""

    def test_concurrent_trip_creation_race_condition(self, app, db_session):
        """
        Test that concurrent trip creation for same session doesn't create duplicates.

        This simulates the race condition where two telemetry uploads arrive
        simultaneously and both try to create a trip.
        """
        from models import Trip

        session_id = uuid.uuid4()
        results = []
        errors = []

        def create_trip_worker():
            """Worker that tries to create a trip using its own session."""
            from database import SessionLocal

            s = SessionLocal()
            try:
                trip = _get_or_create_trip(s, session_id)
                if trip is not None:
                    results.append(trip.id)
                return trip.id if trip else None
            except Exception as e:
                s.rollback()
                errors.append(e)
                return None
            finally:
                s.close()

        # Launch 10 concurrent workers trying to create trip for same session
        with ThreadPoolExecutor(max_workers=10) as executor:
            futures = [executor.submit(create_trip_worker) for _ in range(10)]

            for future in as_completed(futures):
                future.result()

        # Should have no errors
        assert len(errors) == 0, f"Errors during concurrent creation: {errors}"

        # All results should have the same trip ID (only one trip created)
        unique_trip_ids = set(results)
        assert len(unique_trip_ids) == 1, f"Multiple trips created: {unique_trip_ids}"

        # Verify only one trip exists in database
        db_session.expire_all()
        trips = db_session.query(Trip).filter(Trip.session_id == session_id).all()
        assert len(trips) == 1, f"Expected 1 trip, found {len(trips)}"

    def test_concurrent_telemetry_upload_same_session(self, app, db_session):
        """
        Test concurrent telemetry uploads to the same session.

        Simulates multiple rapid uploads from Torque Pro.
        """
        from models import TelemetryRaw, Trip

        session_id = uuid.uuid4()

        # First create the trip
        trip = Trip(session_id=session_id, is_closed=False, start_time=datetime.now(timezone.utc))
        db_session.add(trip)
        db_session.commit()

        upload_count = 50
        errors = []

        def upload_telemetry_worker(index):
            """Worker that uploads telemetry using its own session."""
            from database import SessionLocal

            s = SessionLocal()
            try:
                telemetry = TelemetryRaw(
                    session_id=session_id,
                    timestamp=datetime.now(timezone.utc),
                    state_of_charge=85.0 - (index * 0.1),  # Gradually decreasing
                    speed_mph=30.0 + index,
                )
                s.add(telemetry)
                s.commit()
                return True
            except Exception as e:
                s.rollback()
                errors.append(e)
                return False
            finally:
                s.close()

        # Launch concurrent uploads
        with ThreadPoolExecutor(max_workers=10) as executor:
            futures = [executor.submit(upload_telemetry_worker, i) for i in range(upload_count)]

            results = [future.result() for future in as_completed(futures)]

        # Most should succeed (some conflicts are acceptable due to locking)
        success_count = sum(results)
        assert success_count >= upload_count * 0.8, f"Too many failures: {success_count}/{upload_count}"

        # Verify telemetry was saved
        db_session.expire_all()
        telemetry_count = db_session.query(TelemetryRaw).filter(
            TelemetryRaw.session_id == session_id
        ).count()
        assert telemetry_count > 0, "No telemetry was saved"


class TestConcurrentChargingSession:
    """Test concurrent charging session operations."""

    def test_concurrent_charging_session_detection(self, app, db_session):
        """
        Test that concurrent charging detection doesn't create duplicate sessions.

        ChargingSession has a unique constraint on start_time — five workers
        try to insert a session with the same start_time, and the database
        must allow exactly one to succeed while the rest raise IntegrityError.
        """
        from models import ChargingSession
        from sqlalchemy.exc import IntegrityError

        # All workers attempt to insert with this shared start_time to force
        # contention on the uq_charging_session_start_time unique constraint.
        shared_start = datetime.now(timezone.utc)
        results = []
        errors = []

        def detect_charging_worker():
            """Worker that find-or-creates a charging session in its own DB session."""
            from database import SessionLocal

            s = SessionLocal()
            try:
                existing = (
                    s.query(ChargingSession)
                    .filter(ChargingSession.start_time == shared_start)
                    .first()
                )
                if existing:
                    results.append(existing.id)
                    return existing.id

                charging_session = ChargingSession(
                    start_time=shared_start,
                    start_soc=50.0,
                    is_complete=False,
                )
                s.add(charging_session)
                s.commit()
                results.append(charging_session.id)
                return charging_session.id
            except IntegrityError:
                # Unique constraint violation — another worker beat us to it.
                s.rollback()
                existing = (
                    s.query(ChargingSession)
                    .filter(ChargingSession.start_time == shared_start)
                    .first()
                )
                if existing:
                    results.append(existing.id)
                    return existing.id
                return None
            except Exception as e:
                s.rollback()
                errors.append(e)
                return None
            finally:
                s.close()

        # Launch concurrent workers
        with ThreadPoolExecutor(max_workers=5) as executor:
            futures = [executor.submit(detect_charging_worker) for _ in range(5)]

            for future in as_completed(futures):
                future.result()

        # Should have no unexpected errors (IntegrityError is handled)
        assert len(errors) == 0, f"Unexpected errors: {errors}"

        # All workers should agree on the same single charging session id
        unique_ids = set(results)
        assert len(unique_ids) == 1, f"Multiple charging sessions created: {unique_ids}"

        # Verify only one row exists in the database
        db_session.expire_all()
        count = (
            db_session.query(ChargingSession)
            .filter(ChargingSession.start_time == shared_start)
            .count()
        )
        assert count == 1, f"Expected exactly 1 charging session, got {count}"


class TestConcurrentCSVImport:
    """Test concurrent CSV import operations."""

    def test_concurrent_csv_import_duplicate_detection(self, app, db_session):
        """
        Test that concurrent CSV imports with same file are detected as duplicates.
        """
        import hashlib

        from models import CsvImport
        from sqlalchemy.exc import IntegrityError

        # Simulate same file content
        file_content = b"test,csv,content"
        file_hash = hashlib.sha256(file_content).hexdigest()

        errors = []

        def import_worker(worker_id):
            """Worker that tries to import CSV using its own session."""
            from database import SessionLocal

            s = SessionLocal()
            try:
                # Check for existing import
                existing = s.query(CsvImport).filter(CsvImport.file_hash == file_hash).first()

                if existing:
                    return None  # Duplicate detected

                # Create new import record
                csv_import = CsvImport(
                    import_code=f"IMP-{worker_id}-{uuid.uuid4().hex[:6]}",
                    filename="test.csv",
                    file_hash=file_hash,
                    file_size_bytes=len(file_content),
                    status="success",
                    total_rows=10,
                )
                s.add(csv_import)
                s.commit()
                return csv_import.import_code
            except IntegrityError:
                s.rollback()
                return None  # Another worker won the unique-file_hash race
            except Exception as e:
                s.rollback()
                errors.append(e)
                return None
            finally:
                s.close()

        # Launch concurrent import attempts
        with ThreadPoolExecutor(max_workers=5) as executor:
            futures = [executor.submit(import_worker, i) for i in range(5)]

            for future in as_completed(futures):
                future.result()

        assert len(errors) == 0, f"Unexpected errors during concurrent import: {errors}"

        # Only one should succeed (others detect duplicate)
        db_session.expire_all()
        imports_count = db_session.query(CsvImport).filter(CsvImport.file_hash == file_hash).count()

        assert imports_count == 1, f"Expected exactly 1 import, got {imports_count}"


class TestConcurrentTripFinalization:
    """Test concurrent trip finalization."""

    def test_concurrent_trip_close_attempts(self, app, db_session):
        """
        Test that concurrent attempts to close a trip don't cause issues.

        This can happen if scheduler and manual close happen simultaneously.
        """
        from models import Trip

        session_id = uuid.uuid4()

        # Create trip
        trip = Trip(
            session_id=session_id,
            is_closed=False,
            start_time=datetime.now(timezone.utc),
        )
        db_session.add(trip)
        db_session.commit()
        trip_id = trip.id

        errors = []
        results = []

        def close_trip_worker():
            """Worker that tries to close the trip using its own DB session."""
            from database import SessionLocal

            s = SessionLocal()
            try:
                # Re-fetch trip in this thread's session
                trip = s.query(Trip).filter(Trip.id == trip_id).first()

                if trip and not trip.is_closed:
                    trip.is_closed = True
                    trip.end_time = datetime.now(timezone.utc)
                    s.commit()
                    results.append(trip.id)
                    return trip.id
                return None
            except Exception as e:
                s.rollback()
                errors.append(str(e))
                return None
            finally:
                s.close()

        # Launch concurrent close attempts
        with ThreadPoolExecutor(max_workers=3) as executor:
            futures = [executor.submit(close_trip_worker) for _ in range(3)]

            for future in as_completed(futures):
                future.result()

        # Should have minimal errors (one worker succeeds, others may fail gracefully)
        assert len(errors) <= 2, f"Too many errors: {errors}"

        # Trip should be closed
        db_session.expire_all()
        trip = db_session.query(Trip).filter(Trip.id == trip_id).first()
        assert trip.is_closed, "Trip should be closed"


class TestDatabaseDeadlock:
    """Test scenarios that could cause database deadlocks."""

    def test_concurrent_updates_different_tables(self, app, db_session):
        """
        Test concurrent updates to related tables don't deadlock.

        Updates Trip and TelemetryRaw in different orders from different threads.
        """
        from models import TelemetryRaw, Trip

        session_id = uuid.uuid4()

        # Create trip and telemetry
        trip = Trip(session_id=session_id, is_closed=False, start_time=datetime.now(timezone.utc))
        db_session.add(trip)
        db_session.commit()

        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=datetime.now(timezone.utc),
            state_of_charge=85.0,
        )
        db_session.add(telemetry)
        db_session.commit()

        errors = []
        success_count = 0

        def update_trip_then_telemetry():
            """Update trip, then telemetry, using its own session."""
            from database import SessionLocal

            s = SessionLocal()
            try:
                trip = s.query(Trip).filter(Trip.session_id == session_id).first()
                trip.distance_miles = (trip.distance_miles or 0) + 1
                s.commit()

                time.sleep(0.01)  # Small delay to increase contention

                telemetry = (
                    s.query(TelemetryRaw).filter(TelemetryRaw.session_id == session_id).first()
                )
                telemetry.speed_mph = (telemetry.speed_mph or 0) + 1
                s.commit()

                return True
            except Exception as e:
                s.rollback()
                errors.append(str(e))
                return False
            finally:
                s.close()

        def update_telemetry_then_trip():
            """Update telemetry, then trip, using its own session."""
            from database import SessionLocal

            s = SessionLocal()
            try:
                telemetry = (
                    s.query(TelemetryRaw).filter(TelemetryRaw.session_id == session_id).first()
                )
                telemetry.state_of_charge = (telemetry.state_of_charge or 0) - 0.1
                s.commit()

                time.sleep(0.01)

                trip = s.query(Trip).filter(Trip.session_id == session_id).first()
                trip.distance_miles = (trip.distance_miles or 0) + 0.5
                s.commit()

                return True
            except Exception as e:
                s.rollback()
                errors.append(str(e))
                return False
            finally:
                s.close()

        # Run both patterns concurrently
        with ThreadPoolExecutor(max_workers=4) as executor:
            futures = []
            for _ in range(2):
                futures.append(executor.submit(update_trip_then_telemetry))
                futures.append(executor.submit(update_telemetry_then_trip))

            for future in as_completed(futures):
                if future.result():
                    success_count += 1

        # Most should succeed (some serialization errors acceptable)
        assert success_count >= 2, f"Too many failures: {success_count}/4"

        # No deadlocks (would timeout/hang)
        assert len(errors) <= 2, "Possible deadlock detected"


if __name__ == "__main__":
    pytest.main([__file__, "-v", "-s"])
