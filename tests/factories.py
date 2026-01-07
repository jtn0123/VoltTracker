"""
Test Data Factories for VoltTracker

Provides factory classes to easily create test data with sensible defaults,
reducing boilerplate in tests and making them more maintainable.

Usage:
    # Create a trip with defaults
    trip = TripFactory.create()

    # Create with overrides
    trip = TripFactory.create(distance_miles=50.0, is_closed=True)

    # Create multiple instances
    trips = TripFactory.create_batch(5)
"""

import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional

from models import (
    BatteryHealthReading,
    ChargingSession,
    FuelEvent,
    TelemetryRaw,
    Trip,
    WeatherCache,
)


class BaseFactory:
    """Base factory with common functionality."""

    model = None

    @classmethod
    def create(cls, db_session=None, **kwargs):
        """Create and optionally persist an instance."""
        defaults = cls.get_defaults()
        defaults.update(kwargs)
        instance = cls.model(**defaults)

        if db_session:
            db_session.add(instance)
            db_session.commit()
            db_session.refresh(instance)

        return instance

    @classmethod
    def create_batch(cls, count: int, db_session=None, **kwargs):
        """Create multiple instances."""
        return [cls.create(db_session=db_session, **kwargs) for _ in range(count)]

    @classmethod
    def build(cls, **kwargs):
        """Build instance without persisting to database."""
        defaults = cls.get_defaults()
        defaults.update(kwargs)
        return cls.model(**defaults)

    @classmethod
    def get_defaults(cls) -> Dict[str, Any]:
        """Override in subclasses to provide default values."""
        raise NotImplementedError


class TripFactory(BaseFactory):
    """Factory for Trip instances."""

    model = Trip

    @classmethod
    def get_defaults(cls) -> Dict[str, Any]:
        session_id = uuid.uuid4()
        start_time = datetime.now(timezone.utc) - timedelta(hours=1)

        return {
            "session_id": session_id,
            "start_time": start_time,
            "end_time": start_time + timedelta(hours=1),
            "start_odometer": 50000.0,
            "end_odometer": 50025.0,
            "distance_miles": 25.0,
            "start_soc": 90.0,
            "soc_at_gas_transition": 20.0,
            "electric_miles": 20.0,
            "gas_miles": 5.0,
            "electric_kwh_used": 4.5,
            "kwh_per_mile": 0.225,
            "gas_mpg": 42.0,
            "ambient_temp_avg_f": 70.0,
            "is_closed": True,
        }

    @classmethod
    def create_electric_only(cls, db_session=None, **kwargs):
        """Create an electric-only trip."""
        defaults = {
            "gas_miles": 0.0,
            "electric_miles": kwargs.get("distance_miles", 25.0),
            "gas_mpg": None,
            "gas_mode_entered": False,
        }
        defaults.update(kwargs)
        return cls.create(db_session=db_session, **defaults)

    @classmethod
    def create_gas_only(cls, db_session=None, **kwargs):
        """Create a gas-only trip."""
        defaults = {
            "electric_miles": 0.0,
            "gas_miles": kwargs.get("distance_miles", 25.0),
            "start_soc": 15.0,
            "soc_at_gas_transition": 15.0,
            "electric_kwh_used": None,
            "kwh_per_mile": None,
            "gas_mode_entered": True,
        }
        defaults.update(kwargs)
        return cls.create(db_session=db_session, **defaults)

    @classmethod
    def create_open(cls, db_session=None, **kwargs):
        """Create an open (in-progress) trip."""
        defaults = {
            "end_time": None,
            "end_odometer": None,
            "is_closed": False,
        }
        defaults.update(kwargs)
        return cls.create(db_session=db_session, **defaults)


