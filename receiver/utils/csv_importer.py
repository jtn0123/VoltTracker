"""CSV importer for Torque Pro log files."""

import csv
import io
import uuid
from datetime import datetime, timezone
from typing import List, Dict, Any, Optional, Tuple, Set
import logging

from config import Config
from exceptions import CSVImportError, CSVValidationError, CSVTimestampParseError

logger = logging.getLogger(__name__)


# Validation ranges for key fields
VALIDATION_RANGES = {
    'state_of_charge': (0, 100),      # SOC is 0-100%
    'fuel_level_percent': (0, 100),    # Fuel is 0-100%
    'speed_mph': (0, 200),             # Reasonable speed range
    'engine_rpm': (0, 8000),           # Reasonable RPM range
    'latitude': (-90, 90),             # Valid GPS range
    'longitude': (-180, 180),          # Valid GPS range
    'ambient_temp_f': (-60, 150),      # Reasonable temp range F
    'coolant_temp_f': (0, 300),        # Engine temp range F
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
        'gps time': 'timestamp',
        'device time': 'device_time',
        'timestamp': 'timestamp',

        # GPS
        'latitude': 'latitude',
        'longitude': 'longitude',
        'gps speed (meters/second)': 'speed_mps',
        'gps speed(meters/second)': 'speed_mps',
        'gps speed (km/h)': 'speed_kmh',
        'gps speed(km/h)': 'speed_kmh',
        'speed (gps)(mph)': 'speed_mph',
        'speed (obd)(mph)': 'speed_mph',

        # Engine
        'engine rpm(rpm)': 'engine_rpm',
        'engine rpm (rpm)': 'engine_rpm',
        'rpm': 'engine_rpm',

        # Fuel
        'fuel level (fuel tank)(%)': 'fuel_level_percent',
        'fuel level(%)': 'fuel_level_percent',
        'fuel level (%)': 'fuel_level_percent',
        '!fuel level(%)': 'fuel_level_percent',
        '!fuel level (%)': 'fuel_level_percent',
        'fuel level (from engine ecu)(%)': 'fuel_level_percent',

        # Volt-specific SOC (standard naming)
        'state of charge(%)': 'state_of_charge',
        'state of charge (%)': 'state_of_charge',
        'soc(%)': 'state_of_charge',
        # Volt-specific SOC (Torque custom PID naming with !! and ! prefixes)
        '!! soc (usable)(%)': 'state_of_charge',
        '!! soc(usable)(%)': 'state_of_charge',
        '!! soc(raw)(%)': 'state_of_charge',
        '!! soc (raw)(%)': 'state_of_charge',
        '!hybrid pack remaining (soc)(%)': 'state_of_charge',
        '!hybrid pack remaining(soc)(%)': 'state_of_charge',
        'hybrid battery charge (%)': 'state_of_charge',
        'hybrid battery charge(%)': 'state_of_charge',
        'hybrid/ev battery remaining charge(%)': 'state_of_charge',
        # HV Battery
        'hv battery power(kw)': 'hv_battery_power_kw',
        'hv battery power (kw)': 'hv_battery_power_kw',
        '!! inst. kpower(kw)': 'hv_battery_power_kw',
        '!! hv discharge amps(a)': 'hv_discharge_amps',
        '!! hv volts(v)': 'hv_battery_voltage_v',
        '!hv battery temp(°f)': 'battery_temp_f',

        # Temperature
        'ambient air temp(°f)': 'ambient_temp_f',
        'ambient air temp (°f)': 'ambient_temp_f',
        'ambient air temperature(°f)': 'ambient_temp_f',
        '*outside temp filtered(°f)': 'ambient_temp_f',
        'intake air temp(°f)': 'intake_air_temp_f',
        'intake air temperature(°f)': 'intake_air_temp_f',
        '*m intake air temp iat(°f)': 'intake_air_temp_f',
        'engine coolant temp(°f)': 'coolant_temp_f',
        '!engine coolant temp(°f)': 'coolant_temp_f',
        'engine coolant temperature(°f)': 'coolant_temp_f',
        '!engine oil temp(°f)': 'coolant_temp_f',
        '!tran temp(°f)': 'coolant_temp_f',

        # Odometer
        'trip distance(km)': 'trip_distance_km',
        'trip distance (km)': 'trip_distance_km',
        'trip distance(miles)': 'trip_distance_miles',
        'trip distance (miles)': 'trip_distance_miles',
        'odometer(km)': 'odometer_km',
        'odometer (km)': 'odometer_km',

        # Throttle
        'throttle position(manifold)(%)': 'throttle_position',
        'throttle position (%)': 'throttle_position',

        # Battery voltage
        'voltage (control module)(v)': 'battery_voltage',
        'battery voltage(v)': 'battery_voltage',
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
            if value is not None:
                if value < min_val or value > max_val:
                    warnings.append(
                        f"{field}={value} outside range [{min_val}, {max_val}]"
                    )

        # A record is valid if it has a timestamp (we just warn about out-of-range values)
        is_valid = record.get('timestamp') is not None
        return is_valid, warnings

    @classmethod
    def _find_duplicates(
        cls,
        records: List[Dict[str, Any]],
        existing_timestamps: Optional[Set[datetime]] = None
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

        unique_records = []
        duplicate_count = 0

        for record in records:
            ts = record.get('timestamp')
            if ts is None:
                continue

            # Normalize to naive datetime for comparison
            if ts.tzinfo is not None:
                ts_key = ts.replace(tzinfo=None)
            else:
                ts_key = ts

            if ts_key in existing_timestamps:
                duplicate_count += 1
            else:
                unique_records.append(record)

        return unique_records, duplicate_count

    @classmethod
    def parse_csv(
        cls,
        csv_content: str,
        existing_timestamps: Optional[Set[datetime]] = None
    ) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
        """
        Parse Torque CSV content into telemetry records with validation.

        Args:
            csv_content: Raw CSV file content as string
            existing_timestamps: Optional set of existing timestamps to detect duplicates

        Returns:
            Tuple of (list of telemetry dicts, stats dict with validation info)
        """
        records = []
        stats = {
            'total_rows': 0,
            'parsed_rows': 0,
            'skipped_rows': 0,
            'duplicates_removed': 0,
            'validation_warnings': 0,
            'total_errors': 0,
            'columns_found': [],
            'errors': [],
            'warnings': []
        }

        try:
            # Try to detect delimiter
            dialect = csv.Sniffer().sniff(csv_content[:2000])
        except csv.Error:
            dialect = csv.excel

        reader = csv.DictReader(io.StringIO(csv_content), dialect=dialect)

        # Map columns to our field names
        column_mapping = {}
        if reader.fieldnames:
            for col in reader.fieldnames:
                col_lower = col.lower().strip()
                if col_lower in cls.COLUMN_MAP:
                    column_mapping[col] = cls.COLUMN_MAP[col_lower]
            stats['columns_found'] = list(column_mapping.values())

        # Generate a session ID for this import
        session_id = uuid.uuid4()

        for row_num, row in enumerate(reader, start=2):  # Start at 2 (header is row 1)
            stats['total_rows'] += 1

            try:
                record = cls._parse_row(row, column_mapping, session_id)
                if record and record.get('timestamp'):
                    # Validate the record
                    is_valid, warnings = cls._validate_record(record)
                    if warnings:
                        stats['validation_warnings'] += len(warnings)
                        if len(stats['warnings']) < 10:  # Limit warning messages
                            stats['warnings'].append(f"Row {row_num}: {', '.join(warnings)}")

                    records.append(record)
                    stats['parsed_rows'] += 1
                else:
                    stats['skipped_rows'] += 1
            except CSVImportError as e:
                stats['skipped_rows'] += 1
                stats['total_errors'] += 1
                if len(stats['errors']) < 10:
                    stats['errors'].append(str(e))
            except Exception as e:
                stats['skipped_rows'] += 1
                stats['total_errors'] += 1
                error = CSVImportError(f"Failed to parse row: {e}", row_number=row_num)
                if len(stats['errors']) < 10:
                    stats['errors'].append(str(error))

        # Add truncation note if there were more errors than shown
        if stats['total_errors'] > 10:
            stats['errors'].append(f"... and {stats['total_errors'] - 10} more errors (showing first 10 only)")

        # Remove duplicates
        records, duplicate_count = cls._find_duplicates(records, existing_timestamps)
        stats['duplicates_removed'] = duplicate_count

        return records, stats

    @classmethod
    def _parse_row(
        cls,
        row: Dict[str, str],
        column_mapping: Dict[str, str],
        session_id: uuid.UUID
    ) -> Optional[Dict[str, Any]]:
        """Parse a single CSV row into a telemetry record."""

        record = {
            'session_id': session_id,
            'timestamp': None,
            'latitude': None,
            'longitude': None,
            'speed_mph': None,
            'engine_rpm': None,
            'throttle_position': None,
            'fuel_level_percent': None,
            'fuel_remaining_gallons': None,
            'state_of_charge': None,
            'battery_voltage': None,
            'ambient_temp_f': None,
            'coolant_temp_f': None,
            'intake_air_temp_f': None,
            'odometer_miles': None,
            'hv_battery_power_kw': None,
            'hv_discharge_amps': None,
            'hv_battery_voltage_v': None,
            'battery_temp_f': None,
            'raw_data': {},
        }

        # Parse mapped columns
        for csv_col, field_name in column_mapping.items():
            value = row.get(csv_col, '').strip()
            if not value or value == '-':
                continue

            record['raw_data'][csv_col] = value

            try:
                if field_name == 'timestamp':
                    record['timestamp'] = cls._parse_timestamp(value)
                elif field_name == 'device_time' and not record['timestamp']:
                    record['timestamp'] = cls._parse_timestamp(value)
                elif field_name == 'latitude':
                    record['latitude'] = float(value)
                elif field_name == 'longitude':
                    record['longitude'] = float(value)
                elif field_name == 'speed_mph':
                    record['speed_mph'] = float(value)
                elif field_name == 'speed_mps':
                    # Convert m/s to mph
                    record['speed_mph'] = float(value) * 2.23694
                elif field_name == 'speed_kmh':
                    # Convert km/h to mph
                    record['speed_mph'] = float(value) * 0.621371
                elif field_name == 'engine_rpm':
                    record['engine_rpm'] = float(value)
                elif field_name == 'throttle_position':
                    record['throttle_position'] = float(value)
                elif field_name == 'fuel_level_percent':
                    record['fuel_level_percent'] = float(value)
                    record['fuel_remaining_gallons'] = (
                        float(value) / 100 * Config.TANK_CAPACITY_GALLONS
                    )
                elif field_name == 'state_of_charge':
                    record['state_of_charge'] = float(value)
                elif field_name == 'battery_voltage':
                    record['battery_voltage'] = float(value)
                elif field_name == 'ambient_temp_f':
                    record['ambient_temp_f'] = float(value)
                elif field_name == 'coolant_temp_f':
                    record['coolant_temp_f'] = float(value)
                elif field_name == 'intake_air_temp_f':
                    record['intake_air_temp_f'] = float(value)
                elif field_name == 'odometer_km':
                    record['odometer_miles'] = float(value) * 0.621371
                elif field_name == 'trip_distance_miles':
                    record['odometer_miles'] = float(value)
                elif field_name == 'trip_distance_km':
                    record['odometer_miles'] = float(value) * 0.621371
                elif field_name == 'hv_battery_power_kw':
                    record['hv_battery_power_kw'] = float(value)
                elif field_name == 'hv_discharge_amps':
                    record['hv_discharge_amps'] = float(value)
                elif field_name == 'hv_battery_voltage_v':
                    record['hv_battery_voltage_v'] = float(value)
                elif field_name == 'battery_temp_f':
                    record['battery_temp_f'] = float(value)
            except (ValueError, TypeError) as e:
                logger.debug(f"Failed to parse {field_name}: {value} - {e}")
                continue

        return record if record['timestamp'] else None

    @classmethod
    def _parse_timestamp(cls, value: str) -> Optional[datetime]:
        """
        Parse timestamp from various Torque and international formats.

        Supports:
        - Unix epoch timestamps (seconds and milliseconds)
        - ISO 8601 formats with and without timezone
        - US date formats (MM/DD/YYYY)
        - European date formats (DD/MM/YYYY, DD.MM.YYYY)
        - Torque-specific formats (DD-Mon-YYYY)

        Args:
            value: Raw timestamp string

        Returns:
            Parsed datetime in UTC, or None if parsing failed
        """
        if not value or not value.strip():
            return None

        value = value.strip()

        # Common formats Torque uses, plus international formats
        formats = [
            # Torque formats
            '%d-%b-%Y %H:%M:%S.%f',    # 01-Jan-2024 12:30:45.123
            '%d-%b-%Y %H:%M:%S',        # 01-Jan-2024 12:30:45
            # ISO 8601 formats
            '%Y-%m-%d %H:%M:%S.%f',     # 2024-01-01 12:30:45.123
            '%Y-%m-%d %H:%M:%S',        # 2024-01-01 12:30:45
            '%Y-%m-%dT%H:%M:%S.%f',     # 2024-01-01T12:30:45.123
            '%Y-%m-%dT%H:%M:%S',        # 2024-01-01T12:30:45
            # US formats
            '%m/%d/%Y %H:%M:%S',        # 01/01/2024 12:30:45
            '%m/%d/%Y %I:%M:%S %p',     # 01/01/2024 12:30:45 PM
            '%m-%d-%Y %H:%M:%S',        # 01-01-2024 12:30:45
            # European formats
            '%d/%m/%Y %H:%M:%S',        # 01/01/2024 12:30:45 (European)
            '%d.%m.%Y %H:%M:%S',        # 01.01.2024 12:30:45 (European)
            '%d-%m-%Y %H:%M:%S',        # 01-01-2024 12:30:45 (European)
            # Text month formats
            '%b %d, %Y %H:%M:%S',       # Jan 01, 2024 12:30:45
            '%B %d, %Y %H:%M:%S',       # January 01, 2024 12:30:45
            # Date only (assume midnight)
            '%Y-%m-%d',                  # 2024-01-01
            '%m/%d/%Y',                  # 01/01/2024
            '%d/%m/%Y',                  # 01/01/2024 (European)
        ]

        # Try epoch timestamp (seconds or milliseconds)
        try:
            ts = float(value)
            if ts > 1e12:  # Milliseconds
                ts = ts / 1000
            if 1e9 < ts < 2e9:  # Valid Unix timestamp range (2001-2033)
                return datetime.fromtimestamp(ts, tz=timezone.utc)
        except (ValueError, TypeError):
            pass

        # Try ISO 8601 with timezone offset (e.g., 2024-01-01T12:30:45+05:00)
        try:
            # Handle 'Z' suffix for UTC
            if value.endswith('Z'):
                value_tz = value[:-1] + '+00:00'
            else:
                value_tz = value

            # Python 3.7+ can parse ISO 8601 with timezone
            dt = datetime.fromisoformat(value_tz)
            # Convert to UTC if it has timezone info
            if dt.tzinfo is not None:
                dt = dt.astimezone(timezone.utc)
            else:
                dt = dt.replace(tzinfo=timezone.utc)
            return dt
        except (ValueError, TypeError):
            pass

        # Try standard strptime formats
        for fmt in formats:
            try:
                dt = datetime.strptime(value, fmt)
                # Assume UTC if no timezone
                if dt.tzinfo is None:
                    dt = dt.replace(tzinfo=timezone.utc)
                return dt
            except ValueError:
                continue

        # Fallback: try dateutil parser for flexible parsing
        try:
            from dateutil import parser as dateutil_parser
            dt = dateutil_parser.parse(value, fuzzy=False)
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            else:
                dt = dt.astimezone(timezone.utc)
            return dt
        except (ImportError, ValueError, TypeError):
            pass

        logger.debug(f"Could not parse timestamp: {value}")
        return None
