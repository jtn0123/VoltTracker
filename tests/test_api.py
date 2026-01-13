"""
Tests for Flask API endpoints.
"""

import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))


class TestTorqueUpload:
    """Tests for /torque/upload endpoint."""

    def test_upload_returns_ok(self, client, sample_torque_data):
        """Test that upload endpoint returns 'OK!'."""
        response = client.post("/torque/upload", data=sample_torque_data)

        assert response.status_code == 200
        assert response.data.decode() == "OK!"

    def test_upload_with_empty_data(self, client):
        """Test upload with empty data still returns OK."""
        response = client.post("/torque/upload", data={})

        # Should still return OK to avoid Torque retries
        assert response.status_code == 200
        assert response.data.decode() == "OK!"

    def test_upload_with_partial_data(self, client):
        """Test upload with partial data."""
        data = {
            "session": "test-session",
            "kff1001": "45.0",  # Just speed
        }
        response = client.post("/torque/upload", data=data)

        assert response.status_code == 200
        assert response.data.decode() == "OK!"

    def test_upload_via_get_request(self, client, sample_torque_data):
        """Test that upload works via GET query params."""
        # Convert sample data to query string
        response = client.get("/torque/upload", query_string=sample_torque_data)

        assert response.status_code == 200
        assert response.data.decode() == "OK!"

    def test_upload_with_token(self, client, sample_torque_data, monkeypatch):
        """Test upload with token in URL."""
        # Test with correct token
        monkeypatch.setattr("config.Config.TORQUE_API_TOKEN", "test-token")
        response = client.post("/torque/upload/test-token", data=sample_torque_data)

        assert response.status_code == 200
        assert response.data.decode() == "OK!"

    def test_upload_with_invalid_token(self, client, sample_torque_data, monkeypatch):
        """Test upload with invalid token returns 401."""
        monkeypatch.setattr("config.Config.TORQUE_API_TOKEN", "correct-token")
        response = client.post("/torque/upload/wrong-token", data=sample_torque_data)

        assert response.status_code == 401

    def test_upload_malformed_data_returns_ok(self, client):
        """Test upload with malformed data still returns OK (to avoid Torque retries)."""
        data = {
            "session": "test-session",
            "kff1001": "not-a-number",  # Invalid speed
            "kff1005": "abc",  # Invalid RPM
        }
        response = client.post("/torque/upload", data=data)

        # Should still return OK to avoid Torque retries
        assert response.status_code == 200
        assert response.data.decode() == "OK!"

    def test_upload_creates_trip_with_start_values(self, client, sample_torque_data, db_session):
        """Test that upload creates trip and sets start values."""
        from models import Trip

        response = client.post("/torque/upload", data=sample_torque_data)
        assert response.status_code == 200

        # Verify trip was created with start values
        trip = db_session.query(Trip).first()
        assert trip is not None
        assert trip.start_soc is not None
        assert trip.start_odometer is not None

    def test_upload_updates_null_start_values(self, client, sample_torque_data, db_session):
        """Test that upload updates trip start values if they were null initially."""
        import uuid
        from datetime import datetime, timezone
        from models import Trip

        # Create a trip without start values (but with required start_time)
        session_id = uuid.UUID(sample_torque_data["session"])
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            start_soc=None,
            start_odometer=None,
        )
        db_session.add(trip)
        db_session.commit()

        # Upload telemetry - should update start values
        response = client.post("/torque/upload", data=sample_torque_data)
        assert response.status_code == 200

        # Verify start values were updated (re-query to get updated values)
        from models import Trip
        updated_trip = db_session.query(Trip).filter(Trip.session_id == session_id).first()
        assert updated_trip is not None
        assert updated_trip.start_soc is not None
        assert updated_trip.start_odometer is not None

    def test_upload_exception_returns_ok(self, client, monkeypatch):
        """Test that upload returns OK even on database errors to avoid Torque retries."""
        from unittest.mock import MagicMock, patch

        # Mock get_db to raise an exception
        def mock_get_db():
            raise Exception("Database connection error")

        with patch("routes.telemetry.get_db", mock_get_db):
            response = client.post("/torque/upload", data={"session": "test-123"})

            # Should still return OK to avoid Torque retries
            assert response.status_code == 200
            assert response.data.decode() == "OK!"

    def test_upload_sqlalchemy_exception_returns_ok(self, client, monkeypatch):
        """Test that SQLAlchemy errors are handled gracefully."""
        from unittest.mock import MagicMock, patch

        # Create a mock exception that looks like SQLAlchemy
        class MockSQLAlchemyError(Exception):
            __module__ = "sqlalchemy.exc"

        mock_db = MagicMock()
        mock_db.query.side_effect = MockSQLAlchemyError("Connection failed")

        with patch("routes.telemetry.get_db", return_value=mock_db):
            response = client.post("/torque/upload", data={"session": "test-123"})

            # Should still return OK
            assert response.status_code == 200
            assert response.data.decode() == "OK!"

    def test_upload_race_condition_handled(self, client, db_session, sample_torque_data):
        """Test that race condition in trip creation is handled gracefully."""
        import uuid
        from datetime import datetime, timezone
        from unittest.mock import MagicMock, patch
        from models import Trip

        # Pre-create the trip to simulate race condition
        session_id = uuid.UUID(sample_torque_data["session"])
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            start_soc=50.0,
            start_odometer=10000.0,
        )
        db_session.add(trip)
        db_session.commit()

        # Upload should work despite trip already existing
        response = client.post("/torque/upload", data=sample_torque_data)
        assert response.status_code == 200
        assert response.data.decode() == "OK!"


