"""
Import utilities for CSV import hardening.

Provides functions for:
- Generating unique import codes (IMP-YYYYMMDD-XXXXXX)
- Computing file hashes for duplicate detection
- Formatting reportable error strings
"""

import hashlib
import random
import string
from datetime import datetime, timezone


# Characters that are unambiguous when read aloud or displayed
# Excludes: 0/O, 1/I/L, 8/B, 5/S
UNAMBIGUOUS_CHARS = "ACDEFGHJKMNPQRTUVWXYZ234679"


def generate_import_code() -> str:
    """
    Generate a human-readable import code.

    Format: IMP-YYYYMMDD-XXXXXX
    Example: IMP-20260107-A1B2C3

    The random suffix uses only unambiguous characters to make
    verbal/written communication easier when reporting issues.

    Returns:
        str: Unique import code
    """
    date_part = datetime.now(timezone.utc).strftime("%Y%m%d")
    random_part = "".join(random.choices(UNAMBIGUOUS_CHARS, k=6))
    return f"IMP-{date_part}-{random_part}"


def get_file_hash(content: bytes) -> str:
    """
    Compute SHA-256 hash of file content.

    Used to detect exact duplicate file imports.

    Args:
        content: Raw file bytes

    Returns:
        str: Hex-encoded SHA-256 hash (64 characters)
    """
    return hashlib.sha256(content).hexdigest()


def format_reportable(
    import_code: str,
    status: str,
    failure_reason: str | None = None,
    parsed_rows: int = 0,
    total_rows: int = 0,
    trip_id: int | None = None,
    columns_detected: list | None = None,
) -> str:
    """
    Format a copy-pasteable string for error reporting.

    Users can copy this from the UI and paste into bug reports,
    providing all relevant context without screenshots.

    Args:
        import_code: The unique import code (IMP-YYYYMMDD-XXXXXX)
        status: Import status (success, partial, failed, duplicate)
        failure_reason: Optional failure reason code
        parsed_rows: Number of rows successfully parsed
        total_rows: Total rows in file
        trip_id: Optional associated trip ID
        columns_detected: Optional list of detected column names

    Returns:
        str: Formatted reportable string

    Example output:
        IMP-20260107-A1B2C3 | SUCCESS | 1043/1043 rows | trip_id=47
        IMP-20260107-X9Y8Z7 | FAILED | no_timestamp_column | 0/150 rows | Columns: GPS Speed, Latitude
    """
    parts = [import_code, status.upper()]

    if failure_reason:
        parts.append(failure_reason)

    parts.append(f"{parsed_rows}/{total_rows} rows")

    if trip_id:
        parts.append(f"trip_id={trip_id}")

    if columns_detected and status == "failed":
        # Include detected columns for debugging missing column issues
        cols_str = ", ".join(columns_detected[:5])  # Limit to first 5
        if len(columns_detected) > 5:
            cols_str += f" (+{len(columns_detected) - 5} more)"
        parts.append(f"Columns: {cols_str}")

    return " | ".join(parts)


def get_failure_suggestion(failure_reason: str, columns_detected: list | None = None) -> str:
    """
    Generate actionable suggestion text for a failure reason.

    Provides user-friendly guidance on how to fix common issues.

    Args:
        failure_reason: The error code/reason
        columns_detected: List of columns found in the CSV

    Returns:
        str: Human-readable suggestion
    """
    suggestions = {
        "no_timestamp_column": (
            "CSV is missing a timestamp column. "
            "Expected column names like: 'GPS Time', 'Device Time', 'Timestamp', 'Time'. "
            f"Found: {', '.join(columns_detected[:5]) if columns_detected else 'none'}"
        ),
        "empty_file": "The uploaded file is empty. Please select a valid CSV file.",
        "invalid_csv": "The file could not be parsed as CSV. Ensure it's a valid CSV with comma separators.",
        "encoding_error": (
            "The file contains invalid characters. "
            "Try saving the file as UTF-8 in your spreadsheet application."
        ),
        "no_valid_rows": (
            "No valid data rows found. "
            "Ensure the file has data rows after the header row with valid timestamps and coordinates."
        ),
        "all_duplicates": (
            "All records in this file already exist in the database. "
            "This file may have been imported previously."
        ),
        "date_parse_error": (
            "Could not parse dates in the timestamp column. "
            "Expected format: YYYY-MM-DD HH:MM:SS or similar."
        ),
        "missing_coordinates": (
            "No latitude/longitude columns found. "
            "Expected columns like: 'Latitude', 'Longitude', 'Lat', 'Lon', 'GPS Latitude'."
        ),
    }

    return suggestions.get(
        failure_reason, f"Import failed with error: {failure_reason}. Please check the file format."
    )
