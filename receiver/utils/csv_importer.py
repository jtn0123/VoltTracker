"""CSV importer for Torque Pro log files."""

import csv
import io
import logging
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Set, Tuple

from config import Config
from exceptions import CSVImportError

logger = logging.getLogger(__name__)


# Validation ranges for key fields
VALIDATION_RANGES = {
    "state_of_charge": (0, 100),  # SOC is 0-100%
    "fuel_level_percent": (0, 100),  # Fuel is 0-100%
    "speed_mph": (0, 200),  # Reasonable speed range
    "engine_rpm": (0, 8000),  # Reasonable RPM range
    "latitude": (-90, 90),  # Valid GPS range
    "longitude": (-180, 180),  # Valid GPS range
    "ambient_temp_f": (-60, 150),  # Reasonable temp range F
    "coolant_temp_f": (0, 300),  # Engine temp range F
}


class TorqueCSVImporter:
    """
    Parse and import CSV log files exported from Torque Pro.

    Torque CSV format typically includes:
    - GPS Time: Timestamp in various formats
    - Device Time: Alternative timestamp
    - Longitude, Latitude: GPS coordinates
    - GPS Speed (Meters/second) or GPS Speed (km/h)
    - Engine RPM(rpm)
    - Fuel Level (%)
    - State of Charge (%): If Volt PIDs are configured
    - Various other PIDs as columns
    """

    # Common column name mappings (case-insensitive)
    COLUMN_MAP = {
        # Timestamps
        "gps time": "timestamp",
        "device time": "device_time",
        "timestamp": "timestamp",
        # GPS
        "latitude": "latitude",
        "longitude": "longitude",
        "gps speed (meters/second)": "speed_mps",
        "gps speed(meters/second)": "speed_mps",
        "gps speed (km/h)": "speed_kmh",
        "gps speed(km/h)": "speed_kmh",
        "speed (gps)(mph)": "speed_mph",
        "speed (obd)(mph)": "speed_mph",
        # Engine
        "engine rpm(rpm)": "engine_rpm",
        "engine rpm (rpm)": "engine_rpm",
        "rpm": "engine_rpm",
        # Fuel
        "fuel level (fuel tank)(%)": "fuel_level_percent",
        "fuel level(%)": "fuel_level_percent",
        "fuel level (%)": "fuel_level_percent",
        "!fuel level(%)": "fuel_level_percent",
        "!fuel level (%)": "fuel_level_percent",
        "fuel level (from engine ecu)(%)": "fuel_level_percent",
        # Volt-specific SOC (standard naming)
        "state of charge(%)": "state_of_charge",
        "state of charge (%)": "state_of_charge",
        "soc(%)": "state_of_charge",
        # Volt-specific SOC (Torque custom PID naming with !! and ! prefixes)
        "!! soc (usable)(%)": "state_of_charge",
        "!! soc(usable)(%)": "state_of_charge",
        "!! soc(raw)(%)": "state_of_charge",
        "!! soc (raw)(%)": "state_of_charge",
        "!hybrid pack remaining (soc)(%)": "state_of_charge",
        "!hybrid pack remaining(soc)(%)": "state_of_charge",
        "hybrid battery charge (%)": "state_of_charge",
        "hybrid battery charge(%)": "state_of_charge",
        "hybrid/ev battery remaining charge(%)": "state_of_charge",
        # HV Battery
        "hv battery power(kw)": "hv_battery_power_kw",
        "hv battery power (kw)": "hv_battery_power_kw",
        "!! inst. kpower(kw)": "hv_battery_power_kw",
        "!! hv discharge amps(a)": "hv_discharge_amps",
        "!! hv volts(v)": "hv_battery_voltage_v",
        "!hv battery temp(°f)": "battery_temp_f",
        # Temperature
        "ambient air temp(°f)": "ambient_temp_f",
        "ambient air temp (°f)": "ambient_temp_f",
        "ambient air temperature(°f)": "ambient_temp_f",
        "*outside temp filtered(°f)": "ambient_temp_f",
        "intake air temp(°f)": "intake_air_temp_f",
        "intake air temperature(°f)": "intake_air_temp_f",
        "*m intake air temp iat(°f)": "intake_air_temp_f",
        "engine coolant temp(°f)": "coolant_temp_f",
        "!engine coolant temp(°f)": "coolant_temp_f",
        "engine coolant temperature(°f)": "coolant_temp_f",
        "!engine oil temp(°f)": "coolant_temp_f",
        "!tran temp(°f)": "coolant_temp_f",
        # Odometer
        "trip distance(km)": "trip_distance_km",
        "trip distance (km)": "trip_distance_km",
        "trip distance(miles)": "trip_distance_miles",
        "trip distance (miles)": "trip_distance_miles",
        "odometer(km)": "odometer_km",
        "odometer (km)": "odometer_km",
        # Throttle
        "throttle position(manifold)(%)": "throttle_position",
        "throttle position (%)": "throttle_position",
        # Battery voltage
        "voltage (control module)(v)": "battery_voltage",
        "battery voltage(v)": "battery_voltage",
    }

    @classmethod
    def _validate_record(cls, record: Dict[str, Any]) -> Tuple[bool, List[str]]:
        """
        Validate a record's field values are within acceptable ranges.

        Args:
            record: Telemetry record dict

        Returns:
            Tuple of (is_valid, list of warning messages)
        """
        warnings = []

        for field, (min_val, max_val) in VALIDATION_RANGES.items():
            value = record.get(field)
            if value is not None and (value < min_val or value > max_val):
                warnings.append(f"{field}={value} outside range [{min_val}, {max_val}]")

        # A record is valid if it has a timestamp (we just warn about out-of-range values)
        is_valid = record.get("timestamp") is not None
        return is_valid, warnings

    @classmethod
    def _find_duplicates(
        cls, records: List[Dict[str, Any]], existing_timestamps: Optional[Set[datetime]] = None
    ) -> Tuple[List[Dict[str, Any]], int]:
        """
        Remove records that already exist in the database (by timestamp).

        Only removes duplicates if existing_timestamps is provided. Does not
        deduplicate within the import itself, as the same timestamp from
        different rows might be valid data points.

        Args:
            records: List of telemetry records
            existing_timestamps: Set of existing timestamps from database

        Returns:
            Tuple of (filtered records, count of duplicates removed)
        """
        if not existing_timestamps:
            return records, 0

        # Normalize existing_timestamps to naive UTC for consistent comparison
        # Database returns timezone-aware, CSV parsing may return either
        normalized_existing = set()
        for ts in existing_timestamps:
            if ts.tzinfo is not None:
                # Convert to UTC first, then strip timezone. Using replace()
                # alone would keep the original wall-clock time and miss
                # duplicates whose DB timestamp is in a non-UTC zone.
                normalized_existing.add(ts.astimezone(timezone.utc).replace(tzinfo=None))
            else:
                normalized_existing.add(ts)

        unique_records = []
        duplicate_count = 0

        for record in records:
            ts = record.get("timestamp")
            if ts is None:
                continue

            # Normalize to naive UTC datetime for comparison
            if ts.tzinfo is not None:
                ts_key = ts.astimezone(timezone.utc).replace(tzinfo=None)
            else:
                ts_key = ts

            if ts_key in normalized_existing:
                duplicate_count += 1
            else:
                unique_records.append(record)

        return unique_records, duplicate_count

    @classmethod
    def _build_column_mapping(cls, fieldnames, stats):
        """Map CSV column names to internal field names and update stats."""
        column_mapping = {}
        if not fieldnames:
            return column_mapping
        stats["columns_detected"] = list(fieldnames)
        for col in fieldnames:
            col_lower = col.lower().strip()
            if col_lower in cls.COLUMN_MAP:
                column_mapping[col] = cls.COLUMN_MAP[col_lower]
        stats["columns_mapped"] = list(column_mapping.values())
        stats["columns_found"] = stats["columns_mapped"]
        stats["timestamp_column_found"] = (
            "timestamp" in stats["columns_mapped"]
            or "device_time" in stats["columns_mapped"]
        )
        return column_mapping

    @staticmethod
    def _determine_failure_reason(stats):
        """Determine why no records were parsed."""
        if stats["total_rows"] == 0:
            return "empty_file"
        if not stats["timestamp_column_found"]:
            return "no_timestamp_column"
        return "all_timestamps_unparseable"

    @classmethod
    def parse_csv(
        cls, csv_content: str, existing_timestamps: Optional[Set[datetime]] = None
    ) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
        """
        Parse Torque CSV content into telemetry records with validation.

        Args:
            csv_content: Raw CSV file content as string
            existing_timestamps: Optional set of existing timestamps to detect duplicates

        Returns:
            Tuple of (list of telemetry dicts, stats dict with validation info)
        """
        records: List[Dict[str, Any]] = []
        stats: Dict[str, Any] = {
            "total_rows": 0,
            "parsed_rows": 0,
            "skipped_rows": 0,
            "duplicates_removed": 0,
            "validation_warnings": 0,
            "total_errors": 0,
            # Enhanced column tracking
            "columns_detected": [],  # All columns in the CSV
            "columns_mapped": [],  # Columns we recognized and mapped
            "columns_found": [],  # Legacy alias for columns_mapped
            "timestamp_column_found": False,  # Critical for understanding failures
            # Structured errors instead of just strings
            "errors": [],  # [{row, field, value, reason, error_type}]
            "warnings": [],
            # Failure analysis
            "failure_reason": None,  # High-level reason if import fails
        }

        try:
            # Try to detect delimiter
            dialect = csv.Sniffer().sniff(csv_content[:2000])
        except csv.Error:
            dialect = csv.excel

        reader = csv.DictReader(io.StringIO(csv_content), dialect=dialect)

        column_mapping = cls._build_column_mapping(reader.fieldnames, stats)

        # Generate a session ID for this import
        session_id = uuid.uuid4()

        for row_num, row in enumerate(reader, start=2):  # Start at 2 (header is row 1)
            stats["total_rows"] += 1

            # Check row count limit to prevent memory exhaustion
            if stats["total_rows"] > Config.MAX_CSV_ROWS:
                stats["failure_reason"] = "too_many_rows"
                error_msg = f"CSV file exceeds maximum row limit of {Config.MAX_CSV_ROWS:,} rows"
                stats["errors"].append({
                    "row": row_num, "field": "row_count",
                    "value": stats["total_rows"], "reason": error_msg,
                    "error_type": "validation"
                })
                raise CSVImportError(error_msg, row_number=row_num)

            cls._process_csv_row(
                row, row_num, column_mapping, session_id, records, stats
            )

        # Determine failure reason if no records were parsed
        if stats["parsed_rows"] == 0:
            stats["failure_reason"] = cls._determine_failure_reason(stats)

        # Add truncation note if there were more errors than shown
        if stats["total_errors"] > 50:
            stats["errors"].append({
                "row": None,
                "field": None,
                "value": None,
                "reason": f"... and {stats['total_errors'] - 50} more errors (showing first 50)",
                "error_type": "Truncated"
            })

        # Remove duplicates
        records, duplicate_count = cls._find_duplicates(records, existing_timestamps)
        stats["duplicates_removed"] = duplicate_count

        return records, stats

    @classmethod
    def _process_csv_row(cls, row, row_num, column_mapping, session_id, records, stats):
        """Process a single CSV row, appending to records or updating stats on error."""
        try:
            record = cls._parse_row(row, column_mapping, session_id)
            if record and record.get("timestamp"):
                _is_valid, warnings = cls._validate_record(record)
                if warnings:
                    stats["validation_warnings"] += len(warnings)
                    if len(stats["warnings"]) < 10:
                        stats["warnings"].append(f"Row {row_num}: {', '.join(warnings)}")
                records.append(record)
                stats["parsed_rows"] += 1
                return
            stats["skipped_rows"] += 1
            stats["total_errors"] += 1
            if len(stats["errors"]) < 50:
                sample_values = {k: v[:50] if v else None for k, v in list(row.items())[:5]}
                reason = (
                    "no_timestamp_value"
                    if not stats["timestamp_column_found"]
                    else "timestamp_parse_failed"
                )
                stats["errors"].append({
                    "row": row_num, "field": "timestamp", "value": None,
                    "reason": reason, "error_type": "MissingTimestamp",
                    "sample_data": sample_values,
                })
        except CSVImportError as e:
            cls._record_row_error(stats, row_num, str(e), "CSVImportError")
        except Exception as e:
            cls._record_row_error(stats, row_num, str(e), type(e).__name__)

    @classmethod
    def _record_row_error(cls, stats, row_num, reason, error_type):
        """Record a row-level error in stats."""
        stats["skipped_rows"] += 1
        stats["total_errors"] += 1
        if len(stats["errors"]) < 50:
            stats["errors"].append({
                "row": row_num, "field": None, "value": None,
                "reason": reason, "error_type": error_type,
            })

    # Dispatch table: field_name -> (record_key, conversion_func)
    # For simple float fields that map directly
    _FLOAT_FIELDS = {
        "latitude": "latitude",
        "longitude": "longitude",
        "speed_mph": "speed_mph",
        "engine_rpm": "engine_rpm",
        "throttle_position": "throttle_position",
        "state_of_charge": "state_of_charge",
        "battery_voltage": "battery_voltage",
        "ambient_temp_f": "ambient_temp_f",
        "coolant_temp_f": "coolant_temp_f",
        "intake_air_temp_f": "intake_air_temp_f",
        "hv_battery_power_kw": "hv_battery_power_kw",
        "hv_discharge_amps": "hv_discharge_amps",
        "hv_battery_voltage_v": "hv_battery_voltage_v",
        "battery_temp_f": "battery_temp_f",
        "trip_distance_miles": "odometer_miles",
    }

    # Conversion fields: field_name -> (record_key, multiplier)
    _CONVERSION_FIELDS = {
        "speed_mps": ("speed_mph", 2.23694),
        "speed_kmh": ("speed_mph", 0.621371),
        "odometer_km": ("odometer_miles", 0.621371),
        "trip_distance_km": ("odometer_miles", 0.621371),
    }

    @classmethod
    def _apply_field_value(cls, record, field_name, value):
        """Apply a parsed field value to the record using dispatch tables."""
        if field_name == "timestamp":
            parsed = cls._parse_timestamp(value)
            if parsed is not None:
                record["timestamp"] = parsed
            return
        if field_name == "device_time":
            if not record["timestamp"]:
                parsed = cls._parse_timestamp(value)
                if parsed is not None:
                    record["timestamp"] = parsed
            return
        if field_name == "fuel_level_percent":
            from utils import fuel_percent_to_gallons
            record["fuel_level_percent"] = float(value)
            record["fuel_remaining_gallons"] = fuel_percent_to_gallons(float(value))
            return
        if field_name in cls._FLOAT_FIELDS:
            record[cls._FLOAT_FIELDS[field_name]] = float(value)
            return
        if field_name in cls._CONVERSION_FIELDS:
            key, multiplier = cls._CONVERSION_FIELDS[field_name]
            record[key] = float(value) * multiplier

    @classmethod
    def _parse_row(
        cls, row: Dict[str, str], column_mapping: Dict[str, str], session_id: uuid.UUID
    ) -> Optional[Dict[str, Any]]:
        """Parse a single CSV row into a telemetry record."""

        record: Dict[str, Any] = {
            "session_id": session_id,
            "timestamp": None,
            "latitude": None,
            "longitude": None,
            "speed_mph": None,
            "engine_rpm": None,
            "throttle_position": None,
            "fuel_level_percent": None,
            "fuel_remaining_gallons": None,
            "state_of_charge": None,
            "battery_voltage": None,
            "ambient_temp_f": None,
            "coolant_temp_f": None,
            "intake_air_temp_f": None,
            "odometer_miles": None,
            "hv_battery_power_kw": None,
            "hv_discharge_amps": None,
            "hv_battery_voltage_v": None,
            "battery_temp_f": None,
            "raw_data": {},
        }

        for csv_col, field_name in column_mapping.items():
            value = row.get(csv_col, "").strip()
            if not value or value == "-":
                continue
            record["raw_data"][csv_col] = value
            try:
                cls._apply_field_value(record, field_name, value)
            except (ValueError, TypeError) as e:
                logger.debug(f"Failed to parse {field_name}: {value} - {e}")
                continue

        return record if record["timestamp"] else None

    _TIMESTAMP_FORMATS = [
        "%d-%b-%Y %H:%M:%S.%f", "%d-%b-%Y %H:%M:%S",
        "%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S",
        "%Y-%m-%dT%H:%M:%S.%f", "%Y-%m-%dT%H:%M:%S",
        "%m/%d/%Y %H:%M:%S", "%m/%d/%Y %I:%M:%S %p", "%m-%d-%Y %H:%M:%S",
        "%d/%m/%Y %H:%M:%S", "%d.%m.%Y %H:%M:%S", "%d-%m-%Y %H:%M:%S",
        "%b %d, %Y %H:%M:%S", "%B %d, %Y %H:%M:%S",
        "%Y-%m-%d", "%m/%d/%Y", "%d/%m/%Y",
    ]

    @classmethod
    def _try_epoch(cls, value):
        """Try parsing value as a Unix epoch timestamp."""
        try:
            ts = float(value)
            if ts > 1e12:
                ts = ts / 1000
            if 1e9 < ts < 2e9:
                return datetime.fromtimestamp(ts, tz=timezone.utc)
        except (ValueError, TypeError):
            pass
        return None

    @classmethod
    def _try_isoformat(cls, value):
        """Try parsing value as ISO 8601 with optional timezone."""
        try:
            value_tz = value[:-1] + "+00:00" if value.endswith("Z") else value
            dt = datetime.fromisoformat(value_tz)
            return dt.astimezone(timezone.utc) if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
        except (ValueError, TypeError):
            return None

    @classmethod
    def _try_strptime_formats(cls, value):
        """Try parsing value against known strptime format strings."""
        for fmt in cls._TIMESTAMP_FORMATS:
            try:
                dt = datetime.strptime(value, fmt)
                return dt.replace(tzinfo=timezone.utc) if dt.tzinfo is None else dt
            except ValueError:
                continue
        return None

    @classmethod
    def _try_dateutil(cls, value):
        """Try parsing value with dateutil as a last resort."""
        try:
            from dateutil import parser as dateutil_parser  # type: ignore[import-untyped]
            dt = dateutil_parser.parse(value, fuzzy=False)
            return dt.astimezone(timezone.utc) if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
        except (ImportError, ValueError, TypeError):
            return None

    @classmethod
    def _parse_timestamp(cls, value: str) -> Optional[datetime]:
        """Parse timestamp from various Torque and international formats."""
        if not value or not value.strip():
            return None
        value = value.strip()

        for parser in (cls._try_epoch, cls._try_isoformat, cls._try_strptime_formats, cls._try_dateutil):
            result = parser(value)
            if result is not None:
                return result

        logger.debug(f"Could not parse timestamp: {value}")
        return None