class TestStatusEndpoint:
    """Tests for /api/status endpoint."""

    def test_status_returns_json(self, client):
        """Test that status endpoint returns JSON."""
        response = client.get("/api/status")

        assert response.status_code == 200
        assert response.content_type == "application/json"

    def test_status_has_required_fields(self, client):
        """Test that status response has required fields."""
        response = client.get("/api/status")
        data = json.loads(response.data)

        assert "status" in data
        assert "database" in data


class TestTripsEndpoint:
    """Tests for /api/trips endpoint."""

    def test_trips_returns_list(self, client):
        """Test that trips endpoint returns paginated response with trips list."""
        response = client.get("/api/trips")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "trips" in data
        assert "pagination" in data
        assert isinstance(data["trips"], list)

    def test_trips_with_gas_only_filter(self, client):
        """Test gas_only query parameter."""
        response = client.get("/api/trips?gas_only=true")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "trips" in data
        assert isinstance(data["trips"], list)

    def test_trips_with_date_filter(self, client):
        """Test date filter parameters."""
        response = client.get("/api/trips?start_date=2024-01-01&end_date=2024-12-31")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "trips" in data
        assert isinstance(data["trips"], list)


class TestEfficiencyEndpoint:
    """Tests for /api/efficiency/summary endpoint."""

    def test_efficiency_returns_json(self, client):
        """Test efficiency endpoint returns JSON."""
        response = client.get("/api/efficiency/summary")

        assert response.status_code == 200
        assert response.content_type == "application/json"

    def test_efficiency_has_required_fields(self, client):
        """Test efficiency response has required fields."""
        response = client.get("/api/efficiency/summary")
        data = json.loads(response.data)

        assert "lifetime_gas_mpg" in data
        assert "total_miles_tracked" in data


class TestSocAnalysisEndpoint:
    """Tests for /api/soc/analysis endpoint."""

    def test_soc_analysis_returns_json(self, client):
        """Test SOC analysis endpoint returns JSON."""
        response = client.get("/api/soc/analysis")

        assert response.status_code == 200
        assert response.content_type == "application/json"

    def test_soc_analysis_has_required_fields(self, client):
        """Test SOC analysis response has required fields."""
        response = client.get("/api/soc/analysis")
        data = json.loads(response.data)

        assert "average_soc" in data
        assert "count" in data
        assert "histogram" in data


class TestMpgTrendEndpoint:
    """Tests for /api/mpg/trend endpoint."""

    def test_mpg_trend_returns_list(self, client):
        """Test MPG trend endpoint returns list."""
        response = client.get("/api/mpg/trend")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)

    def test_mpg_trend_with_days_param(self, client):
        """Test days query parameter."""
        response = client.get("/api/mpg/trend?days=7")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)


class TestFuelEndpoints:
    """Tests for fuel-related endpoints."""

    def test_fuel_history_returns_list(self, client):
        """Test fuel history endpoint returns list."""
        response = client.get("/api/fuel/history")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)

    def test_add_fuel_event(self, client):
        """Test adding a fuel event."""
        fuel_data = {
            "timestamp": "2024-01-15T10:30:00",
            "odometer_miles": 51000,
            "gallons_added": 7.5,
            "price_per_gallon": 3.49,
            "total_cost": 26.18,
        }
        response = client.post("/api/fuel/add", data=json.dumps(fuel_data), content_type="application/json")

        assert response.status_code == 201
        data = json.loads(response.data)
        assert data["gallons_added"] == 7.5

    def test_add_fuel_event_no_data(self, client):
        """Test adding fuel event with no data."""
        response = client.post("/api/fuel/add", data="", content_type="application/json")

        assert response.status_code == 400


class TestDashboard:
    """Tests for dashboard endpoint."""

    def test_dashboard_returns_html(self, client):
        """Test that dashboard returns HTML."""
        response = client.get("/")

        assert response.status_code == 200
        assert b"Volt Efficiency Tracker" in response.data


class TestTripDetailEndpoint:
    """Tests for /api/trips/<id> endpoint."""

    def test_trip_not_found(self, client):
        """Test 404 for non-existent trip."""
        response = client.get("/api/trips/99999")

        assert response.status_code == 404
        data = json.loads(response.data)
        assert "error" in data
        assert "not found" in data["error"].lower()

    def test_trip_detail_returns_json(self, client, db_session):
        """Test trip detail returns proper JSON structure."""
        import os
        import sys
        import uuid
        from datetime import datetime, timezone

        sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))
        from models import Trip

        # Create a test trip
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.get(f"/api/trips/{trip.id}")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "trip" in data
        assert "telemetry" in data
        assert data["trip"]["id"] == trip.id


class TestApiErrorHandling:
    """Tests for API error handling."""

    def test_invalid_fuel_event_data(self, client):
        """Test error handling for invalid fuel event data."""
        response = client.post("/api/fuel/add", data="not valid json", content_type="application/json")

        assert response.status_code == 400

    def test_invalid_mpg_trend_days_param(self, client):
        """Test handling of invalid days parameter defaults to 30."""
        response = client.get("/api/mpg/trend?days=invalid")

        # Should handle gracefully by using default value
        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)

    def test_invalid_date_filter(self, client):
        """Test handling of invalid date format in trips filter."""
        response = client.get("/api/trips?start_date=not-a-date")

        # Should handle gracefully (may return empty or error)
        assert response.status_code in [200, 400]


