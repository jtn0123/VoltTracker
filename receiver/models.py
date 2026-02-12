import uuid as uuid_module


from config import Config
from sqlalchemy import (
    BigInteger,
    JSON,
    Boolean,
    Column,
    Date,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    TypeDecorator,
    UniqueConstraint,
    create_engine,
)
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import DeclarativeBase, relationship, sessionmaker  # type: ignore[attr-defined]
from utils.timezone import utc_now


class Base(DeclarativeBase):
    """Base class for all SQLAlchemy models using the modern DeclarativeBase pattern (C4)."""
    pass


class SerializableMixin:
    """Mixin that auto-generates to_dict() from column inspection (C2).

    Models can override to_dict() for computed fields or custom serialization.
    C16: Reviewed — overrides kept where they exclude fields (created_at, raw_data)
    or add computed values (duration_minutes, soc_gained, degradation_percent).
    """

    def to_dict(self):
        """Auto-generate dict from column inspection."""
        result = {}
        for col in self.__table__.columns:
            value = getattr(self, col.name)
            if hasattr(value, 'isoformat'):
                value = value.isoformat()
            elif isinstance(value, uuid_module.UUID):
                value = str(value)
            result[col.name] = value
        return result


# Custom UUID type that works with both PostgreSQL and SQLite
class GUID(TypeDecorator):
    """Platform-independent GUID type.

    Uses PostgreSQL's UUID type when available, otherwise stores as String(36).
    """

    impl = String(36)
    cache_ok = True

    def load_dialect_impl(self, dialect):
        if dialect.name == "postgresql":
            return dialect.type_descriptor(UUID(as_uuid=True))
        else:
            return dialect.type_descriptor(String(36))

    def process_bind_param(self, value, dialect):
        if value is None:
            return value
        elif dialect.name == "postgresql":
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
        if dialect.name == "postgresql":
            return dialect.type_descriptor(JSONB)
        else:
            return dialect.type_descriptor(JSON)


class TelemetryRaw(SerializableMixin, Base):
    """Raw telemetry data from Torque Pro."""

    __tablename__ = "telemetry_raw"
    __table_args__ = (
        # Composite index for common query pattern: telemetry for a session ordered by time
        Index("ix_telemetry_session_timestamp", "session_id", "timestamp"),
        # D31: Prevent duplicate telemetry points for the same session+timestamp
        UniqueConstraint("session_id", "timestamp", name="uq_telemetry_session_timestamp"),
    )

    # D30: BigInteger PK to handle high-volume telemetry (>2B rows over lifetime)
    # Use Integer variant on SQLite so autoincrement works (SQLite only auto-increments INTEGER PKs)
    id = Column(  # type: ignore[arg-type]
        BigInteger().with_variant(Integer, "sqlite"),
        primary_key=True, autoincrement=True,
    )
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
    hv_battery_power_kw = Column(Float)
    hv_battery_current_a = Column(Float)
    hv_battery_voltage_v = Column(Float)
    hv_discharge_amps = Column(Float)
    battery_temp_f = Column(Float)
    battery_coolant_temp_f = Column(Float)

    # Charging status (expanded)
    # charger_ac_power_kw: AC-side power from the onboard charger (what the EVSE delivers)
    # charger_power_kw: DC-side power going into the HV battery (after conversion losses)
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

    # Elevation (populated by elevation API during trip finalization)
    elevation_meters = Column(Float)

    # WARNING: raw_data stores the full Torque POST payload as JSONB. This can grow
    # significantly over time (each row ~1-2KB). At high upload rates (~1/sec during
    # trips), expect ~5-10MB/day. TODO: Implement a data retention policy to archive
    # or purge raw_data older than N months. Consider moving to a separate table or
    # using TimescaleDB compression (already enabled) to mitigate storage concerns.
    raw_data = Column(JSONType())
    created_at = Column(DateTime(timezone=True), default=utc_now)

    def to_dict(self):
        return {
            "id": self.id,
            "session_id": str(self.session_id),
            "timestamp": self.timestamp.isoformat() if self.timestamp else None,
            "latitude": self.latitude,
            "longitude": self.longitude,
            "speed_mph": self.speed_mph,
            "engine_rpm": self.engine_rpm,
            "throttle_position": self.throttle_position,
            "coolant_temp_f": self.coolant_temp_f,
            "intake_air_temp_f": self.intake_air_temp_f,
            "fuel_level_percent": self.fuel_level_percent,
            "fuel_remaining_gallons": self.fuel_remaining_gallons,
            "state_of_charge": self.state_of_charge,
            "battery_voltage": self.battery_voltage,
            "ambient_temp_f": self.ambient_temp_f,
            "odometer_miles": self.odometer_miles,
            # HV Battery
            "hv_battery_power_kw": self.hv_battery_power_kw,
            "hv_battery_current_a": self.hv_battery_current_a,
            "hv_battery_voltage_v": self.hv_battery_voltage_v,
            "hv_discharge_amps": self.hv_discharge_amps,
            "battery_temp_f": self.battery_temp_f,
            "battery_coolant_temp_f": self.battery_coolant_temp_f,
            # Charging
            "charger_ac_power_kw": self.charger_ac_power_kw,
            "charger_connected": self.charger_connected,
            "charger_status": self.charger_status,
            "charger_power_kw": self.charger_power_kw,
            "charger_ac_voltage": self.charger_ac_voltage,
            "charger_ac_current": self.charger_ac_current,
            "last_charge_wh": self.last_charge_wh,
            # Motor/Generator
            "motor_a_rpm": self.motor_a_rpm,
            "motor_b_rpm": self.motor_b_rpm,
            "generator_rpm": self.generator_rpm,
            "motor_temp_max_f": self.motor_temp_max_f,
            # Engine
            "engine_oil_temp_f": self.engine_oil_temp_f,
            "engine_torque_nm": self.engine_torque_nm,
            "engine_running": self.engine_running,
            "transmission_temp_f": self.transmission_temp_f,
            # Battery health
            "battery_capacity_kwh": self.battery_capacity_kwh,
            # Lifetime counters
            "lifetime_ev_miles": self.lifetime_ev_miles,
            "lifetime_gas_miles": self.lifetime_gas_miles,
            "dte_electric_miles": self.dte_electric_miles,
            "dte_gas_miles": self.dte_gas_miles,
        }


