"""
Tests for toast notification emitter
"""

import pytest
from unittest.mock import patch, MagicMock
from receiver.utils.toast_emitter import (
    emit_toast,
    emit_success,
    emit_info,
    emit_warning,
    emit_error,
    emit_trip_detected,
    emit_trip_finalized,
    emit_import_complete,
    emit_import_failed,
    emit_charging_session_added,
    emit_data_export,
    emit_low_gps_accuracy,
    emit_battery_health_updated,
    ToastType
)


@pytest.fixture
def mock_emit():
    """Mock flask_socketio.emit"""
    with patch('receiver.utils.toast_emitter.emit') as mock:
        yield mock


class TestToastEmitter:
    """Tests for toast notification emission"""

    def test_emit_toast_basic(self, mock_emit):
        """Test basic toast emission"""
        emit_toast('Test message', ToastType.INFO, 3000)

        mock_emit.assert_called_once()
        args, kwargs = mock_emit.call_args

        assert args[0] == 'toast'  # Event name
        payload = args[1]
        assert payload['message'] == 'Test message'
        assert payload['type'] == ToastType.INFO
        assert payload['duration'] == 3000
        assert payload['actions'] == []
        assert kwargs['broadcast'] is True

    def test_emit_toast_with_actions(self, mock_emit):
        """Test toast emission with action buttons"""
        actions = [{'label': 'Undo', 'onClick': 'undoAction()'}]
        emit_toast('Action required', ToastType.WARNING, 0, actions)

        payload = mock_emit.call_args[0][1]
        assert payload['actions'] == actions
        assert payload['duration'] == 0  # Persistent

    def test_emit_toast_to_room(self, mock_emit):
        """Test toast emission to specific room"""
        emit_toast('Room message', ToastType.INFO, 3000, room='user123')

        args, kwargs = mock_emit.call_args
        assert kwargs.get('room') == 'user123'
        assert 'broadcast' not in kwargs

    def test_emit_success(self, mock_emit):
        """Test success toast helper"""
        emit_success('Operation successful', 2000)

        payload = mock_emit.call_args[0][1]
        assert payload['message'] == 'Operation successful'
        assert payload['type'] == ToastType.SUCCESS
        assert payload['duration'] == 2000

    def test_emit_info(self, mock_emit):
        """Test info toast helper"""
        emit_info('Information message')

        payload = mock_emit.call_args[0][1]
        assert payload['type'] == ToastType.INFO
        assert payload['duration'] == 3000  # Default

    def test_emit_warning(self, mock_emit):
        """Test warning toast helper"""
        emit_warning('Warning message')

        payload = mock_emit.call_args[0][1]
        assert payload['type'] == ToastType.WARNING
        assert payload['duration'] == 4000  # Default

    def test_emit_error(self, mock_emit):
        """Test error toast helper"""
        emit_error('Error occurred')

        payload = mock_emit.call_args[0][1]
        assert payload['type'] == ToastType.ERROR
        assert payload['duration'] == 0  # Persistent by default

    def test_emit_trip_detected(self, mock_emit):
        """Test trip detected notification"""
        emit_trip_detected(trip_distance=15.2, trip_id='trip123')

        payload = mock_emit.call_args[0][1]
        assert 'New trip detected' in payload['message']
        assert '15.2 miles' in payload['message']
        assert payload['type'] == ToastType.INFO

    def test_emit_trip_detected_no_distance(self, mock_emit):
        """Test trip detected without distance"""
        emit_trip_detected()

        payload = mock_emit.call_args[0][1]
        assert payload['message'] == 'New trip detected'

    def test_emit_trip_finalized_with_mpg(self, mock_emit):
        """Test trip finalized notification with MPG"""
        emit_trip_finalized('trip123', distance=25.5, mpg=85, electric_miles=15.0)

        payload = mock_emit.call_args[0][1]
        assert '25.5 miles' in payload['message']
        assert '85 MPG' in payload['message']
        assert payload['type'] == ToastType.SUCCESS

    def test_emit_trip_finalized_all_electric(self, mock_emit):
        """Test trip finalized notification for all-electric trip"""
        emit_trip_finalized('trip123', distance=12.3, mpg=None, electric_miles=12.3)

        payload = mock_emit.call_args[0][1]
        assert '12.3 miles' in payload['message']
        assert 'all electric' in payload['message']

    def test_emit_import_complete(self, mock_emit):
        """Test import complete notification"""
        emit_import_complete(1500, filename='torque_log.csv')

        payload = mock_emit.call_args[0][1]
        assert '1500 records added' in payload['message']
        assert 'torque_log.csv' in payload['message']
        assert payload['type'] == ToastType.SUCCESS

    def test_emit_import_complete_no_filename(self, mock_emit):
        """Test import complete without filename"""
        emit_import_complete(1500)

        payload = mock_emit.call_args[0][1]
        assert 'Import complete: 1500 records added' in payload['message']

    def test_emit_import_failed(self, mock_emit):
        """Test import failed notification"""
        emit_import_failed('Invalid CSV format', filename='bad_file.csv')

        payload = mock_emit.call_args[0][1]
        assert 'Import failed' in payload['message']
        assert 'Invalid CSV format' in payload['message']
        assert 'bad_file.csv' in payload['message']
        assert payload['type'] == ToastType.ERROR
        assert payload['duration'] == 0  # Persistent

    def test_emit_charging_session_added(self, mock_emit):
        """Test charging session added notification"""
        emit_charging_session_added(12.5, location='Home')

        payload = mock_emit.call_args[0][1]
        assert '12.5 kWh' in payload['message']
        assert 'Home' in payload['message']
        assert payload['type'] == ToastType.SUCCESS

    def test_emit_charging_session_added_no_location(self, mock_emit):
        """Test charging session without location"""
        emit_charging_session_added(10.0)

        payload = mock_emit.call_args[0][1]
        assert '10.0 kWh' in payload['message']
        assert 'Charging session added' in payload['message']

    def test_emit_data_export(self, mock_emit):
        """Test data export notification"""
        emit_data_export('CSV', 1200)

        payload = mock_emit.call_args[0][1]
        assert 'Exporting 1200 records as CSV' in payload['message']
        assert payload['type'] == ToastType.INFO

    def test_emit_low_gps_accuracy(self, mock_emit):
        """Test low GPS accuracy warning"""
        emit_low_gps_accuracy(150.5)

        payload = mock_emit.call_args[0][1]
        assert 'GPS signal weak' in payload['message']
        assert '150m accuracy' in payload['message']
        assert payload['type'] == ToastType.WARNING

    def test_emit_battery_health_updated(self, mock_emit):
        """Test battery health update notification"""
        emit_battery_health_updated(17.2, 93.5)

        payload = mock_emit.call_args[0][1]
        assert '17.2 kWh' in payload['message']
        assert '94%' in payload['message']  # Rounded
        assert payload['type'] == ToastType.INFO

    def test_emit_toast_exception_handling(self, mock_emit):
        """Test that emit_toast handles exceptions gracefully"""
        mock_emit.side_effect = Exception('Socket error')

        # Should not raise exception
        try:
            emit_toast('Test message', ToastType.INFO)
        except Exception:
            pytest.fail("emit_toast should not raise exceptions")

        mock_emit.assert_called_once()

    def test_toast_types_constants(self):
        """Test toast type constants are defined correctly"""
        assert ToastType.SUCCESS == 'success'
        assert ToastType.INFO == 'info'
        assert ToastType.WARNING == 'warning'
        assert ToastType.ERROR == 'error'

    def test_emit_toast_broadcast_default(self, mock_emit):
        """Test that broadcast is True by default when no room specified"""
        emit_toast('Test', ToastType.INFO)

        kwargs = mock_emit.call_args[1]
        assert kwargs['broadcast'] is True
        assert 'room' not in kwargs

    def test_emit_with_custom_duration(self, mock_emit):
        """Test custom duration values"""
        # Very short duration
        emit_toast('Quick', ToastType.INFO, 1000)
        payload = mock_emit.call_args[0][1]
        assert payload['duration'] == 1000

        # Persistent (0 duration)
        emit_toast('Persistent', ToastType.ERROR, 0)
        payload = mock_emit.call_args[0][1]
        assert payload['duration'] == 0

        # Long duration
        emit_toast('Long', ToastType.WARNING, 10000)
        payload = mock_emit.call_args[0][1]
        assert payload['duration'] == 10000