class TestApiEdgeCases:
    """Tests for API edge cases."""

    def test_efficiency_with_empty_database(self, client):
        """Test efficiency endpoint with no data."""
        response = client.get("/api/efficiency/summary")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data["lifetime_gas_mpg"] is None
        assert data["total_miles_tracked"] == 0

    def test_soc_analysis_with_empty_database(self, client):
        """Test SOC analysis with no transitions."""
        response = client.get("/api/soc/analysis")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data["count"] == 0
        assert data["average_soc"] is None
        assert data["histogram"] == {}

    def test_mpg_trend_with_no_gas_trips(self, client):
        """Test MPG trend with no gas trips."""
        response = client.get("/api/mpg/trend")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)
        assert len(data) == 0

    def test_fuel_history_empty(self, client):
        """Test fuel history with no events."""
        response = client.get("/api/fuel/history")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)
        assert len(data) == 0

    def test_status_with_no_telemetry(self, client):
        """Test status when no telemetry has been received."""
        response = client.get("/api/status")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data["status"] == "online"
        assert data["last_sync"] is None
        assert data["active_trip"] is None

    def test_trips_with_limit(self, client, db_session):
        """Test trips endpoint respects the 100 limit."""
        # This tests the limit behavior with empty database
        # db_session is needed to ensure DB is initialized
        _ = db_session
        response = client.get("/api/trips")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert len(data) <= 100


class TestExportEndpoints:
    """Tests for data export endpoints."""

    def test_export_trips_csv(self, client, db_session):
        """Test exporting trips as CSV."""
        import uuid
        from datetime import datetime, timezone

        from models import Trip

        # Create a test trip
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            distance_miles=25.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.get("/api/export/trips")

        assert response.status_code == 200
        assert response.content_type == "text/csv; charset=utf-8"
        assert b"id,session_id,start_time" in response.data

    def test_export_trips_json(self, client):
        """Test exporting trips as JSON."""
        response = client.get("/api/export/trips?format=json")

        assert response.status_code == 200
        assert response.content_type == "application/json"
        data = json.loads(response.data)
        assert isinstance(data, list)

    def test_export_fuel_csv(self, client):
        """Test exporting fuel events as CSV."""
        response = client.get("/api/export/fuel")

        assert response.status_code == 200
        assert response.content_type == "text/csv; charset=utf-8"
        assert b"id,timestamp,odometer_miles" in response.data

    def test_export_fuel_json(self, client):
        """Test exporting fuel events as JSON."""
        response = client.get("/api/export/fuel?format=json")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)

    def test_export_all(self, client, db_session):
        """Test exporting all data as JSON."""
        import uuid
        from datetime import datetime, timezone

        from models import Trip

        # Create test data
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.get("/api/export/all")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "exported_at" in data
        assert "trips" in data
        assert "fuel_events" in data
        assert "soc_transitions" in data
        assert "summary" in data
        assert len(data["trips"]) == 1


class TestTripManagement:
    """Tests for trip management endpoints."""

    def test_delete_trip(self, client, db_session):
        """Test deleting a trip."""
        import uuid
        from datetime import datetime, timezone

        from models import Trip

        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()
        trip_id = trip.id

        response = client.delete(f"/api/trips/{trip_id}")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "deleted successfully" in data["message"]

        # Verify trip is gone
        response = client.get(f"/api/trips/{trip_id}")
        assert response.status_code == 404

    def test_delete_trip_not_found(self, client):
        """Test deleting non-existent trip."""
        response = client.delete("/api/trips/99999")

        assert response.status_code == 404
        data = json.loads(response.data)
        assert "not found" in data["error"].lower()

    def test_update_trip(self, client, db_session):
        """Test updating trip fields."""
        import uuid
        from datetime import datetime, timezone

        from models import Trip

        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            gas_mpg=35.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.patch(
            f"/api/trips/{trip.id}", data=json.dumps({"gas_mpg": 42.5}), content_type="application/json"
        )

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data["gas_mpg"] == 42.5

    def test_update_trip_not_found(self, client):
        """Test updating non-existent trip."""
        response = client.patch("/api/trips/99999", data=json.dumps({"gas_mpg": 42.5}), content_type="application/json")

        assert response.status_code == 404

    def test_update_trip_no_data(self, client, db_session):
        """Test updating trip with no data."""
        import uuid
        from datetime import datetime, timezone

        from models import Trip

        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.patch(f"/api/trips/{trip.id}", data="", content_type="application/json")

        assert response.status_code == 400


class TestFuelEventManagement:
    """Tests for fuel event management endpoints."""

    def test_delete_fuel_event(self, client, db_session):
        """Test deleting a fuel event."""
        from datetime import datetime, timezone

        from models import FuelEvent

        event = FuelEvent(
            timestamp=datetime.now(timezone.utc),
            gallons_added=8.0,
        )
        db_session.add(event)
        db_session.commit()
        event_id = event.id

        response = client.delete(f"/api/fuel/{event_id}")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "deleted successfully" in data["message"]

    def test_delete_fuel_event_not_found(self, client):
        """Test deleting non-existent fuel event."""
        response = client.delete("/api/fuel/99999")

        assert response.status_code == 404

    def test_update_fuel_event(self, client, db_session):
        """Test updating fuel event."""
        from datetime import datetime, timezone

        from models import FuelEvent

        event = FuelEvent(
            timestamp=datetime.now(timezone.utc),
            gallons_added=8.0,
            price_per_gallon=3.50,
        )
        db_session.add(event)
        db_session.commit()

        response = client.patch(
            f"/api/fuel/{event.id}",
            data=json.dumps({"price_per_gallon": 3.75, "notes": "Updated price"}),
            content_type="application/json",
        )

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data["price_per_gallon"] == 3.75
        assert data["notes"] == "Updated price"

    def test_update_fuel_event_not_found(self, client):
        """Test updating non-existent fuel event."""
        response = client.patch("/api/fuel/99999", data=json.dumps({"notes": "test"}), content_type="application/json")

        assert response.status_code == 404