class Trip(SerializableMixin, Base):
    """Aggregated trip summaries."""

    __tablename__ = "trips"
    __table_args__ = (
        # Composite index for common query pattern: closed trips ordered by start_time
        Index("ix_trips_is_closed_start_time", "is_closed", "start_time"),
        # Composite index for gas mode queries by date
        Index("ix_trips_gas_mode_start_time", "gas_mode_entered", "start_time"),
        # Composite index for filtered trip listing (most common query)
        Index("ix_trips_closed_deleted_time", "is_closed", "deleted_at", "start_time"),
    )

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
    is_imported = Column(Boolean, default=False, index=True)  # True if imported from CSV
    deleted_at = Column(DateTime(timezone=True), nullable=True, index=True)  # Soft delete for imported trips
    created_at = Column(DateTime(timezone=True), default=utc_now)
    updated_at = Column(DateTime(timezone=True), default=utc_now, onupdate=utc_now)

    # Weather data (from Open-Meteo API)
    weather_temp_f = Column(Float)
    weather_precipitation_in = Column(Float)
    weather_wind_mph = Column(Float)
    weather_conditions = Column(String(50))
    weather_impact_factor = Column(Float)  # Estimated efficiency impact multiplier
    # Flagged if conditions were extreme (freezing, very hot, heavy rain, strong wind)
    extreme_weather = Column(Boolean, default=False)

    # Elevation data (from Open-Meteo Elevation API)
    elevation_start_m = Column(Float)  # Starting elevation in meters
    elevation_end_m = Column(Float)  # Ending elevation in meters
    elevation_gain_m = Column(Float)  # Total elevation gained (sum of climbs)
    elevation_loss_m = Column(Float)  # Total elevation lost (sum of descents)
    elevation_net_change_m = Column(Float)  # Net change (end - start)
    elevation_max_m = Column(Float)  # Maximum elevation during trip
    elevation_min_m = Column(Float)  # Minimum elevation during trip

    # Relationships
    soc_transitions = relationship("SocTransition", back_populates="trip")

    def to_dict(self):
        return {
            "id": self.id,
            "session_id": str(self.session_id),
            "start_time": self.start_time.isoformat() if self.start_time else None,
            "end_time": self.end_time.isoformat() if self.end_time else None,
            "start_odometer": self.start_odometer,
            "end_odometer": self.end_odometer,
            "distance_miles": self.distance_miles,
            "start_soc": self.start_soc,
            "soc_at_gas_transition": self.soc_at_gas_transition,
            "electric_miles": self.electric_miles,
            "electric_kwh_used": self.electric_kwh_used,
            "kwh_per_mile": self.kwh_per_mile,
            "gas_mode_entered": self.gas_mode_entered,
            "gas_mode_entry_time": self.gas_mode_entry_time.isoformat() if self.gas_mode_entry_time else None,
            "gas_miles": self.gas_miles,
            "fuel_used_gallons": self.fuel_used_gallons,
            "gas_mpg": self.gas_mpg,
            "ambient_temp_avg_f": self.ambient_temp_avg_f,
            "is_closed": self.is_closed,
            "is_imported": self.is_imported,
            "weather_temp_f": self.weather_temp_f,
            "weather_precipitation_in": self.weather_precipitation_in,
            "weather_wind_mph": self.weather_wind_mph,
            "weather_conditions": self.weather_conditions,
            "weather_impact_factor": self.weather_impact_factor,
            "extreme_weather": self.extreme_weather,
            "elevation_start_m": self.elevation_start_m,
            "elevation_end_m": self.elevation_end_m,
            "elevation_gain_m": self.elevation_gain_m,
            "elevation_loss_m": self.elevation_loss_m,
            "elevation_net_change_m": self.elevation_net_change_m,
            "elevation_max_m": self.elevation_max_m,
            "elevation_min_m": self.elevation_min_m,
        }


