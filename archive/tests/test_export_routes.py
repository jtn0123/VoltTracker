"""
Tests for export routes.
"""

import os
import sys
import uuid
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

from models import Trip, FuelEvent


class TestExportTrips:
    """Tests for /api/export/trips endpoint."""

    def _create_trip(self, db_session, days_ago=1, gas=True):
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc) - timedelta(days=days_ago),
            end_time=datetime.now(timezone.utc) - timedelta(days=days_ago) + timedelta(hours=1),
            distance_miles=25.0,
            electric_miles=20.0,
            gas_miles=5.0,
            start_soc=90.0,
            gas_mode_entered=gas,
            gas_mpg=38.0 if gas else None,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()
        return trip

    def test_export_trips_csv_default(self, client, db_session):
        """Default CSV export."""
        self._create_trip(db_session)
        response = client.get("/api/export/trips")
        assert response.status_code == 200
        assert "text/csv" in response.content_type

    def test_export_trips_csv_non_streaming(self, client, db_session):
        """Non-streaming CSV export."""
        self._create_trip(db_session)
        response = client.get("/api/export/trips?stream=false")
        assert response.status_code == 200
        assert "text/csv" in response.content_type

    def test_export_trips_json(self, client, db_session):
        """JSON export of trips."""
        self._create_trip(db_session)
        response = client.get("/api/export/trips?format=json&stream=false")
        assert response.status_code == 200
        data = response.get_json()
        assert isinstance(data, list)

    def test_export_trips_json_streaming(self, client, db_session):
        """Streaming JSON export (ndjson)."""
        self._create_trip(db_session)
        response = client.get("/api/export/trips?format=json&stream=true")
        assert response.status_code == 200
        assert "ndjson" in response.content_type

    def test_export_trips_gas_only(self, client, db_session):
        """Export gas-only trips."""
        self._create_trip(db_session, gas=True)
        self._create_trip(db_session, gas=False, days_ago=2)
        response = client.get("/api/export/trips?gas_only=true&stream=false&format=json")
        assert response.status_code == 200

    def test_export_trips_date_filter(self, client, db_session):
        """Export trips with date filter."""
        self._create_trip(db_session, days_ago=1)
        start = (datetime.now(timezone.utc) - timedelta(days=7)).strftime("%Y-%m-%d")
        response = client.get(f"/api/export/trips?start_date={start}&format=json&stream=false")
        assert response.status_code == 200

    def test_export_trips_empty(self, client, db_session):
        """Export with no trips."""
        response = client.get("/api/export/trips?format=json&stream=false")
        assert response.status_code == 200


class TestExportFuel:
    """Tests for /api/export/fuel endpoint."""

    def test_export_fuel_csv(self, client, db_session):
        """Export fuel events as CSV."""
        event = FuelEvent(
            timestamp=datetime.now(timezone.utc),
            odometer_miles=50000.0,
            gallons_added=8.5,
            fuel_level_before=25.0,
            fuel_level_after=85.0,
        )
        db_session.add(event)
        db_session.commit()

        response = client.get("/api/export/fuel")
        assert response.status_code == 200
        assert "text/csv" in response.content_type
        assert b"gallons_added" in response.data

    def test_export_fuel_json(self, client, db_session):
        """Export fuel events as JSON."""
        response = client.get("/api/export/fuel?format=json")
        assert response.status_code == 200


class TestExportAll:
    """Tests for /api/export/all endpoint."""

    def test_export_all_basic(self, client, db_session):
        """Export all data types."""
        response = client.get("/api/export/all")
        assert response.status_code == 200
        data = response.get_json()
        assert "trips" in data
        assert "fuel_events" in data
        assert "soc_transitions" in data
        assert "charging_sessions" in data
        assert "summary" in data

    def test_export_all_with_date_range(self, client, db_session):
        """Export all with date range filter."""
        start = (datetime.now(timezone.utc) - timedelta(days=30)).strftime("%Y-%m-%dT%H:%M:%S")
        end = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
        response = client.get(f"/api/export/all?start_date={start}&end_date={end}")
        assert response.status_code == 200

    def test_export_all_with_limit(self, client, db_session):
        """Export all with custom limit."""
        response = client.get("/api/export/all?limit=5")
        assert response.status_code == 200

    def test_export_all_invalid_start_date(self, client, db_session):
        """Export all with invalid start_date."""
        response = client.get("/api/export/all?start_date=not-a-date")
        assert response.status_code == 400

    def test_export_all_invalid_end_date(self, client, db_session):
        """Export all with invalid end_date."""
        start = datetime.now(timezone.utc).isoformat()
        response = client.get(f"/api/export/all?start_date={start}&end_date=not-a-date")
        assert response.status_code == 400

    def test_export_all_invalid_limit(self, client, db_session):
        """Export all with non-numeric limit uses default."""
        response = client.get("/api/export/all?limit=abc")
        assert response.status_code == 200


class TestExportTorquePids:
    """Tests for /api/export/torque-pids endpoint."""

    def test_torque_pids_file_not_found(self, client, db_session):
        """Torque PIDs when file doesn't exist."""
        response = client.get("/api/export/torque-pids")
        # Will return 404 since the file doesn't exist in test env
        assert response.status_code in (200, 404)
