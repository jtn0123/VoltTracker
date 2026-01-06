"""
Error Code Taxonomy for VoltTracker

Structured error codes for better alerting, debugging, and monitoring.
Following loggingsucks.com recommendations for error classification.

Error Code Format:
- E001-E099: Validation errors (bad input data)
- E100-E199: External API errors (weather, geocoding)
- E200-E299: Database errors (connection, query failures)
- E300-E399: Parsing errors (Torque data, JSON)
- E400-E499: Business logic errors (trip processing, calculations)
- E500-E599: System errors (resource exhaustion, timeouts)
"""

from enum import Enum
from typing import Optional


class ErrorCategory(str, Enum):
    """High-level error categories for grouping and alerting."""

    VALIDATION = "validation"
    EXTERNAL_API = "external_api"
    DATABASE = "database"
    PARSING = "parsing"
    BUSINESS_LOGIC = "business_logic"
    SYSTEM = "system"


class ErrorCode(str, Enum):
    """Structured error codes with consistent format."""

    # Validation Errors (E001-E099)
    E001_INVALID_TOKEN = "E001"  # Invalid API token
    E002_MISSING_REQUIRED_FIELD = "E002"  # Required field missing in request
    E003_INVALID_DATA_TYPE = "E003"  # Field has wrong data type
    E004_OUT_OF_RANGE = "E004"  # Value outside acceptable range (MPG, kWh/mi, etc.)
    E005_INVALID_SESSION_ID = "E005"  # Malformed session ID

    # External API Errors (E100-E199)
    E100_WEATHER_API_TIMEOUT = "E100"  # Weather API request timed out
    E101_WEATHER_API_CONNECTION = "E101"  # Weather API connection failed
    E102_WEATHER_API_INVALID_RESPONSE = "E102"  # Weather API returned invalid data
    E103_WEATHER_API_RATE_LIMIT = "E103"  # Weather API rate limit exceeded
    E104_GEOCODING_API_ERROR = "E104"  # Geocoding service error

    # Database Errors (E200-E299)
    E200_DB_CONNECTION_FAILED = "E200"  # Database connection failed
    E201_DB_QUERY_TIMEOUT = "E201"  # Database query timed out
    E202_DB_CONSTRAINT_VIOLATION = "E202"  # Database constraint violated
    E203_DB_TRANSACTION_ROLLBACK = "E203"  # Database transaction rolled back
    E204_DB_RACE_CONDITION = "E204"  # Database race condition detected

    # Parsing Errors (E300-E399)
    E300_TORQUE_PARSE_FAILED = "E300"  # Failed to parse Torque data
    E301_INVALID_TIMESTAMP = "E301"  # Invalid timestamp format
    E302_INVALID_GPS_COORDS = "E302"  # Invalid GPS coordinates
    E303_JSON_DECODE_ERROR = "E303"  # JSON decoding failed
    E304_MISSING_TELEMETRY_FIELD = "E304"  # Required telemetry field missing

    # Business Logic Errors (E400-E499)
    E400_TRIP_NOT_FOUND = "E400"  # Trip not found for session
    E401_TRIP_ALREADY_CLOSED = "E401"  # Attempt to modify closed trip
    E402_NO_TELEMETRY_DATA = "E402"  # Trip has no telemetry data
    E403_INVALID_TRIP_STATE = "E403"  # Trip in invalid state for operation
    E404_GAS_MODE_CALCULATION_FAILED = "E404"  # Gas mode calculation error
    E405_EFFICIENCY_CALCULATION_FAILED = "E405"  # Efficiency calculation error
    E406_ROUTE_DETECTION_FAILED = "E406"  # Route detection algorithm failed

    # System Errors (E500-E599)
    E500_INTERNAL_SERVER_ERROR = "E500"  # Unhandled internal error
    E501_RESOURCE_EXHAUSTED = "E501"  # Memory, disk, or connection pool exhausted
    E502_OPERATION_TIMEOUT = "E502"  # Operation exceeded timeout
    E503_SERVICE_UNAVAILABLE = "E503"  # Service temporarily unavailable