class FuelEvent(SerializableMixin, Base):
    """Refueling events for tank-based efficiency calculations."""

    __tablename__ = "fuel_events"

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
            "id": self.id,
            "timestamp": self.timestamp.isoformat() if self.timestamp else None,
            "odometer_miles": self.odometer_miles,
            "gallons_added": self.gallons_added,
            "fuel_level_before": self.fuel_level_before,
            "fuel_level_after": self.fuel_level_after,
            "price_per_gallon": self.price_per_gallon,
            "total_cost": self.total_cost,
            "notes": self.notes,
        }


class SocTransition(SerializableMixin, Base):
    """Records electric-to-gas transitions for SOC floor analysis."""

    __tablename__ = "soc_transitions"

    id = Column(Integer, primary_key=True)
    trip_id = Column(Integer, ForeignKey("trips.id"))
    timestamp = Column(DateTime(timezone=True), nullable=False, index=True)
    soc_at_transition = Column(Float)
    ambient_temp_f = Column(Float)
    odometer_miles = Column(Float)
    created_at = Column(DateTime(timezone=True), default=utc_now)

    # Relationships
    trip = relationship("Trip", back_populates="soc_transitions")

    def to_dict(self):
        return {
            "id": self.id,
            "trip_id": self.trip_id,
            "timestamp": self.timestamp.isoformat() if self.timestamp else None,
            "soc_at_transition": self.soc_at_transition,
            "ambient_temp_f": self.ambient_temp_f,
            "odometer_miles": self.odometer_miles,
        }


class ChargingSession(SerializableMixin, Base):
    """Tracks charging sessions for energy analysis."""

    __tablename__ = "charging_sessions"
    __table_args__ = (
        UniqueConstraint('start_time', name='uq_charging_session_start_time'),
        Index('ix_charging_sessions_is_complete_start_time', 'is_complete', 'start_time'),
    )

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
            "id": self.id,
            "start_time": self.start_time.isoformat() if self.start_time else None,
            "end_time": self.end_time.isoformat() if self.end_time else None,
            "start_soc": self.start_soc,
            "end_soc": self.end_soc,
            "kwh_added": self.kwh_added,
            "peak_power_kw": self.peak_power_kw,
            "avg_power_kw": self.avg_power_kw,
            "latitude": self.latitude,
            "longitude": self.longitude,
            "location_name": self.location_name,
            "charge_type": self.charge_type,
            "cost": self.cost,
            "cost_per_kwh": self.cost_per_kwh,
            "electricity_rate": self.electricity_rate,
            "notes": self.notes,
            "is_complete": self.is_complete,
            "charging_curve": self.charging_curve,
            "duration_minutes": (
                (self.end_time - self.start_time).total_seconds() / 60 if self.end_time and self.start_time else None
            ),
            "soc_gained": (
                self.end_soc - self.start_soc if self.end_soc is not None and self.start_soc is not None else None
            ),
        }


class BatteryHealthReading(SerializableMixin, Base):
    """Tracks battery capacity over time for degradation analysis.

    The battery capacity PID (2241A3) reports the current usable capacity.
    By tracking this over time, we can detect degradation trends.
    Original Gen 2 Volt capacity: 18.4 kWh usable.
    """

    __tablename__ = "battery_health_readings"

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
            "id": self.id,
            "timestamp": self.timestamp.isoformat() if self.timestamp else None,
            "capacity_kwh": self.capacity_kwh,
            "normalized_capacity_kwh": self.normalized_capacity_kwh,
            "soc_at_reading": self.soc_at_reading,
            "ambient_temp_f": self.ambient_temp_f,
            "odometer_miles": self.odometer_miles,
        }

    @property
    def degradation_percent(self):
        """Calculate degradation percentage from original battery capacity."""
        if self.normalized_capacity_kwh:
            original = Config.BATTERY_ORIGINAL_CAPACITY_KWH
            return round((1 - (self.normalized_capacity_kwh / original)) * 100, 2)
        return None


