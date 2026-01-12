"""
Toast Notification Emitter for WebSocket
Provides helper functions to emit toast notifications to connected clients
"""

from typing import Optional, List, Dict, Any
from flask_socketio import emit
import logging

logger = logging.getLogger(__name__)


class ToastType:
    """Toast notification types"""
    SUCCESS = 'success'
    INFO = 'info'
    WARNING = 'warning'
    ERROR = 'error'


def emit_toast(
    message: str,
    toast_type: str = ToastType.INFO,
    duration: int = 3000,
    actions: Optional[List[Dict[str, Any]]] = None,
    room: Optional[str] = None
) -> None:
    """
    Emit a toast notification to connected WebSocket clients.

    Args:
        message: The message to display in the toast
        toast_type: Type of toast ('success', 'info', 'warning', 'error')
        duration: Duration in milliseconds (0 = persistent)
        actions: Optional list of action buttons, e.g.:
                 [{'label': 'Undo', 'onClick': 'undoAction()', 'isPrimary': False}]
        room: Optional room to emit to (None = broadcast to all)

    Example:
        >>> emit_toast('Trip deleted successfully', ToastType.SUCCESS)
        >>> emit_toast('Import failed: Invalid CSV', ToastType.ERROR, duration=0)
        >>> emit_toast('New trip detected', ToastType.INFO, duration=3000)
    """
    try:
        payload = {
            'message': message,
            'type': toast_type,
            'duration': duration,
            'actions': actions or []
        }

        if room:
            emit('toast', payload, room=room)
        else:
            emit('toast', payload, broadcast=True)

        logger.debug(f"Toast emitted: {toast_type} - {message}")

    except Exception as e:
        logger.error(f"Failed to emit toast notification: {e}")


def emit_success(message: str, duration: int = 3000, room: Optional[str] = None) -> None:
    """
    Emit a success toast notification.

    Args:
        message: Success message to display
        duration: Duration in milliseconds
        room: Optional room to emit to
    """
    emit_toast(message, ToastType.SUCCESS, duration, room=room)


def emit_info(message: str, duration: int = 3000, room: Optional[str] = None) -> None:
    """
    Emit an info toast notification.

    Args:
        message: Info message to display
        duration: Duration in milliseconds
        room: Optional room to emit to
    """
    emit_toast(message, ToastType.INFO, duration, room=room)


def emit_warning(message: str, duration: int = 4000, room: Optional[str] = None) -> None:
    """
    Emit a warning toast notification.

    Args:
        message: Warning message to display
        duration: Duration in milliseconds
        room: Optional room to emit to
    """
    emit_toast(message, ToastType.WARNING, duration, room=room)


def emit_error(message: str, duration: int = 0, room: Optional[str] = None) -> None:
    """
    Emit an error toast notification (persistent by default).

    Args:
        message: Error message to display
        duration: Duration in milliseconds (0 = persistent)
        room: Optional room to emit to
    """
    emit_toast(message, ToastType.ERROR, duration, room=room)


def emit_trip_detected(trip_distance: float = None, trip_id: str = None) -> None:
    """
    Emit a toast when a new trip is detected.

    Args:
        trip_distance: Distance of the trip in miles (optional)
        trip_id: Trip ID (optional)
    """
    message = "New trip detected"
    if trip_distance:
        message += f": {trip_distance:.1f} miles"

    emit_info(message, duration=3000)


def emit_trip_finalized(
    trip_id: str,
    distance: float,
    mpg: Optional[float] = None,
    electric_miles: Optional[float] = None
) -> None:
    """
    Emit a toast when a trip is finalized.

    Args:
        trip_id: Trip ID
        distance: Total distance in miles
        mpg: Miles per gallon (if gas was used)
        electric_miles: Electric-only miles
    """
    message = f"Trip finalized: {distance:.1f} miles"

    if mpg and mpg > 0:
        message += f", {mpg:.0f} MPG"
    elif electric_miles and electric_miles > 0:
        message += f" (all electric)"

    emit_success(message, duration=4000)


def emit_import_complete(records_imported: int, filename: str = None) -> None:
    """
    Emit a toast when CSV import is complete.

    Args:
        records_imported: Number of records successfully imported
        filename: Name of the imported file (optional)
    """
    message = f"Import complete: {records_imported} records added"
    if filename:
        message = f"{filename}: {records_imported} records added"

    emit_success(message, duration=4000)


def emit_import_failed(error_message: str, filename: str = None) -> None:
    """
    Emit a toast when CSV import fails.

    Args:
        error_message: Error message describing the failure
        filename: Name of the file that failed (optional)
    """
    message = f"Import failed: {error_message}"
    if filename:
        message = f"{filename}: Import failed - {error_message}"

    emit_error(message, duration=0)


def emit_charging_session_added(kwh_added: float, location: str = None) -> None:
    """
    Emit a toast when a charging session is added.

    Args:
        kwh_added: kWh added during the session
        location: Location of the charging session (optional)
    """
    message = f"Charging session added: {kwh_added:.1f} kWh"
    if location:
        message += f" at {location}"

    emit_success(message, duration=3000)


def emit_data_export(export_type: str, record_count: int) -> None:
    """
    Emit a toast when data export is requested.

    Args:
        export_type: Type of export (e.g., 'CSV', 'JSON')
        record_count: Number of records being exported
    """
    message = f"Exporting {record_count} records as {export_type}"
    emit_info(message, duration=2000)


def emit_low_gps_accuracy(accuracy: float) -> None:
    """
    Emit a toast when GPS accuracy is poor.

    Args:
        accuracy: GPS accuracy in meters
    """
    message = f"GPS signal weak: {accuracy:.0f}m accuracy. Data may be inaccurate."
    emit_warning(message, duration=5000)


def emit_battery_health_updated(capacity_kwh: float, percent: float) -> None:
    """
    Emit a toast when battery health is updated.

    Args:
        capacity_kwh: Current battery capacity in kWh
        percent: Battery health percentage
    """
    message = f"Battery health updated: {capacity_kwh:.1f} kWh ({percent:.0f}%)"
    emit_info(message, duration=3000)
