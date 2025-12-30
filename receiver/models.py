from datetime import datetime
from sqlalchemy import (
    Column, Integer, BigInteger, String, Float, Boolean,
    DateTime, ForeignKey, Text, create_engine
)
from sqlalchemy.dialects.postgresql import UUID, JSONB
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker, relationship
import uuid

Base = declarative_base()


class TelemetryRaw(Base):
    """Raw telemetry data from Torque Pro."""

    __tablename__ = 'telemetry_raw'

    id = Column(BigInteger, primary_key=True)
    session_id = Column(UUID(as_uuid=True), nullable=False, index=True)
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
    raw_data = Column(JSONB)
    created_at = Column(DateTime(timezone=True), default=datetime.utcnow)

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
        }


class Trip(Base):
    """Aggregated trip summaries."""

    __tablename__ = 'trips'

    id = Column(Integer, primary_key=True)
    session_id = Column(UUID(as_uuid=True), unique=True, nullable=False)
    start_time = Column(DateTime(timezone=True), nullable=False, index=True)
    end_time = Column(DateTime(timezone=True))
    start_odometer = Column(Float)
    end_odometer = Column(Float)
    distance_miles = Column(Float)

    # Electric portion
    start_soc = Column(Float)
    soc_at_gas_transition = Column(Float)
    electric_miles = Column(Float)

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
    created_at = Column(DateTime(timezone=True), default=datetime.utcnow)
    updated_at = Column(DateTime(timezone=True), default=datetime.utcnow, onupdate=datetime.utcnow)

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
            'gas_mode_entered': self.gas_mode_entered,
            'gas_mode_entry_time': self.gas_mode_entry_time.isoformat() if self.gas_mode_entry_time else None,
            'gas_miles': self.gas_miles,
            'fuel_used_gallons': self.fuel_used_gallons,
            'gas_mpg': self.gas_mpg,
            'ambient_temp_avg_f': self.ambient_temp_avg_f,
            'is_closed': self.is_closed,
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
    created_at = Column(DateTime(timezone=True), default=datetime.utcnow)

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
    created_at = Column(DateTime(timezone=True), default=datetime.utcnow)

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


def get_engine(database_url):
    """Create database engine."""
    return create_engine(database_url, pool_pre_ping=True)


def get_session(engine):
    """Create database session."""
    Session = sessionmaker(bind=engine)
    return Session()