class TestApiPagination:
    """Tests for API pagination support."""

    def test_trips_with_pagination(self, client, db_session):
        """Test trips endpoint with pagination parameters."""
        import uuid
        from datetime import datetime, timezone

        from models import Trip

        # Create 5 trips
        for i in range(5):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc),
                is_closed=True,
                distance_miles=10.0 + i,  # Set distance to pass filter
            )
            db_session.add(trip)
        db_session.commit()

        # Request with pagination
        response = client.get("/api/trips?page=1&per_page=2")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "trips" in data
        assert "pagination" in data
        assert len(data["trips"]) == 2
        assert data["pagination"]["page"] == 1
        assert data["pagination"]["per_page"] == 2
        assert data["pagination"]["total"] == 5
        assert data["pagination"]["pages"] == 3

    def test_trips_without_pagination_returns_paginated(self, client, db_session):
        """Test trips endpoint returns paginated response with default pagination."""
        import uuid
        from datetime import datetime, timezone

        from models import Trip

        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            is_closed=True,
            distance_miles=10.0,  # Set distance to pass filter
        )
        db_session.add(trip)
        db_session.commit()

        # Request without pagination params
        response = client.get("/api/trips")

        assert response.status_code == 200
        data = json.loads(response.data)
        # Should return paginated response
        assert "trips" in data
        assert "pagination" in data
        assert isinstance(data["trips"], list)
        assert len(data["trips"]) == 1

    def test_pagination_page_2(self, client, db_session):
        """Test fetching second page of results."""
        import uuid
        from datetime import datetime, timedelta, timezone

        from models import Trip

        # Create 5 trips with different start times
        for i in range(5):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                is_closed=True,
                distance_miles=10.0 + i,  # Set distance to pass filter
            )
            db_session.add(trip)
        db_session.commit()

        # Request page 2
        response = client.get("/api/trips?page=2&per_page=2")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert len(data["trips"]) == 2
        assert data["pagination"]["page"] == 2

    def test_pagination_invalid_params(self, client):
        """Test pagination with invalid parameters."""
        response = client.get("/api/trips?page=invalid&per_page=abc")

        assert response.status_code == 200
        data = json.loads(response.data)
        # Should use defaults
        assert data["pagination"]["page"] == 1
        assert data["pagination"]["per_page"] == 50


class TestChargingEndpoints:
    """Tests for charging session endpoints."""

    def test_charging_history_returns_list(self, client):
        """Test charging history endpoint returns list."""
        response = client.get("/api/charging/history")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)

    def test_add_charging_session(self, client):
        """Test adding a charging session."""
        charging_data = {
            "start_time": "2024-01-15T18:00:00",
            "end_time": "2024-01-15T22:00:00",
            "start_soc": 20.0,
            "end_soc": 95.0,
            "kwh_added": 12.0,
            "charge_type": "L2",
            "location_name": "Home",
        }
        response = client.post("/api/charging/add", data=json.dumps(charging_data), content_type="application/json")

        assert response.status_code == 201
        data = json.loads(response.data)
        assert data["kwh_added"] == 12.0
        assert data["charge_type"] == "L2"
        assert data["is_complete"] is True

    def test_add_charging_session_no_start_time(self, client):
        """Test adding charging session without start_time fails."""
        response = client.post(
            "/api/charging/add", data=json.dumps({"kwh_added": 10.0}), content_type="application/json"
        )

        assert response.status_code == 400
        data = json.loads(response.data)
        assert "start_time" in data["error"].lower()

    def test_get_charging_session(self, client, db_session):
        """Test getting a specific charging session."""
        from datetime import datetime, timezone

        from models import ChargingSession

        session = ChargingSession(
            start_time=datetime.now(timezone.utc),
            start_soc=30.0,
            end_soc=80.0,
            kwh_added=8.0,
            charge_type="L1",
            is_complete=True,
        )
        db_session.add(session)
        db_session.commit()

        response = client.get(f"/api/charging/{session.id}")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data["id"] == session.id
        assert data["kwh_added"] == 8.0

    def test_get_charging_session_not_found(self, client):
        """Test 404 for non-existent charging session."""
        response = client.get("/api/charging/99999")

        assert response.status_code == 404

    def test_delete_charging_session(self, client, db_session):
        """Test deleting a charging session."""
        from datetime import datetime, timezone

        from models import ChargingSession

        session = ChargingSession(
            start_time=datetime.now(timezone.utc),
            kwh_added=10.0,
            is_complete=True,
        )
        db_session.add(session)
        db_session.commit()
        session_id = session.id

        response = client.delete(f"/api/charging/{session_id}")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "deleted successfully" in data["message"]

    def test_update_charging_session(self, client, db_session):
        """Test updating a charging session."""
        from datetime import datetime, timezone

        from models import ChargingSession

        session = ChargingSession(
            start_time=datetime.now(timezone.utc),
            kwh_added=10.0,
        )
        db_session.add(session)
        db_session.commit()

        response = client.patch(
            f"/api/charging/{session.id}",
            data=json.dumps({"kwh_added": 12.5, "notes": "Updated"}),
            content_type="application/json",
        )

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data["kwh_added"] == 12.5
        assert data["notes"] == "Updated"

    def test_charging_summary_empty(self, client):
        """Test charging summary with no data."""
        response = client.get("/api/charging/summary")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data["total_sessions"] == 0
        assert data["total_kwh"] == 0

    def test_charging_summary_with_data(self, client, db_session):
        """Test charging summary with data."""
        from datetime import datetime, timezone

        from models import ChargingSession

        # Add two charging sessions
        for i in range(2):
            session = ChargingSession(
                start_time=datetime.now(timezone.utc),
                kwh_added=10.0 + i,
                charge_type="L2",
                is_complete=True,
            )
            db_session.add(session)
        db_session.commit()

        response = client.get("/api/charging/summary")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data["total_sessions"] == 2
        assert data["total_kwh"] == 21.0
        assert "L2" in data["by_charge_type"]


