"""
Integration tests for end-to-end data flow.
"""

import pytest
import json
import uuid
import sys
import os
from datetime import datetime, timezone, timedelta

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'receiver'))


class TestTelemetryToTripFlow:
    """Tests for telemetry upload to trip creation flow."""

    def test_upload_creates_trip(self, client, db_session):
        """Test that uploading telemetry creates a new trip."""
        from models import Trip

        session_id = str(uuid.uuid4())
        timestamp_ms = int(datetime.now(timezone.utc).timestamp() * 1000)

        data = {
            'session': session_id,
            'time': str(timestamp_ms),
            'kff1001': '45.5',  # Speed
            'kff1006': '37.7749',  # Latitude
            'kff1005': '-122.4194',  # Longitude
            'k22005b': '85.0',  # SOC
            'kff1271': '50000.0',  # Odometer
        }

        response = client.post('/torque/upload', data=data)
        assert response.status_code == 200

        # Check trip was created
        trip = db_session.query(Trip).filter_by(session_id=uuid.UUID(session_id)).first()
        assert trip is not None
        assert trip.start_soc == 85.0

    def test_multiple_uploads_same_session(self, client, db_session):
        """Test multiple uploads to same session use same trip."""
        from models import Trip, TelemetryRaw

        session_id = str(uuid.uuid4())
        base_time = int(datetime.now(timezone.utc).timestamp() * 1000)

        # First upload
        data1 = {
            'session': session_id,
            'time': str(base_time),
            'kff1001': '0',  # Stopped
            'k22005b': '100.0',  # Full charge
            'kff1271': '50000.0',
        }
        client.post('/torque/upload', data=data1)

        # Second upload (1 second later)
        data2 = {
            'session': session_id,
            'time': str(base_time + 1000),
            'kff1001': '35.0',  # Moving
            'k22005b': '99.0',  # SOC dropping
            'kff1271': '50000.5',
        }
        client.post('/torque/upload', data=data2)

        # Should only have one trip
        trips = db_session.query(Trip).filter_by(session_id=uuid.UUID(session_id)).all()
        assert len(trips) == 1

        # Should have two telemetry points
        telemetry = db_session.query(TelemetryRaw).filter_by(
            session_id=uuid.UUID(session_id)
        ).all()
        assert len(telemetry) == 2

    def test_new_session_creates_new_trip(self, client, db_session):
        """Test different sessions create different trips."""
        from models import Trip

        session1 = str(uuid.uuid4())
        session2 = str(uuid.uuid4())
        timestamp = str(int(datetime.now(timezone.utc).timestamp() * 1000))

        # Upload for session 1
        client.post('/torque/upload', data={
            'session': session1,
            'time': timestamp,
            'kff1001': '45.0',
        })

        # Upload for session 2
        client.post('/torque/upload', data={
            'session': session2,
            'time': timestamp,
            'kff1001': '50.0',
        })

        # Should have two trips
        trips = db_session.query(Trip).all()
        assert len(trips) == 2


class TestFuelEventFlow:
    """Tests for fuel event handling."""

    def test_manual_fuel_event_appears_in_history(self, client):
        """Test manually added fuel event shows in history."""
        fuel_data = {
            'timestamp': datetime.now(timezone.utc).isoformat(),
            'odometer_miles': 51000,
            'gallons_added': 8.0,
            'price_per_gallon': 3.25,
            'total_cost': 26.00,
        }

        # Add fuel event
        response = client.post(
            '/api/fuel/add',
            data=json.dumps(fuel_data),
            content_type='application/json'
        )
        assert response.status_code == 201

        # Check it appears in history
        response = client.get('/api/fuel/history')
        data = json.loads(response.data)

        assert len(data) >= 1
        assert data[0]['gallons_added'] == 8.0


class TestEfficiencySummaryFlow:
    """Tests for efficiency summary calculations."""

    def test_efficiency_summary_empty_database(self, client):
        """Test efficiency summary with no data returns sensible defaults."""
        response = client.get('/api/efficiency/summary')

        assert response.status_code == 200
        data = json.loads(response.data)

        assert data['lifetime_gas_mpg'] is None
        assert data['total_miles_tracked'] == 0
        assert data['recent_30d_mpg'] is None
        assert data['current_tank_mpg'] is None

    def test_efficiency_with_trip_data(self, client, db_session):
        """Test efficiency summary with trip data."""
        from models import Trip

        # Create a closed trip with gas data
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            end_time=datetime.now(timezone.utc),
            distance_miles=30.0,
            electric_miles=20.0,
            gas_miles=10.0,
            gas_mode_entered=True,
            gas_mpg=42.5,
            fuel_used_gallons=0.235,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.get('/api/efficiency/summary')
        data = json.loads(response.data)

        assert data['total_miles_tracked'] == 30.0
        # Allow for rounding differences in MPG calculation
        assert 42.0 <= data['lifetime_gas_mpg'] <= 43.0


