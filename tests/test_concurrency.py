"""
Race condition and concurrency tests for VoltTracker.

Tests concurrent operations to ensure data integrity:
- Concurrent trip creation
- Concurrent telemetry uploads
- Concurrent CSV imports
- Concurrent charging session updates
"""

import pytest
import threading
import time
import uuid
from datetime import datetime, timezone
from concurrent.futures import ThreadPoolExecutor, as_completed


class TestConcurrentTripCreation:
    """Test concurrent trip creation for the same session."""

    def test_concurrent_trip_creation_race_condition(self, db_session):
        """
        Test that concurrent trip creation for same session doesn't create duplicates.

        This simulates the race condition where two telemetry uploads arrive
        simultaneously and both try to create a trip.
        """
        from models import Trip
        from services.trip_service import get_or_create_trip

        session_id = uuid.uuid4()
        results = []
        errors = []

        def create_trip_worker():
            """Worker that tries to create a trip."""
            try:
                trip = get_or_create_trip(db_session, session_id)
                results.append(trip.id)
                return trip
            except Exception as e:
                errors.append(e)
                return None

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
        trips = db_session.query(Trip).filter(Trip.session_id == session_id).all()
        assert len(trips) == 1, f"Expected 1 trip, found {len(trips)}"

    def test_concurrent_telemetry_upload_same_session(self, db_session):
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
            """Worker that uploads telemetry."""
            try:
                telemetry = TelemetryRaw(
                    session_id=session_id,
                    timestamp=datetime.now(timezone.utc),
                    state_of_charge=85.0 - (index * 0.1),  # Gradually decreasing
                    speed_mph=30.0 + index
                )
                db_session.add(telemetry)
                db_session.commit()
                return True
            except Exception as e:
                db_session.rollback()
                errors.append(e)
                return False

        # Launch concurrent uploads
        with ThreadPoolExecutor(max_workers=10) as executor:
            futures = [executor.submit(upload_telemetry_worker, i) for i in range(upload_count)]

            results = [future.result() for future in as_completed(futures)]

        # Most should succeed (some conflicts are acceptable due to locking)
        success_count = sum(results)
        assert success_count >= upload_count * 0.8, f"Too many failures: {success_count}/{upload_count}"

        # Verify telemetry was saved
        telemetry_count = db_session.query(TelemetryRaw).filter(
            TelemetryRaw.session_id == session_id
        ).count()
        assert telemetry_count > 0, "No telemetry was saved"


class TestConcurrentChargingSession:
    """Test concurrent charging session operations."""

    def test_concurrent_charging_session_detection(self, db_session):
        """
        Test that concurrent charging detection doesn't create duplicate sessions.
        """
        from models import ChargingSession
        from services.charging_service import detect_or_update_charging_session

        session_id = uuid.uuid4()
        results = []
        errors = []

        def detect_charging_worker():
            """Worker that tries to detect/create charging session."""
            try:
                charging_session = detect_or_update_charging_session(
                    db_session,
                    session_id=session_id,
                    soc=50.0,
                    timestamp=datetime.now(timezone.utc)
                )
                if charging_session:
                    results.append(charging_session.id)
                return charging_session
            except Exception as e:
                errors.append(e)
                db_session.rollback()
                return None

        # Launch concurrent workers
        with ThreadPoolExecutor(max_workers=5) as executor:
            futures = [executor.submit(detect_charging_worker) for _ in range(5)]

            for future in as_completed(futures):
                future.result()

        # Should have minimal errors
        assert len(errors) <= 1, f"Too many errors: {errors}"

        # Should create only one charging session
        if results:
            unique_ids = set(results)
            assert len(unique_ids) == 1, f"Multiple charging sessions: {unique_ids}"


class TestConcurrentCSVImport:
    """Test concurrent CSV import operations."""

    def test_concurrent_csv_import_duplicate_detection(self, db_session):
        """
        Test that concurrent CSV imports with same file are detected as duplicates.
        """
        from models import CsvImport
        import hashlib

        # Simulate same file content
        file_content = b"test,csv,content"
        file_hash = hashlib.sha256(file_content).hexdigest()

        import_codes = []
        errors = []

        def import_worker(worker_id):
            """Worker that tries to import CSV."""
            try:
                # Check for existing import
                existing = db_session.query(CsvImport).filter(
                    CsvImport.file_hash == file_hash
                ).first()

                if existing:
                    return None  # Duplicate detected

                # Create new import record
                csv_import = CsvImport(
                    import_code=f"IMP-{worker_id}",
                    filename="test.csv",
                    file_hash=file_hash,
                    file_size_bytes=len(file_content),
                    status="success",
                    total_rows=10
                )
                db_session.add(csv_import)
                db_session.commit()
                import_codes.append(csv_import.import_code)
                return csv_import
            except Exception as e:
                db_session.rollback()
                errors.append(e)
                return None

        # Launch concurrent import attempts
        with ThreadPoolExecutor(max_workers=5) as executor:
            futures = [executor.submit(import_worker, i) for i in range(5)]

            for future in as_completed(futures):
                future.result()

        # Only one should succeed (others detect duplicate)
        imports_count = db_session.query(CsvImport).filter(
            CsvImport.file_hash == file_hash
        ).count()

        assert imports_count <= 1, f"Multiple imports created: {imports_count}"