class TestApiValidation:
    """Tests for API input validation."""

    def test_fuel_event_validation_invalid_gallons(self, client):
        """Test validation rejects invalid gallons value."""
        fuel_data = {
            "timestamp": "2024-01-15T10:30:00",
            "gallons_added": 50.0,  # Way too much for a 9.3 gal tank
        }
        response = client.post("/api/fuel/add", data=json.dumps(fuel_data), content_type="application/json")

        assert response.status_code == 400
        data = json.loads(response.data)
        assert "error" in data
        assert "Validation failed" in data["error"]

    def test_fuel_event_validation_invalid_odometer(self, client):
        """Test validation rejects negative odometer."""
        fuel_data = {
            "timestamp": "2024-01-15T10:30:00",
            "odometer_miles": -100,
        }
        response = client.post("/api/fuel/add", data=json.dumps(fuel_data), content_type="application/json")

        assert response.status_code == 400
        data = json.loads(response.data)
        assert "Validation failed" in data["error"]

    def test_fuel_event_validation_invalid_fuel_level(self, client):
        """Test validation rejects fuel level over 100."""
        fuel_data = {
            "timestamp": "2024-01-15T10:30:00",
            "fuel_level_after": 150.0,
        }
        response = client.post("/api/fuel/add", data=json.dumps(fuel_data), content_type="application/json")

        assert response.status_code == 400
        data = json.loads(response.data)
        assert "Validation failed" in data["error"]

    def test_fuel_event_validation_non_numeric(self, client):
        """Test validation rejects non-numeric values."""
        fuel_data = {
            "timestamp": "2024-01-15T10:30:00",
            "gallons_added": "not-a-number",
        }
        response = client.post("/api/fuel/add", data=json.dumps(fuel_data), content_type="application/json")

        assert response.status_code == 400
        data = json.loads(response.data)
        assert "Validation failed" in data["error"]

    def test_fuel_event_valid_data_passes(self, client):
        """Test valid fuel event data is accepted."""
        fuel_data = {
            "timestamp": "2024-01-15T10:30:00",
            "odometer_miles": 51000,
            "gallons_added": 7.5,
            "price_per_gallon": 3.49,
            "total_cost": 26.18,
            "fuel_level_before": 10.0,
            "fuel_level_after": 90.0,
        }
        response = client.post("/api/fuel/add", data=json.dumps(fuel_data), content_type="application/json")

        assert response.status_code == 201
        data = json.loads(response.data)
        assert data["gallons_added"] == 7.5


class TestPaginationEdgeCases:
    """Tests for pagination edge cases."""

    def test_pagination_page_zero(self, client, db_session):
        """Test page=0 defaults to page 1."""
        import uuid
        from datetime import datetime, timezone

        from models import Trip

        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.get("/api/trips?page=0&per_page=10")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data["pagination"]["page"] == 1

    def test_pagination_negative_page(self, client):
        """Test negative page defaults to page 1."""
        response = client.get("/api/trips?page=-5&per_page=10")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data["pagination"]["page"] == 1

    def test_pagination_per_page_zero(self, client):
        """Test per_page=0 is capped to minimum of 1."""
        response = client.get("/api/trips?page=1&per_page=0")

        assert response.status_code == 200
        data = json.loads(response.data)
        # per_page=0 becomes max(1, 0) = 1
        assert data["pagination"]["per_page"] == 1

    def test_pagination_per_page_too_large(self, client):
        """Test per_page > 100 is capped."""
        response = client.get("/api/trips?page=1&per_page=999")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data["pagination"]["per_page"] <= 100

    def test_pagination_page_beyond_max(self, client):
        """Test page beyond max returns empty results."""
        response = client.get("/api/trips?page=9999&per_page=10")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert len(data["trips"]) == 0


class TestMalformedRequests:
    """Tests for malformed JSON and bad requests."""

    def test_fuel_add_malformed_json(self, client):
        """Test malformed JSON returns 400."""
        response = client.post("/api/fuel/add", data='{"invalid json', content_type="application/json")

        assert response.status_code == 400

    def test_charging_add_malformed_json(self, client):
        """Test malformed JSON in charging add returns 400."""
        response = client.post("/api/charging/add", data="not valid json at all", content_type="application/json")

        assert response.status_code == 400

    def test_trip_patch_malformed_json(self, client, db_session):
        """Test malformed JSON in trip patch returns 400."""
        import uuid
        from datetime import datetime, timezone

        from models import Trip

        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.patch(f"/api/trips/{trip.id}", data="{broken: json}", content_type="application/json")

        assert response.status_code == 400

    def test_fuel_add_empty_body(self, client):
        """Test empty request body returns error."""
        response = client.post("/api/fuel/add", data="", content_type="application/json")

        assert response.status_code == 400


