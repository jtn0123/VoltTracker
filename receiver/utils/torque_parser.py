"""Parse Torque Pro POST data into structured telemetry."""

from datetime import datetime, timezone
from typing import Any, Dict, Optional
import uuid
import logging

from config import Config
from utils.timezone import utc_now

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
        'k22004f': 'ambient_temp_c',  # Volt-specific ambient temp

        # Fuel
        'k2f': 'fuel_level_percent',
        'k22002f': 'fuel_level_percent',  # Volt-specific

        # Battery/SOC
        'k22005b': 'state_of_charge',
        'k5b': 'state_of_charge',
        'k42': 'battery_voltage',

        # HV Battery (Volt-specific PIDs) - CORRECTED
        'k22000b': 'hv_battery_power_kw',  # Signed, positive=discharge, negative=charge
        'k22000a': 'hv_battery_current_a',  # Signed current
        'k220009': 'hv_battery_voltage_v',  # Pack voltage /100
        'k222429': 'hv_battery_voltage_v',  # Alternative pack voltage
        'k222414': 'hv_discharge_amps',     # HV discharge amps
        'k22434f': 'battery_temp_c',        # Battery temperature sensor
        'k220038': 'battery_coolant_temp_c',  # Battery coolant temp

        # Charging (Volt-specific PIDs) - CORRECTED
        'k220057': 'charger_status',        # Charger status 0-10
        'k22006e': 'charger_power_kw',      # Charger power kW
        'k224373': 'charger_power_w',       # Charger power watts
        'k224368': 'charger_ac_voltage',    # Charger AC voltage
        'k224369': 'charger_ac_current',    # Charger AC current
        'k22436b': 'charger_hv_voltage',    # Charger HV side voltage
        'k22436c': 'charger_hv_current',    # Charger HV side current
        'k22437d': 'last_charge_wh',        # Last charge energy Wh

        # Motor/Generator (NEW)
        'k220051': 'motor_a_rpm',
        'k220052': 'motor_b_rpm',
        'k220053': 'generator_rpm',
        'k221570': 'motor_temp_1_c',
        'k221571': 'motor_temp_2_c',
        'k221572': 'motor_temp_3_c',
        'k221573': 'motor_temp_4_c',

        # Engine details (NEW)
        'k221154': 'engine_oil_temp_c',
        'k22203f': 'engine_torque_nm',
        'k221930': 'engine_running',
        'k220049': 'engine_coolant_temp_c',
        'k220047': 'transmission_temp_c',

        # Battery Health (NEW)
        'k2241a3': 'battery_capacity_kwh',

        # Lifetime Counters (NEW)
        'k224322': 'lifetime_ev_miles',
        'k224323': 'lifetime_gas_miles',
        'k224324': 'lifetime_fuel_gal',
        'k224325': 'lifetime_kwh',
        'k22430a': 'dte_electric_miles',
        'k22430c': 'dte_gas_miles',

        # Odometer
        'kff1271': 'odometer_miles',
        'k21': 'odometer_km',  # Convert to miles if present
    }

    @classmethod
    def parse(cls, form_data: dict) -> Dict[str, Any]:
        """
        Parse Torque Pro form data into structured telemetry.

        Args:
            form_data: Dictionary from request.form

        Returns:
            Dictionary with structured telemetry data
        """
        result: Dict[str, Any] = {
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
            'hv_discharge_amps': None,
            'battery_temp_f': None,
            'battery_coolant_temp_f': None,
            # Charging status (expanded)
            'charger_status': None,
            'charger_power_kw': None,
            'charger_power_w': None,
            'charger_ac_voltage': None,
            'charger_ac_current': None,
            'charger_hv_voltage': None,
            'charger_hv_current': None,
            'last_charge_wh': None,
            # Legacy charging fields (for compatibility)
            'charger_ac_power_kw': None,
            'charger_connected': None,
            # Motor/Generator
            'motor_a_rpm': None,
            'motor_b_rpm': None,
            'generator_rpm': None,
            'motor_temp_max_f': None,
            # Engine details
            'engine_oil_temp_f': None,
            'engine_torque_nm': None,
            'engine_running': None,
            'transmission_temp_f': None,
            # Battery health
            'battery_capacity_kwh': None,
            # Lifetime counters
            'lifetime_ev_miles': None,
            'lifetime_gas_miles': None,
            'lifetime_fuel_gal': None,
            'lifetime_kwh': None,
            'dte_electric_miles': None,
            'dte_gas_miles': None,
            # Raw data
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
                result['timestamp'] = utc_now()
        else:
            result['timestamp'] = utc_now()

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
        if 'hv_discharge_amps' in temp_values:
            result['hv_discharge_amps'] = temp_values['hv_discharge_amps']
        if 'battery_temp_c' in temp_values:
            result['battery_temp_f'] = cls._celsius_to_fahrenheit(temp_values['battery_temp_c'])
        if 'battery_coolant_temp_c' in temp_values:
            result['battery_coolant_temp_f'] = cls._celsius_to_fahrenheit(temp_values['battery_coolant_temp_c'])

        # Charging status (expanded)
        if 'charger_status' in temp_values:
            result['charger_status'] = temp_values['charger_status']
            # Derive charger_connected from status (status > 0 means connected)
            result['charger_connected'] = temp_values['charger_status'] > 0
        if 'charger_power_kw' in temp_values:
            result['charger_power_kw'] = temp_values['charger_power_kw']
            result['charger_ac_power_kw'] = temp_values['charger_power_kw']  # Legacy field
        if 'charger_power_w' in temp_values:
            result['charger_power_w'] = temp_values['charger_power_w']
            # Also set kW version if not already set
            if result['charger_power_kw'] is None:
                result['charger_power_kw'] = temp_values['charger_power_w'] / 1000
                result['charger_ac_power_kw'] = temp_values['charger_power_w'] / 1000
        if 'charger_ac_voltage' in temp_values:
            result['charger_ac_voltage'] = temp_values['charger_ac_voltage']
        if 'charger_ac_current' in temp_values:
            result['charger_ac_current'] = temp_values['charger_ac_current']
        if 'charger_hv_voltage' in temp_values:
            result['charger_hv_voltage'] = temp_values['charger_hv_voltage']
        if 'charger_hv_current' in temp_values:
            result['charger_hv_current'] = temp_values['charger_hv_current']
        if 'last_charge_wh' in temp_values:
            result['last_charge_wh'] = temp_values['last_charge_wh']

        # Motor/Generator
        if 'motor_a_rpm' in temp_values:
            result['motor_a_rpm'] = temp_values['motor_a_rpm']
        if 'motor_b_rpm' in temp_values:
            result['motor_b_rpm'] = temp_values['motor_b_rpm']
        if 'generator_rpm' in temp_values:
            result['generator_rpm'] = temp_values['generator_rpm']

        # Motor temperatures - collect all and find max
        motor_temps = []
        for key in ['motor_temp_1_c', 'motor_temp_2_c', 'motor_temp_3_c', 'motor_temp_4_c']:
            if key in temp_values and temp_values[key] is not None:
                motor_temps.append(temp_values[key])
        if motor_temps:
            result['motor_temp_max_f'] = cls._celsius_to_fahrenheit(max(motor_temps))

        # Engine details
        if 'engine_oil_temp_c' in temp_values:
            result['engine_oil_temp_f'] = cls._celsius_to_fahrenheit(temp_values['engine_oil_temp_c'])
        if 'engine_torque_nm' in temp_values:
            result['engine_torque_nm'] = temp_values['engine_torque_nm']
        if 'engine_running' in temp_values:
            result['engine_running'] = temp_values['engine_running'] > 0
        if 'engine_coolant_temp_c' in temp_values:
            # Use Volt-specific coolant temp if available, overrides generic
            result['coolant_temp_f'] = cls._celsius_to_fahrenheit(temp_values['engine_coolant_temp_c'])
        if 'transmission_temp_c' in temp_values:
            result['transmission_temp_f'] = cls._celsius_to_fahrenheit(temp_values['transmission_temp_c'])

        # Battery health
        if 'battery_capacity_kwh' in temp_values:
            result['battery_capacity_kwh'] = temp_values['battery_capacity_kwh']

        # Lifetime counters
        if 'lifetime_ev_miles' in temp_values:
            result['lifetime_ev_miles'] = temp_values['lifetime_ev_miles']
        if 'lifetime_gas_miles' in temp_values:
            result['lifetime_gas_miles'] = temp_values['lifetime_gas_miles']
        if 'lifetime_fuel_gal' in temp_values:
            result['lifetime_fuel_gal'] = temp_values['lifetime_fuel_gal']
        if 'lifetime_kwh' in temp_values:
            result['lifetime_kwh'] = temp_values['lifetime_kwh']
        if 'dte_electric_miles' in temp_values:
            result['dte_electric_miles'] = temp_values['dte_electric_miles']
        if 'dte_gas_miles' in temp_values:
            result['dte_gas_miles'] = temp_values['dte_gas_miles']

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