class BatteryCellReading(SerializableMixin, Base):
    """Stores individual cell voltage readings from the HV battery pack.

    The Gen 2 Volt has 96 cells arranged in 3 sections/modules.
    This table stores snapshots of cell voltages for battery health analysis.
    """

    __tablename__ = "battery_cell_readings"

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
            "id": self.id,
            "timestamp": self.timestamp.isoformat() if self.timestamp else None,
            "cell_voltages": self.cell_voltages,
            "min_voltage": self.min_voltage,
            "max_voltage": self.max_voltage,
            "avg_voltage": self.avg_voltage,
            "voltage_delta": self.voltage_delta,
            "module1_avg": self.module1_avg,
            "module2_avg": self.module2_avg,
            "module3_avg": self.module3_avg,
            "ambient_temp_f": self.ambient_temp_f,
            "state_of_charge": self.state_of_charge,
            "is_charging": self.is_charging,
        }

    @classmethod
    def from_cell_voltages(cls, timestamp, cell_voltages, ambient_temp_f=None, state_of_charge=None, is_charging=False):
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


class WebVital(SerializableMixin, Base):
    """Stores Web Vitals performance metrics from the frontend."""

    __tablename__ = "web_vitals"
    __table_args__ = (
        Index("ix_web_vitals_name_timestamp", "name", "timestamp"),
        Index("ix_web_vitals_rating", "rating"),
    )

    id = Column(Integer, primary_key=True, autoincrement=True)
    timestamp = Column(DateTime(timezone=True), nullable=False, default=utc_now, index=True)

    # Metric details
    name = Column(String(50), nullable=False)
    value = Column(Float, nullable=False)
    rating = Column(String(20))

    # Context
    metric_id = Column(String(100))
    navigation_type = Column(String(50))
    url = Column(String(500))
    user_agent = Column(Text)

    # Metadata
    created_at = Column(DateTime(timezone=True), default=utc_now)

    def to_dict(self):
        """Convert to dictionary for JSON serialization."""
        return {
            "id": self.id,
            "timestamp": self.timestamp.isoformat() if self.timestamp else None,
            "name": self.name,
            "value": self.value,
            "rating": self.rating,
            "metric_id": self.metric_id,
            "navigation_type": self.navigation_type,
            "url": self.url,
            "created_at": self.created_at.isoformat() if self.created_at else None,
        }

    @classmethod
    def create_from_frontend(cls, data):
        """Create a WebVital record from frontend data."""
        from datetime import datetime

        timestamp = data.get("timestamp")
        if timestamp and isinstance(timestamp, str):
            try:
                timestamp = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
            except (ValueError, AttributeError):
                timestamp = utc_now()
        else:
            timestamp = utc_now()

        return cls(
            timestamp=timestamp,
            name=data.get("name"),
            value=data.get("value"),
            rating=data.get("rating"),
            metric_id=data.get("id"),
            navigation_type=data.get("navigationType"),
            url=data.get("url"),
            user_agent=data.get("userAgent"),
        )

    def __repr__(self):
        return f"<WebVital(name={self.name}, value={self.value}, rating={self.rating})>"


class MaintenanceRecord(SerializableMixin, Base):
    """Track maintenance items and predict when service is due."""

    __tablename__ = "maintenance_records"
    __table_args__ = (Index("ix_maintenance_type_date", "maintenance_type", "service_date"),)

    id = Column(Integer, primary_key=True, autoincrement=True)
    maintenance_type = Column(String(100), nullable=False)
    service_date = Column(DateTime(timezone=True), nullable=False, index=True)
    odometer_miles = Column(Float)
    engine_hours = Column(Float)  # For oil changes
    cost = Column(Float)
    location = Column(String(200))
    notes = Column(Text)
    next_due_date = Column(DateTime(timezone=True))
    next_due_miles = Column(Float)
    next_due_engine_hours = Column(Float)
    created_at = Column(DateTime(timezone=True), default=utc_now)
    updated_at = Column(DateTime(timezone=True), default=utc_now, onupdate=utc_now)

    def to_dict(self):
        """Convert to dictionary for JSON serialization."""
        return {
            "id": self.id,
            "maintenance_type": self.maintenance_type,
            "service_date": self.service_date.isoformat() if self.service_date else None,
            "odometer_miles": self.odometer_miles,
            "engine_hours": self.engine_hours,
            "cost": self.cost,
            "location": self.location,
            "notes": self.notes,
            "next_due_date": self.next_due_date.isoformat() if self.next_due_date else None,
            "next_due_miles": self.next_due_miles,
            "next_due_engine_hours": self.next_due_engine_hours,
        }