# Error metadata: maps error codes to categories and descriptions
ERROR_METADATA = {
    # Validation Errors
    ErrorCode.E001_INVALID_TOKEN: {
        "category": ErrorCategory.VALIDATION,
        "description": "Invalid API token provided",
        "severity": "warning",
        "alert": False,
    },
    ErrorCode.E002_MISSING_REQUIRED_FIELD: {
        "category": ErrorCategory.VALIDATION,
        "description": "Required field missing in request",
        "severity": "warning",
        "alert": False,
    },
    ErrorCode.E003_INVALID_DATA_TYPE: {
        "category": ErrorCategory.VALIDATION,
        "description": "Field has wrong data type",
        "severity": "warning",
        "alert": False,
    },
    ErrorCode.E004_OUT_OF_RANGE: {
        "category": ErrorCategory.VALIDATION,
        "description": "Value outside acceptable range",
        "severity": "warning",
        "alert": False,
    },
    ErrorCode.E005_INVALID_SESSION_ID: {
        "category": ErrorCategory.VALIDATION,
        "description": "Malformed session ID",
        "severity": "warning",
        "alert": False,
    },
    # External API Errors
    ErrorCode.E100_WEATHER_API_TIMEOUT: {
        "category": ErrorCategory.EXTERNAL_API,
        "description": "Weather API request timed out",
        "severity": "warning",
        "alert": True,  # Alert if weather API consistently timing out
    },
    ErrorCode.E101_WEATHER_API_CONNECTION: {
        "category": ErrorCategory.EXTERNAL_API,
        "description": "Weather API connection failed",
        "severity": "warning",
        "alert": True,
    },
    ErrorCode.E102_WEATHER_API_INVALID_RESPONSE: {
        "category": ErrorCategory.EXTERNAL_API,
        "description": "Weather API returned invalid data",
        "severity": "warning",
        "alert": True,
    },
    ErrorCode.E103_WEATHER_API_RATE_LIMIT: {
        "category": ErrorCategory.EXTERNAL_API,
        "description": "Weather API rate limit exceeded",
        "severity": "error",
        "alert": True,
    },
    ErrorCode.E104_GEOCODING_API_ERROR: {
        "category": ErrorCategory.EXTERNAL_API,
        "description": "Geocoding service error",
        "severity": "warning",
        "alert": False,
    },
    # Database Errors
    ErrorCode.E200_DB_CONNECTION_FAILED: {
        "category": ErrorCategory.DATABASE,
        "description": "Database connection failed",
        "severity": "critical",
        "alert": True,
    },
    ErrorCode.E201_DB_QUERY_TIMEOUT: {
        "category": ErrorCategory.DATABASE,
        "description": "Database query timed out",
        "severity": "error",
        "alert": True,
    },
    ErrorCode.E202_DB_CONSTRAINT_VIOLATION: {
        "category": ErrorCategory.DATABASE,
        "description": "Database constraint violated",
        "severity": "error",
        "alert": True,
    },
    ErrorCode.E203_DB_TRANSACTION_ROLLBACK: {
        "category": ErrorCategory.DATABASE,
        "description": "Database transaction rolled back",
        "severity": "warning",
        "alert": False,
    },
    ErrorCode.E204_DB_RACE_CONDITION: {
        "category": ErrorCategory.DATABASE,
        "description": "Database race condition detected",
        "severity": "info",
        "alert": False,  # Expected during concurrent uploads
    },
    # Parsing Errors
    ErrorCode.E300_TORQUE_PARSE_FAILED: {
        "category": ErrorCategory.PARSING,
        "description": "Failed to parse Torque data",
        "severity": "warning",
        "alert": False,  # Common with malformed Torque data
    },
    ErrorCode.E301_INVALID_TIMESTAMP: {
        "category": ErrorCategory.PARSING,
        "description": "Invalid timestamp format",
        "severity": "warning",
        "alert": False,
    },
    ErrorCode.E302_INVALID_GPS_COORDS: {
        "category": ErrorCategory.PARSING,
        "description": "Invalid GPS coordinates",
        "severity": "warning",
        "alert": False,
    },
    ErrorCode.E303_JSON_DECODE_ERROR: {
        "category": ErrorCategory.PARSING,
        "description": "JSON decoding failed",
        "severity": "warning",
        "alert": False,
    },
    ErrorCode.E304_MISSING_TELEMETRY_FIELD: {
        "category": ErrorCategory.PARSING,
        "description": "Required telemetry field missing",
        "severity": "warning",
        "alert": False,
    },
    # Business Logic Errors
    ErrorCode.E400_TRIP_NOT_FOUND: {
        "category": ErrorCategory.BUSINESS_LOGIC,
        "description": "Trip not found for session",
        "severity": "error",
        "alert": True,
    },
    ErrorCode.E401_TRIP_ALREADY_CLOSED: {
        "category": ErrorCategory.BUSINESS_LOGIC,
        "description": "Attempt to modify closed trip",
        "severity": "warning",
        "alert": False,
    },
    ErrorCode.E402_NO_TELEMETRY_DATA: {
        "category": ErrorCategory.BUSINESS_LOGIC,
        "description": "Trip has no telemetry data",
        "severity": "warning",
        "alert": False,
    },
    ErrorCode.E403_INVALID_TRIP_STATE: {
        "category": ErrorCategory.BUSINESS_LOGIC,
        "description": "Trip in invalid state for operation",
        "severity": "error",
        "alert": True,
    },
    ErrorCode.E404_GAS_MODE_CALCULATION_FAILED: {
        "category": ErrorCategory.BUSINESS_LOGIC,
        "description": "Gas mode calculation error",
        "severity": "error",
        "alert": True,
    },
    ErrorCode.E405_EFFICIENCY_CALCULATION_FAILED: {
        "category": ErrorCategory.BUSINESS_LOGIC,
        "description": "Efficiency calculation error",
        "severity": "error",
        "alert": True,
    },
    ErrorCode.E406_ROUTE_DETECTION_FAILED: {
        "category": ErrorCategory.BUSINESS_LOGIC,
        "description": "Route detection algorithm failed",
        "severity": "warning",
        "alert": False,
    },
    # System Errors
    ErrorCode.E500_INTERNAL_SERVER_ERROR: {
        "category": ErrorCategory.SYSTEM,
        "description": "Unhandled internal error",
        "severity": "critical",
        "alert": True,
    },
    ErrorCode.E501_RESOURCE_EXHAUSTED: {
        "category": ErrorCategory.SYSTEM,
        "description": "Memory, disk, or connection pool exhausted",
        "severity": "critical",
        "alert": True,
    },
    ErrorCode.E502_OPERATION_TIMEOUT: {
        "category": ErrorCategory.SYSTEM,
        "description": "Operation exceeded timeout",
        "severity": "error",
        "alert": True,
    },
    ErrorCode.E503_SERVICE_UNAVAILABLE: {
        "category": ErrorCategory.SYSTEM,
        "description": "Service temporarily unavailable",
        "severity": "critical",
        "alert": True,
    },
}