class TelemetryFactory(BaseFactory):
    """Factory for TelemetryRaw instances."""

    model = TelemetryRaw

    @classmethod
    def get_defaults(cls) -> Dict[str, Any]:
        return {
            "session_id": uuid.uuid4(),
            "timestamp": datetime.now(timezone.utc),
            "speed_mph": 45.0,
            "engine_rpm": 0,
            "state_of_charge": 80.0,
            "fuel_level_percent": 75.0,
            "odometer_miles": 50000.0,
            "ambient_temp_f": 70.0,
            "latitude": 37.7749,
            "longitude": -122.4194,
        }

    @classmethod
    def create_sequence(
        cls,
        count: int,
        session_id: Optional[uuid.UUID] = None,
        start_time: Optional[datetime] = None,
        interval_seconds: int = 60,
        db_session=None,
    ) -> List[TelemetryRaw]:
        """Create a sequence of telemetry points simulating a trip."""
        session_id = session_id or uuid.uuid4()
        start_time = start_time or datetime.now(timezone.utc)

        points = []
        for i in range(count):
            timestamp = start_time + timedelta(seconds=i * interval_seconds)
            point = cls.create(
                session_id=session_id,
                timestamp=timestamp,
                state_of_charge=max(15.0, 100.0 - (i * 2)),  # Drain SOC
                odometer_miles=50000.0 + (i * 0.5),
                speed_mph=40.0 + (i % 10),  # Vary speed
                db_session=db_session,
            )
            points.append(point)

        return points

    @classmethod
    def create_electric_mode(cls, db_session=None, **kwargs):
        """Create telemetry in electric mode."""
        defaults = {"engine_rpm": 0, "state_of_charge": 80.0}
        defaults.update(kwargs)
        return cls.create(db_session=db_session, **defaults)

    @classmethod
    def create_gas_mode(cls, db_session=None, **kwargs):
        """Create telemetry in gas mode."""
        defaults = {"engine_rpm": 1200, "state_of_charge": 15.0}
        defaults.update(kwargs)
        return cls.create(db_session=db_session, **defaults)

    @classmethod
    def create_charging(cls, db_session=None, **kwargs):
        """Create telemetry during charging."""
        defaults = {
            "speed_mph": 0.0,
            "charger_connected": True,
            "charger_power_kw": 6.6,
            "engine_rpm": 0,
        }
        defaults.update(kwargs)
        return cls.create(db_session=db_session, **defaults)


class ChargingSessionFactory(BaseFactory):
    """Factory for ChargingSession instances."""

    model = ChargingSession

    @classmethod
    def get_defaults(cls) -> Dict[str, Any]:
        start_time = datetime.now(timezone.utc) - timedelta(hours=4)

        return {
            "start_time": start_time,
            "end_time": start_time + timedelta(hours=4),
            "start_soc": 20.0,
            "end_soc": 95.0,
            "kwh_added": 13.8,
            "charge_type": "L2",
            "peak_power_kw": 6.8,
            "avg_power_kw": 6.6,
            "is_complete": True,
            "latitude": 37.7749,
            "longitude": -122.4194,
        }

    @classmethod
    def create_l1(cls, db_session=None, **kwargs):
        """Create L1 (120V) charging session."""
        defaults = {
            "charge_type": "L1",
            "peak_power_kw": 1.4,
            "avg_power_kw": 1.3,
        }
        defaults.update(kwargs)
        return cls.create(db_session=db_session, **defaults)

    @classmethod
    def create_l2(cls, db_session=None, **kwargs):
        """Create L2 (240V) charging session."""
        defaults = {
            "charge_type": "L2",
            "peak_power_kw": 6.8,
            "avg_power_kw": 6.6,
        }
        defaults.update(kwargs)
        return cls.create(db_session=db_session, **defaults)

    @classmethod
    def create_dcfc(cls, db_session=None, **kwargs):
        """Create DC fast charging session."""
        defaults = {
            "charge_type": "DCFC",
            "peak_power_kw": 50.0,
            "avg_power_kw": 45.0,
            "end_time": datetime.now(timezone.utc) - timedelta(minutes=30),
        }
        defaults.update(kwargs)
        return cls.create(db_session=db_session, **defaults)

    @classmethod
    def create_in_progress(cls, db_session=None, **kwargs):
        """Create an in-progress charging session."""
        defaults = {
            "end_time": None,
            "end_soc": None,
            "is_complete": False,
        }
        defaults.update(kwargs)
        return cls.create(db_session=db_session, **defaults)


