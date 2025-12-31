from datetime import datetime
from sqlalchemy import (
    Column, Integer, String, Float, Boolean,
    DateTime, ForeignKey, Text, create_engine, JSON
)
from sqlalchemy.dialects.postgresql import UUID, JSONB
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker, relationship
from sqlalchemy import TypeDecorator
import uuid as uuid_module

from config import Config
from utils.timezone import utc_now


Base = declarative_base()


# Custom UUID type that works with both PostgreSQL and SQLite
class GUID(TypeDecorator):
    """Platform-independent GUID type.

    Uses PostgreSQL's UUID type when available, otherwise stores as String(36).
    """
    impl = String(36)
    cache_ok = True

    def load_dialect_impl(self, dialect):
        if dialect.name == 'postgresql':
            return dialect.type_descriptor(UUID(as_uuid=True))
        else:
            return dialect.type_descriptor(String(36))

    def process_bind_param(self, value, dialect):
        if value is None:
            return value
        elif dialect.name == 'postgresql':
            return value
        else:
            return str(value)

    def process_result_value(self, value, dialect):
        if value is None:
            return value
        elif isinstance(value, uuid_module.UUID):
            return value
        else:
            return uuid_module.UUID(value)


# Custom JSON type that works with both PostgreSQL (JSONB) and SQLite (JSON)
class JSONType(TypeDecorator):
    """Platform-independent JSON type.

    Uses PostgreSQL's JSONB type when available, otherwise uses JSON.
    """
    impl = JSON
    cache_ok = True

    def load_dialect_impl(self, dialect):
        if dialect.name == 'postgresql':
            return dialect.type_descriptor(JSONB)
        else:
            return dialect.type_descriptor(JSON)


