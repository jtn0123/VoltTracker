"""
Tests for charging routes in VoltTracker.

Tests CRUD operations and database error handling for charging sessions.
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
def charging_sessions(db_session):
    """Create sample charging sessions for testing."""
    from models import ChargingSession

    sessions = []
    now = datetime.now(timezone.utc)

    # L2 complete session
    l2_session = ChargingSession(
        start_time=now - timedelta(hours=5),
        end_time=now - timedelta(hours=1),
        start_soc=20.0,
        end_soc=95.0,
        kwh_added=13.8,
        charge_type="L2",
        peak_power_kw=6.8,
        avg_power_kw=6.6,
        location_name="Home",
        cost=2.76,
        is_complete=True,
    )
    sessions.append(l2_session)
    db_session.add(l2_session)

    # L1 complete session
    l1_session = ChargingSession(
        start_time=now - timedelta(days=1, hours=10),
        end_time=now - timedelta(days=1),
        start_soc=30.0,
        end_soc=100.0,
        kwh_added=12.9,
        charge_type="L1",
        peak_power_kw=1.4,
        avg_power_kw=1.3,
        location_name="Work",
        is_complete=True,
    )
    sessions.append(l1_session)
    db_session.add(l1_session)

    # Incomplete session
    incomplete_session = ChargingSession(
        start_time=now - timedelta(hours=1),
        start_soc=50.0,
        charge_type="L2",
        is_complete=False,
    )
    sessions.append(incomplete_session)
    db_session.add(incomplete_session)

    db_session.commit()
    return sessions


# ============================================================================
# Charging History Tests
# ============================================================================


class TestChargingHistory:
    """Tests for GET /api/charging/history."""

    def test_get_charging_history_empty(self, client):
        """Test charging history with no sessions returns empty list."""
        response = client.get("/api/charging/history")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)
        assert len(data) == 0

    def test_get_charging_history_with_data(self, client, charging_sessions):
        """Test charging history returns sessions in order."""
        response = client.get("/api/charging/history")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)
        assert len(data) == 3

        # Verify ordering (most recent first)
        for i, session in enumerate(data[:-1]):
            next_session = data[i + 1]
            assert session["start_time"] >= next_session["start_time"]

    def test_get_charging_history_returns_all_fields(self, client, charging_sessions):
        """Test charging history returns expected fields."""
        response = client.get("/api/charging/history")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert len(data) > 0

        session = data[0]
        expected_fields = [
            "id",
            "start_time",
            "start_soc",
            "charge_type",
            "is_complete",
        ]
        for field in expected_fields:
            assert field in session


# ============================================================================
# Add Charging Session Tests
# ============================================================================


class TestAddChargingSession:
    """Tests for POST /api/charging/add."""

    def test_add_charging_session_success(self, client):
        """Test successfully adding a charging session."""
        now = datetime.now(timezone.utc)
        data = {
            "start_time": (now - timedelta(hours=2)).isoformat(),
            "end_time": now.isoformat(),
            "start_soc": 25.0,
            "end_soc": 90.0,
            "kwh_added": 11.9,
            "charge_type": "L2",
            "location_name": "Test Location",
            "cost": 2.38,
        }

        response = client.post(
            "/api/charging/add",
            data=json.dumps(data),
            content_type="application/json",
        )

        assert response.status_code == 201
        result = json.loads(response.data)
        assert result["start_soc"] == 25.0
        assert result["end_soc"] == 90.0
        assert result["is_complete"] is True

    def test_add_charging_session_no_data(self, client):
        """Test adding session with no data returns error."""
        response = client.post(
            "/api/charging/add",
            data=json.dumps(None),
            content_type="application/json",
        )

        assert response.status_code == 400
        result = json.loads(response.data)
        assert "error" in result

    def test_add_charging_session_missing_start_time(self, client):
        """Test adding session without start_time returns error."""
        data = {"start_soc": 30.0}

        response = client.post(
            "/api/charging/add",
            data=json.dumps(data),
            content_type="application/json",
        )

        assert response.status_code == 400
        result = json.loads(response.data)
        assert "start_time" in result["error"].lower()

    def test_add_charging_session_invalid_start_time(self, client):
        """Test adding session with invalid start_time returns error."""
        data = {"start_time": "not-a-valid-datetime"}

        response = client.post(
            "/api/charging/add",
            data=json.dumps(data),
            content_type="application/json",
        )

        assert response.status_code == 400
        result = json.loads(response.data)
        assert "start_time" in result["error"].lower()

    def test_add_charging_session_without_end_time_is_incomplete(self, client):
        """Test session without end_time is marked incomplete."""
        data = {
            "start_time": datetime.now(timezone.utc).isoformat(),
            "start_soc": 40.0,
            "charge_type": "L1",
        }

        response = client.post(
            "/api/charging/add",
            data=json.dumps(data),
            content_type="application/json",
        )

        assert response.status_code == 201
        result = json.loads(response.data)
        assert result["is_complete"] is False

    def test_add_charging_session_integrity_error(self, client):
        """Test handling of IntegrityError on add."""
        data = {"start_time": datetime.now(timezone.utc).isoformat()}

        with patch("routes.charging.get_db") as mock_get_db:
            mock_db = MagicMock()
            mock_db.commit.side_effect = IntegrityError("mock", "params", "orig")
            mock_get_db.return_value = mock_db

            response = client.post(
                "/api/charging/add",
                data=json.dumps(data),
                content_type="application/json",
            )

            assert response.status_code == 409
            result = json.loads(response.data)
            assert "constraint" in result["error"].lower()
            mock_db.rollback.assert_called_once()

    def test_add_charging_session_operational_error(self, client):
        """Test handling of OperationalError on add."""
        data = {"start_time": datetime.now(timezone.utc).isoformat()}

        with patch("routes.charging.get_db") as mock_get_db:
            mock_db = MagicMock()
            mock_db.commit.side_effect = OperationalError("mock", "params", "orig")
            mock_get_db.return_value = mock_db

            response = client.post(
                "/api/charging/add",
                data=json.dumps(data),
                content_type="application/json",
            )

            assert response.status_code == 500
            result = json.loads(response.data)
            assert "database" in result["error"].lower()
            mock_db.rollback.assert_called_once()


# ============================================================================
# Get Charging Session Tests
# ============================================================================


class TestGetChargingSession:
    """Tests for GET /api/charging/<id>."""

    def test_get_charging_session_success(self, client, charging_sessions):
        """Test getting a specific charging session."""
        session_id = charging_sessions[0].id

        response = client.get(f"/api/charging/{session_id}")

        assert response.status_code == 200
        result = json.loads(response.data)
        assert result["id"] == session_id
        assert result["charge_type"] == "L2"

    def test_get_charging_session_not_found(self, client):
        """Test getting non-existent session returns 404."""
        response = client.get("/api/charging/99999")

        assert response.status_code == 404
        result = json.loads(response.data)
        assert "not found" in result["error"].lower()


# ============================================================================
# Get Charging Curve Tests
# ============================================================================


class TestGetChargingCurve:
    """Tests for GET /api/charging/<id>/curve."""

    def test_get_charging_curve_no_data(self, client, charging_sessions):
        """Test getting curve for session without stored curve data."""
        session_id = charging_sessions[0].id

        response = client.get(f"/api/charging/{session_id}/curve")

        assert response.status_code == 200
        result = json.loads(response.data)
        assert "session_id" in result
        assert "curve" in result
        # May be empty if no telemetry available
        assert isinstance(result["curve"], list)

    def test_get_charging_curve_not_found(self, client):
        """Test getting curve for non-existent session returns 404."""
        response = client.get("/api/charging/99999/curve")

        assert response.status_code == 404
        result = json.loads(response.data)
        assert "not found" in result["error"].lower()


# ============================================================================
# Delete Charging Session Tests
# ============================================================================


class TestDeleteChargingSession:
    """Tests for DELETE /api/charging/<id>."""

    def test_delete_charging_session_success(self, client, charging_sessions, db_session):
        """Test successfully deleting a charging session."""
        session_id = charging_sessions[0].id

        response = client.delete(f"/api/charging/{session_id}")

        assert response.status_code == 200
        result = json.loads(response.data)
        assert "deleted" in result["message"].lower()

        # Verify session is actually deleted
        from models import ChargingSession

        deleted = db_session.query(ChargingSession).filter(ChargingSession.id == session_id).first()
        assert deleted is None

    def test_delete_charging_session_not_found(self, client):
        """Test deleting non-existent session returns 404."""
        response = client.delete("/api/charging/99999")

        assert response.status_code == 404
        result = json.loads(response.data)
        assert "not found" in result["error"].lower()

    def test_delete_charging_session_database_error(self, client, charging_sessions):
        """Test handling of database error on delete."""
        session_id = charging_sessions[0].id

        with patch("routes.charging.get_db") as mock_get_db:
            mock_db = MagicMock()
            mock_session = MagicMock()
            mock_db.query.return_value.filter.return_value.first.return_value = mock_session
            mock_db.commit.side_effect = OperationalError("mock", "params", "orig")
            mock_get_db.return_value = mock_db

            response = client.delete(f"/api/charging/{session_id}")

            assert response.status_code == 500
            result = json.loads(response.data)
            assert "database" in result["error"].lower()
            mock_db.rollback.assert_called_once()


# ============================================================================
# Update Charging Session Tests
# ============================================================================


class TestUpdateChargingSession:
    """Tests for PATCH /api/charging/<id>."""

    def test_update_charging_session_success(self, client, charging_sessions):
        """Test successfully updating a charging session."""
        session_id = charging_sessions[0].id
        update_data = {
            "end_soc": 98.0,
            "kwh_added": 14.2,
            "notes": "Updated session",
        }

        response = client.patch(
            f"/api/charging/{session_id}",
            data=json.dumps(update_data),
            content_type="application/json",
        )

        assert response.status_code == 200
        result = json.loads(response.data)
        assert result["end_soc"] == 98.0
        assert result["kwh_added"] == 14.2
        assert result["notes"] == "Updated session"

    def test_update_charging_session_not_found(self, client):
        """Test updating non-existent session returns 404."""
        update_data = {"end_soc": 85.0}

        response = client.patch(
            "/api/charging/99999",
            data=json.dumps(update_data),
            content_type="application/json",
        )

        assert response.status_code == 404
        result = json.loads(response.data)
        assert "not found" in result["error"].lower()

    def test_update_charging_session_no_data(self, client, charging_sessions):
        """Test updating with no data returns error."""
        session_id = charging_sessions[0].id

        response = client.patch(
            f"/api/charging/{session_id}",
            data=json.dumps(None),
            content_type="application/json",
        )

        assert response.status_code == 400
        result = json.loads(response.data)
        assert "error" in result

    def test_update_charging_session_invalid_datetime(self, client, charging_sessions):
        """Test updating with invalid datetime returns error."""
        session_id = charging_sessions[0].id
        update_data = {"end_time": "not-a-valid-datetime"}

        response = client.patch(
            f"/api/charging/{session_id}",
            data=json.dumps(update_data),
            content_type="application/json",
        )

        assert response.status_code == 400
        result = json.loads(response.data)
        assert "datetime" in result["error"].lower()

    def test_update_charging_session_integrity_error(self, client, charging_sessions):
        """Test handling of IntegrityError on update."""
        session_id = charging_sessions[0].id
        update_data = {"kwh_added": 15.0}

        with patch("routes.charging.get_db") as mock_get_db:
            mock_db = MagicMock()
            mock_session = MagicMock()
            mock_db.query.return_value.filter.return_value.first.return_value = mock_session
            mock_db.commit.side_effect = IntegrityError("mock", "params", "orig")
            mock_get_db.return_value = mock_db

            response = client.patch(
                f"/api/charging/{session_id}",
                data=json.dumps(update_data),
                content_type="application/json",
            )

            assert response.status_code == 409
            result = json.loads(response.data)
            assert "constraint" in result["error"].lower()
            mock_db.rollback.assert_called_once()

    def test_update_charging_session_operational_error(self, client, charging_sessions):
        """Test handling of OperationalError on update."""
        session_id = charging_sessions[0].id
        update_data = {"kwh_added": 15.0}

        with patch("routes.charging.get_db") as mock_get_db:
            mock_db = MagicMock()
            mock_session = MagicMock()
            mock_db.query.return_value.filter.return_value.first.return_value = mock_session
            mock_db.commit.side_effect = OperationalError("mock", "params", "orig")
            mock_get_db.return_value = mock_db

            response = client.patch(
                f"/api/charging/{session_id}",
                data=json.dumps(update_data),
                content_type="application/json",
            )

            assert response.status_code == 500
            result = json.loads(response.data)
            assert "database" in result["error"].lower()
            mock_db.rollback.assert_called_once()


# ============================================================================
# Charging Summary Tests
# ============================================================================


class TestChargingSummary:
    """Tests for GET /api/charging/summary."""

    def test_get_charging_summary_empty(self, client):
        """Test charging summary with no sessions."""
        response = client.get("/api/charging/summary")

        assert response.status_code == 200
        result = json.loads(response.data)
        assert result["total_sessions"] == 0
        assert result["total_kwh"] == 0

    def test_get_charging_summary_with_data(self, client, charging_sessions):
        """Test charging summary with session data."""
        response = client.get("/api/charging/summary")

        assert response.status_code == 200
        result = json.loads(response.data)
        # Only complete sessions count
        assert result["total_sessions"] == 2
        assert result["total_kwh"] > 0
        assert "l1_sessions" in result
        assert "l2_sessions" in result
        assert result["l1_sessions"] == 1
        assert result["l2_sessions"] == 1

    def test_get_charging_summary_includes_cost_info(self, client, charging_sessions):
        """Test charging summary includes cost information."""
        response = client.get("/api/charging/summary")

        assert response.status_code == 200
        result = json.loads(response.data)
        assert "electricity_rate" in result
        assert "estimated_cost" in result
        assert "by_charge_type" in result
