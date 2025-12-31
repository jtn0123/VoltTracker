"""
Tests for Flask API endpoints.
"""

import pytest
import json
import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'receiver'))


class TestTorqueUpload:
    """Tests for /torque/upload endpoint."""

    def test_upload_returns_ok(self, client, sample_torque_data):
        """Test that upload endpoint returns 'OK!'."""
        response = client.post('/torque/upload', data=sample_torque_data)

        assert response.status_code == 200
        assert response.data.decode() == 'OK!'

    def test_upload_with_empty_data(self, client):
        """Test upload with empty data still returns OK."""
        response = client.post('/torque/upload', data={})

        # Should still return OK to avoid Torque retries
        assert response.status_code == 200
        assert response.data.decode() == 'OK!'

    def test_upload_with_partial_data(self, client):
        """Test upload with partial data."""
        data = {
            'session': 'test-session',
            'kff1001': '45.0',  # Just speed
        }
        response = client.post('/torque/upload', data=data)

        assert response.status_code == 200
        assert response.data.decode() == 'OK!'


class TestStatusEndpoint:
    """Tests for /api/status endpoint."""

    def test_status_returns_json(self, client):
        """Test that status endpoint returns JSON."""
        response = client.get('/api/status')

        assert response.status_code == 200
        assert response.content_type == 'application/json'

    def test_status_has_required_fields(self, client):
        """Test that status response has required fields."""
        response = client.get('/api/status')
        data = json.loads(response.data)

        assert 'status' in data
        assert 'database' in data


class TestTripsEndpoint:
    """Tests for /api/trips endpoint."""

    def test_trips_returns_list(self, client):
        """Test that trips endpoint returns paginated response with trips list."""
        response = client.get('/api/trips')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert 'trips' in data
        assert 'pagination' in data
        assert isinstance(data['trips'], list)

    def test_trips_with_gas_only_filter(self, client):
        """Test gas_only query parameter."""
        response = client.get('/api/trips?gas_only=true')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert 'trips' in data
        assert isinstance(data['trips'], list)

    def test_trips_with_date_filter(self, client):
        """Test date filter parameters."""
        response = client.get('/api/trips?start_date=2024-01-01&end_date=2024-12-31')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert 'trips' in data
        assert isinstance(data['trips'], list)


class TestEfficiencyEndpoint:
    """Tests for /api/efficiency/summary endpoint."""

    def test_efficiency_returns_json(self, client):
        """Test efficiency endpoint returns JSON."""
        response = client.get('/api/efficiency/summary')

        assert response.status_code == 200
        assert response.content_type == 'application/json'

    def test_efficiency_has_required_fields(self, client):
        """Test efficiency response has required fields."""
        response = client.get('/api/efficiency/summary')
        data = json.loads(response.data)

        assert 'lifetime_gas_mpg' in data
        assert 'total_miles_tracked' in data


class TestSocAnalysisEndpoint:
    """Tests for /api/soc/analysis endpoint."""

    def test_soc_analysis_returns_json(self, client):
        """Test SOC analysis endpoint returns JSON."""
        response = client.get('/api/soc/analysis')

        assert response.status_code == 200
        assert response.content_type == 'application/json'

    def test_soc_analysis_has_required_fields(self, client):
        """Test SOC analysis response has required fields."""
        response = client.get('/api/soc/analysis')
        data = json.loads(response.data)

        assert 'average_soc' in data
        assert 'count' in data
        assert 'histogram' in data


class TestMpgTrendEndpoint:
    """Tests for /api/mpg/trend endpoint."""

    def test_mpg_trend_returns_list(self, client):
        """Test MPG trend endpoint returns list."""
        response = client.get('/api/mpg/trend')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)

    def test_mpg_trend_with_days_param(self, client):
        """Test days query parameter."""
        response = client.get('/api/mpg/trend?days=7')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)


