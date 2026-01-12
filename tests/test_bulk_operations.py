"""
Tests for bulk operations endpoints.

Tests bulk operations for:
- Bulk delete (soft and permanent)
- Bulk restore
- Bulk update
- Bulk export
- Bulk stats
"""

import uuid
from datetime import datetime, timedelta, timezone


class TestBulkDeleteTrips:
    """Tests for bulk trip deletion."""

    def test_bulk_delete_soft_delete_trips(self, client, db_session):
        """Soft delete multiple trips."""
        from models import Trip

        # Create test trips
        trips = []
        for i in range(3):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                start_odometer=50000.0 + (i * 50),
                distance_miles=25.0,
                is_closed=True,
            )
            db_session.add(trip)
            trips.append(trip)
        db_session.commit()

        trip_ids = [t.id for t in trips]

        response = client.post(
            "/api/bulk/trips/delete",
            json={"trip_ids": trip_ids, "permanent": False}
        )

        assert response.status_code == 200
        data = response.get_json()
        assert data["deleted_count"] == 3
        assert data["permanent"] is False

        # Verify soft delete
        for trip_id in trip_ids:
            trip = db_session.query(Trip).filter(Trip.id == trip_id).first()
            assert trip.deleted_at is not None

    def test_bulk_delete_permanent_delete_trips(self, client, db_session):
        """Permanently delete trips and associated telemetry."""
        from models import Trip, TelemetryRaw

        # Create trips with telemetry
        session_ids = []
        trip_ids = []
        for i in range(2):
            session_id = uuid.uuid4()
            session_ids.append(session_id)

            trip = Trip(
                session_id=session_id,
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                start_odometer=50000.0,
                distance_miles=25.0,
                is_closed=True,
            )
            db_session.add(trip)
            db_session.flush()
            trip_ids.append(trip.id)

            # Add telemetry
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=datetime.now(timezone.utc) - timedelta(days=i),
                speed_mph=45.0,
            )
            db_session.add(telemetry)

        db_session.commit()

        response = client.post(
            "/api/bulk/trips/delete",
            json={"trip_ids": trip_ids, "permanent": True}
        )

        assert response.status_code == 200
        data = response.get_json()
        assert data["deleted_count"] == 2
        assert data["permanent"] is True
        assert data["telemetry_deleted"] >= 0

        # Verify permanent deletion
        for trip_id in trip_ids:
            trip = db_session.query(Trip).filter(Trip.id == trip_id).first()
            assert trip is None

    def test_bulk_delete_missing_trip_ids(self, client):
        """Request without trip_ids returns 400."""
        response = client.post("/api/bulk/trips/delete", json={})
        assert response.status_code == 400
        assert "trip_ids required" in response.get_json()["error"]

    def test_bulk_delete_empty_list(self, client):
        """Empty trip_ids list returns 400."""
        response = client.post("/api/bulk/trips/delete", json={"trip_ids": []})
        assert response.status_code == 400
        assert "non-empty list" in response.get_json()["error"]

    def test_bulk_delete_too_many_trips(self, client):
        """Request with >1000 trips returns 400."""
        trip_ids = list(range(1001))
        response = client.post("/api/bulk/trips/delete", json={"trip_ids": trip_ids})
        assert response.status_code == 400
        assert "Maximum 1000" in response.get_json()["error"]

    def test_bulk_delete_nonexistent_trips(self, client):
        """Deleting nonexistent trips returns success with 0 count."""
        response = client.post(
            "/api/bulk/trips/delete",
            json={"trip_ids": [999999, 999998], "permanent": False}
        )
        assert response.status_code == 200
        data = response.get_json()
        assert data["deleted_count"] == 0


