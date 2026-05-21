"""
Additional tests for bulk_operations to cover uncovered lines:
- bulk_delete error handler (101-104)
- bulk_restore error handler (155-158)
- bulk_update success path with valid trips (203-235)
- bulk_export/stats error handlers (319-321, 400-402)
"""

import os
import sys
import uuid
from datetime import datetime, timezone
from unittest.mock import patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

from models import Trip


class TestBulkUpdateSuccess:
    """Tests for bulk update with valid data reaching the DB."""

    def _create_trip(self, db_session):
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            distance_miles=25.0,
            electric_miles=20.0,
            gas_miles=5.0,
            gas_mpg=38.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()
        return trip

    def test_bulk_update_valid_field(self, client, db_session):
        """Update a valid field on existing trips."""
        trip = self._create_trip(db_session)
        response = client.post("/api/bulk/trips/update", json={
            "trip_ids": [trip.id],
            "updates": {"gas_mpg": 42.0}
        })
        assert response.status_code == 200
        data = response.get_json()
        assert data["updated_count"] == 1

    def test_bulk_update_multiple_trips(self, client, db_session):
        """Update multiple trips at once."""
        trips = [self._create_trip(db_session) for _ in range(3)]
        response = client.post("/api/bulk/trips/update", json={
            "trip_ids": [t.id for t in trips],
            "updates": {"gas_miles": 10.0}
        })
        assert response.status_code == 200
        data = response.get_json()
        assert data["updated_count"] == 3

    def test_bulk_update_no_matching_trips(self, client, db_session):
        """Update with IDs that don't exist returns 0 updated."""
        response = client.post("/api/bulk/trips/update", json={
            "trip_ids": [99999],
            "updates": {"gas_mpg": 42.0}
        })
        assert response.status_code == 200
        data = response.get_json()
        assert data["updated_count"] == 0

    def test_bulk_update_multiple_fields(self, client, db_session):
        """Update multiple valid fields at once."""
        trip = self._create_trip(db_session)
        response = client.post("/api/bulk/trips/update", json={
            "trip_ids": [trip.id],
            "updates": {"gas_mpg": 45.0, "electric_miles": 18.0}
        })
        assert response.status_code == 200
        data = response.get_json()
        assert data["updated_count"] == 1


class TestBulkErrorHandlers:
    """Tests for error handler paths in bulk operations."""

    def test_bulk_delete_db_error(self, client, db_session):
        """Bulk delete error handler when DB fails."""
        with patch("routes.bulk_operations.get_db") as mock_db:
            mock_db.return_value.query.side_effect = Exception("DB error")
            response = client.post("/api/bulk/trips/delete", json={
                "trip_ids": [1, 2, 3]
            })
            assert response.status_code == 500
            assert "error" in response.get_json()

    def test_bulk_restore_db_error(self, client, db_session):
        """Bulk restore error handler when DB fails."""
        with patch("routes.bulk_operations.get_db") as mock_db:
            mock_db.return_value.query.side_effect = Exception("DB error")
            response = client.post("/api/bulk/trips/restore", json={
                "trip_ids": [1, 2, 3]
            })
            assert response.status_code == 500
            assert "error" in response.get_json()

    def test_bulk_update_db_error(self, client, db_session):
        """Bulk update error handler when DB fails."""
        with patch("routes.bulk_operations.get_db") as mock_db:
            mock_db.return_value.query.side_effect = Exception("DB error")
            response = client.post("/api/bulk/trips/update", json={
                "trip_ids": [1],
                "updates": {"gas_mpg": 42.0}
            })
            assert response.status_code == 500
            assert "error" in response.get_json()

    def test_bulk_export_db_error(self, client, db_session):
        """Bulk export error handler when DB fails."""
        with patch("routes.bulk_operations.get_db") as mock_db:
            mock_db.return_value.query.side_effect = Exception("DB error")
            response = client.post("/api/bulk/trips/export", json={
                "trip_ids": [1, 2]
            })
            assert response.status_code == 500
            assert "error" in response.get_json()

    def test_bulk_stats_db_error(self, client, db_session):
        """Bulk stats error handler when DB fails."""
        with patch("routes.bulk_operations.get_db") as mock_db:
            mock_db.return_value.query.side_effect = Exception("DB error")
            response = client.post("/api/bulk/trips/stats", json={
                "trip_ids": [1, 2]
            })
            assert response.status_code == 500
            assert "error" in response.get_json()