class Route(SerializableMixin, Base):
    """Common routes detected from GPS patterns."""

    __tablename__ = "routes"

    id = Column(Integer, primary_key=True, autoincrement=True)
    name = Column(String(200))
    start_lat = Column(Float, nullable=False)
    start_lon = Column(Float, nullable=False)
    end_lat = Column(Float, nullable=False)
    end_lon = Column(Float, nullable=False)
    trip_count = Column(Integer, default=1)
    avg_distance_miles = Column(Float)
    avg_efficiency_kwh_per_mile = Column(Float)
    avg_duration_minutes = Column(Float)
    best_efficiency = Column(Float)
    worst_efficiency = Column(Float)
    last_traveled = Column(DateTime(timezone=True))
    created_at = Column(DateTime(timezone=True), default=utc_now)

    # Elevation data
    elevation_profile = Column(JSONType())
    avg_elevation_gain_m = Column(Float)
    avg_elevation_loss_m = Column(Float)

    def to_dict(self):
        """Convert to dictionary for JSON serialization."""
        return {
            "id": self.id,
            "name": self.name,
            "start": {"lat": self.start_lat, "lon": self.start_lon},
            "end": {"lat": self.end_lat, "lon": self.end_lon},
            "trip_count": self.trip_count,
            "avg_distance_miles": self.avg_distance_miles,
            "avg_efficiency": self.avg_efficiency_kwh_per_mile,
            "avg_duration_minutes": self.avg_duration_minutes,
            "best_efficiency": self.best_efficiency,
            "worst_efficiency": self.worst_efficiency,
            "last_traveled": self.last_traveled.isoformat() if self.last_traveled else None,
            "avg_elevation_gain_m": self.avg_elevation_gain_m,
            "avg_elevation_loss_m": self.avg_elevation_loss_m,
        }


class WeatherCache(SerializableMixin, Base):
    """Persistent cache for weather API responses."""

    __tablename__ = "weather_cache"
    __table_args__ = (
        UniqueConstraint("latitude_key", "longitude_key", "timestamp_hour", name="uq_weather_cache_key"),
        Index("ix_weather_cache_lookup", "latitude_key", "longitude_key", "timestamp_hour"),
        Index("ix_weather_cache_fetched_at", "fetched_at"),
    )

    id = Column(Integer, primary_key=True, autoincrement=True)

    latitude_key = Column(Float, nullable=False)
    longitude_key = Column(Float, nullable=False)
    timestamp_hour = Column(String(16), nullable=False)

    temperature_f = Column(Float)
    precipitation_in = Column(Float)
    wind_speed_mph = Column(Float)
    weather_code = Column(Integer)
    conditions = Column(String(50))

    fetched_at = Column(DateTime(timezone=True), nullable=False, default=utc_now)
    api_source = Column(String(20))

    def to_dict(self):
        """Convert to weather data dict (same format as API response)."""
        return {
            "temperature_f": self.temperature_f,
            "precipitation_in": self.precipitation_in,
            "wind_speed_mph": self.wind_speed_mph,
            "weather_code": self.weather_code,
            "conditions": self.conditions,
            "timestamp": self.timestamp_hour,
            "is_cached": True,
            "cached_at": self.fetched_at.isoformat() if self.fetched_at else None,
        }

    @classmethod
    def create_cache_key(cls, latitude: float, longitude: float, timestamp_hour: str):
        """Create cache key components from coordinates and timestamp."""
        return (
            round(latitude, 2),
            round(longitude, 2),
            timestamp_hour
        )


