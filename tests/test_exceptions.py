"""Tests for custom VoltTracker exceptions."""

import pytest

from receiver.exceptions import (
    ChargingSessionError,
    ConfigurationError,
    CSVImportError,
    CSVTimestampParseError,
    CSVValidationError,
    DatabaseError,
    TelemetryParsingError,
    TripProcessingError,
    VoltTrackerError,
    WeatherAPIError,
)


class TestVoltTrackerError:
    """Tests for base VoltTrackerError."""

    def test_basic_message(self):
        """Test exception with just a message."""
        error = VoltTrackerError("Something went wrong")
        assert str(error) == "Something went wrong"
        assert error.message == "Something went wrong"
        assert error.details == {}

    def test_with_details(self):
        """Test exception with details dict."""
        error = VoltTrackerError("Error occurred", {"key": "value", "count": 42})
        assert "Error occurred" in str(error)
        assert error.details == {"key": "value", "count": 42}

    def test_is_exception(self):
        """Test that it's a proper Exception subclass."""
        error = VoltTrackerError("test")
        assert isinstance(error, Exception)

        with pytest.raises(VoltTrackerError):
            raise error


class TestDatabaseError:
    """Tests for DatabaseError."""

    def test_inheritance(self):
        """Test that DatabaseError inherits from VoltTrackerError."""
        error = DatabaseError("DB connection failed")
        assert isinstance(error, VoltTrackerError)
        assert isinstance(error, Exception)

    def test_message(self):
        """Test DatabaseError message."""
        error = DatabaseError("Failed to connect to database")
        assert "Failed to connect to database" in str(error)


class TestTelemetryParsingError:
    """Tests for TelemetryParsingError."""

    def test_basic_error(self):
        """Test basic telemetry parsing error."""
        error = TelemetryParsingError("Invalid data format")
        assert "Invalid data format" in str(error)
        assert error.field is None
        assert error.value is None

    def test_with_field_and_value(self):
        """Test error with field and value context."""
        error = TelemetryParsingError("Could not parse field", field="state_of_charge", value="not_a_number")
        assert error.field == "state_of_charge"
        assert error.value == "not_a_number"
        assert error.details["field"] == "state_of_charge"
        assert error.details["value"] == "not_a_number"


class TestCSVImportError:
    """Tests for CSVImportError."""

    def test_basic_error(self):
        """Test basic CSV import error."""
        error = CSVImportError("File not found")
        assert "File not found" in str(error)
        assert error.row_number is None
        assert error.filename is None

    def test_with_row_number(self):
        """Test error with row number context."""
        error = CSVImportError("Invalid format", row_number=42)
        assert error.row_number == 42
        assert error.details["row_number"] == 42

    def test_with_filename(self):
        """Test error with filename context."""
        error = CSVImportError("Parse failed", filename="data.csv")
        assert error.filename == "data.csv"
        assert error.details["filename"] == "data.csv"

    def test_with_all_context(self):
        """Test error with full context."""
        error = CSVImportError("Parse error", row_number=100, filename="torque_log.csv")
        assert error.row_number == 100
        assert error.filename == "torque_log.csv"


class TestCSVValidationError:
    """Tests for CSVValidationError."""

    def test_inheritance(self):
        """Test that CSVValidationError inherits from CSVImportError."""
        error = CSVValidationError("Validation failed")
        assert isinstance(error, CSVImportError)
        assert isinstance(error, VoltTrackerError)

    def test_with_validation_context(self):
        """Test error with validation context."""
        error = CSVValidationError(
            "Value out of range", row_number=50, field="state_of_charge", value="150", expected_range=(0, 100)
        )
        assert error.row_number == 50
        assert error.field == "state_of_charge"
        assert error.value == "150"
        assert error.expected_range == (0, 100)
        assert error.details["expected_range"] == (0, 100)