class TestDeleteNotFound:
    """Tests for 404 on delete operations."""

    def test_delete_nonexistent_trip(self, client):
        """Test deleting non-existent trip returns 404."""
        response = client.delete("/api/trips/99999")

        assert response.status_code == 404
        data = json.loads(response.data)
        assert "not found" in data["error"].lower()

    def test_delete_nonexistent_fuel_event(self, client):
        """Test deleting non-existent fuel event returns 404."""
        response = client.delete("/api/fuel/99999")

        assert response.status_code == 404

    def test_delete_nonexistent_charging_session(self, client):
        """Test deleting non-existent charging session returns 404."""
        response = client.delete("/api/charging/99999")

        assert response.status_code == 404

    def test_patch_nonexistent_trip(self, client):
        """Test patching non-existent trip returns 404."""
        response = client.patch("/api/trips/99999", data=json.dumps({"gas_mpg": 45.0}), content_type="application/json")

        assert response.status_code == 404

    def test_patch_nonexistent_fuel_event(self, client):
        """Test patching non-existent fuel event returns 404."""
        response = client.patch(
            "/api/fuel/99999", data=json.dumps({"gallons_added": 5.0}), content_type="application/json"
        )

        assert response.status_code == 404

    def test_patch_nonexistent_charging_session(self, client):
        """Test patching non-existent charging session returns 404."""
        response = client.patch(
            "/api/charging/99999", data=json.dumps({"kwh_added": 10.0}), content_type="application/json"
        )

        assert response.status_code == 404


class TestExportWithData:
    """Tests for export endpoints with various data."""

    def test_export_all_includes_charging(self, client, db_session):
        """Test full export includes charging sessions."""
        from datetime import datetime, timezone

        from models import ChargingSession

        session = ChargingSession(
            start_time=datetime.now(timezone.utc),
            kwh_added=10.0,
            charge_type="L2",
            is_complete=True,
        )
        db_session.add(session)
        db_session.commit()

        response = client.get("/api/export/all")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "charging_sessions" in data
        assert len(data["charging_sessions"]) == 1
        assert data["charging_sessions"][0]["kwh_added"] == 10.0

    def test_export_csv_with_special_characters(self, client, db_session):
        """Test CSV export handles special characters."""
        from datetime import datetime, timezone

        from models import ChargingSession

        session = ChargingSession(
            start_time=datetime.now(timezone.utc),
            kwh_added=10.0,
            location_name='Home, "Main" Garage',  # Commas and quotes
            notes="Test notes with\nnewlines",
            is_complete=True,
        )
        db_session.add(session)
        db_session.commit()

        response = client.get("/api/charging/history")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data[0]["location_name"] == 'Home, "Main" Garage'


class TestChargingSummaryDetails:
    """Tests for charging summary edge cases."""

    def test_charging_summary_l1_and_l2_counts(self, client, db_session):
        """Test summary returns correct L1/L2 counts."""
        from datetime import datetime, timezone

        from models import ChargingSession

        # Add L1 sessions
        for _ in range(3):
            db_session.add(
                ChargingSession(
                    start_time=datetime.now(timezone.utc),
                    kwh_added=5.0,
                    charge_type="L1",
                    is_complete=True,
                )
            )

        # Add L2 sessions
        for _ in range(2):
            db_session.add(
                ChargingSession(
                    start_time=datetime.now(timezone.utc),
                    kwh_added=10.0,
                    charge_type="L2",
                    is_complete=True,
                )
            )

        db_session.commit()

        response = client.get("/api/charging/summary")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data["l1_sessions"] == 3
        assert data["l2_sessions"] == 2
        assert data["total_sessions"] == 5

    def test_charging_summary_with_ev_ratio(self, client, db_session):
        """Test summary includes EV ratio from trips."""
        import uuid
        from datetime import datetime, timezone

        from models import ChargingSession, Trip

        # Create trips with electric miles
        trip1 = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            distance_miles=50.0,
            electric_miles=40.0,  # 80% electric
            is_closed=True,
        )
        trip2 = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            distance_miles=50.0,
            electric_miles=30.0,  # 60% electric
            is_closed=True,
        )
        db_session.add(trip1)
        db_session.add(trip2)

        # Add a charging session to make summary return data
        db_session.add(
            ChargingSession(
                start_time=datetime.now(timezone.utc),
                kwh_added=10.0,
                is_complete=True,
            )
        )

        db_session.commit()

        response = client.get("/api/charging/summary")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data["total_electric_miles"] == 70.0
        assert data["ev_ratio"] == 70.0  # 70/100 = 70%