class TripDailyStats(SerializableMixin, Base):
    """Daily aggregation of trip statistics for fast analytics."""

    __tablename__ = "trip_daily_stats"

    id = Column(Integer, primary_key=True, autoincrement=True)
    date = Column(Date, nullable=False, unique=True)

    total_trips = Column(Integer, default=0)
    ev_only_trips = Column(Integer, default=0)
    gas_mode_trips = Column(Integer, default=0)
    extreme_weather_trips = Column(Integer, default=0)

    total_distance_miles = Column(Float, default=0)
    total_electric_miles = Column(Float, default=0)
    total_gas_miles = Column(Float, default=0)
    avg_trip_distance = Column(Float)

    avg_kwh_per_mile = Column(Float)
    best_kwh_per_mile = Column(Float)
    worst_kwh_per_mile = Column(Float)
    avg_mpg = Column(Float)

    total_elevation_gain_m = Column(Float, default=0)
    avg_elevation_gain_m = Column(Float)

    avg_temp_f = Column(Float)
    min_temp_f = Column(Float)
    max_temp_f = Column(Float)
    avg_wind_mph = Column(Float)
    total_precipitation_in = Column(Float, default=0)

    avg_speed_mph = Column(Float)
    max_speed_mph = Column(Float)

    total_kwh_used = Column(Float, default=0)
    avg_weather_impact_factor = Column(Float)

    created_at = Column(DateTime(timezone=True), default=utc_now)
    updated_at = Column(DateTime(timezone=True), default=utc_now, onupdate=utc_now)

    def to_dict(self):
        """Convert to dictionary."""
        return {
            "date": self.date.isoformat() if self.date else None,
            "total_trips": self.total_trips,
            "ev_only_trips": self.ev_only_trips,
            "gas_mode_trips": self.gas_mode_trips,
            "extreme_weather_trips": self.extreme_weather_trips,
            "total_distance_miles": round(self.total_distance_miles, 1) if self.total_distance_miles else 0,
            "total_electric_miles": round(self.total_electric_miles, 1) if self.total_electric_miles else 0,
            "total_gas_miles": round(self.total_gas_miles, 1) if self.total_gas_miles else 0,
            "avg_trip_distance": round(self.avg_trip_distance, 1) if self.avg_trip_distance else None,
            "avg_kwh_per_mile": round(self.avg_kwh_per_mile, 3) if self.avg_kwh_per_mile else None,
            "best_kwh_per_mile": round(self.best_kwh_per_mile, 3) if self.best_kwh_per_mile else None,
            "worst_kwh_per_mile": round(self.worst_kwh_per_mile, 3) if self.worst_kwh_per_mile else None,
            "avg_mpg": round(self.avg_mpg, 1) if self.avg_mpg else None,
            "total_elevation_gain_m": round(self.total_elevation_gain_m, 0) if self.total_elevation_gain_m else 0,
            "avg_elevation_gain_m": round(self.avg_elevation_gain_m, 0) if self.avg_elevation_gain_m else None,
            "avg_temp_f": round(self.avg_temp_f, 1) if self.avg_temp_f else None,
            "min_temp_f": round(self.min_temp_f, 1) if self.min_temp_f else None,
            "max_temp_f": round(self.max_temp_f, 1) if self.max_temp_f else None,
            "avg_wind_mph": round(self.avg_wind_mph, 1) if self.avg_wind_mph else None,
            "total_precipitation_in": round(self.total_precipitation_in, 2) if self.total_precipitation_in else 0,
            "avg_speed_mph": round(self.avg_speed_mph, 1) if self.avg_speed_mph else None,
            "max_speed_mph": round(self.max_speed_mph, 1) if self.max_speed_mph else None,
            "total_kwh_used": round(self.total_kwh_used, 2) if self.total_kwh_used else 0,
            "avg_weather_impact_factor": (
                round(self.avg_weather_impact_factor, 2)
                if self.avg_weather_impact_factor else None
            ),
        }


class ChargingHourlyStats(SerializableMixin, Base):
    """Hourly aggregation of charging statistics."""

    __tablename__ = "charging_hourly_stats"

    id = Column(Integer, primary_key=True, autoincrement=True)
    hour_timestamp = Column(DateTime(timezone=True), nullable=False, unique=True)

    total_sessions = Column(Integer, default=0)
    l1_sessions = Column(Integer, default=0)
    l2_sessions = Column(Integer, default=0)
    dcfc_sessions = Column(Integer, default=0)
    completed_sessions = Column(Integer, default=0)

    total_kwh_added = Column(Float, default=0)
    avg_kwh_per_session = Column(Float)
    avg_peak_power_kw = Column(Float)
    avg_avg_power_kw = Column(Float)

    avg_start_soc = Column(Float)
    avg_end_soc = Column(Float)
    avg_soc_gained = Column(Float)

    avg_session_duration = Column(Float)
    total_charging_minutes = Column(Float, default=0)

    created_at = Column(DateTime(timezone=True), default=utc_now)
    updated_at = Column(DateTime(timezone=True), default=utc_now, onupdate=utc_now)

    def to_dict(self):
        """Convert to dictionary."""
        return {
            "hour": self.hour_timestamp.isoformat() if self.hour_timestamp else None,
            "total_sessions": self.total_sessions,
            "l1_sessions": self.l1_sessions,
            "l2_sessions": self.l2_sessions,
            "dcfc_sessions": self.dcfc_sessions,
            "completed_sessions": self.completed_sessions,
            "total_kwh_added": round(self.total_kwh_added, 2) if self.total_kwh_added else 0,
            "avg_kwh_per_session": round(self.avg_kwh_per_session, 2) if self.avg_kwh_per_session else None,
            "avg_peak_power_kw": round(self.avg_peak_power_kw, 1) if self.avg_peak_power_kw else None,
            "avg_avg_power_kw": round(self.avg_avg_power_kw, 1) if self.avg_avg_power_kw else None,
            "avg_start_soc": round(self.avg_start_soc, 1) if self.avg_start_soc else None,
            "avg_end_soc": round(self.avg_end_soc, 1) if self.avg_end_soc else None,
            "avg_soc_gained": round(self.avg_soc_gained, 1) if self.avg_soc_gained else None,
            "avg_session_duration": round(self.avg_session_duration, 1) if self.avg_session_duration else None,
            "total_charging_minutes": round(self.total_charging_minutes, 1) if self.total_charging_minutes else 0,
        }


