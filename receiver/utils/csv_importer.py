"""CSV importer for Torque Pro log files."""

import csv
import io
import uuid
from datetime import datetime, timezone
from typing import List, Dict, Any, Optional, Tuple
import logging

from config import Config

logger = logging.getLogger(__name__)


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

        # Volt-specific
        'state of charge(%)': 'state_of_charge',
        'state of charge (%)': 'state_of_charge',
        'soc(%)': 'state_of_charge',
        'hv battery power(kw)': 'hv_battery_power_kw',
        'hv battery power (kw)': 'hv_battery_power_kw',

        # Temperature
        'ambient air temp(°f)': 'ambient_temp_f',
        'ambient air temp (°f)': 'ambient_temp_f',
        'ambient air temperature(°f)': 'ambient_temp_f',
        'intake air temp(°f)': 'intake_air_temp_f',
        'engine coolant temp(°f)': 'coolant_temp_f',

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
    def parse_csv(cls, csv_content: str) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
        """
        Parse Torque CSV content into telemetry records.

        Args:
            csv_content: Raw CSV file content as string

        Returns:
            Tuple of (list of telemetry dicts, stats dict)
        """
        records = []
        stats = {
            'total_rows': 0,
            'parsed_rows': 0,
            'skipped_rows': 0,
            'columns_found': [],
            'errors': []
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
                    records.append(record)
                    stats['parsed_rows'] += 1
                else:
                    stats['skipped_rows'] += 1
            except Exception as e:
                stats['skipped_rows'] += 1
                if len(stats['errors']) < 10:  # Limit error messages
                    stats['errors'].append(f"Row {row_num}: {str(e)}")

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
            except (ValueError, TypeError) as e:
                logger.debug(f"Failed to parse {field_name}: {value} - {e}")
                continue

        return record if record['timestamp'] else None

    @classmethod
    def _parse_timestamp(cls, value: str) -> Optional[datetime]:
        """Parse timestamp from various Torque formats."""

        # Common formats Torque uses
        formats = [
            '%d-%b-%Y %H:%M:%S.%f',    # 01-Jan-2024 12:30:45.123
            '%d-%b-%Y %H:%M:%S',        # 01-Jan-2024 12:30:45
            '%Y-%m-%d %H:%M:%S.%f',     # 2024-01-01 12:30:45.123
            '%Y-%m-%d %H:%M:%S',        # 2024-01-01 12:30:45
            '%Y-%m-%dT%H:%M:%S.%f',     # 2024-01-01T12:30:45.123
            '%Y-%m-%dT%H:%M:%S',        # 2024-01-01T12:30:45
            '%m/%d/%Y %H:%M:%S',        # 01/01/2024 12:30:45
            '%m/%d/%Y %I:%M:%S %p',     # 01/01/2024 12:30:45 PM
        ]

        # Try epoch timestamp (milliseconds)
        try:
            ts = float(value)
            if ts > 1e12:  # Milliseconds
                ts = ts / 1000
            if 1e9 < ts < 2e9:  # Valid Unix timestamp range
                return datetime.fromtimestamp(ts, tz=timezone.utc)
        except ValueError:
            pass

        # Try string formats
        for fmt in formats:
            try:
                dt = datetime.strptime(value.strip(), fmt)
                # Assume UTC if no timezone
                if dt.tzinfo is None:
                    dt = dt.replace(tzinfo=timezone.utc)
                return dt
            except ValueError:
                continue

        logger.debug(f"Could not parse timestamp: {value}")
        return None