class TelemetryRaw(Base):
    """Raw telemetry data from Torque Pro."""

    __tablename__ = 'telemetry_raw'

    id = Column(Integer, primary_key=True, autoincrement=True)
    session_id = Column(GUID(), nullable=False, index=True)
    timestamp = Column(DateTime(timezone=True), nullable=False, index=True)
    latitude = Column(Float)
    longitude = Column(Float)
    speed_mph = Column(Float)
    engine_rpm = Column(Float, index=True)
    throttle_position = Column(Float)
    coolant_temp_f = Column(Float)
    intake_air_temp_f = Column(Float)
    fuel_level_percent = Column(Float)
    fuel_remaining_gallons = Column(Float)
    state_of_charge = Column(Float, index=True)
    battery_voltage = Column(Float)
    ambient_temp_f = Column(Float)
    odometer_miles = Column(Float)

    # HV Battery tracking for kWh calculations
    hv_battery_power_kw = Column(Float)  # Positive = discharging, negative = charging
    hv_battery_current_a = Column(Float)
    hv_battery_voltage_v = Column(Float)
    hv_discharge_amps = Column(Float)
    battery_temp_f = Column(Float)
    battery_coolant_temp_f = Column(Float)

    # Charging status (expanded)
    charger_ac_power_kw = Column(Float)
    charger_connected = Column(Boolean)
    charger_status = Column(Float)
    charger_power_kw = Column(Float)
    charger_power_w = Column(Float)
    charger_ac_voltage = Column(Float)
    charger_ac_current = Column(Float)
    charger_hv_voltage = Column(Float)
    charger_hv_current = Column(Float)
    last_charge_wh = Column(Float)

    # Motor/Generator
    motor_a_rpm = Column(Float)
    motor_b_rpm = Column(Float)
    generator_rpm = Column(Float)
    motor_temp_max_f = Column(Float)

    # Engine details
    engine_oil_temp_f = Column(Float)
    engine_torque_nm = Column(Float)
    engine_running = Column(Boolean)
    transmission_temp_f = Column(Float)

    # Battery health
    battery_capacity_kwh = Column(Float)

    # Lifetime counters
    lifetime_ev_miles = Column(Float)
    lifetime_gas_miles = Column(Float)
    lifetime_fuel_gal = Column(Float)
    lifetime_kwh = Column(Float)
    dte_electric_miles = Column(Float)
    dte_gas_miles = Column(Float)

    raw_data = Column(JSONType())
    created_at = Column(DateTime(timezone=True), default=utc_now)

    def to_dict(self):
        return {
            'id': self.id,
            'session_id': str(self.session_id),
            'timestamp': self.timestamp.isoformat() if self.timestamp else None,
            'latitude': self.latitude,
            'longitude': self.longitude,
            'speed_mph': self.speed_mph,
            'engine_rpm': self.engine_rpm,
            'throttle_position': self.throttle_position,
            'coolant_temp_f': self.coolant_temp_f,
            'intake_air_temp_f': self.intake_air_temp_f,
            'fuel_level_percent': self.fuel_level_percent,
            'fuel_remaining_gallons': self.fuel_remaining_gallons,
            'state_of_charge': self.state_of_charge,
            'battery_voltage': self.battery_voltage,
            'ambient_temp_f': self.ambient_temp_f,
            'odometer_miles': self.odometer_miles,
            # HV Battery
            'hv_battery_power_kw': self.hv_battery_power_kw,
            'hv_battery_current_a': self.hv_battery_current_a,
            'hv_battery_voltage_v': self.hv_battery_voltage_v,
            'hv_discharge_amps': self.hv_discharge_amps,
            'battery_temp_f': self.battery_temp_f,
            'battery_coolant_temp_f': self.battery_coolant_temp_f,
            # Charging
            'charger_ac_power_kw': self.charger_ac_power_kw,
            'charger_connected': self.charger_connected,
            'charger_status': self.charger_status,
            'charger_power_kw': self.charger_power_kw,
            'charger_ac_voltage': self.charger_ac_voltage,
            'charger_ac_current': self.charger_ac_current,
            'last_charge_wh': self.last_charge_wh,
            # Motor/Generator
            'motor_a_rpm': self.motor_a_rpm,
            'motor_b_rpm': self.motor_b_rpm,
            'generator_rpm': self.generator_rpm,
            'motor_temp_max_f': self.motor_temp_max_f,
            # Engine
            'engine_oil_temp_f': self.engine_oil_temp_f,
            'engine_torque_nm': self.engine_torque_nm,
            'engine_running': self.engine_running,
            'transmission_temp_f': self.transmission_temp_f,
            # Battery health
            'battery_capacity_kwh': self.battery_capacity_kwh,
            # Lifetime counters
            'lifetime_ev_miles': self.lifetime_ev_miles,
            'lifetime_gas_miles': self.lifetime_gas_miles,
            'dte_electric_miles': self.dte_electric_miles,
            'dte_gas_miles': self.dte_gas_miles,
        }