class MonthlySummary(SerializableMixin, Base):
    """Monthly summary statistics for high-level overview."""

    __tablename__ = "monthly_summary"
    __table_args__ = (UniqueConstraint("year", "month", name="uq_monthly_year_month"),)

    id = Column(Integer, primary_key=True, autoincrement=True)
    year = Column(Integer, nullable=False)
    month = Column(Integer, nullable=False)

    total_trips = Column(Integer, default=0)
    total_distance_miles = Column(Float, default=0)
    total_electric_miles = Column(Float, default=0)
    total_gas_miles = Column(Float, default=0)
    electric_percentage = Column(Float)

    avg_kwh_per_mile = Column(Float)
    avg_mpg = Column(Float)
    total_kwh_used = Column(Float, default=0)
    total_gallons_used = Column(Float, default=0)

    total_charging_sessions = Column(Integer, default=0)
    total_kwh_charged = Column(Float, default=0)
    l1_sessions = Column(Integer, default=0)
    l2_sessions = Column(Integer, default=0)
    dcfc_sessions = Column(Integer, default=0)

    estimated_electricity_cost = Column(Float)
    estimated_gas_cost = Column(Float)

    co2_avoided_lbs = Column(Float)

    avg_temp_f = Column(Float)
    extreme_weather_trips = Column(Integer, default=0)

    created_at = Column(DateTime(timezone=True), default=utc_now)
    updated_at = Column(DateTime(timezone=True), default=utc_now, onupdate=utc_now)

    def to_dict(self):
        """Convert to dictionary."""
        return {
            "year": self.year,
            "month": self.month,
            "total_trips": self.total_trips,
            "total_distance_miles": round(self.total_distance_miles, 1) if self.total_distance_miles else 0,
            "total_electric_miles": round(self.total_electric_miles, 1) if self.total_electric_miles else 0,
            "total_gas_miles": round(self.total_gas_miles, 1) if self.total_gas_miles else 0,
            "electric_percentage": round(self.electric_percentage, 1) if self.electric_percentage else None,
            "avg_kwh_per_mile": round(self.avg_kwh_per_mile, 3) if self.avg_kwh_per_mile else None,
            "avg_mpg": round(self.avg_mpg, 1) if self.avg_mpg else None,
            "total_kwh_used": round(self.total_kwh_used, 2) if self.total_kwh_used else 0,
            "total_gallons_used": round(self.total_gallons_used, 2) if self.total_gallons_used else 0,
            "total_charging_sessions": self.total_charging_sessions,
            "total_kwh_charged": round(self.total_kwh_charged, 2) if self.total_kwh_charged else 0,
            "l1_sessions": self.l1_sessions,
            "l2_sessions": self.l2_sessions,
            "dcfc_sessions": self.dcfc_sessions,
            "estimated_electricity_cost": (
                round(self.estimated_electricity_cost, 2)
                if self.estimated_electricity_cost else None
            ),
            "estimated_gas_cost": (
                round(self.estimated_gas_cost, 2)
                if self.estimated_gas_cost else None
            ),
            "co2_avoided_lbs": round(self.co2_avoided_lbs, 1) if self.co2_avoided_lbs else None,
            "avg_temp_f": round(self.avg_temp_f, 1) if self.avg_temp_f else None,
            "extreme_weather_trips": self.extreme_weather_trips,
        }


class AuditLog(SerializableMixin, Base):
    """Audit log for tracking data changes and operations."""

    __tablename__ = "audit_logs"
    __table_args__ = (
        Index("ix_audit_logs_entity", "entity_type", "entity_id"),
        Index("ix_audit_logs_action", "action", "timestamp"),
        Index("ix_audit_logs_timestamp", "timestamp"),
        Index("ix_audit_logs_ip", "ip_address"),
    )

    id = Column(Integer, primary_key=True, autoincrement=True)

    entity_type = Column(String(50), nullable=False)
    entity_id = Column(String(50), nullable=False)
    action = Column(String(20), nullable=False)

    old_data = Column(JSON)
    new_data = Column(JSON)
    details = Column(Text)

    user_id = Column(String(50))
    username = Column(String(100))
    ip_address = Column(String(50))
    user_agent = Column(Text)

    timestamp = Column(DateTime(timezone=True), nullable=False, default=utc_now)

    def to_dict(self):
        """Convert to dictionary."""
        return {
            "id": self.id,
            "entity_type": self.entity_type,
            "entity_id": self.entity_id,
            "action": self.action,
            "old_data": self.old_data,
            "new_data": self.new_data,
            "details": self.details,
            "user_id": self.user_id,
            "username": self.username,
            "ip_address": self.ip_address,
            "user_agent": self.user_agent,
            "timestamp": self.timestamp.isoformat() if self.timestamp else None,
        }

    def save(self, db):
        """Save audit log to database.

        R14: Does NOT commit — callers manage their own transactions.
        This avoids premature commits that could break caller's transaction scope.
        """
        db.add(self)
        db.flush()


