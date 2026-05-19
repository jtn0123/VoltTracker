"""Parse Torque Pro POST data into structured telemetry."""

import logging
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, Optional

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
        "kff1006": "latitude",
        "kff1005": "longitude",
        "kff1001": "speed_mph",
        # Engine
        "kc": "engine_rpm",
        "k0c": "engine_rpm",
        "k11": "throttle_position",
        # Temperatures (in Celsius from OBD, convert to F)
        "k5": "coolant_temp_c",
        "k05": "coolant_temp_c",
        "kf": "intake_air_temp_c",
        "k0f": "intake_air_temp_c",
        "kff1010": "ambient_temp_c",
        "k22004f": "ambient_temp_c",  # Volt-specific ambient temp
        # Fuel
        "k2f": "fuel_level_percent",
        "k22002f": "fuel_level_percent",  # Volt-specific
        # Battery/SOC
        "k22005b": "state_of_charge",
        "k5b": "state_of_charge",
        "k42": "battery_voltage",
        # HV Battery PIDs
        "k22000b": "hv_battery_power_kw",  # Signed: positive means discharge, negative means charge
        "k22000a": "hv_battery_current_a",  # Signed current
        "k220009": "hv_battery_voltage_v",  # Pack voltage /100
        "k222429": "hv_battery_voltage_v",  # Alternative pack voltage
        "k222414": "hv_discharge_amps",  # HV discharge amps
        "k22434f": "battery_temp_c",  # Battery temperature sensor
        "k220038": "battery_coolant_temp_c",  # Battery coolant temp
        # Charging (Volt-specific PIDs) - CORRECTED
        "k220057": "charger_status",  # Charger status 0-10
        "k22006e": "charger_power_kw",  # Charger power kW
        "k224373": "charger_power_w",  # Charger power watts
        "k224368": "charger_ac_voltage",  # Charger AC voltage
        "k224369": "charger_ac_current",  # Charger AC current
        "k22436b": "charger_hv_voltage",  # Charger HV side voltage
        "k22436c": "charger_hv_current",  # Charger HV side current
        "k22437d": "last_charge_wh",  # Last charge energy Wh
        # Motor/Generator (NEW)
        "k220051": "motor_a_rpm",
        "k220052": "motor_b_rpm",
        "k220053": "generator_rpm",
        "k221570": "motor_temp_1_c",
        "k221571": "motor_temp_2_c",
        "k221572": "motor_temp_3_c",
        "k221573": "motor_temp_4_c",
        # Engine details (NEW)
        "k221154": "engine_oil_temp_c",
        "k22203f": "engine_torque_nm",
        "k221930": "engine_running",
        "k220049": "engine_coolant_temp_c",
        "k220047": "transmission_temp_c",
        # Battery Health (NEW)
        "k2241a3": "battery_capacity_kwh",
        # Lifetime Counters (NEW)
        "k224322": "lifetime_ev_miles",
        "k224323": "lifetime_gas_miles",
        "k224324": "lifetime_fuel_gal",
        "k224325": "lifetime_kwh",
        "k22430a": "dte_electric_miles",
        "k22430c": "dte_gas_miles",
        # Odometer
        "kff1271": "odometer_miles",
        "k21": "odometer_km",  # Convert to miles if present
        # TPMS - Experimental GM PIDs (may not work on Volt)
        # See: https://www.gm-volt.com/threads/obd2-and-vold-pids-for-android-torque-in-googledoc.45097/
        # Formula for pressure: ((A*1373/1000) * 0.145037738) for PSI
        # Formula for temp: (9/5*(A-40))+32 for °F
        "k22c901": "tpms_pressure_raw",  # May contain data for multiple wheels
        "k22c902": "tpms_temp_raw",  # May contain data for multiple wheels
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
            "session_id": None,
            "timestamp": None,
            "latitude": None,
            "longitude": None,
            "speed_mph": None,
            "engine_rpm": None,
            "throttle_position": None,
            "coolant_temp_f": None,
            "intake_air_temp_f": None,
            "fuel_level_percent": None,
            "fuel_remaining_gallons": None,
            "state_of_charge": None,
            "battery_voltage": None,
            "ambient_temp_f": None,
            "odometer_miles": None,
            # HV Battery tracking
            "hv_battery_power_kw": None,
            "hv_battery_current_a": None,
            "hv_battery_voltage_v": None,
            "hv_discharge_amps": None,
            "battery_temp_f": None,
            "battery_coolant_temp_f": None,
            # Charging status (expanded)
            "charger_status": None,
            "charger_power_kw": None,
            "charger_power_w": None,
            "charger_ac_voltage": None,
            "charger_ac_current": None,
            "charger_hv_voltage": None,
            "charger_hv_current": None,
            "last_charge_wh": None,
            # Legacy charging fields (for compatibility)
            "charger_ac_power_kw": None,
            "charger_connected": None,
            # Motor/Generator
            "motor_a_rpm": None,
            "motor_b_rpm": None,
            "generator_rpm": None,
            "motor_temp_max_f": None,
            # Engine details
            "engine_oil_temp_f": None,
            "engine_torque_nm": None,
            "engine_running": None,
            "transmission_temp_f": None,
            # Battery health
            "battery_capacity_kwh": None,
            # Lifetime counters
            "lifetime_ev_miles": None,
            "lifetime_gas_miles": None,
            "lifetime_fuel_gal": None,
            "lifetime_kwh": None,
            "dte_electric_miles": None,
            "dte_gas_miles": None,
            # TPMS - Experimental (may not work on Volt)
            "tpms_pressure_raw": None,
            "tpms_temp_raw": None,
            # Raw data
            "raw_data": dict(form_data),
        }

        # Parse session ID
        session_str = form_data.get("session", "")
        if session_str:
            try:
                # Torque sends session as a string, convert to UUID
                result["session_id"] = (
                    uuid.UUID(session_str) if "-" in session_str else uuid.uuid5(uuid.NAMESPACE_OID, session_str)
                )
            except (ValueError, AttributeError):
                result["session_id"] = uuid.uuid5(uuid.NAMESPACE_OID, session_str)
        else:
            result["session_id"] = uuid.uuid4()

        # Parse timestamp
        time_str = form_data.get("time", "")
        if time_str:
            try:
                # Torque sends time in milliseconds since epoch.
                # Produce a naive UTC datetime to stay consistent with the
                # utc_now() fallback below (and the rest of the codebase,
                # which standardizes on naive UTC via utils.timezone).
                # A mismatch here causes "can't subtract offset-naive and
                # offset-aware datetimes" errors downstream.
                timestamp_ms = int(time_str)
                result["timestamp"] = datetime.fromtimestamp(
                    timestamp_ms / 1000, tz=timezone.utc
                ).replace(tzinfo=None)
            except (ValueError, TypeError):
                result["timestamp"] = utc_now()
        else:
            result["timestamp"] = utc_now()

        # Parse PID values
        temp_values = {}

        for key, value in form_data.items():
            key_lower = key.lower()

            if key_lower in cls.PID_MAP:
                field_name = cls.PID_MAP[key_lower]
                parsed_value = cls._parse_value(value)

                if parsed_value is not None:
                    temp_values[field_name] = parsed_value

        # C17: Map parsed PID values to result fields using declarative mappings + loop
        # Direct copy: PID field name -> result field name (1:1 mapping)
        DIRECT_FIELDS = [
            "latitude", "longitude", "speed_mph", "engine_rpm", "throttle_position",
            "state_of_charge", "battery_voltage", "fuel_level_percent",
            "hv_battery_power_kw", "hv_battery_current_a", "hv_battery_voltage_v",
            "hv_discharge_amps", "charger_ac_voltage", "charger_ac_current",
            "charger_hv_voltage", "charger_hv_current", "last_charge_wh",
            "motor_a_rpm", "motor_b_rpm", "generator_rpm",
            "engine_torque_nm", "battery_capacity_kwh",
            "lifetime_ev_miles", "lifetime_gas_miles", "lifetime_fuel_gal",
            "lifetime_kwh", "dte_electric_miles", "dte_gas_miles",
            "charger_status", "charger_power_kw", "charger_power_w",
            "odometer_miles",
        ]
        for field in DIRECT_FIELDS:
            if field in temp_values:
                result[field] = temp_values[field]

        # Celsius-to-Fahrenheit conversions: (source_c_key, result_f_key)
        TEMP_CONVERSIONS = [
            ("coolant_temp_c", "coolant_temp_f"),
            ("intake_air_temp_c", "intake_air_temp_f"),
            ("ambient_temp_c", "ambient_temp_f"),
            ("battery_temp_c", "battery_temp_f"),
            ("battery_coolant_temp_c", "battery_coolant_temp_f"),
            ("engine_oil_temp_c", "engine_oil_temp_f"),
            ("engine_coolant_temp_c", "coolant_temp_f"),  # Volt-specific overrides generic
            ("transmission_temp_c", "transmission_temp_f"),
        ]
        for src, dst in TEMP_CONVERSIONS:
            if src in temp_values:
                result[dst] = cls._celsius_to_fahrenheit(temp_values[src])

        # Special handling: fuel gallons from percent
        if "fuel_level_percent" in temp_values:
            from utils import fuel_percent_to_gallons
            result["fuel_remaining_gallons"] = fuel_percent_to_gallons(temp_values["fuel_level_percent"])

        # Odometer km fallback
        if result["odometer_miles"] is None and "odometer_km" in temp_values:
            result["odometer_miles"] = temp_values["odometer_km"] * 0.621371

        # Charger status → charger_connected
        if "charger_status" in temp_values:
            result["charger_connected"] = temp_values["charger_status"] > 0

        # Charger power legacy fields
        if "charger_power_kw" in temp_values:
            result["charger_ac_power_kw"] = temp_values["charger_power_kw"]
        if "charger_power_w" in temp_values and result["charger_power_kw"] is None:
            result["charger_power_kw"] = temp_values["charger_power_w"] / 1000
            result["charger_ac_power_kw"] = temp_values["charger_power_w"] / 1000

        # Motor temperatures — find max across all sensors
        motor_temps = [
            temp_values[k] for k in ["motor_temp_1_c", "motor_temp_2_c", "motor_temp_3_c", "motor_temp_4_c"]
            if k in temp_values and temp_values[k] is not None
        ]
        if motor_temps:
            result["motor_temp_max_f"] = cls._celsius_to_fahrenheit(max(motor_temps))

        # Engine running boolean
        if "engine_running" in temp_values:
            result["engine_running"] = temp_values["engine_running"] > 0

        # TPMS — experimental (log when data arrives)
        for tpms_key in ("tpms_pressure_raw", "tpms_temp_raw"):
            if tpms_key in temp_values:
                result[tpms_key] = temp_values[tpms_key]
                logger.info(f"TPMS data received (experimental): {tpms_key}={temp_values[tpms_key]}")

        return result

    @staticmethod
    def _parse_value(value: str) -> Optional[float]:
        """Parse a string value to float, handling empty/invalid values."""
        if not value or value.strip() == "":
            return None
        try:
            return float(value)
        except (ValueError, TypeError):
            return None

    @staticmethod
    def _celsius_to_fahrenheit(celsius: float) -> float:
        """Convert Celsius to Fahrenheit."""
        return (celsius * 9 / 5) + 32