class TestSocAnalysisFlow:
    """Tests for SOC analysis flow."""

    def test_soc_analysis_with_transitions(self, client, db_session):
        """Test SOC analysis with transition data."""
        from models import Trip, SocTransition

        # Create trip with SOC transition
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            is_closed=True,
        )
        db_session.add(trip)
        db_session.flush()

        # Add SOC transitions
        for soc, temp in [(17.5, 72.0), (18.0, 68.0), (19.5, 32.0), (17.2, 75.0), (20.0, 28.0)]:
            transition = SocTransition(
                trip_id=trip.id,
                timestamp=datetime.now(timezone.utc),
                soc_at_transition=soc,
                ambient_temp_f=temp,
            )
            db_session.add(transition)
        db_session.commit()

        response = client.get('/api/soc/analysis')
        data = json.loads(response.data)

        assert data['count'] == 5
        assert data['average_soc'] is not None
        assert 17.0 <= data['average_soc'] <= 20.0


class TestDashboardDataFlow:
    """Tests for complete dashboard data flow."""

    def test_dashboard_loads_with_data(self, client, db_session):
        """Test dashboard HTML loads with data in database."""
        from models import Trip

        # Add some trip data
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            distance_miles=25.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.get('/')
        assert response.status_code == 200
        assert b'Volt Efficiency Tracker' in response.data

    def test_all_dashboard_api_endpoints_work(self, client):
        """Test all API endpoints needed for dashboard return valid data."""
        endpoints = [
            '/api/status',
            '/api/efficiency/summary',
            '/api/trips',
            '/api/soc/analysis',
            '/api/mpg/trend',
            '/api/fuel/history',
        ]

        for endpoint in endpoints:
            response = client.get(endpoint)
            assert response.status_code == 200, f"Failed for {endpoint}"
            # Verify JSON is returned
            data = json.loads(response.data)
            assert data is not None


class TestTripListFiltering:
    """Tests for trip list filtering."""

    def test_trips_gas_only_filter(self, client, db_session):
        """Test gas_only filter on trips endpoint."""
        from models import Trip

        # Create electric-only trip
        electric_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            gas_mode_entered=False,
            is_closed=True,
        )
        db_session.add(electric_trip)

        # Create gas trip
        gas_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            gas_mode_entered=True,
            gas_mpg=40.0,
            is_closed=True,
        )
        db_session.add(gas_trip)
        db_session.commit()

        # Get all trips
        response = client.get('/api/trips')
        all_data = json.loads(response.data)

        # Get gas-only trips
        response = client.get('/api/trips?gas_only=true')
        gas_data = json.loads(response.data)

        assert len(all_data) == 2
        assert len(gas_data) == 1
        assert gas_data[0]['gas_mode_entered'] is True

    def test_trips_date_filter(self, client, db_session):
        """Test date filtering on trips endpoint."""
        from models import Trip

        # Create old trip
        old_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime(2023, 1, 15, tzinfo=timezone.utc),
            is_closed=True,
        )
        db_session.add(old_trip)

        # Create recent trip
        recent_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            is_closed=True,
        )
        db_session.add(recent_trip)
        db_session.commit()

        # Filter for 2024+
        response = client.get('/api/trips?start_date=2024-01-01')
        data = json.loads(response.data)

        # Should only include recent trip
        assert len(data) == 1


class TestMpgTrendFlow:
    """Tests for MPG trend data flow."""

    def test_mpg_trend_with_trips(self, client, db_session):
        """Test MPG trend returns trip data."""
        from models import Trip

        # Create trips with MPG data over past week
        for i in range(5):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                gas_mode_entered=True,
                gas_mpg=40.0 + i,
                gas_miles=10.0,
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        response = client.get('/api/mpg/trend?days=7')
        data = json.loads(response.data)

        assert len(data) == 5
        # Trips should be ordered by date
        for trip in data:
            assert 'date' in trip
            assert 'mpg' in trip
            assert trip['mpg'] is not None


class TestStatusEndpointIntegration:
    """Tests for status endpoint integration."""

    def test_status_with_active_trip(self, client, db_session):
        """Test status shows active trip when one exists."""
        from models import Trip

        # Create an open (active) trip
        active_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            is_closed=False,
        )
        db_session.add(active_trip)
        db_session.commit()

        response = client.get('/api/status')
        data = json.loads(response.data)

        assert data['status'] == 'online'
        assert data['active_trip'] is not None

    def test_status_with_recent_telemetry(self, client, db_session):
        """Test status shows last sync time."""
        from models import TelemetryRaw

        # Create recent telemetry
        telemetry = TelemetryRaw(
            session_id=uuid.uuid4(),
            timestamp=datetime.now(timezone.utc),
        )
        db_session.add(telemetry)
        db_session.commit()

        response = client.get('/api/status')
        data = json.loads(response.data)

        assert data['last_sync'] is not None
        assert data['database'] == 'connected'
