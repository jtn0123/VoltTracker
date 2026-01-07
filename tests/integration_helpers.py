"""
Integration Test Helpers for VoltTracker

Provides utilities for integration testing:
- End-to-end test scenarios
- Multi-component test helpers
- Database seeding and verification
- Workflow simulation

Usage:
    # Simulate a complete trip workflow
    scenario = TripScenario(client, db_session)
    scenario.start_trip()
    scenario.add_telemetry_points(10)
    scenario.end_trip()
    scenario.verify_trip_created()
"""

from datetime import datetime, timedelta, timezone
from typing import Dict, List, Optional
import uuid

from flask.testing import FlaskClient
from sqlalchemy.orm import Session

from models import ChargingSession, FuelEvent, TelemetryRaw, Trip
from tests.factories import (
    ChargingSessionFactory,
    FuelEventFactory,
    TelemetryFactory,
    TripFactory,
)
from tests.test_helpers import APITestHelper


# ============================================================================
# Integration Test Scenarios
# ============================================================================


class TripScenario:
    """Simulate a complete trip workflow for integration testing."""

    def __init__(self, client: FlaskClient, db_session: Session):
        self.client = client
        self.db_session = db_session
        self.api = APITestHelper(client)
        self.session_id = uuid.uuid4()
        self.start_time = datetime.now(timezone.utc)
        self.trip = None
        self.telemetry_points = []

    def start_trip(self, **kwargs) -> "TripScenario":
        """Start a new trip."""
        defaults = {
            "session_id": self.session_id,
            "start_time": self.start_time,
            "start_odometer": 50000.0,
            "start_soc": 90.0,
            "is_closed": False,
        }
        defaults.update(kwargs)

        self.trip = TripFactory.create(db_session=self.db_session, **defaults)
        return self

    def add_telemetry_points(
        self,
        count: int = 10,
        mode: str = "electric"
    ) -> "TripScenario":
        """Add telemetry points to the trip."""
        for i in range(count):
            timestamp = self.start_time + timedelta(minutes=i * 5)

            if mode == "electric":
                point = TelemetryFactory.create_electric_mode(
                    session_id=self.session_id,
                    timestamp=timestamp,
                    state_of_charge=max(15.0, 90.0 - (i * 3)),
                    odometer_miles=50000.0 + (i * 2),
                    db_session=self.db_session,
                )
            elif mode == "gas":
                point = TelemetryFactory.create_gas_mode(
                    session_id=self.session_id,
                    timestamp=timestamp,
                    state_of_charge=15.0,
                    odometer_miles=50000.0 + (i * 2),
                    db_session=self.db_session,
                )
            else:  # mixed
                if i < count // 2:
                    point = TelemetryFactory.create_electric_mode(
                        session_id=self.session_id,
                        timestamp=timestamp,
                        state_of_charge=max(15.0, 90.0 - (i * 6)),
                        odometer_miles=50000.0 + (i * 2),
                        db_session=self.db_session,
                    )
                else:
                    point = TelemetryFactory.create_gas_mode(
                        session_id=self.session_id,
                        timestamp=timestamp,
                        state_of_charge=15.0,
                        odometer_miles=50000.0 + (i * 2),
                        db_session=self.db_session,
                    )

            self.telemetry_points.append(point)

        return self

    def end_trip(self, **kwargs) -> "TripScenario":
        """End the trip."""
        if not self.trip:
            raise ValueError("Trip not started")

        defaults = {
            "end_time": datetime.now(timezone.utc),
            "end_odometer": 50050.0,
            "end_soc": 60.0,
            "is_closed": True,
        }
        defaults.update(kwargs)

        for key, value in defaults.items():
            setattr(self.trip, key, value)

        self.db_session.commit()
        return self

    def verify_trip_created(self) -> "TripScenario":
        """Verify trip was created correctly."""
        trip = self.db_session.query(Trip).filter(
            Trip.session_id == self.session_id
        ).first()

        assert trip is not None, "Trip not created in database"
        assert trip.is_closed == self.trip.is_closed
        return self

    def verify_telemetry_count(self, expected_count: int) -> "TripScenario":
        """Verify telemetry point count."""
        count = self.db_session.query(TelemetryRaw).filter(
            TelemetryRaw.session_id == self.session_id
        ).count()

        assert count == expected_count, (
            f"Expected {expected_count} telemetry points, found {count}"
        )
        return self

    def get_trip_via_api(self) -> Dict:
        """Fetch trip via API."""
        response = self.api.get(f"/api/trips/{self.trip.id}")
        return response