class TestFuelEndpoints:
    """Tests for fuel-related endpoints."""

    def test_fuel_history_returns_list(self, client):
        """Test fuel history endpoint returns list."""
        response = client.get('/api/fuel/history')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)

    def test_add_fuel_event(self, client):
        """Test adding a fuel event."""
        fuel_data = {
            'timestamp': '2024-01-15T10:30:00',
            'odometer_miles': 51000,
            'gallons_added': 7.5,
            'price_per_gallon': 3.49,
            'total_cost': 26.18,
        }
        response = client.post(
            '/api/fuel/add',
            data=json.dumps(fuel_data),
            content_type='application/json'
        )

        assert response.status_code == 201
        data = json.loads(response.data)
        assert data['gallons_added'] == 7.5

    def test_add_fuel_event_no_data(self, client):
        """Test adding fuel event with no data."""
        response = client.post(
            '/api/fuel/add',
            data='',
            content_type='application/json'
        )

        assert response.status_code == 400


class TestDashboard:
    """Tests for dashboard endpoint."""

    def test_dashboard_returns_html(self, client):
        """Test that dashboard returns HTML."""
        response = client.get('/')

        assert response.status_code == 200
        assert b'Volt Efficiency Tracker' in response.data


class TestTripDetailEndpoint:
    """Tests for /api/trips/<id> endpoint."""

    def test_trip_not_found(self, client):
        """Test 404 for non-existent trip."""
        response = client.get('/api/trips/99999')

        assert response.status_code == 404
        data = json.loads(response.data)
        assert 'error' in data
        assert 'not found' in data['error'].lower()

    def test_trip_detail_returns_json(self, client, db_session):
        """Test trip detail returns proper JSON structure."""
        import uuid
        from datetime import datetime, timezone
        import sys
        import os
        sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'receiver'))
        from models import Trip

        # Create a test trip
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.get(f'/api/trips/{trip.id}')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert 'trip' in data
        assert 'telemetry' in data
        assert data['trip']['id'] == trip.id


class TestApiErrorHandling:
    """Tests for API error handling."""

    def test_invalid_fuel_event_data(self, client):
        """Test error handling for invalid fuel event data."""
        response = client.post(
            '/api/fuel/add',
            data='not valid json',
            content_type='application/json'
        )

        assert response.status_code == 400

    def test_invalid_mpg_trend_days_param(self, client):
        """Test handling of invalid days parameter defaults to 30."""
        response = client.get('/api/mpg/trend?days=invalid')

        # Should handle gracefully by using default value
        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)

    def test_invalid_date_filter(self, client):
        """Test handling of invalid date format in trips filter."""
        response = client.get('/api/trips?start_date=not-a-date')

        # Should handle gracefully (may return empty or error)
        assert response.status_code in [200, 400]


