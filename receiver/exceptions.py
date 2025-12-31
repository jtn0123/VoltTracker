"""
Custom exceptions for VoltTracker.

This module provides a hierarchy of exceptions for better error handling
and more informative error messages throughout the application.
"""


class VoltTrackerError(Exception):
    """Base exception for all VoltTracker errors."""

    def __init__(self, message: str, details: dict = None):
        super().__init__(message)
        self.message = message
        self.details = details or {}

    def __str__(self):
        if self.details:
            return f"{self.message} - {self.details}"
        return self.message


class DatabaseError(VoltTrackerError):
    """Database operation failed."""

    pass


class TelemetryParsingError(VoltTrackerError):
    """Failed to parse telemetry data from Torque Pro."""

    def __init__(self, message: str, field: str = None, value: str = None):
        details = {}
        if field:
            details['field'] = field
        if value:
            details['value'] = value
        super().__init__(message, details)
        self.field = field
        self.value = value


class CSVImportError(VoltTrackerError):
    """CSV import operation failed."""

    def __init__(self, message: str, row_number: int = None, filename: str = None):
        details = {}
        if row_number:
            details['row_number'] = row_number
        if filename:
            details['filename'] = filename
        super().__init__(message, details)
        self.row_number = row_number
        self.filename = filename


class CSVValidationError(CSVImportError):
    """CSV data validation failed."""

    def __init__(
        self,
        message: str,
        row_number: int = None,
        field: str = None,
        value: str = None,
        expected_range: tuple = None
    ):
        super().__init__(message, row_number)
        self.field = field
        self.value = value
        self.expected_range = expected_range
        if field:
            self.details['field'] = field
        if value:
            self.details['value'] = value
        if expected_range:
            self.details['expected_range'] = expected_range


class CSVTimestampParseError(CSVImportError):
    """Failed to parse timestamp in CSV row."""

    def __init__(self, message: str, row_number: int = None, raw_value: str = None):
        super().__init__(message, row_number)
        self.raw_value = raw_value
        if raw_value:
            self.details['raw_value'] = raw_value


class WeatherAPIError(VoltTrackerError):
    """Weather API request failed."""

    def __init__(
        self,
        message: str,
        latitude: float = None,
        longitude: float = None,
        status_code: int = None
    ):
        details = {}
        if latitude is not None:
            details['latitude'] = latitude
        if longitude is not None:
            details['longitude'] = longitude
        if status_code:
            details['status_code'] = status_code
        super().__init__(message, details)
        self.latitude = latitude
        self.longitude = longitude
        self.status_code = status_code


class TripProcessingError(VoltTrackerError):
    """Trip finalization or processing failed."""

    def __init__(self, message: str, trip_id: int = None, session_id: str = None):
        details = {}
        if trip_id:
            details['trip_id'] = trip_id
        if session_id:
            details['session_id'] = session_id
        super().__init__(message, details)
        self.trip_id = trip_id
        self.session_id = session_id


class ChargingSessionError(VoltTrackerError):
    """Charging session operation failed."""

    def __init__(self, message: str, session_id: int = None):
        details = {}
        if session_id:
            details['session_id'] = session_id
        super().__init__(message, details)
        self.session_id = session_id


class ConfigurationError(VoltTrackerError):
    """Invalid or missing configuration."""

    def __init__(self, message: str, config_key: str = None):
        details = {}
        if config_key:
            details['config_key'] = config_key
        super().__init__(message, details)
        self.config_key = config_key