class TestCSVTimestampParseError:
    """Tests for CSVTimestampParseError."""

    def test_inheritance(self):
        """Test inheritance from CSVImportError."""
        error = CSVTimestampParseError("Cannot parse timestamp")
        assert isinstance(error, CSVImportError)

    def test_with_raw_value(self):
        """Test error with raw timestamp value."""
        error = CSVTimestampParseError("Invalid timestamp format", row_number=10, raw_value="not-a-date")
        assert error.row_number == 10
        assert error.raw_value == "not-a-date"
        assert error.details["raw_value"] == "not-a-date"


class TestWeatherAPIError:
    """Tests for WeatherAPIError."""

    def test_basic_error(self):
        """Test basic weather API error."""
        error = WeatherAPIError("API request failed")
        assert "API request failed" in str(error)
        assert error.latitude is None
        assert error.longitude is None
        assert error.status_code is None

    def test_with_location(self):
        """Test error with location context."""
        error = WeatherAPIError("Request timeout", latitude=37.7749, longitude=-122.4194)
        assert error.latitude == 37.7749
        assert error.longitude == -122.4194
        assert error.details["latitude"] == 37.7749
        assert error.details["longitude"] == -122.4194

    def test_with_status_code(self):
        """Test error with HTTP status code."""
        error = WeatherAPIError("Server error", status_code=500)
        assert error.status_code == 500
        assert error.details["status_code"] == 500


class TestTripProcessingError:
    """Tests for TripProcessingError."""

    def test_basic_error(self):
        """Test basic trip processing error."""
        error = TripProcessingError("Failed to finalize trip")
        assert "Failed to finalize trip" in str(error)
        assert error.trip_id is None
        assert error.session_id is None

    def test_with_trip_id(self):
        """Test error with trip ID context."""
        error = TripProcessingError("Calculation error", trip_id=123)
        assert error.trip_id == 123
        assert error.details["trip_id"] == 123

    def test_with_session_id(self):
        """Test error with session ID context."""
        error = TripProcessingError("Trip creation failed", session_id="abc-123-def")
        assert error.session_id == "abc-123-def"
        assert error.details["session_id"] == "abc-123-def"


class TestChargingSessionError:
    """Tests for ChargingSessionError."""

    def test_basic_error(self):
        """Test basic charging session error."""
        error = ChargingSessionError("Session detection failed")
        assert "Session detection failed" in str(error)
        assert error.session_id is None

    def test_with_session_id(self):
        """Test error with session ID context."""
        error = ChargingSessionError("Failed to update session", session_id=42)
        assert error.session_id == 42
        assert error.details["session_id"] == 42


class TestConfigurationError:
    """Tests for ConfigurationError."""

    def test_basic_error(self):
        """Test basic configuration error."""
        error = ConfigurationError("Missing configuration")
        assert "Missing configuration" in str(error)
        assert error.config_key is None

    def test_with_config_key(self):
        """Test error with config key context."""
        error = ConfigurationError("Invalid value for config", config_key="DATABASE_URL")
        assert error.config_key == "DATABASE_URL"
        assert error.details["config_key"] == "DATABASE_URL"


class TestExceptionRaising:
    """Test that exceptions can be properly raised and caught."""

    def test_catch_by_base_class(self):
        """Test catching specific exceptions by base class."""
        with pytest.raises(VoltTrackerError):
            raise DatabaseError("DB error")

        with pytest.raises(VoltTrackerError):
            raise CSVImportError("Import error")

        with pytest.raises(VoltTrackerError):
            raise WeatherAPIError("Weather error")

    def test_catch_csv_hierarchy(self):
        """Test catching CSV exceptions in hierarchy."""
        with pytest.raises(CSVImportError):
            raise CSVValidationError("Validation failed")

        with pytest.raises(CSVImportError):
            raise CSVTimestampParseError("Parse failed")

    def test_exception_in_try_except(self):
        """Test using exceptions in try-except blocks."""

        def function_that_raises():
            raise TripProcessingError("Trip failed", trip_id=1)

        try:
            function_that_raises()
            assert False, "Should have raised"
        except TripProcessingError as e:
            assert e.trip_id == 1
            assert "Trip failed" in str(e)