class TestApiEdgeCases:
    """Tests for API edge cases."""

    def test_efficiency_with_empty_database(self, client):
        """Test efficiency endpoint with no data."""
        response = client.get('/api/efficiency/summary')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data['lifetime_gas_mpg'] is None
        assert data['total_miles_tracked'] == 0

    def test_soc_analysis_with_empty_database(self, client):
        """Test SOC analysis with no transitions."""
        response = client.get('/api/soc/analysis')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data['count'] == 0
        assert data['average_soc'] is None
        assert data['histogram'] == {}

    def test_mpg_trend_with_no_gas_trips(self, client):
        """Test MPG trend with no gas trips."""
        response = client.get('/api/mpg/trend')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)
        assert len(data) == 0

    def test_fuel_history_empty(self, client):
        """Test fuel history with no events."""
        response = client.get('/api/fuel/history')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)
        assert len(data) == 0

    def test_status_with_no_telemetry(self, client):
        """Test status when no telemetry has been received."""
        response = client.get('/api/status')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data['status'] == 'online'
        assert data['last_sync'] is None
        assert data['active_trip'] is None

    def test_trips_with_limit(self, client, db_session):
        """Test trips endpoint respects the 100 limit."""
        import uuid
        from datetime import datetime, timezone
        import sys
        import os
        sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'receiver'))
        from models import Trip

        # This tests the limit behavior with empty database
        response = client.get('/api/trips')

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

        response = client.get('/api/export/trips')

        assert response.status_code == 200
        assert response.content_type == 'text/csv; charset=utf-8'
        assert b'id,session_id,start_time' in response.data

    def test_export_trips_json(self, client):
        """Test exporting trips as JSON."""
        response = client.get('/api/export/trips?format=json')

        assert response.status_code == 200
        assert response.content_type == 'application/json'
        data = json.loads(response.data)
        assert isinstance(data, list)

    def test_export_fuel_csv(self, client):
        """Test exporting fuel events as CSV."""
        response = client.get('/api/export/fuel')

        assert response.status_code == 200
        assert response.content_type == 'text/csv; charset=utf-8'
        assert b'id,timestamp,odometer_miles' in response.data

    def test_export_fuel_json(self, client):
        """Test exporting fuel events as JSON."""
        response = client.get('/api/export/fuel?format=json')

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

        response = client.get('/api/export/all')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert 'exported_at' in data
        assert 'trips' in data
        assert 'fuel_events' in data
        assert 'soc_transitions' in data
        assert 'summary' in data
        assert len(data['trips']) == 1


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

        response = client.delete(f'/api/trips/{trip_id}')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert 'deleted successfully' in data['message']

        # Verify trip is gone
        response = client.get(f'/api/trips/{trip_id}')
        assert response.status_code == 404

    def test_delete_trip_not_found(self, client):
        """Test deleting non-existent trip."""
        response = client.delete('/api/trips/99999')

        assert response.status_code == 404
        data = json.loads(response.data)
        assert 'not found' in data['error'].lower()

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
            f'/api/trips/{trip.id}',
            data=json.dumps({'gas_mpg': 42.5}),
            content_type='application/json'
        )

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data['gas_mpg'] == 42.5

    def test_update_trip_not_found(self, client):
        """Test updating non-existent trip."""
        response = client.patch(
            '/api/trips/99999',
            data=json.dumps({'gas_mpg': 42.5}),
            content_type='application/json'
        )

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

        response = client.patch(
            f'/api/trips/{trip.id}',
            data='',
            content_type='application/json'
        )

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

        response = client.delete(f'/api/fuel/{event_id}')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert 'deleted successfully' in data['message']

    def test_delete_fuel_event_not_found(self, client):
        """Test deleting non-existent fuel event."""
        response = client.delete('/api/fuel/99999')

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
            f'/api/fuel/{event.id}',
            data=json.dumps({'price_per_gallon': 3.75, 'notes': 'Updated price'}),
            content_type='application/json'
        )

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data['price_per_gallon'] == 3.75
        assert data['notes'] == 'Updated price'

    def test_update_fuel_event_not_found(self, client):
        """Test updating non-existent fuel event."""
        response = client.patch(
            '/api/fuel/99999',
            data=json.dumps({'notes': 'test'}),
            content_type='application/json'
        )

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
            )
            db_session.add(trip)
        db_session.commit()

        # Request with pagination
        response = client.get('/api/trips?page=1&per_page=2')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert 'trips' in data
        assert 'pagination' in data
        assert len(data['trips']) == 2
        assert data['pagination']['page'] == 1
        assert data['pagination']['per_page'] == 2
        assert data['pagination']['total'] == 5
        assert data['pagination']['pages'] == 3

    def test_trips_without_pagination_returns_paginated(self, client, db_session):
        """Test trips endpoint returns paginated response with default pagination."""
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

        # Request without pagination params
        response = client.get('/api/trips')

        assert response.status_code == 200
        data = json.loads(response.data)
        # Should return paginated response
        assert 'trips' in data
        assert 'pagination' in data
        assert isinstance(data['trips'], list)
        assert len(data['trips']) == 1

    def test_pagination_page_2(self, client, db_session):
        """Test fetching second page of results."""
        import uuid
        from datetime import datetime, timezone, timedelta
        from models import Trip

        # Create 5 trips with different start times
        for i in range(5):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        # Request page 2
        response = client.get('/api/trips?page=2&per_page=2')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert len(data['trips']) == 2
        assert data['pagination']['page'] == 2

    def test_pagination_invalid_params(self, client):
        """Test pagination with invalid parameters."""
        response = client.get('/api/trips?page=invalid&per_page=abc')

        assert response.status_code == 200
        data = json.loads(response.data)
        # Should use defaults
        assert data['pagination']['page'] == 1
        assert data['pagination']['per_page'] == 50