class FuelEventFactory(BaseFactory):
    """Factory for FuelEvent instances."""

    model = FuelEvent

    @classmethod
    def get_defaults(cls) -> Dict[str, Any]:
        return {
            "timestamp": datetime.now(timezone.utc),
            "gallons": 8.5,
            "odometer_miles": 50000.0,
            "fuel_level_percent": 95.0,
            "cost_usd": 32.50,
            "location": "Gas Station",
        }


class BatteryHealthFactory(BaseFactory):
    """Factory for BatteryHealthReading instances."""

    model = BatteryHealthReading

    @classmethod
    def get_defaults(cls) -> Dict[str, Any]:
        return {
            "timestamp": datetime.now(timezone.utc),
            "capacity_kwh": 18.0,
            "normalized_capacity_kwh": 18.0,
            "soc_at_reading": 100.0,
            "ambient_temp_f": 70.0,
            "odometer_miles": 50000.0,
        }

    @classmethod
    def create_degradation_series(
        cls,
        months: int = 12,
        degradation_per_month: float = 0.05,
        db_session=None,
    ) -> List[BatteryHealthReading]:
        """Create a series showing battery degradation over time."""
        readings = []
        now = datetime.now(timezone.utc)

        for month in range(months):
            reading = cls.create(
                timestamp=now - timedelta(days=month * 30),
                capacity_kwh=18.4 - (month * degradation_per_month),
                normalized_capacity_kwh=18.4 - (month * degradation_per_month),
                odometer_miles=50000.0 + (month * 1000),
                db_session=db_session,
            )
            readings.append(reading)

        return readings


class WeatherCacheFactory(BaseFactory):
    """Factory for WeatherCache instances."""

    model = WeatherCache

    @classmethod
    def get_defaults(cls) -> Dict[str, Any]:
        return {
            "latitude": 37.7749,
            "longitude": -122.4194,
            "timestamp": datetime.now(timezone.utc),
            "temperature_f": 70.0,
            "precipitation_inch": 0.0,
            "wind_speed_mph": 10.0,
            "weather_code": 0,
            "cached_at": datetime.now(timezone.utc),
        }

    @classmethod
    def create_rainy(cls, db_session=None, **kwargs):
        """Create rainy weather."""
        defaults = {
            "precipitation_inch": 0.5,
            "weather_code": 61,  # Rain code
        }
        defaults.update(kwargs)
        return cls.create(db_session=db_session, **defaults)

    @classmethod
    def create_cold(cls, db_session=None, **kwargs):
        """Create cold weather."""
        defaults = {"temperature_f": 25.0}
        defaults.update(kwargs)
        return cls.create(db_session=db_session, **defaults)


# Convenience functions for common test scenarios

def create_complete_trip_with_telemetry(
    db_session,
    telemetry_points: int = 10,
    **trip_kwargs
) -> tuple[Trip, List[TelemetryRaw]]:
    """Create a complete trip with associated telemetry."""
    session_id = uuid.uuid4()
    start_time = datetime.now(timezone.utc) - timedelta(hours=1)

    # Create trip
    trip = TripFactory.create(
        db_session=db_session,
        session_id=session_id,
        start_time=start_time,
        **trip_kwargs
    )

    # Create telemetry
    telemetry = TelemetryFactory.create_sequence(
        count=telemetry_points,
        session_id=session_id,
        start_time=start_time,
        db_session=db_session,
    )

    return trip, telemetry


def create_charging_session_with_telemetry(
    db_session,
    telemetry_points: int = 24,
    **charging_kwargs
) -> tuple[ChargingSession, List[TelemetryRaw]]:
    """Create a charging session with telemetry curve."""
    start_time = datetime.now(timezone.utc) - timedelta(hours=4)

    # Create charging session
    session = ChargingSessionFactory.create(
        db_session=db_session,
        start_time=start_time,
        **charging_kwargs
    )

    # Create telemetry showing SOC increasing
    telemetry = []
    for i in range(telemetry_points):
        point = TelemetryFactory.create_charging(
            db_session=db_session,
            timestamp=start_time + timedelta(minutes=i * 10),
            state_of_charge=20.0 + (i * 3.125),  # 20% to 95%
            charger_power_kw=6.6,
        )
        telemetry.append(point)

    return session, telemetry
