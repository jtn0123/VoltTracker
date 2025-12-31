"""Parse Torque Pro POST data into structured telemetry."""

from datetime import datetime, timezone
from typing import Optional
import uuid
import logging

from receiver.config import Config

logger = logging.getLogger(__name__)


class TorqueParser:
    """
    Parses form-encoded POST data from Torque Pro Android app.

    Torque sends data with dynamic field names:
    - Standard fields: eml, v, session, id, time
    - PID fields: kXXXXX where XXXXX is the PID code

    Common PIDs:
    - kff1006: Latitude
    - kff1005: Longitude
    - kff1001: GPS Speed (mph)
    - kc or k0c: Engine RPM
    - k11: Throttle Position (%)
    - k5: Coolant Temp (Celsius)
    - kf: Intake Air Temp (Celsius)
    - k2f: Fuel Level (%)
    - k22002f: Volt-specific Fuel Level (%)
    - k22005b or k5b: State of Charge (%)
    - k42: Battery Voltage
    - kff1010: Ambient Air Temp
    - kff1271: Trip odometer
    """

    # PID mappings - lowercase keys for case-insensitive matching
    PID_MAP = {
        # GPS
        'kff1006': 'latitude',
        'kff1005': 'longitude',
        'kff1001': 'speed_mph',

        # Engine
        'kc': 'engine_rpm',
        'k0c': 'engine_rpm',
        'k11': 'throttle_position',

        # Temperatures (in Celsius from OBD, convert to F)
        'k5': 'coolant_temp_c',
        'k05': 'coolant_temp_c',
        'kf': 'intake_air_temp_c',
        'k0f': 'intake_air_temp_c',
        'kff1010': 'ambient_temp_c',

        # Fuel
        'k2f': 'fuel_level_percent',
        'k22002f': 'fuel_level_percent',  # Volt-specific

        # Battery/SOC
        'k22005b': 'state_of_charge',
        'k5b': 'state_of_charge',
        'k42': 'battery_voltage',

        # HV Battery (Volt-specific PIDs)
        'k220101': 'hv_battery_power_kw',  # HV Battery Power (kW)
        'k220104': 'hv_battery_current_a',  # HV Battery Current (A)
        'k220105': 'hv_battery_voltage_v',  # HV Battery Voltage (V)

        # Charging (Volt-specific PIDs)
        'k220130': 'charger_ac_power_kw',  # Charger AC Line Power (kW)
        'k220131': 'charger_connected',  # Charger Plug Present (0/1)

        # Odometer
        'kff1271': 'odometer_miles',
        'k21': 'odometer_km',  # Convert to miles if present
    }

    @classmethod
    def parse(cls, form_data: dict) -> dict:
        """
        Parse Torque Pro form data into structured telemetry.

        Args:
            form_data: Dictionary from request.form

        Returns:
            Dictionary with structured telemetry data
        """
        result = {
            'session_id': None,
            'timestamp': None,
            'latitude': None,
            'longitude': None,
            'speed_mph': None,
            'engine_rpm': None,
            'throttle_position': None,
            'coolant_temp_f': None,
            'intake_air_temp_f': None,
            'fuel_level_percent': None,
            'fuel_remaining_gallons': None,
            'state_of_charge': None,
            'battery_voltage': None,
            'ambient_temp_f': None,
            'odometer_miles': None,
            # HV Battery tracking
            'hv_battery_power_kw': None,
            'hv_battery_current_a': None,
            'hv_battery_voltage_v': None,
            # Charging status
            'charger_ac_power_kw': None,
            'charger_connected': None,
            'raw_data': dict(form_data),
        }

        # Parse session ID
        session_str = form_data.get('session', '')
        if session_str:
            try:
                # Torque sends session as a string, convert to UUID
                result['session_id'] = uuid.UUID(session_str) if '-' in session_str else uuid.uuid5(
                    uuid.NAMESPACE_OID, session_str
                )
            except (ValueError, AttributeError):
                result['session_id'] = uuid.uuid5(uuid.NAMESPACE_OID, session_str)
        else:
            result['session_id'] = uuid.uuid4()

        # Parse timestamp
        time_str = form_data.get('time', '')
        if time_str:
            try:
                # Torque sends time in milliseconds since epoch
                timestamp_ms = int(time_str)
                result['timestamp'] = datetime.fromtimestamp(
                    timestamp_ms / 1000, tz=timezone.utc
                )
            except (ValueError, TypeError):
                result['timestamp'] = datetime.now(timezone.utc)
        else:
            result['timestamp'] = datetime.now(timezone.utc)

        # Parse PID values
        temp_values = {}

        for key, value in form_data.items():
            key_lower = key.lower()

            if key_lower in cls.PID_MAP:
                field_name = cls.PID_MAP[key_lower]
                parsed_value = cls._parse_value(value)

                if parsed_value is not None:
                    temp_values[field_name] = parsed_value

        # Map parsed values to result
        if 'latitude' in temp_values:
            result['latitude'] = temp_values['latitude']
        if 'longitude' in temp_values:
            result['longitude'] = temp_values['longitude']
        if 'speed_mph' in temp_values:
            result['speed_mph'] = temp_values['speed_mph']
        if 'engine_rpm' in temp_values:
            result['engine_rpm'] = temp_values['engine_rpm']
        if 'throttle_position' in temp_values:
            result['throttle_position'] = temp_values['throttle_position']
        if 'fuel_level_percent' in temp_values:
            result['fuel_level_percent'] = temp_values['fuel_level_percent']
            # Calculate gallons remaining using configured tank capacity
            result['fuel_remaining_gallons'] = (
                temp_values['fuel_level_percent'] / 100 * Config.TANK_CAPACITY_GALLONS
            )
        if 'state_of_charge' in temp_values:
            result['state_of_charge'] = temp_values['state_of_charge']
        if 'battery_voltage' in temp_values:
            result['battery_voltage'] = temp_values['battery_voltage']

        # Convert temperatures from C to F
        if 'coolant_temp_c' in temp_values:
            result['coolant_temp_f'] = cls._celsius_to_fahrenheit(
                temp_values['coolant_temp_c']
            )
        if 'intake_air_temp_c' in temp_values:
            result['intake_air_temp_f'] = cls._celsius_to_fahrenheit(
                temp_values['intake_air_temp_c']
            )
        if 'ambient_temp_c' in temp_values:
            result['ambient_temp_f'] = cls._celsius_to_fahrenheit(
                temp_values['ambient_temp_c']
            )

        # Handle odometer
        if 'odometer_miles' in temp_values:
            result['odometer_miles'] = temp_values['odometer_miles']
        elif 'odometer_km' in temp_values:
            result['odometer_miles'] = temp_values['odometer_km'] * 0.621371

        # HV Battery tracking
        if 'hv_battery_power_kw' in temp_values:
            result['hv_battery_power_kw'] = temp_values['hv_battery_power_kw']
        if 'hv_battery_current_a' in temp_values:
            result['hv_battery_current_a'] = temp_values['hv_battery_current_a']
        if 'hv_battery_voltage_v' in temp_values:
            result['hv_battery_voltage_v'] = temp_values['hv_battery_voltage_v']

        # Charging status
        if 'charger_ac_power_kw' in temp_values:
            result['charger_ac_power_kw'] = temp_values['charger_ac_power_kw']
        if 'charger_connected' in temp_values:
            # Convert to boolean (0/1 from OBD)
            result['charger_connected'] = temp_values['charger_connected'] > 0

        return result

    @staticmethod
    def _parse_value(value: str) -> Optional[float]:
        """Parse a string value to float, handling empty/invalid values."""
        if not value or value.strip() == '':
            return None
        try:
            return float(value)
        except (ValueError, TypeError):
            return None

    @staticmethod
    def _celsius_to_fahrenheit(celsius: float) -> float:
        """Convert Celsius to Fahrenheit."""
        return (celsius * 9 / 5) + 32