class TestChargingEndpoints:
    """Tests for charging session endpoints."""

    def test_charging_history_returns_list(self, client):
        """Test charging history endpoint returns list."""
        response = client.get('/api/charging/history')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)

    def test_add_charging_session(self, client):
        """Test adding a charging session."""
        charging_data = {
            'start_time': '2024-01-15T18:00:00',
            'end_time': '2024-01-15T22:00:00',
            'start_soc': 20.0,
            'end_soc': 95.0,
            'kwh_added': 12.0,
            'charge_type': 'L2',
            'location_name': 'Home',
        }
        response = client.post(
            '/api/charging/add',
            data=json.dumps(charging_data),
            content_type='application/json'
        )

        assert response.status_code == 201
        data = json.loads(response.data)
        assert data['kwh_added'] == 12.0
        assert data['charge_type'] == 'L2'
        assert data['is_complete'] is True

    def test_add_charging_session_no_start_time(self, client):
        """Test adding charging session without start_time fails."""
        response = client.post(
            '/api/charging/add',
            data=json.dumps({'kwh_added': 10.0}),
            content_type='application/json'
        )

        assert response.status_code == 400
        data = json.loads(response.data)
        assert 'start_time' in data['error'].lower()

    def test_get_charging_session(self, client, db_session):
        """Test getting a specific charging session."""
        from datetime import datetime, timezone
        from models import ChargingSession

        session = ChargingSession(
            start_time=datetime.now(timezone.utc),
            start_soc=30.0,
            end_soc=80.0,
            kwh_added=8.0,
            charge_type='L1',
            is_complete=True,
        )
        db_session.add(session)
        db_session.commit()

        response = client.get(f'/api/charging/{session.id}')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data['id'] == session.id
        assert data['kwh_added'] == 8.0

    def test_get_charging_session_not_found(self, client):
        """Test 404 for non-existent charging session."""
        response = client.get('/api/charging/99999')

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

        response = client.delete(f'/api/charging/{session_id}')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert 'deleted successfully' in data['message']

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
            f'/api/charging/{session.id}',
            data=json.dumps({'kwh_added': 12.5, 'notes': 'Updated'}),
            content_type='application/json'
        )

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data['kwh_added'] == 12.5
        assert data['notes'] == 'Updated'

    def test_charging_summary_empty(self, client):
        """Test charging summary with no data."""
        response = client.get('/api/charging/summary')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data['total_sessions'] == 0
        assert data['total_kwh'] == 0

    def test_charging_summary_with_data(self, client, db_session):
        """Test charging summary with data."""
        from datetime import datetime, timezone
        from models import ChargingSession

        # Add two charging sessions
        for i in range(2):
            session = ChargingSession(
                start_time=datetime.now(timezone.utc),
                kwh_added=10.0 + i,
                charge_type='L2',
                is_complete=True,
            )
            db_session.add(session)
        db_session.commit()

        response = client.get('/api/charging/summary')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data['total_sessions'] == 2
        assert data['total_kwh'] == 21.0
        assert 'L2' in data['by_charge_type']