def get_error_metadata(error_code: ErrorCode) -> dict:
    """Get metadata for an error code."""
    return ERROR_METADATA.get(
        error_code,
        {
            "category": ErrorCategory.SYSTEM,
            "description": "Unknown error",
            "severity": "error",
            "alert": True,
        },
    )


class StructuredError:
    """Structured error with code, category, and metadata."""

    def __init__(self, code: ErrorCode, message: str, exception: Optional[Exception] = None, **context):
        """
        Create a structured error.

        Args:
            code: Error code from ErrorCode enum
            message: Human-readable error message
            exception: Original exception (if applicable)
            **context: Additional context fields (session_id, trip_id, etc.)
        """
        self.code = code
        self.message = message
        self.exception = exception
        self.context = context
        self.metadata = get_error_metadata(code)

    def to_dict(self) -> dict:
        """Convert to dictionary for logging."""
        error_dict = {
            "code": self.code.value,
            "category": self.metadata["category"].value,
            "message": self.message,
            "severity": self.metadata["severity"],
            "alert": self.metadata["alert"],
        }

        if self.exception:
            error_dict["exception_type"] = type(self.exception).__name__
            error_dict["exception_message"] = str(self.exception)

        if self.context:
            error_dict["context"] = self.context

        return error_dict

    def __str__(self) -> str:
        """String representation."""
        return f"[{self.code.value}] {self.message}"