class Settings(Base):
    """
    Key-value settings store for user-configurable preferences.

    Stores application settings like electricity cost, gas price,
    battery capacity, and distance units.
    """

    __tablename__ = "settings"

    id = Column(Integer, primary_key=True, autoincrement=True)
    key = Column(String(100), unique=True, nullable=False, index=True)
    value = Column(Text, nullable=False)
    updated_at = Column(DateTime(timezone=True), default=utc_now, onupdate=utc_now)

    def __repr__(self):
        return f"<Settings {self.key}={self.value}>"


class CsvImport(SerializableMixin, Base):
    """Track every CSV import attempt for audit trail and duplicate detection."""

    __tablename__ = "csv_imports"

    id = Column(Integer, primary_key=True, autoincrement=True)
    import_code = Column(String(20), unique=True, nullable=False)
    filename = Column(String(255), nullable=False)
    file_hash = Column(String(64), nullable=False, unique=True)
    file_size_bytes = Column(Integer, nullable=False)

    status = Column(String(20), nullable=False)
    failure_reason = Column(String(100))
    failure_details = Column(JSONType())
    suggestion = Column(Text)

    total_rows = Column(Integer, default=0)
    parsed_rows = Column(Integer, default=0)
    skipped_rows = Column(Integer, default=0)
    duplicate_rows = Column(Integer, default=0)

    columns_detected = Column(JSONType())
    columns_mapped = Column(JSONType())
    timestamp_range_start = Column(DateTime(timezone=True))
    timestamp_range_end = Column(DateTime(timezone=True))

    trip_id = Column(Integer, ForeignKey("trips.id", ondelete="SET NULL"), nullable=True)
    session_id = Column(String(36))

    created_at = Column(DateTime(timezone=True), default=utc_now)

    def to_dict(self):
        """Convert to dictionary for JSON serialization."""
        return {
            "id": self.id,
            "import_code": self.import_code,
            "filename": self.filename,
            "file_hash": self.file_hash,
            "file_size_bytes": self.file_size_bytes,
            "status": self.status,
            "failure_reason": self.failure_reason,
            "failure_details": self.failure_details,
            "suggestion": self.suggestion,
            "total_rows": self.total_rows,
            "parsed_rows": self.parsed_rows,
            "skipped_rows": self.skipped_rows,
            "duplicate_rows": self.duplicate_rows,
            "columns_detected": self.columns_detected,
            "columns_mapped": self.columns_mapped,
            "timestamp_range_start": self.timestamp_range_start.isoformat() if self.timestamp_range_start else None,
            "timestamp_range_end": self.timestamp_range_end.isoformat() if self.timestamp_range_end else None,
            "trip_id": self.trip_id,
            "session_id": self.session_id,
            "created_at": self.created_at.isoformat() if self.created_at else None,
        }

    @property
    def reportable(self):
        """Generate copy-pasteable string for error reporting."""
        parts = [self.import_code, self.status.upper()]
        if self.failure_reason:
            parts.append(self.failure_reason)
        parts.append(f"{self.parsed_rows}/{self.total_rows} rows")
        if self.trip_id:
            parts.append(f"trip_id={self.trip_id}")
        return " | ".join(parts)


def get_engine(database_url):
    """
    Create database engine with proper connection pooling.

    Note: Connection pooling parameters only apply to PostgreSQL.
    SQLite uses StaticPool for in-memory databases to share state across connections.
    """
    from sqlalchemy.pool import StaticPool

    # Base configuration
    config = {
        "echo_pool": False,  # Set to True for debugging
    }

    # Handle SQLite in-memory database (used in tests)
    if database_url == "sqlite:///:memory:":
        config.update(
            {
                "connect_args": {"check_same_thread": False},
                "poolclass": StaticPool,  # Share in-memory DB across connections
            }
        )
    # Only add pooling parameters for PostgreSQL (not SQLite)
    elif not database_url.startswith("sqlite"):
        config.update(
            {
                "pool_pre_ping": True,  # Verify connections before use
                "pool_size": 10,  # Max connections in pool
                "max_overflow": 20,  # Allow 20 additional connections
                "pool_recycle": 3600,  # Recycle connections after 1 hour
                "pool_timeout": 30,  # Wait up to 30s for a connection
            }
        )

    return create_engine(database_url, **config)


def get_session(engine):
    """Create database session."""
    Session = sessionmaker(bind=engine)
    return Session()