class Trip(Base):
    """Aggregated trip summaries."""

    __tablename__ = 'trips'

    id = Column(Integer, primary_key=True)
    session_id = Column(GUID(), unique=True, nullable=False)
    start_time = Column(DateTime(timezone=True), nullable=False, index=True)
    end_time = Column(DateTime(timezone=True))
    start_odometer = Column(Float)
    end_odometer = Column(Float)
    distance_miles = Column(Float)

    # Electric portion
    start_soc = Column(Float)
    soc_at_gas_transition = Column(Float)
    electric_miles = Column(Float)
    electric_kwh_used = Column(Float)  # Total kWh consumed during electric driving
    kwh_per_mile = Column(Float)  # Electric efficiency: kWh/mile

    # Gas portion
    gas_mode_entered = Column(Boolean, default=False, index=True)
    gas_mode_entry_time = Column(DateTime(timezone=True))
    gas_miles = Column(Float)
    fuel_used_gallons = Column(Float)
    gas_mpg = Column(Float)

    # Fuel levels for calculation
    fuel_level_at_gas_entry = Column(Float)
    fuel_level_at_end = Column(Float)

    # Metadata
    ambient_temp_avg_f = Column(Float)
    is_closed = Column(Boolean, default=False, index=True)
    created_at = Column(DateTime(timezone=True), default=utc_now)
    updated_at = Column(DateTime(timezone=True), default=utc_now, onupdate=utc_now)

    # Weather data (from Open-Meteo API)
    weather_temp_f = Column(Float)
    weather_precipitation_in = Column(Float)
    weather_wind_mph = Column(Float)
    weather_conditions = Column(String(50))
    weather_impact_factor = Column(Float)  # Estimated efficiency impact multiplier

    # Relationships
    soc_transitions = relationship('SocTransition', back_populates='trip')

    def to_dict(self):
        return {
            'id': self.id,
            'session_id': str(self.session_id),
            'start_time': self.start_time.isoformat() if self.start_time else None,
            'end_time': self.end_time.isoformat() if self.end_time else None,
            'start_odometer': self.start_odometer,
            'end_odometer': self.end_odometer,
            'distance_miles': self.distance_miles,
            'start_soc': self.start_soc,
            'soc_at_gas_transition': self.soc_at_gas_transition,
            'electric_miles': self.electric_miles,
            'electric_kwh_used': self.electric_kwh_used,
            'kwh_per_mile': self.kwh_per_mile,
            'gas_mode_entered': self.gas_mode_entered,
            'gas_mode_entry_time': self.gas_mode_entry_time.isoformat() if self.gas_mode_entry_time else None,
            'gas_miles': self.gas_miles,
            'fuel_used_gallons': self.fuel_used_gallons,
            'gas_mpg': self.gas_mpg,
            'ambient_temp_avg_f': self.ambient_temp_avg_f,
            'is_closed': self.is_closed,
            'weather_temp_f': self.weather_temp_f,
            'weather_precipitation_in': self.weather_precipitation_in,
            'weather_wind_mph': self.weather_wind_mph,
            'weather_conditions': self.weather_conditions,
            'weather_impact_factor': self.weather_impact_factor,
        }


class FuelEvent(Base):
    """Refueling events for tank-based efficiency calculations."""

    __tablename__ = 'fuel_events'

    id = Column(Integer, primary_key=True)
    timestamp = Column(DateTime(timezone=True), nullable=False, index=True)
    odometer_miles = Column(Float)
    gallons_added = Column(Float)
    fuel_level_before = Column(Float)
    fuel_level_after = Column(Float)
    price_per_gallon = Column(Float)
    total_cost = Column(Float)
    notes = Column(Text)
    created_at = Column(DateTime(timezone=True), default=utc_now)

    def to_dict(self):
        return {
            'id': self.id,
            'timestamp': self.timestamp.isoformat() if self.timestamp else None,
            'odometer_miles': self.odometer_miles,
            'gallons_added': self.gallons_added,
            'fuel_level_before': self.fuel_level_before,
            'fuel_level_after': self.fuel_level_after,
            'price_per_gallon': self.price_per_gallon,
            'total_cost': self.total_cost,
            'notes': self.notes,
        }


class SocTransition(Base):
    """Records electric-to-gas transitions for SOC floor analysis."""

    __tablename__ = 'soc_transitions'

    id = Column(Integer, primary_key=True)
    trip_id = Column(Integer, ForeignKey('trips.id'))
    timestamp = Column(DateTime(timezone=True), nullable=False, index=True)
    soc_at_transition = Column(Float)
    ambient_temp_f = Column(Float)
    odometer_miles = Column(Float)
    created_at = Column(DateTime(timezone=True), default=utc_now)

    # Relationships
    trip = relationship('Trip', back_populates='soc_transitions')

    def to_dict(self):
        return {
            'id': self.id,
            'trip_id': self.trip_id,
            'timestamp': self.timestamp.isoformat() if self.timestamp else None,
            'soc_at_transition': self.soc_at_transition,
            'ambient_temp_f': self.ambient_temp_f,
            'odometer_miles': self.odometer_miles,
        }