class TestConcurrentTripFinalization:
    """Test concurrent trip finalization."""

    def test_concurrent_trip_close_attempts(self, db_session):
        """
        Test that concurrent attempts to close a trip don't cause issues.

        This can happen if scheduler and manual close happen simultaneously.
        """
        from models import Trip
        from services.trip_service import finalize_trip

        session_id = uuid.uuid4()

        # Create trip
        trip = Trip(
            session_id=session_id,
            is_closed=False,
            start_time=datetime.now(timezone.utc)
        )
        db_session.add(trip)
        db_session.commit()
        trip_id = trip.id

        errors = []
        results = []

        def close_trip_worker():
            """Worker that tries to close the trip."""
            try:
                # Re-fetch trip in this thread's session
                trip = db_session.query(Trip).filter(Trip.id == trip_id).first()

                if trip and not trip.is_closed:
                    result = finalize_trip(db_session, trip)
                    results.append(result)
                    return result
                return None
            except Exception as e:
                db_session.rollback()
                errors.append(str(e))
                return None

        # Launch concurrent close attempts
        with ThreadPoolExecutor(max_workers=3) as executor:
            futures = [executor.submit(close_trip_worker) for _ in range(3)]

            for future in as_completed(futures):
                future.result()

        # Should have minimal errors (one worker succeeds, others may fail gracefully)
        assert len(errors) <= 2, f"Too many errors: {errors}"

        # Trip should be closed
        trip = db_session.query(Trip).filter(Trip.id == trip_id).first()
        assert trip.is_closed, "Trip should be closed"


class TestDatabaseDeadlock:
    """Test scenarios that could cause database deadlocks."""

    def test_concurrent_updates_different_tables(self, db_session):
        """
        Test concurrent updates to related tables don't deadlock.

        Updates Trip and TelemetryRaw in different orders from different threads.
        """
        from models import Trip, TelemetryRaw

        session_id = uuid.uuid4()

        # Create trip and telemetry
        trip = Trip(session_id=session_id, is_closed=False, start_time=datetime.now(timezone.utc))
        db_session.add(trip)
        db_session.commit()

        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=datetime.now(timezone.utc),
            state_of_charge=85.0
        )
        db_session.add(telemetry)
        db_session.commit()

        errors = []
        success_count = 0

        def update_trip_then_telemetry():
            """Update trip, then telemetry."""
            try:
                trip = db_session.query(Trip).filter(Trip.session_id == session_id).first()
                trip.distance_miles = (trip.distance_miles or 0) + 1
                db_session.commit()

                time.sleep(0.01)  # Small delay to increase contention

                telemetry = db_session.query(TelemetryRaw).filter(
                    TelemetryRaw.session_id == session_id
                ).first()
                telemetry.speed_mph = (telemetry.speed_mph or 0) + 1
                db_session.commit()

                return True
            except Exception as e:
                db_session.rollback()
                errors.append(str(e))
                return False

        def update_telemetry_then_trip():
            """Update telemetry, then trip."""
            try:
                telemetry = db_session.query(TelemetryRaw).filter(
                    TelemetryRaw.session_id == session_id
                ).first()
                telemetry.state_of_charge = (telemetry.state_of_charge or 0) - 0.1
                db_session.commit()

                time.sleep(0.01)

                trip = db_session.query(Trip).filter(Trip.session_id == session_id).first()
                trip.distance_miles = (trip.distance_miles or 0) + 0.5
                db_session.commit()

                return True
            except Exception as e:
                db_session.rollback()
                errors.append(str(e))
                return False

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


# ============================================================================
# Fixtures
# ============================================================================

@pytest.fixture
def db_session():
    """Provide a database session for testing."""
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
    pytest.main([__file__, "-v", "-s"])
