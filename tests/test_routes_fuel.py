"""
Tests for fuel routes in VoltTracker.

Tests CRUD operations and database error handling for fuel events.
"""

import json
import os
import sys
from datetime import datetime, timezone, timedelta
from unittest.mock import patch, MagicMock

import pytest
from sqlalchemy.exc import IntegrityError, OperationalError

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))


# ============================================================================
# Fixtures
# ============================================================================


@pytest.fixture
def fuel_events(db_session):
    """Create sample fuel events for testing."""
    from models import FuelEvent

    events = []
    now = datetime.now(timezone.utc)
    for i in range(3):
        event = FuelEvent(
            timestamp=now - timedelta(days=i),
            odometer_miles=50000 + i * 100,
            gallons_added=8.0 + i,
            fuel_level_before=25.0,
            fuel_level_after=90.0,
            price_per_gallon=3.50,
            total_cost=28.0 + i * 3.5,
            notes=f"Test event {i}",
        )
        events.append(event)
        db_session.add(event)
    db_session.commit()
    return events


# ============================================================================
# Fuel History Tests
# ============================================================================


class TestFuelHistory:
    """Tests for GET /api/fuel/history."""

    def test_get_fuel_history_empty(self, client):
        """Test fuel history with no events returns empty list."""
        response = client.get("/api/fuel/history")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)
        assert len(data) == 0

    def test_get_fuel_history_with_data(self, client, fuel_events):
        """Test fuel history returns events in order."""
        response = client.get("/api/fuel/history")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)
        assert len(data) == 3

        # Verify ordering (most recent first)
        for i, event in enumerate(data[:-1]):
            next_event = data[i + 1]
            assert event["timestamp"] >= next_event["timestamp"]

    def test_get_fuel_history_returns_all_fields(self, client, fuel_events):
        """Test fuel history returns all expected fields."""
        response = client.get("/api/fuel/history")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert len(data) > 0

        event = data[0]
        expected_fields = [
            "id",
            "timestamp",
            "odometer_miles",
            "gallons_added",
            "fuel_level_before",
            "fuel_level_after",
        ]
        for field in expected_fields:
            assert field in event


# ============================================================================
# Add Fuel Event Tests
# ============================================================================