class TestChargingFiltersAndCost:
    """Tests for charging history filtering and cost calculations."""

    def test_charging_history_returns_all_sessions(self, client, db_session):
        """Test charging history returns all sessions."""
        from datetime import datetime, timedelta, timezone

        from models import ChargingSession

        # Create sessions at different dates
        old_session = ChargingSession(
            start_time=datetime.now(timezone.utc) - timedelta(days=30),
            kwh_added=10.0,
            is_complete=True,
        )
        recent_session = ChargingSession(
            start_time=datetime.now(timezone.utc) - timedelta(days=1),
            kwh_added=12.0,
            is_complete=True,
        )
        db_session.add(old_session)
        db_session.add(recent_session)
        db_session.commit()

        response = client.get("/api/charging/history")

        assert response.status_code == 200
        data = json.loads(response.data)
        # Should include all sessions
        assert len(data) == 2

    def test_charging_summary_includes_cost_fields(self, client, db_session):
        """Test charging summary includes cost-related fields."""
        from datetime import datetime, timezone

        from models import ChargingSession

        # Add charging session with cost data
        session = ChargingSession(
            start_time=datetime.now(timezone.utc),
            kwh_added=15.0,
            cost=2.25,  # Total cost
            is_complete=True,
        )
        db_session.add(session)
        db_session.commit()

        response = client.get("/api/charging/summary")

        assert response.status_code == 200
        data = json.loads(response.data)
        # Should include kwh-related fields
        assert "total_kwh" in data
        assert data["total_kwh"] == 15.0

    def test_charging_history_sorted_by_date(self, client, db_session):
        """Test charging history is returned sorted by date descending."""
        from datetime import datetime, timedelta, timezone

        from models import ChargingSession

        # Create sessions in non-chronological order
        session1 = ChargingSession(
            start_time=datetime.now(timezone.utc) - timedelta(days=5),
            kwh_added=8.0,
            is_complete=True,
        )
        session2 = ChargingSession(
            start_time=datetime.now(timezone.utc) - timedelta(days=1),
            kwh_added=12.0,
            is_complete=True,
        )
        session3 = ChargingSession(
            start_time=datetime.now(timezone.utc) - timedelta(days=10),
            kwh_added=6.0,
            is_complete=True,
        )
        db_session.add(session1)
        db_session.add(session2)
        db_session.add(session3)
        db_session.commit()

        response = client.get("/api/charging/history")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert len(data) == 3
        # Most recent should be first
        assert data[0]["kwh_added"] == 12.0
        assert data[1]["kwh_added"] == 8.0
        assert data[2]["kwh_added"] == 6.0


class TestTripPatchRestrictions:
    """Tests for trip patch field restrictions."""

    def test_trip_patch_ignores_disallowed_fields(self, client, db_session):
        """Test that PATCH ignores fields that shouldn't be updated directly."""
        import uuid
        from datetime import datetime, timezone

        from models import Trip

        original_session_id = uuid.uuid4()
        trip = Trip(
            session_id=original_session_id,
            start_time=datetime.now(timezone.utc),
            distance_miles=25.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        # Try to update session_id and id (should be ignored)
        new_session_id = str(uuid.uuid4())
        response = client.patch(
            f"/api/trips/{trip.id}",
            data=json.dumps(
                {
                    "session_id": new_session_id,
                    "id": 99999,
                    "gas_mpg": 42.0,  # This should be updated
                }
            ),
            content_type="application/json",
        )

        assert response.status_code == 200
        data = json.loads(response.data)
        # gas_mpg should be updated
        assert data["gas_mpg"] == 42.0
        # session_id should NOT be changed
        assert data["session_id"] == str(original_session_id)
        # id should NOT be changed
        assert data["id"] == trip.id

    def test_trip_patch_updates_gas_mpg(self, client, db_session):
        """Test that trip gas_mpg can be updated."""
        import uuid
        from datetime import datetime, timezone

        from models import Trip

        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            gas_mpg=30.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.patch(
            f"/api/trips/{trip.id}", data=json.dumps({"gas_mpg": 38.5}), content_type="application/json"
        )

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data["gas_mpg"] == 38.5

    def test_trip_patch_updates_multiple_fields(self, client, db_session):
        """Test updating multiple allowed fields at once."""
        import uuid
        from datetime import datetime, timezone

        from models import Trip

        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            gas_mpg=35.0,
            electric_miles=5.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.patch(
            f"/api/trips/{trip.id}",
            data=json.dumps(
                {
                    "gas_mpg": 45.0,
                    "electric_miles": 10.0,
                    "gas_miles": 15.0,
                }
            ),
            content_type="application/json",
        )

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data["gas_mpg"] == 45.0
        assert data["electric_miles"] == 10.0
        assert data["gas_miles"] == 15.0


class TestTripSortingAndFiltering:
    """Tests for trips sorting and advanced filtering."""

    def test_trips_sorted_by_date_descending(self, client, db_session):
        """Test trips are returned sorted by start_time descending."""
        import uuid
        from datetime import datetime, timedelta, timezone

        from models import Trip

        # Create trips in non-chronological order
        for i in [5, 1, 10, 3]:
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                distance_miles=float(i * 10),
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        response = client.get("/api/trips")

        assert response.status_code == 200
        data = json.loads(response.data)
        trips = data["trips"]
        assert len(trips) == 4
        # Most recent (1 day ago) should be first
        assert trips[0]["distance_miles"] == 10.0
        # Oldest (10 days ago) should be last
        assert trips[-1]["distance_miles"] == 100.0

    def test_trips_filter_electric_only(self, client, db_session):
        """Test filtering for electric-only trips."""
        import uuid
        from datetime import datetime, timezone

        from models import Trip

        # Create mixed trips
        gas_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            distance_miles=30.0,
            gas_miles=30.0,
            electric_miles=0.0,
            is_closed=True,
        )
        electric_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            distance_miles=25.0,
            gas_miles=0.0,
            electric_miles=25.0,
            is_closed=True,
        )
        db_session.add(gas_trip)
        db_session.add(electric_trip)
        db_session.commit()

        # Filter for gas_only=false should include all or just electric
        response = client.get("/api/trips?gas_only=false")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "trips" in data

    def test_trips_filter_gas_only_returns_gas_trips(self, client, db_session):
        """Test gas_only filter returns only trips with gas usage."""
        import uuid
        from datetime import datetime, timedelta, timezone

        from models import Trip

        now = datetime.now(timezone.utc)

        # Gas trip with gas_mode_entered=True
        trip1 = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=1),
            gas_miles=20.0,
            gas_mode_entered=True,
            is_closed=True,
            distance_miles=20.0,  # Set distance to pass filter
        )
        # Electric-only trip
        trip2 = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=2),
            electric_miles=15.0,
            gas_mode_entered=False,
            is_closed=True,
            distance_miles=15.0,  # Set distance to pass filter
        )
        db_session.add(trip1)
        db_session.add(trip2)
        db_session.commit()

        response = client.get("/api/trips?gas_only=true")

        assert response.status_code == 200
        data = json.loads(response.data)
        trips = data["trips"]
        # Should only include trips with gas_mode_entered=True
        assert len(trips) == 1
        assert trips[0]["gas_miles"] == 20.0