class ChargingSession(Base):
    """Tracks charging sessions for energy analysis."""

    __tablename__ = 'charging_sessions'

    id = Column(Integer, primary_key=True)
    start_time = Column(DateTime(timezone=True), nullable=False, index=True)
    end_time = Column(DateTime(timezone=True))
    start_soc = Column(Float)
    end_soc = Column(Float)

    # Energy tracking
    kwh_added = Column(Float)  # Total kWh added during session
    peak_power_kw = Column(Float)  # Maximum charging power observed
    avg_power_kw = Column(Float)  # Average charging power

    # Location (if available)
    latitude = Column(Float)
    longitude = Column(Float)
    location_name = Column(String(255))

    # Charging type
    charge_type = Column(String(50))  # 'L1', 'L2', 'DCFC'

    # Cost tracking (manual entry)
    cost = Column(Float)
    cost_per_kwh = Column(Float)
    electricity_rate = Column(Float)  # $/kWh rate used for auto-calculation
    notes = Column(Text)

    # Charging curve data (power readings over time)
    charging_curve = Column(JSONType())  # [{timestamp, power_kw, soc}, ...]

    # Status
    is_complete = Column(Boolean, default=False, index=True)
    created_at = Column(DateTime(timezone=True), default=utc_now)
    updated_at = Column(DateTime(timezone=True), default=utc_now, onupdate=utc_now)

    def to_dict(self):
        return {
            'id': self.id,
            'start_time': self.start_time.isoformat() if self.start_time else None,
            'end_time': self.end_time.isoformat() if self.end_time else None,
            'start_soc': self.start_soc,
            'end_soc': self.end_soc,
            'kwh_added': self.kwh_added,
            'peak_power_kw': self.peak_power_kw,
            'avg_power_kw': self.avg_power_kw,
            'latitude': self.latitude,
            'longitude': self.longitude,
            'location_name': self.location_name,
            'charge_type': self.charge_type,
            'cost': self.cost,
            'cost_per_kwh': self.cost_per_kwh,
            'electricity_rate': self.electricity_rate,
            'notes': self.notes,
            'is_complete': self.is_complete,
            'charging_curve': self.charging_curve,
            'duration_minutes': (
                (self.end_time - self.start_time).total_seconds() / 60
                if self.end_time and self.start_time else None
            ),
            'soc_gained': (
                self.end_soc - self.start_soc
                if self.end_soc and self.start_soc else None
            ),
        }


class BatteryHealthReading(Base):
    """Tracks battery capacity over time for degradation analysis.

    The battery capacity PID (2241A3) reports the current usable capacity.
    By tracking this over time, we can detect degradation trends.
    Original Gen 2 Volt capacity: 18.4 kWh usable.
    """

    __tablename__ = 'battery_health_readings'

    id = Column(Integer, primary_key=True)
    timestamp = Column(DateTime(timezone=True), nullable=False, index=True)

    # Raw capacity reading from OBD
    capacity_kwh = Column(Float)

    # Normalized to 100% SOC for accurate comparison
    normalized_capacity_kwh = Column(Float)

    # Context for the reading
    soc_at_reading = Column(Float)
    ambient_temp_f = Column(Float)
    odometer_miles = Column(Float)

    created_at = Column(DateTime(timezone=True), default=utc_now)

    def to_dict(self):
        return {
            'id': self.id,
            'timestamp': self.timestamp.isoformat() if self.timestamp else None,
            'capacity_kwh': self.capacity_kwh,
            'normalized_capacity_kwh': self.normalized_capacity_kwh,
            'soc_at_reading': self.soc_at_reading,
            'ambient_temp_f': self.ambient_temp_f,
            'odometer_miles': self.odometer_miles,
        }

    @property
    def degradation_percent(self):
        """Calculate degradation percentage from original battery capacity."""
        if self.normalized_capacity_kwh:
            original = Config.BATTERY_ORIGINAL_CAPACITY_KWH
            return round((1 - (self.normalized_capacity_kwh / original)) * 100, 2)
        return None