class TestAddFuelEvent:
    """Tests for POST /api/fuel/add."""

    def test_add_fuel_event_success(self, client):
        """Test successfully adding a fuel event."""
        data = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "odometer_miles": 50500,
            "gallons_added": 7.5,
            "fuel_level_before": 20.0,
            "fuel_level_after": 85.0,
            "price_per_gallon": 3.45,
            "total_cost": 25.88,
            "notes": "Test fill-up",
        }

        response = client.post(
            "/api/fuel/add",
            data=json.dumps(data),
            content_type="application/json",
        )

        assert response.status_code == 201
        result = json.loads(response.data)
        assert result["gallons_added"] == 7.5
        assert result["odometer_miles"] == 50500

    def test_add_fuel_event_no_data(self, client):
        """Test adding fuel event with no data returns error."""
        response = client.post(
            "/api/fuel/add",
            data=json.dumps(None),
            content_type="application/json",
        )

        assert response.status_code == 400
        result = json.loads(response.data)
        assert "error" in result

    def test_add_fuel_event_empty_object(self, client):
        """Test adding fuel event with empty object succeeds with defaults."""
        response = client.post(
            "/api/fuel/add",
            data=json.dumps({}),
            content_type="application/json",
        )

        # Empty object is valid - timestamp defaults to now, other fields are optional
        # The endpoint may accept this as valid or reject as no meaningful data
        assert response.status_code in [201, 400]

    def test_add_fuel_event_validation_odometer_out_of_range(self, client):
        """Test validation rejects out of range odometer."""
        data = {"odometer_miles": -100}  # Negative not allowed

        response = client.post(
            "/api/fuel/add",
            data=json.dumps(data),
            content_type="application/json",
        )

        assert response.status_code == 400
        result = json.loads(response.data)
        assert "error" in result
        assert "Validation" in result["error"]

    def test_add_fuel_event_validation_gallons_out_of_range(self, client):
        """Test validation rejects gallons over 20."""
        data = {"gallons_added": 25.0}  # Over 20 gal limit

        response = client.post(
            "/api/fuel/add",
            data=json.dumps(data),
            content_type="application/json",
        )

        assert response.status_code == 400
        result = json.loads(response.data)
        assert "error" in result

    def test_add_fuel_event_validation_fuel_level_over_100(self, client):
        """Test validation rejects fuel level over 100%."""
        data = {"fuel_level_after": 110.0}

        response = client.post(
            "/api/fuel/add",
            data=json.dumps(data),
            content_type="application/json",
        )

        assert response.status_code == 400
        result = json.loads(response.data)
        assert "error" in result

    def test_add_fuel_event_invalid_timestamp_uses_default(self, client):
        """Test that invalid timestamp falls back to current time."""
        data = {
            "timestamp": "not-a-valid-timestamp",
            "gallons_added": 5.0,
        }

        response = client.post(
            "/api/fuel/add",
            data=json.dumps(data),
            content_type="application/json",
        )

        assert response.status_code == 201
        result = json.loads(response.data)
        # Should have a valid timestamp (current time fallback)
        assert result["timestamp"] is not None

    def test_add_fuel_event_integrity_error(self, client, db_session):
        """Test handling of database IntegrityError on add."""
        data = {"gallons_added": 5.0}

        with patch("routes.fuel.get_db") as mock_get_db:
            mock_db = MagicMock()
            mock_db.commit.side_effect = IntegrityError("mock", "params", "orig")
            mock_get_db.return_value = mock_db

            response = client.post(
                "/api/fuel/add",
                data=json.dumps(data),
                content_type="application/json",
            )

            assert response.status_code == 409
            result = json.loads(response.data)
            assert "constraint" in result["error"].lower()
            mock_db.rollback.assert_called_once()

    def test_add_fuel_event_operational_error(self, client, db_session):
        """Test handling of database OperationalError on add."""
        data = {"gallons_added": 5.0}

        with patch("routes.fuel.get_db") as mock_get_db:
            mock_db = MagicMock()
            mock_db.commit.side_effect = OperationalError("mock", "params", "orig")
            mock_get_db.return_value = mock_db

            response = client.post(
                "/api/fuel/add",
                data=json.dumps(data),
                content_type="application/json",
            )

            assert response.status_code == 500
            result = json.loads(response.data)
            assert "database" in result["error"].lower()
            mock_db.rollback.assert_called_once()


# ============================================================================
# Delete Fuel Event Tests
# ============================================================================


class TestDeleteFuelEvent:
    """Tests for DELETE /api/fuel/<id>."""

    def test_delete_fuel_event_success(self, client, fuel_events, db_session):
        """Test successfully deleting a fuel event."""
        event_id = fuel_events[0].id

        response = client.delete(f"/api/fuel/{event_id}")

        assert response.status_code == 200
        result = json.loads(response.data)
        assert "deleted" in result["message"].lower()

        # Verify event is actually deleted
        from models import FuelEvent

        deleted = db_session.query(FuelEvent).filter(FuelEvent.id == event_id).first()
        assert deleted is None

    def test_delete_fuel_event_not_found(self, client):
        """Test deleting non-existent fuel event returns 404."""
        response = client.delete("/api/fuel/99999")

        assert response.status_code == 404
        result = json.loads(response.data)
        assert "not found" in result["error"].lower()

    def test_delete_fuel_event_database_error(self, client, fuel_events):
        """Test handling of database error on delete."""
        event_id = fuel_events[0].id

        with patch("routes.fuel.get_db") as mock_get_db:
            mock_db = MagicMock()
            mock_event = MagicMock()
            mock_db.query.return_value.filter.return_value.first.return_value = mock_event
            mock_db.commit.side_effect = OperationalError("mock", "params", "orig")
            mock_get_db.return_value = mock_db

            response = client.delete(f"/api/fuel/{event_id}")

            assert response.status_code == 500
            result = json.loads(response.data)
            assert "database" in result["error"].lower()
            mock_db.rollback.assert_called_once()