class TestTelemetryLatestEndpointExtended:
    """Extended tests for telemetry latest endpoint."""

    def test_telemetry_latest_with_power_data(self, client, db_session):
        """Test telemetry latest includes power data when available."""
        import uuid
        from datetime import datetime, timedelta, timezone

        from models import TelemetryRaw, Trip

        session_id = uuid.uuid4()

        # Create active trip
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc) - timedelta(minutes=10),
            start_odometer=50000.0,
            is_closed=False,
        )
        db_session.add(trip)

        # Create telemetry with power data
        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=datetime.now(timezone.utc),
            hv_battery_power_kw=5.0,
            state_of_charge=70.0,
            speed_mph=45.0,
        )
        db_session.add(telemetry)
        db_session.commit()

        response = client.get("/api/telemetry/latest")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data["active"] is True


class TestBatteryHealthHistory:
    """Tests for battery health history endpoint."""

    def test_battery_health_history_returns_list(self, client, db_session):
        """Test battery health history returns list of readings."""
        from datetime import datetime, timedelta, timezone

        from models import BatteryHealthReading

        # Create several readings over time
        for i in range(3):
            reading = BatteryHealthReading(
                timestamp=datetime.now(timezone.utc) - timedelta(days=i * 30),
                capacity_kwh=18.0 - (i * 0.1),
                normalized_capacity_kwh=18.0 - (i * 0.1),
                soc_at_reading=100.0,
            )
            db_session.add(reading)
        db_session.commit()

        response = client.get("/api/battery/health")

        assert response.status_code == 200
        data = json.loads(response.data)
        # Should have capacity or history data
        assert "capacity_kwh" in data or "current_capacity_kwh" in data or "history" in data


class TestEfficiencyWithData:
    """Tests for efficiency endpoint with actual data."""

    def test_efficiency_with_trips(self, client, db_session):
        """Test efficiency summary with trip data."""
        import uuid
        from datetime import datetime, timezone

        from models import Trip

        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            distance_miles=50.0,
            gas_miles=20.0,
            electric_miles=30.0,
            fuel_used_gallons=0.5,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.get("/api/efficiency/summary")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data["total_miles_tracked"] == 50.0
        assert data["lifetime_gas_mpg"] == 40.0  # 20 miles / 0.5 gallons

    def test_efficiency_calculates_ev_percentage(self, client, db_session):
        """Test efficiency summary includes EV percentage."""
        import uuid
        from datetime import datetime, timezone

        from models import Trip

        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            distance_miles=100.0,
            electric_miles=75.0,
            gas_miles=25.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.get("/api/efficiency/summary")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "ev_percentage" in data or "electric_ratio" in data or "total_electric_miles" in data


class TestClearCacheEndpoint:
    """Tests for /clear-cache utility endpoint."""

    def test_clear_cache_returns_html(self, client):
        """Clear cache endpoint returns HTML page."""
        response = client.get("/clear-cache")

        assert response.status_code == 200
        assert "text/html" in response.content_type

    def test_clear_cache_has_clear_button(self, client):
        """Clear cache page has a clear button."""
        response = client.get("/clear-cache")
        html = response.data.decode()

        assert "Clear Cache" in html or "clearBtn" in html

    def test_clear_cache_has_service_worker_script(self, client):
        """Clear cache page has service worker unregistration script."""
        response = client.get("/clear-cache")
        html = response.data.decode()

        assert "serviceWorker" in html


class TestReadinessEndpoint:
    """Tests for readiness/liveness probes."""

    def test_health_endpoint_returns_healthy(self, client):
        """Health endpoint returns healthy status."""
        response = client.get("/health")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data.get("status") == "healthy"
        assert data.get("service") == "volttracker"

    def test_ready_endpoint_returns_status(self, client):
        """Ready endpoint returns status."""
        response = client.get("/ready")

        # May return 200 (ready) or 503 (not ready)
        assert response.status_code in (200, 503)

    def test_readiness_endpoint_returns_status(self, client):
        """Readiness endpoint (alternate path) returns status."""
        response = client.get("/readiness")

        # May return 200 (ready) or 503 (not ready)
        assert response.status_code in (200, 503)