class TestBulkRestoreTrips:
    """Tests for bulk trip restoration."""

    def test_bulk_restore_soft_deleted_trips(self, client, db_session):
        """Restore soft-deleted trips."""
        from models import Trip

        # Create soft-deleted trips
        trips = []
        for i in range(2):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                start_odometer=50000.0,
                distance_miles=20.0,
                is_closed=True,
                deleted_at=datetime.now(timezone.utc),
            )
            db_session.add(trip)
            trips.append(trip)
        db_session.commit()

        trip_ids = [t.id for t in trips]

        response = client.post("/api/bulk/trips/restore", json={"trip_ids": trip_ids})

        assert response.status_code == 200
        data = response.get_json()
        assert data["restored_count"] == 2

        # Verify restoration
        for trip_id in trip_ids:
            trip = db_session.query(Trip).filter(Trip.id == trip_id).first()
            assert trip.deleted_at is None

    def test_bulk_restore_missing_trip_ids(self, client):
        """Request without trip_ids returns 400."""
        response = client.post("/api/bulk/trips/restore", json={})
        assert response.status_code == 400

    def test_bulk_restore_empty_list(self, client):
        """Empty trip_ids list returns 400."""
        response = client.post("/api/bulk/trips/restore", json={"trip_ids": []})
        assert response.status_code == 400

    def test_bulk_restore_nonexistent_trips(self, client):
        """Restoring nonexistent trips returns 0 count."""
        response = client.post("/api/bulk/trips/restore", json={"trip_ids": [999999]})
        assert response.status_code == 200
        data = response.get_json()
        assert data["restored_count"] == 0


class TestBulkUpdateTrips:
    """Tests for bulk trip updates.

    Note: Trip model does not currently have 'notes' or 'tags' fields.
    These tests verify the bulk update endpoint's error handling.
    """

    def test_bulk_update_missing_required_fields(self, client):
        """Request without trip_ids or updates returns 400."""
        response = client.post("/api/bulk/trips/update", json={"trip_ids": [1]})
        assert response.status_code == 400

        response = client.post("/api/bulk/trips/update", json={"updates": {}})
        assert response.status_code == 400

    def test_bulk_update_empty_trip_ids(self, client):
        """Empty trip_ids list returns 400."""
        response = client.post(
            "/api/bulk/trips/update",
            json={"trip_ids": [], "updates": {"notes": "Test"}}
        )
        assert response.status_code == 400

    def test_bulk_update_too_many_trips(self, client):
        """Request with >1000 trips returns 400."""
        trip_ids = list(range(1001))
        response = client.post(
            "/api/bulk/trips/update",
            json={"trip_ids": trip_ids, "updates": {"notes": "Test"}}
        )
        assert response.status_code == 400

    def test_bulk_update_invalid_fields(self, client):
        """Updating invalid fields returns 400."""
        response = client.post(
            "/api/bulk/trips/update",
            json={
                "trip_ids": [1],
                "updates": {"distance_miles": 999}  # Not allowed
            }
        )
        assert response.status_code == 400
        data = response.get_json()
        assert "Invalid fields" in data["error"]


class TestBulkExportTrips:
    """Tests for bulk trip export."""

    def test_bulk_export_trips_json(self, client, db_session):
        """Export trips as JSON."""
        from models import Trip

        # Create trips
        trips = []
        for i in range(2):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                start_odometer=50000.0 + (i * 30),
                distance_miles=25.0,
                is_closed=True,
            )
            db_session.add(trip)
            trips.append(trip)
        db_session.commit()

        trip_ids = [t.id for t in trips]

        response = client.post(
            "/api/bulk/trips/export",
            json={"trip_ids": trip_ids, "format": "json"}
        )

        assert response.status_code == 200
        data = response.get_json()
        assert isinstance(data, list)
        assert len(data) == 2

    def test_bulk_export_trips_csv(self, client, db_session):
        """Export trips as CSV."""
        from models import Trip

        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            distance_miles=30.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.post(
            "/api/bulk/trips/export",
            json={"trip_ids": [trip.id], "format": "csv"}
        )

        assert response.status_code == 200
        assert response.mimetype == "text/csv"
        assert b"id,session_id" in response.data

    def test_bulk_export_missing_trip_ids(self, client):
        """Request without trip_ids returns 400."""
        response = client.post("/api/bulk/trips/export", json={})
        assert response.status_code == 400

    def test_bulk_export_empty_list(self, client):
        """Empty trip_ids list returns 400."""
        response = client.post("/api/bulk/trips/export", json={"trip_ids": []})
        assert response.status_code == 400

    def test_bulk_export_too_many_trips(self, client):
        """Request with >10000 trips returns 400."""
        trip_ids = list(range(10001))
        response = client.post("/api/bulk/trips/export", json={"trip_ids": trip_ids})
        assert response.status_code == 400

    def test_bulk_export_nonexistent_trips(self, client):
        """Exporting nonexistent trips returns 404."""
        response = client.post(
            "/api/bulk/trips/export",
            json={"trip_ids": [999999]}
        )
        assert response.status_code == 404