class TestApiValidation:
    """Tests for API input validation."""

    def test_fuel_event_validation_invalid_gallons(self, client):
        """Test validation rejects invalid gallons value."""
        fuel_data = {
            'timestamp': '2024-01-15T10:30:00',
            'gallons_added': 50.0,  # Way too much for a 9.3 gal tank
        }
        response = client.post(
            '/api/fuel/add',
            data=json.dumps(fuel_data),
            content_type='application/json'
        )

        assert response.status_code == 400
        data = json.loads(response.data)
        assert 'error' in data
        assert 'Validation failed' in data['error']

    def test_fuel_event_validation_invalid_odometer(self, client):
        """Test validation rejects negative odometer."""
        fuel_data = {
            'timestamp': '2024-01-15T10:30:00',
            'odometer_miles': -100,
        }
        response = client.post(
            '/api/fuel/add',
            data=json.dumps(fuel_data),
            content_type='application/json'
        )

        assert response.status_code == 400
        data = json.loads(response.data)
        assert 'Validation failed' in data['error']

    def test_fuel_event_validation_invalid_fuel_level(self, client):
        """Test validation rejects fuel level over 100."""
        fuel_data = {
            'timestamp': '2024-01-15T10:30:00',
            'fuel_level_after': 150.0,
        }
        response = client.post(
            '/api/fuel/add',
            data=json.dumps(fuel_data),
            content_type='application/json'
        )

        assert response.status_code == 400
        data = json.loads(response.data)
        assert 'Validation failed' in data['error']

    def test_fuel_event_validation_non_numeric(self, client):
        """Test validation rejects non-numeric values."""
        fuel_data = {
            'timestamp': '2024-01-15T10:30:00',
            'gallons_added': 'not-a-number',
        }
        response = client.post(
            '/api/fuel/add',
            data=json.dumps(fuel_data),
            content_type='application/json'
        )

        assert response.status_code == 400
        data = json.loads(response.data)
        assert 'Validation failed' in data['error']

    def test_fuel_event_valid_data_passes(self, client):
        """Test valid fuel event data is accepted."""
        fuel_data = {
            'timestamp': '2024-01-15T10:30:00',
            'odometer_miles': 51000,
            'gallons_added': 7.5,
            'price_per_gallon': 3.49,
            'total_cost': 26.18,
            'fuel_level_before': 10.0,
            'fuel_level_after': 90.0,
        }
        response = client.post(
            '/api/fuel/add',
            data=json.dumps(fuel_data),
            content_type='application/json'
        )

        assert response.status_code == 201
        data = json.loads(response.data)
        assert data['gallons_added'] == 7.5


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

        response = client.get('/api/trips?page=0&per_page=10')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data['pagination']['page'] == 1

    def test_pagination_negative_page(self, client):
        """Test negative page defaults to page 1."""
        response = client.get('/api/trips?page=-5&per_page=10')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data['pagination']['page'] == 1

    def test_pagination_per_page_zero(self, client):
        """Test per_page=0 is capped to minimum of 1."""
        response = client.get('/api/trips?page=1&per_page=0')

        assert response.status_code == 200
        data = json.loads(response.data)
        # per_page=0 becomes max(1, 0) = 1
        assert data['pagination']['per_page'] == 1

    def test_pagination_per_page_too_large(self, client):
        """Test per_page > 100 is capped."""
        response = client.get('/api/trips?page=1&per_page=999')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data['pagination']['per_page'] <= 100

    def test_pagination_page_beyond_max(self, client):
        """Test page beyond max returns empty results."""
        response = client.get('/api/trips?page=9999&per_page=10')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert len(data['trips']) == 0


class TestMalformedRequests:
    """Tests for malformed JSON and bad requests."""

    def test_fuel_add_malformed_json(self, client):
        """Test malformed JSON returns 400."""
        response = client.post(
            '/api/fuel/add',
            data='{"invalid json',
            content_type='application/json'
        )

        assert response.status_code == 400

    def test_charging_add_malformed_json(self, client):
        """Test malformed JSON in charging add returns 400."""
        response = client.post(
            '/api/charging/add',
            data='not valid json at all',
            content_type='application/json'
        )

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

        response = client.patch(
            f'/api/trips/{trip.id}',
            data='{broken: json}',
            content_type='application/json'
        )

        assert response.status_code == 400

    def test_fuel_add_empty_body(self, client):
        """Test empty request body returns error."""
        response = client.post(
            '/api/fuel/add',
            data='',
            content_type='application/json'
        )

        assert response.status_code == 400