class ChargingScenario:
    """Simulate a complete charging workflow."""

    def __init__(self, client: FlaskClient, db_session: Session):
        self.client = client
        self.db_session = db_session
        self.api = APITestHelper(client)
        self.session = None
        self.telemetry_points = []
        self.start_time = datetime.now(timezone.utc)

    def start_charging(
        self,
        charge_type: str = "L2",
        start_soc: float = 20.0,
        **kwargs
    ) -> "ChargingScenario":
        """Start a charging session."""
        defaults = {
            "start_time": self.start_time,
            "start_soc": start_soc,
            "charge_type": charge_type,
            "is_complete": False,
        }
        defaults.update(kwargs)

        if charge_type == "L1":
            self.session = ChargingSessionFactory.create_l1(
                db_session=self.db_session,
                **defaults
            )
        elif charge_type == "L2":
            self.session = ChargingSessionFactory.create_l2(
                db_session=self.db_session,
                **defaults
            )
        elif charge_type == "DCFC":
            self.session = ChargingSessionFactory.create_dcfc(
                db_session=self.db_session,
                **defaults
            )

        return self

    def add_charging_telemetry(
        self,
        points: int = 10,
        target_soc: float = 90.0
    ) -> "ChargingScenario":
        """Add telemetry during charging."""
        start_soc = self.session.start_soc
        soc_per_point = (target_soc - start_soc) / points

        for i in range(points):
            timestamp = self.start_time + timedelta(minutes=i * 10)
            current_soc = start_soc + (soc_per_point * i)

            point = TelemetryFactory.create_charging(
                timestamp=timestamp,
                state_of_charge=current_soc,
                charger_power_kw=self.session.avg_power_kw,
                db_session=self.db_session,
            )
            self.telemetry_points.append(point)

        return self

    def complete_charging(self, end_soc: float = 95.0) -> "ChargingScenario":
        """Complete the charging session."""
        self.session.end_time = datetime.now(timezone.utc)
        self.session.end_soc = end_soc
        self.session.is_complete = True

        # Calculate kWh added
        soc_gained = end_soc - self.session.start_soc
        self.session.kwh_added = (soc_gained / 100.0) * 18.4  # Volt battery capacity

        self.db_session.commit()
        return self

    def verify_session_created(self) -> "ChargingScenario":
        """Verify charging session was created."""
        session = self.db_session.query(ChargingSession).filter(
            ChargingSession.id == self.session.id
        ).first()

        assert session is not None, "Charging session not created"
        return self


class FuelingScenario:
    """Simulate a fueling workflow."""

    def __init__(self, client: FlaskClient, db_session: Session):
        self.client = client
        self.db_session = db_session
        self.api = APITestHelper(client)
        self.fuel_event = None

    def add_fuel(
        self,
        gallons: float = 8.5,
        odometer: float = 50000.0,
        **kwargs
    ) -> "FuelingScenario":
        """Add a fuel event."""
        defaults = {
            "gallons": gallons,
            "odometer_miles": odometer,
            "fuel_level_percent": 95.0,
        }
        defaults.update(kwargs)

        self.fuel_event = FuelEventFactory.create(
            db_session=self.db_session,
            **defaults
        )
        return self

    def verify_fuel_event_created(self) -> "FuelingScenario":
        """Verify fuel event was created."""
        event = self.db_session.query(FuelEvent).filter(
            FuelEvent.id == self.fuel_event.id
        ).first()

        assert event is not None, "Fuel event not created"
        return self


# ============================================================================
# Complete Workflow Scenarios
# ============================================================================


class DailyDrivingScenario:
    """Simulate a complete day of driving."""

    def __init__(self, client: FlaskClient, db_session: Session):
        self.client = client
        self.db_session = db_session
        self.api = APITestHelper(client)
        self.trips = []
        self.charging_sessions = []

    def morning_commute(
        self,
        distance_miles: float = 25.0
    ) -> "DailyDrivingScenario":
        """Simulate morning commute (electric mode)."""
        scenario = TripScenario(self.client, self.db_session)
        scenario.start_trip(
            start_time=datetime.now(timezone.utc).replace(hour=8, minute=0)
        )
        scenario.add_telemetry_points(count=10, mode="electric")
        scenario.end_trip(
            end_odometer=50000.0 + distance_miles,
            end_soc=70.0,
        )

        self.trips.append(scenario.trip)
        return self

    def evening_commute(
        self,
        distance_miles: float = 25.0,
        use_gas: bool = False
    ) -> "DailyDrivingScenario":
        """Simulate evening commute."""
        mode = "gas" if use_gas else "electric"

        scenario = TripScenario(self.client, self.db_session)
        scenario.start_trip(
            start_time=datetime.now(timezone.utc).replace(hour=17, minute=0),
            start_odometer=50025.0,
            start_soc=70.0 if not use_gas else 15.0,
        )
        scenario.add_telemetry_points(count=10, mode=mode)
        scenario.end_trip(
            end_odometer=50025.0 + distance_miles,
            end_soc=50.0 if not use_gas else 15.0,
        )

        self.trips.append(scenario.trip)
        return self

    def overnight_charge(self) -> "DailyDrivingScenario":
        """Simulate overnight L2 charging."""
        scenario = ChargingScenario(self.client, self.db_session)
        scenario.start_charging(
            charge_type="L2",
            start_soc=50.0,
        )
        scenario.add_charging_telemetry(points=20, target_soc=95.0)
        scenario.complete_charging(end_soc=95.0)

        self.charging_sessions.append(scenario.session)
        return self

    def weekend_road_trip(
        self,
        distance_miles: float = 150.0
    ) -> "DailyDrivingScenario":
        """Simulate longer weekend road trip with gas usage."""
        scenario = TripScenario(self.client, self.db_session)
        scenario.start_trip(
            start_time=datetime.now(timezone.utc).replace(hour=10, minute=0),
            start_odometer=50100.0,
            start_soc=95.0,
        )
        scenario.add_telemetry_points(count=30, mode="mixed")
        scenario.end_trip(
            end_odometer=50100.0 + distance_miles,
            end_soc=15.0,
        )

        self.trips.append(scenario.trip)
        return self

    def verify_daily_stats(self) -> "DailyDrivingScenario":
        """Verify daily driving statistics."""
        total_trips = len(self.trips)
        total_charging = len(self.charging_sessions)

        assert total_trips > 0, "No trips recorded"
        print(f"Daily stats: {total_trips} trips, {total_charging} charging sessions")

        return self