class TestBulkTripStats:
    """Tests for bulk trip statistics."""

    def test_bulk_stats_calculates_totals(self, client, db_session):
        """Calculate statistics for multiple trips."""
        from models import Trip

        # Create trips with various stats
        trips = [
            Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=1),
                start_odometer=50000.0,
                distance_miles=30.0,
                electric_miles=25.0,
                gas_miles=5.0,
                gas_mode_entered=True,
                gas_mpg=42.0,
                kwh_per_mile=0.35,
                is_closed=True,
            ),
            Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=2),
                start_odometer=50030.0,
                distance_miles=20.0,
                electric_miles=20.0,
                gas_miles=0.0,
                gas_mode_entered=False,
                kwh_per_mile=0.32,
                is_closed=True,
            ),
        ]
        for trip in trips:
            db_session.add(trip)
        db_session.commit()

        trip_ids = [t.id for t in trips]

        response = client.post("/api/bulk/trips/stats", json={"trip_ids": trip_ids})

        assert response.status_code == 200
        data = response.get_json()
        assert data["trip_count"] == 2
        assert data["total_distance_miles"] == 50.0
        assert data["electric_miles"] == 45.0
        assert data["gas_miles"] == 5.0
        assert data["ev_percent"] == 90.0

    def test_bulk_stats_gas_stats(self, client, db_session):
        """Calculate gas mode statistics."""
        from models import Trip

        trips = [
            Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                start_odometer=50000.0 + (i * 30),
                distance_miles=25.0,
                gas_mode_entered=True,
                gas_mpg=40.0 + i,
                is_closed=True,
            )
            for i in range(3)
        ]
        for trip in trips:
            db_session.add(trip)
        db_session.commit()

        trip_ids = [t.id for t in trips]

        response = client.post("/api/bulk/trips/stats", json={"trip_ids": trip_ids})

        assert response.status_code == 200
        data = response.get_json()
        assert "gas_stats" in data
        assert data["gas_stats"]["trip_count"] == 3
        assert "avg_mpg" in data["gas_stats"]
        assert "median_mpg" in data["gas_stats"]

    def test_bulk_stats_ev_stats(self, client, db_session):
        """Calculate EV efficiency statistics."""
        from models import Trip

        trips = [
            Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                start_odometer=50000.0 + (i * 20),
                distance_miles=20.0,
                kwh_per_mile=0.30 + (i * 0.05),
                is_closed=True,
            )
            for i in range(3)
        ]
        for trip in trips:
            db_session.add(trip)
        db_session.commit()

        trip_ids = [t.id for t in trips]

        response = client.post("/api/bulk/trips/stats", json={"trip_ids": trip_ids})

        assert response.status_code == 200
        data = response.get_json()
        assert "ev_stats" in data
        assert data["ev_stats"]["trip_count"] == 3
        assert "avg_kwh_per_mile" in data["ev_stats"]
        assert "median_kwh_per_mile" in data["ev_stats"]

    def test_bulk_stats_missing_trip_ids(self, client):
        """Request without trip_ids returns 400."""
        response = client.post("/api/bulk/trips/stats", json={})
        assert response.status_code == 400

    def test_bulk_stats_empty_list(self, client):
        """Empty trip_ids list returns 400."""
        response = client.post("/api/bulk/trips/stats", json={"trip_ids": []})
        assert response.status_code == 400

    def test_bulk_stats_nonexistent_trips(self, client):
        """Stats for nonexistent trips returns 404."""
        response = client.post("/api/bulk/trips/stats", json={"trip_ids": [999999]})
        assert response.status_code == 404