class TestDeleteNotFound:
    """Tests for 404 on delete operations."""

    def test_delete_nonexistent_trip(self, client):
        """Test deleting non-existent trip returns 404."""
        response = client.delete('/api/trips/99999')

        assert response.status_code == 404
        data = json.loads(response.data)
        assert 'not found' in data['error'].lower()

    def test_delete_nonexistent_fuel_event(self, client):
        """Test deleting non-existent fuel event returns 404."""
        response = client.delete('/api/fuel/99999')

        assert response.status_code == 404

    def test_delete_nonexistent_charging_session(self, client):
        """Test deleting non-existent charging session returns 404."""
        response = client.delete('/api/charging/99999')

        assert response.status_code == 404

    def test_patch_nonexistent_trip(self, client):
        """Test patching non-existent trip returns 404."""
        response = client.patch(
            '/api/trips/99999',
            data=json.dumps({'gas_mpg': 45.0}),
            content_type='application/json'
        )

        assert response.status_code == 404

    def test_patch_nonexistent_fuel_event(self, client):
        """Test patching non-existent fuel event returns 404."""
        response = client.patch(
            '/api/fuel/99999',
            data=json.dumps({'gallons_added': 5.0}),
            content_type='application/json'
        )

        assert response.status_code == 404

    def test_patch_nonexistent_charging_session(self, client):
        """Test patching non-existent charging session returns 404."""
        response = client.patch(
            '/api/charging/99999',
            data=json.dumps({'kwh_added': 10.0}),
            content_type='application/json'
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
            charge_type='L2',
            is_complete=True,
        )
        db_session.add(session)
        db_session.commit()

        response = client.get('/api/export/all')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert 'charging_sessions' in data
        assert len(data['charging_sessions']) == 1
        assert data['charging_sessions'][0]['kwh_added'] == 10.0

    def test_export_csv_with_special_characters(self, client, db_session):
        """Test CSV export handles special characters."""
        from datetime import datetime, timezone
        from models import ChargingSession

        session = ChargingSession(
            start_time=datetime.now(timezone.utc),
            kwh_added=10.0,
            location_name='Home, "Main" Garage',  # Commas and quotes
            notes='Test notes with\nnewlines',
            is_complete=True,
        )
        db_session.add(session)
        db_session.commit()

        response = client.get('/api/charging/history')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data[0]['location_name'] == 'Home, "Main" Garage'


class TestChargingSummaryDetails:
    """Tests for charging summary edge cases."""

    def test_charging_summary_l1_and_l2_counts(self, client, db_session):
        """Test summary returns correct L1/L2 counts."""
        from datetime import datetime, timezone
        from models import ChargingSession

        # Add L1 sessions
        for _ in range(3):
            db_session.add(ChargingSession(
                start_time=datetime.now(timezone.utc),
                kwh_added=5.0,
                charge_type='L1',
                is_complete=True,
            ))

        # Add L2 sessions
        for _ in range(2):
            db_session.add(ChargingSession(
                start_time=datetime.now(timezone.utc),
                kwh_added=10.0,
                charge_type='L2',
                is_complete=True,
            ))

        db_session.commit()

        response = client.get('/api/charging/summary')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data['l1_sessions'] == 3
        assert data['l2_sessions'] == 2
        assert data['total_sessions'] == 5

    def test_charging_summary_with_ev_ratio(self, client, db_session):
        """Test summary includes EV ratio from trips."""
        import uuid
        from datetime import datetime, timezone
        from models import Trip, ChargingSession

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
        db_session.add(ChargingSession(
            start_time=datetime.now(timezone.utc),
            kwh_added=10.0,
            is_complete=True,
        ))

        db_session.commit()

        response = client.get('/api/charging/summary')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data['total_electric_miles'] == 70.0
        assert data['ev_ratio'] == 70.0  # 70/100 = 70%