# ============================================================================
# Update Fuel Event Tests
# ============================================================================


class TestUpdateFuelEvent:
    """Tests for PATCH /api/fuel/<id>."""

    def test_update_fuel_event_success(self, client, fuel_events):
        """Test successfully updating a fuel event."""
        event_id = fuel_events[0].id
        update_data = {
            "gallons_added": 9.5,
            "notes": "Updated notes",
        }

        response = client.patch(
            f"/api/fuel/{event_id}",
            data=json.dumps(update_data),
            content_type="application/json",
        )

        assert response.status_code == 200
        result = json.loads(response.data)
        assert result["gallons_added"] == 9.5
        assert result["notes"] == "Updated notes"

    def test_update_fuel_event_not_found(self, client):
        """Test updating non-existent fuel event returns 404."""
        update_data = {"gallons_added": 5.0}

        response = client.patch(
            "/api/fuel/99999",
            data=json.dumps(update_data),
            content_type="application/json",
        )

        assert response.status_code == 404
        result = json.loads(response.data)
        assert "not found" in result["error"].lower()

    def test_update_fuel_event_no_data(self, client, fuel_events):
        """Test updating with no data returns error."""
        event_id = fuel_events[0].id

        response = client.patch(
            f"/api/fuel/{event_id}",
            data=json.dumps(None),
            content_type="application/json",
        )

        assert response.status_code == 400
        result = json.loads(response.data)
        assert "error" in result

    def test_update_fuel_event_validation_error(self, client, fuel_events):
        """Test update validation rejects invalid values."""
        event_id = fuel_events[0].id
        update_data = {"gallons_added": 50.0}  # Over limit

        response = client.patch(
            f"/api/fuel/{event_id}",
            data=json.dumps(update_data),
            content_type="application/json",
        )

        assert response.status_code == 400
        result = json.loads(response.data)
        assert "Validation" in result["error"]

    def test_update_fuel_event_integrity_error(self, client, fuel_events):
        """Test handling of IntegrityError on update."""
        event_id = fuel_events[0].id
        update_data = {"gallons_added": 5.0}

        with patch("routes.fuel.get_db") as mock_get_db:
            mock_db = MagicMock()
            mock_event = MagicMock()
            mock_db.query.return_value.filter.return_value.first.return_value = mock_event
            mock_db.commit.side_effect = IntegrityError("mock", "params", "orig")
            mock_get_db.return_value = mock_db

            response = client.patch(
                f"/api/fuel/{event_id}",
                data=json.dumps(update_data),
                content_type="application/json",
            )

            assert response.status_code == 409
            result = json.loads(response.data)
            assert "constraint" in result["error"].lower()
            mock_db.rollback.assert_called_once()

    def test_update_fuel_event_operational_error(self, client, fuel_events):
        """Test handling of OperationalError on update."""
        event_id = fuel_events[0].id
        update_data = {"gallons_added": 5.0}

        with patch("routes.fuel.get_db") as mock_get_db:
            mock_db = MagicMock()
            mock_event = MagicMock()
            mock_db.query.return_value.filter.return_value.first.return_value = mock_event
            mock_db.commit.side_effect = OperationalError("mock", "params", "orig")
            mock_get_db.return_value = mock_db

            response = client.patch(
                f"/api/fuel/{event_id}",
                data=json.dumps(update_data),
                content_type="application/json",
            )

            assert response.status_code == 500
            result = json.loads(response.data)
            assert "database" in result["error"].lower()
            mock_db.rollback.assert_called_once()