# ============================================================================
# Database Seeding Utilities
# ============================================================================


class DatabaseSeeder:
    """Seed database with realistic test data."""

    def __init__(self, db_session: Session):
        self.db_session = db_session

    def seed_month_of_trips(
        self,
        trips_per_day: int = 2,
        days: int = 30
    ) -> List[Trip]:
        """Create a month worth of trip data."""
        trips = []
        current_date = datetime.now(timezone.utc) - timedelta(days=days)
        current_odometer = 50000.0

        for day in range(days):
            for trip_num in range(trips_per_day):
                trip_time = current_date + timedelta(
                    days=day,
                    hours=8 + (trip_num * 9)  # 8am and 5pm
                )

                distance = 25.0 + (trip_num * 5)  # Vary distance

                trip = TripFactory.create(
                    db_session=self.db_session,
                    start_time=trip_time,
                    end_time=trip_time + timedelta(hours=1),
                    start_odometer=current_odometer,
                    end_odometer=current_odometer + distance,
                    distance_miles=distance,
                )

                trips.append(trip)
                current_odometer += distance

        return trips

    def seed_charging_history(
        self,
        sessions: int = 30,
        days_back: int = 90
    ) -> List[ChargingSession]:
        """Create charging session history."""
        charging_sessions = []
        start_date = datetime.now(timezone.utc) - timedelta(days=days_back)

        for i in range(sessions):
            session_time = start_date + timedelta(
                days=i * (days_back / sessions)
            )

            charge_type = "L2" if i % 3 != 0 else "L1"

            session = ChargingSessionFactory.create(
                db_session=self.db_session,
                start_time=session_time,
                end_time=session_time + timedelta(hours=4 if charge_type == "L2" else 12),
                charge_type=charge_type,
            )

            charging_sessions.append(session)

        return charging_sessions

    def seed_fuel_events(
        self,
        events: int = 10,
        days_back: int = 90
    ) -> List[FuelEvent]:
        """Create fuel event history."""
        fuel_events = []
        start_date = datetime.now(timezone.utc) - timedelta(days=days_back)
        current_odometer = 50000.0

        for i in range(events):
            event_time = start_date + timedelta(
                days=i * (days_back / events)
            )

            event = FuelEventFactory.create(
                db_session=self.db_session,
                timestamp=event_time,
                odometer_miles=current_odometer,
                gallons=8.0 + (i % 3),  # Vary amount
            )

            fuel_events.append(event)
            current_odometer += 200.0  # Increment odometer

        return fuel_events

    def seed_complete_dataset(self) -> Dict:
        """Seed a complete, realistic dataset."""
        trips = self.seed_month_of_trips(trips_per_day=2, days=30)
        charging = self.seed_charging_history(sessions=30, days_back=90)
        fuel = self.seed_fuel_events(events=10, days_back=90)

        return {
            "trips": trips,
            "charging_sessions": charging,
            "fuel_events": fuel,
        }


# ============================================================================
# Verification Utilities
# ============================================================================


class DataVerifier:
    """Verify database state and data integrity."""

    def __init__(self, db_session: Session):
        self.db_session = db_session

    def verify_trip_telemetry_consistency(self, trip: Trip) -> bool:
        """Verify trip has consistent telemetry data."""
        telemetry = self.db_session.query(TelemetryRaw).filter(
            TelemetryRaw.session_id == trip.session_id
        ).all()

        if not telemetry:
            return False

        # Check timestamps are within trip timeframe
        for point in telemetry:
            if point.timestamp < trip.start_time:
                return False
            if trip.end_time and point.timestamp > trip.end_time:
                return False

        return True

    def verify_no_orphaned_telemetry(self) -> bool:
        """Verify all telemetry belongs to a trip."""
        orphaned = self.db_session.query(TelemetryRaw).outerjoin(
            Trip,
            TelemetryRaw.session_id == Trip.session_id
        ).filter(Trip.id.is_(None)).count()

        return orphaned == 0

    def verify_data_integrity(self) -> Dict[str, bool]:
        """Run all verification checks."""
        results = {
            "no_orphaned_telemetry": self.verify_no_orphaned_telemetry(),
        }

        return results