class BatteryCellReading(Base):
    """Stores individual cell voltage readings from the HV battery pack.

    The Gen 2 Volt has 96 cells arranged in 3 sections/modules.
    This table stores snapshots of cell voltages for battery health analysis.
    """

    __tablename__ = 'battery_cell_readings'

    id = Column(Integer, primary_key=True)
    timestamp = Column(DateTime(timezone=True), nullable=False, index=True)

    # Cell voltages stored as JSON array (96 values)
    # Format: [cell1_v, cell2_v, ..., cell96_v]
    cell_voltages = Column(JSONType())

    # Summary statistics (calculated at insert time for quick queries)
    min_voltage = Column(Float)
    max_voltage = Column(Float)
    avg_voltage = Column(Float)
    voltage_delta = Column(Float)  # max - min (imbalance indicator)

    # Module-level summaries (cells 1-32, 33-64, 65-96)
    module1_avg = Column(Float)
    module2_avg = Column(Float)
    module3_avg = Column(Float)

    # Environmental context
    ambient_temp_f = Column(Float)
    state_of_charge = Column(Float)
    is_charging = Column(Boolean, default=False)

    created_at = Column(DateTime(timezone=True), default=utc_now)

    def to_dict(self):
        return {
            'id': self.id,
            'timestamp': self.timestamp.isoformat() if self.timestamp else None,
            'cell_voltages': self.cell_voltages,
            'min_voltage': self.min_voltage,
            'max_voltage': self.max_voltage,
            'avg_voltage': self.avg_voltage,
            'voltage_delta': self.voltage_delta,
            'module1_avg': self.module1_avg,
            'module2_avg': self.module2_avg,
            'module3_avg': self.module3_avg,
            'ambient_temp_f': self.ambient_temp_f,
            'state_of_charge': self.state_of_charge,
            'is_charging': self.is_charging,
        }

    @classmethod
    def from_cell_voltages(cls, timestamp, cell_voltages, ambient_temp_f=None,
                           state_of_charge=None, is_charging=False):
        """Create a reading from a list of cell voltages with calculated stats."""
        if not cell_voltages or len(cell_voltages) == 0:
            return None

        import statistics

        valid_voltages = [v for v in cell_voltages if v is not None and v > 0]
        if not valid_voltages:
            return None

        min_v = min(valid_voltages)
        max_v = max(valid_voltages)
        avg_v = statistics.mean(valid_voltages)

        # Calculate module averages (assuming 96 cells, 32 per module)
        module1 = [v for v in cell_voltages[:32] if v is not None and v > 0]
        module2 = [v for v in cell_voltages[32:64] if v is not None and v > 0]
        module3 = [v for v in cell_voltages[64:] if v is not None and v > 0]

        return cls(
            timestamp=timestamp,
            cell_voltages=cell_voltages,
            min_voltage=round(min_v, 4),
            max_voltage=round(max_v, 4),
            avg_voltage=round(avg_v, 4),
            voltage_delta=round(max_v - min_v, 4),
            module1_avg=round(statistics.mean(module1), 4) if module1 else None,
            module2_avg=round(statistics.mean(module2), 4) if module2 else None,
            module3_avg=round(statistics.mean(module3), 4) if module3 else None,
            ambient_temp_f=ambient_temp_f,
            state_of_charge=state_of_charge,
            is_charging=is_charging,
        )


def get_engine(database_url):
    """Create database engine."""
    return create_engine(database_url, pool_pre_ping=True)


def get_session(engine):
    """Create database session."""
    Session = sessionmaker(bind=engine)
    return Session()
