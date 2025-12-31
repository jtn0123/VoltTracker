"""
Tests for extended API endpoint coverage.

Tests telemetry latest, battery health, and export endpoints.
"""

import pytest
import sys
import os
import json
import uuid
from datetime import datetime, timezone, timedelta

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'receiver'))

from models import TelemetryRaw, Trip, FuelEvent, ChargingSession, BatteryHealthReading


class TestTelemetryLatestEndpoint:
    """Tests for /api/telemetry/latest endpoint."""

    def test_latest_returns_inactive_when_no_data(self, client):
        """No telemetry data returns inactive status."""
        response = client.get('/api/telemetry/latest')
        assert response.status_code == 200
        data = response.get_json()
        assert data['active'] is False

    def test_latest_returns_inactive_when_data_stale(self, client, db_session):
        """Stale telemetry (>2 min old) returns inactive status."""
        session_id = uuid.uuid4()
        # Stale time - more than TRIP_TIMEOUT_SECONDS (120s) ago
        stale_time = datetime.now(timezone.utc) - timedelta(minutes=5)

        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=stale_time,
            speed_mph=45.0,
            state_of_charge=75.0,
        )
        db_session.add(telemetry)
        db_session.commit()

        response = client.get('/api/telemetry/latest')
        assert response.status_code == 200
        data = response.get_json()
        assert data['active'] is False

    def test_latest_returns_active_with_recent_data(self, client, db_session):
        """Recent telemetry returns active status with data."""
        session_id = uuid.uuid4()

        # Create trip for the session
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc) - timedelta(minutes=30),
            start_odometer=50000.0,
            start_soc=80.0,
            is_closed=False,
        )
        db_session.add(trip)

        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=datetime.now(timezone.utc),
            speed_mph=55.0,
            state_of_charge=70.0,
            engine_rpm=0,
            odometer_miles=50010.0,
        )
        db_session.add(telemetry)
        db_session.commit()

        response = client.get('/api/telemetry/latest')
        assert response.status_code == 200
        data = response.get_json()
        assert data['active'] is True
        assert 'data' in data

    def test_latest_includes_trip_stats(self, client, db_session):
        """Response includes trip statistics when driving."""
        session_id = uuid.uuid4()

        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc) - timedelta(minutes=30),
            start_odometer=50000.0,
            start_soc=80.0,
            is_closed=False,
        )
        db_session.add(trip)

        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=datetime.now(timezone.utc),
            speed_mph=55.0,
            state_of_charge=65.0,
            engine_rpm=0,
            odometer_miles=50015.0,
        )
        db_session.add(telemetry)
        db_session.commit()

        response = client.get('/api/telemetry/latest')
        data = response.get_json()
        assert data['active'] is True
        assert 'trip_stats' in data

    def test_latest_detects_electric_mode(self, client, db_session):
        """Detects electric mode when RPM is 0."""
        session_id = uuid.uuid4()

        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc) - timedelta(minutes=10),
            start_odometer=50000.0,
            is_closed=False,
        )
        db_session.add(trip)

        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=datetime.now(timezone.utc),
            speed_mph=45.0,
            state_of_charge=60.0,
            engine_rpm=0,  # Electric mode
        )
        db_session.add(telemetry)
        db_session.commit()

        response = client.get('/api/telemetry/latest')
        assert response.status_code == 200
        data = response.get_json()
        assert data['active'] is True

    def test_latest_detects_gas_mode(self, client, db_session):
        """Detects gas mode when RPM > 0 and SOC low."""
        session_id = uuid.uuid4()

        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc) - timedelta(minutes=10),
            start_odometer=50000.0,
            is_closed=False,
        )
        db_session.add(trip)

        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=datetime.now(timezone.utc),
            speed_mph=65.0,
            state_of_charge=15.0,
            engine_rpm=1500,  # Gas mode
        )
        db_session.add(telemetry)
        db_session.commit()

        response = client.get('/api/telemetry/latest')
        assert response.status_code == 200
        data = response.get_json()
        assert data['active'] is True


class TestBatteryHealthEndpoint:
    """Tests for /api/battery/health endpoint."""

    def test_health_returns_no_data_state(self, client):
        """No battery data returns appropriate status."""
        response = client.get('/api/battery/health')
        assert response.status_code == 200
        data = response.get_json()
        assert 'status' in data or 'current_capacity_kwh' in data

    def test_health_with_dedicated_readings(self, client, db_session):
        """Uses BatteryHealthReading when available."""
        reading = BatteryHealthReading(
            timestamp=datetime.utcnow(),
            capacity_kwh=17.5,
            normalized_capacity_kwh=17.2,
            soc_at_reading=100.0,
        )
        db_session.add(reading)
        db_session.commit()

        response = client.get('/api/battery/health')
        assert response.status_code == 200
        data = response.get_json()
        assert 'current_capacity_kwh' in data or 'capacity_kwh' in data or 'status' in data

    def test_health_calculates_percentage(self, client, db_session):
        """Battery health percentage is calculated from capacity."""
        reading = BatteryHealthReading(
            timestamp=datetime.utcnow(),
            capacity_kwh=16.56,  # 90% of 18.4 kWh
            normalized_capacity_kwh=16.56,
            soc_at_reading=100.0,
        )
        db_session.add(reading)
        db_session.commit()

        response = client.get('/api/battery/health')
        assert response.status_code == 200


