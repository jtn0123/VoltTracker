"""
Tests for Flask API endpoints.
"""

import pytest
import json
import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))


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
        """Test that trips endpoint returns a list."""
        response = client.get('/api/trips')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)

    def test_trips_with_gas_only_filter(self, client):
        """Test gas_only query parameter."""
        response = client.get('/api/trips?gas_only=true')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)

    def test_trips_with_date_filter(self, client):
        """Test date filter parameters."""
        response = client.get('/api/trips?start_date=2024-01-01&end_date=2024-12-31')

        assert response.status_code == 200
        data = json.loads(response.data)
        assert isinstance(data, list)


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
