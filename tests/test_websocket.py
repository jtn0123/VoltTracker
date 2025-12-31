"""
Tests for WebSocket/SocketIO functionality.

Tests real-time telemetry emission and client connections.
"""

import sys
import os
from unittest.mock import patch, MagicMock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'receiver'))

from routes.telemetry import emit_telemetry_update  # noqa: E402


class TestEmitTelemetryUpdate:
    """Tests for the emit_telemetry_update function."""

    def test_emit_telemetry_update_extracts_speed(self, app):
        """speed_mph correctly extracted from data dict."""
        data = {
            'speed_mph': 65.5,
            'engine_rpm': 0,
            'state_of_charge': 80.0,
        }

        mock_socketio = MagicMock()
        emit_telemetry_update(mock_socketio, data)
        mock_socketio.emit.assert_called_once()
        call_args = mock_socketio.emit.call_args
        assert call_args[0][0] == 'telemetry'
        assert call_args[0][1]['speed'] == 65.5

    def test_emit_telemetry_update_extracts_soc(self, app):
        """state_of_charge correctly extracted."""
        data = {
            'state_of_charge': 75.5,
        }

        mock_socketio = MagicMock()
        emit_telemetry_update(mock_socketio, data)
        call_args = mock_socketio.emit.call_args
        assert call_args[0][1]['soc'] == 75.5

    def test_emit_telemetry_update_handles_missing_keys(self, app):
        """Missing keys in data dict return None, not error."""
        data = {}  # Empty data

        mock_socketio = MagicMock()
        # Should not raise
        emit_telemetry_update(mock_socketio, data)
        mock_socketio.emit.assert_called_once()
        call_args = mock_socketio.emit.call_args
        assert call_args[0][1]['speed'] is None
        assert call_args[0][1]['soc'] is None
        assert call_args[0][1]['rpm'] is None

    def test_emit_includes_timestamp(self, app):
        """Emitted data includes ISO timestamp."""
        data = {'speed_mph': 50.0}

        mock_socketio = MagicMock()
        emit_telemetry_update(mock_socketio, data)
        call_args = mock_socketio.emit.call_args
        timestamp = call_args[0][1]['timestamp']
        assert timestamp is not None
        # Should be ISO format
        assert 'T' in timestamp

    def test_emit_includes_all_expected_fields(self, app):
        """Emitted telemetry includes all expected fields."""
        data = {
            'speed_mph': 55.0,
            'engine_rpm': 1200,
            'state_of_charge': 18.0,
            'fuel_level_percent': 75.0,
            'hv_battery_power_kw': -5.5,
            'latitude': 37.7749,
            'longitude': -122.4194,
            'odometer_miles': 50123.4,
        }

        mock_socketio = MagicMock()
        emit_telemetry_update(mock_socketio, data)
        call_args = mock_socketio.emit.call_args
        emitted = call_args[0][1]

        assert emitted['speed'] == 55.0
        assert emitted['rpm'] == 1200
        assert emitted['soc'] == 18.0
        assert emitted['fuel_percent'] == 75.0
        assert emitted['hv_power'] == -5.5
        assert emitted['latitude'] == 37.7749
        assert emitted['longitude'] == -122.4194
        assert emitted['odometer'] == 50123.4

    def test_emit_handles_null_values(self, app):
        """NULL telemetry fields don't break emission."""
        data = {
            'speed_mph': None,
            'engine_rpm': None,
            'state_of_charge': None,
            'fuel_level_percent': None,
        }

        mock_socketio = MagicMock()
        # Should not raise
        emit_telemetry_update(mock_socketio, data)
        mock_socketio.emit.assert_called_once()


class TestTelemetryUploadEmitsEvent:
    """Tests for telemetry emission on upload."""

    def test_torque_upload_emits_telemetry_event(self, client, sample_torque_data):
        """POSTing to /torque/upload emits 'telemetry' SocketIO event."""
        with patch('routes.telemetry.emit_telemetry_update') as mock_emit:
            response = client.post('/torque/upload', data=sample_torque_data)
            assert response.status_code == 200

            # emit_telemetry_update should have been called
            mock_emit.assert_called_once()
            call_args = mock_emit.call_args[0][1]  # Second arg is data
            assert 'session_id' in call_args

    def test_torque_upload_passes_parsed_data_to_emit(self, client, sample_torque_data):
        """Parsed telemetry data is passed to emit function."""
        with patch('routes.telemetry.emit_telemetry_update') as mock_emit:
            client.post('/torque/upload', data=sample_torque_data)

            call_args = mock_emit.call_args[0][1]  # Second arg is data
            # Check that parsed values are present
            assert 'speed_mph' in call_args
            assert 'state_of_charge' in call_args
            assert 'timestamp' in call_args


class TestSocketIOIntegration:
    """Integration tests for SocketIO functionality."""

    def test_socketio_initialization(self, app):
        """SocketIO is properly initialized with the app."""
        from app import socketio
        assert socketio is not None

    def test_emit_event_name_is_telemetry(self, app):
        """Events are emitted with 'telemetry' event name."""
        mock_socketio = MagicMock()
        emit_telemetry_update(mock_socketio, {'speed_mph': 50.0})
        call_args = mock_socketio.emit.call_args
        assert call_args[0][0] == 'telemetry'