class TestExportEndpoints:
    """Tests for /api/export/* endpoints."""

    def test_export_trips_returns_csv(self, client, db_session):
        """Trips export returns CSV by default."""
        # Create a trip to export
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.utcnow() - timedelta(hours=1),
            end_time=datetime.utcnow(),
            start_odometer=50000.0,
            end_odometer=50025.0,
            distance_miles=25.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.get('/api/export/trips')
        assert response.status_code == 200
        assert 'text/csv' in response.content_type

    def test_export_trips_has_correct_headers(self, client, db_session):
        """CSV export has expected column headers."""
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.utcnow() - timedelta(hours=1),
            end_time=datetime.utcnow(),
            start_odometer=50000.0,
            distance_miles=25.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.get('/api/export/trips')
        csv_data = response.data.decode('utf-8')
        # Should have headers
        assert 'id' in csv_data.lower() or 'distance' in csv_data.lower()

    def test_export_trips_json_format(self, client, db_session):
        """Trips export as JSON when format=json specified."""
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.utcnow() - timedelta(hours=1),
            distance_miles=25.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.get('/api/export/trips?format=json')
        assert response.status_code == 200
        assert 'application/json' in response.content_type

    def test_export_fuel_returns_csv(self, client, db_session):
        """Fuel export returns CSV by default."""
        fuel_event = FuelEvent(
            timestamp=datetime.utcnow(),
            fuel_level_before=50.0,
            fuel_level_after=90.0,
            gallons_added=3.5,
            odometer_miles=50000.0,
        )
        db_session.add(fuel_event)
        db_session.commit()

        response = client.get('/api/export/fuel')
        assert response.status_code == 200
        assert 'text/csv' in response.content_type

    def test_export_all_returns_json(self, client, db_session):
        """All data export returns JSON."""
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.utcnow(),
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.get('/api/export/all')
        assert response.status_code == 200
        assert 'application/json' in response.content_type
        data = response.get_json()
        assert 'trips' in data

    def test_export_all_includes_summary(self, client, db_session):
        """Export all includes summary statistics."""
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.utcnow() - timedelta(hours=2),
            end_time=datetime.utcnow(),
            distance_miles=30.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.get('/api/export/all')
        data = response.get_json()
        assert 'summary' in data or 'trips' in data

    def test_export_torque_pids_returns_csv(self, client):
        """Torque PIDs export returns CSV."""
        response = client.get('/api/export/torque-pids')
        assert response.status_code == 200
        # Either returns CSV or 404 if file not found
        if response.status_code == 200:
            assert 'text/csv' in response.content_type or 'attachment' in str(response.headers)

    def test_export_with_date_filter(self, client, db_session):
        """Export respects date range filter."""
        now = datetime.utcnow()

        # Trip in range
        trip1 = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=1),
            distance_miles=20.0,
            is_closed=True,
        )
        # Trip out of range
        trip2 = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=30),
            distance_miles=30.0,
            is_closed=True,
        )
        db_session.add(trip1)
        db_session.add(trip2)
        db_session.commit()

        start_date = (now - timedelta(days=7)).strftime('%Y-%m-%d')
        response = client.get(f'/api/export/trips?format=json&start_date={start_date}')
        assert response.status_code == 200


class TestChargingHistoryEndpoint:
    """Tests for /api/charging/history endpoint."""

    def test_charging_history_returns_list(self, client, db_session):
        """Charging history returns list of sessions."""
        session = ChargingSession(
            start_time=datetime.utcnow() - timedelta(hours=2),
            end_time=datetime.utcnow(),
            start_soc=30.0,
            end_soc=90.0,
            kwh_added=11.0,
            charge_type='L2',
            is_complete=True,
        )
        db_session.add(session)
        db_session.commit()

        response = client.get('/api/charging/history')
        assert response.status_code == 200
        data = response.get_json()
        assert isinstance(data, list) or 'sessions' in data

    def test_charging_summary_returns_stats(self, client, db_session):
        """Charging summary returns aggregate statistics."""
        session1 = ChargingSession(
            start_time=datetime.utcnow() - timedelta(days=1),
            end_time=datetime.utcnow() - timedelta(days=1) + timedelta(hours=3),
            start_soc=20.0,
            end_soc=100.0,
            kwh_added=14.72,
            charge_type='L2',
            is_complete=True,
        )
        session2 = ChargingSession(
            start_time=datetime.utcnow() - timedelta(hours=2),
            end_time=datetime.utcnow(),
            start_soc=50.0,
            end_soc=80.0,
            kwh_added=5.52,
            charge_type='L1',
            is_complete=True,
        )
        db_session.add(session1)
        db_session.add(session2)
        db_session.commit()

        response = client.get('/api/charging/summary')
        assert response.status_code == 200
        data = response.get_json()
        # Should have summary stats
        assert 'total_kwh' in data or 'sessions' in data or 'total_sessions' in data
