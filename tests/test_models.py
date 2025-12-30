"""
Tests for SQLAlchemy models and serialization.
"""

import pytest
import sys
import os
import uuid
from datetime import datetime, timezone

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'receiver'))

from models import TelemetryRaw, Trip, FuelEvent, SocTransition


class TestTelemetryRawModel:
    """Tests for TelemetryRaw model."""

    def test_to_dict_all_fields(self, db_session):
        """Test to_dict with all fields populated."""
        session_id = uuid.uuid4()
        timestamp = datetime.now(timezone.utc)

        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=timestamp,
            latitude=37.7749,
            longitude=-122.4194,
            speed_mph=45.5,
            engine_rpm=0,
            throttle_position=15.0,
            coolant_temp_f=180.0,
            intake_air_temp_f=75.0,
            fuel_level_percent=75.5,
            fuel_remaining_gallons=7.0,
            state_of_charge=85.0,
            battery_voltage=12.6,
            ambient_temp_f=72.0,
            odometer_miles=50123.4,
            raw_data={'test': 'data'},
        )
        db_session.add(telemetry)
        db_session.commit()

        result = telemetry.to_dict()

        assert result['session_id'] == str(session_id)
        assert result['latitude'] == 37.7749
        assert result['longitude'] == -122.4194
        assert result['speed_mph'] == 45.5
        assert result['engine_rpm'] == 0
        assert result['state_of_charge'] == 85.0
        assert result['fuel_level_percent'] == 75.5
        assert result['odometer_miles'] == 50123.4

    def test_to_dict_null_fields(self, db_session):
        """Test to_dict handles None values correctly."""
        session_id = uuid.uuid4()
        timestamp = datetime.now(timezone.utc)

        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=timestamp,
            # All other fields are None
        )
        db_session.add(telemetry)
        db_session.commit()

        result = telemetry.to_dict()

        assert result['session_id'] == str(session_id)
        assert result['latitude'] is None
        assert result['longitude'] is None
        assert result['speed_mph'] is None
        assert result['engine_rpm'] is None
        assert result['state_of_charge'] is None

    def test_timestamp_formatting(self, db_session):
        """Test timestamp is formatted as ISO string."""
        session_id = uuid.uuid4()
        timestamp = datetime(2024, 6, 15, 10, 30, 0, tzinfo=timezone.utc)

        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=timestamp,
        )
        db_session.add(telemetry)
        db_session.commit()

        result = telemetry.to_dict()
        assert '2024-06-15' in result['timestamp']
        assert '10:30:00' in result['timestamp']


class TestTripModel:
    """Tests for Trip model."""

    def test_to_dict_all_fields(self, db_session):
        """Test to_dict with all fields populated."""
        session_id = uuid.uuid4()
        start_time = datetime.now(timezone.utc)
        end_time = datetime.now(timezone.utc)
        gas_entry_time = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=start_time,
            end_time=end_time,
            start_odometer=50000.0,
            end_odometer=50050.0,
            distance_miles=50.0,
            start_soc=100.0,
            soc_at_gas_transition=18.0,
            electric_miles=25.0,
            gas_mode_entered=True,
            gas_mode_entry_time=gas_entry_time,
            gas_miles=25.0,
            fuel_used_gallons=0.6,
            gas_mpg=41.7,
            fuel_level_at_gas_entry=80.0,
            fuel_level_at_end=73.5,
            ambient_temp_avg_f=72.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        result = trip.to_dict()

        assert result['session_id'] == str(session_id)
        assert result['distance_miles'] == 50.0
        assert result['electric_miles'] == 25.0
        assert result['gas_miles'] == 25.0
        assert result['gas_mpg'] == 41.7
        assert result['soc_at_gas_transition'] == 18.0
        assert result['gas_mode_entered'] is True
        assert result['is_closed'] is True

    def test_to_dict_electric_only_trip(self, db_session):
        """Test to_dict for electric-only trip."""
        session_id = uuid.uuid4()
        start_time = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=start_time,
            start_odometer=50000.0,
            end_odometer=50030.0,
            distance_miles=30.0,
            start_soc=100.0,
            electric_miles=30.0,
            gas_mode_entered=False,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        result = trip.to_dict()

        assert result['electric_miles'] == 30.0
        assert result['gas_miles'] is None
        assert result['gas_mpg'] is None
        assert result['gas_mode_entered'] is False
        assert result['soc_at_gas_transition'] is None

    def test_to_dict_open_trip(self, db_session):
        """Test to_dict for open/active trip."""
        session_id = uuid.uuid4()
        start_time = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=start_time,
            start_odometer=50000.0,
            is_closed=False,
        )
        db_session.add(trip)
        db_session.commit()

        result = trip.to_dict()

        assert result['is_closed'] is False
        assert result['end_time'] is None
        assert result['end_odometer'] is None
        assert result['distance_miles'] is None


class TestFuelEventModel:
    """Tests for FuelEvent model."""

    def test_to_dict_all_fields(self, db_session):
        """Test to_dict with all fields populated."""
        timestamp = datetime.now(timezone.utc)

        fuel_event = FuelEvent(
            timestamp=timestamp,
            odometer_miles=51000.0,
            gallons_added=7.5,
            fuel_level_before=25.0,
            fuel_level_after=85.0,
            price_per_gallon=3.49,
            total_cost=26.18,
            notes='Filled at Shell station',
        )
        db_session.add(fuel_event)
        db_session.commit()

        result = fuel_event.to_dict()

        assert result['odometer_miles'] == 51000.0
        assert result['gallons_added'] == 7.5
        assert result['fuel_level_before'] == 25.0
        assert result['fuel_level_after'] == 85.0
        assert result['price_per_gallon'] == 3.49
        assert result['total_cost'] == 26.18
        assert result['notes'] == 'Filled at Shell station'

    def test_to_dict_minimal_fields(self, db_session):
        """Test to_dict with only required fields."""
        timestamp = datetime.now(timezone.utc)

        fuel_event = FuelEvent(
            timestamp=timestamp,
            gallons_added=8.0,
        )
        db_session.add(fuel_event)
        db_session.commit()

        result = fuel_event.to_dict()

        assert result['gallons_added'] == 8.0
        assert result['price_per_gallon'] is None
        assert result['total_cost'] is None
        assert result['notes'] is None


class TestSocTransitionModel:
    """Tests for SocTransition model."""

    def test_to_dict_all_fields(self, db_session):
        """Test to_dict with all fields populated."""
        # First create a trip
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            is_closed=False,
        )
        db_session.add(trip)
        db_session.commit()

        timestamp = datetime.now(timezone.utc)
        soc_transition = SocTransition(
            trip_id=trip.id,
            timestamp=timestamp,
            soc_at_transition=17.5,
            ambient_temp_f=72.0,
            odometer_miles=50025.0,
        )
        db_session.add(soc_transition)
        db_session.commit()

        result = soc_transition.to_dict()

        assert result['trip_id'] == trip.id
        assert result['soc_at_transition'] == 17.5
        assert result['ambient_temp_f'] == 72.0
        assert result['odometer_miles'] == 50025.0

    def test_trip_relationship(self, db_session):
        """Test that SocTransition links to Trip correctly."""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            is_closed=False,
        )
        db_session.add(trip)
        db_session.commit()

        soc_transition = SocTransition(
            trip_id=trip.id,
            timestamp=datetime.now(timezone.utc),
            soc_at_transition=18.0,
        )
        db_session.add(soc_transition)
        db_session.commit()

        # Verify relationship
        assert soc_transition.trip.id == trip.id
        assert soc_transition in trip.soc_transitions

    def test_multiple_transitions_per_trip(self, db_session):
        """Test multiple SOC transitions for a single trip."""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            is_closed=False,
        )
        db_session.add(trip)
        db_session.commit()

        # Add multiple transitions (shouldn't happen in real use, but test anyway)
        for soc in [18.0, 17.5, 17.0]:
            transition = SocTransition(
                trip_id=trip.id,
                timestamp=datetime.now(timezone.utc),
                soc_at_transition=soc,
            )
            db_session.add(transition)
        db_session.commit()

        assert len(trip.soc_transitions) == 3


class TestGUIDType:
    """Tests for the custom GUID type."""

    def test_uuid_storage_and_retrieval(self, db_session):
        """Test that UUIDs are stored and retrieved correctly."""
        original_uuid = uuid.uuid4()

        telemetry = TelemetryRaw(
            session_id=original_uuid,
            timestamp=datetime.now(timezone.utc),
        )
        db_session.add(telemetry)
        db_session.commit()

        # Clear session and re-fetch
        db_session.expire_all()
        fetched = db_session.query(TelemetryRaw).filter_by(id=telemetry.id).first()

        assert fetched.session_id == original_uuid
        assert isinstance(fetched.session_id, uuid.UUID)

    def test_uuid_querying(self, db_session):
        """Test querying by UUID works correctly."""
        session_id = uuid.uuid4()

        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=datetime.now(timezone.utc),
        )
        db_session.add(telemetry)
        db_session.commit()

        # Query by UUID
        result = db_session.query(TelemetryRaw).filter_by(session_id=session_id).first()
        assert result is not None
        assert result.session_id == session_id


class TestJSONType:
    """Tests for the custom JSON type."""

    def test_json_storage_and_retrieval(self, db_session):
        """Test that JSON data is stored and retrieved correctly."""
        raw_data = {
            'kff1001': '45.5',
            'kc': '0',
            'k22005b': '85.0',
            'nested': {'key': 'value'},
            'list': [1, 2, 3],
        }

        telemetry = TelemetryRaw(
            session_id=uuid.uuid4(),
            timestamp=datetime.now(timezone.utc),
            raw_data=raw_data,
        )
        db_session.add(telemetry)
        db_session.commit()

        # Clear session and re-fetch
        db_session.expire_all()
        fetched = db_session.query(TelemetryRaw).filter_by(id=telemetry.id).first()

        assert fetched.raw_data == raw_data
        assert fetched.raw_data['kff1001'] == '45.5'
        assert fetched.raw_data['nested']['key'] == 'value'
        assert fetched.raw_data['list'] == [1, 2, 3]

    def test_null_json(self, db_session):
        """Test that null JSON is handled correctly."""
        telemetry = TelemetryRaw(
            session_id=uuid.uuid4(),
            timestamp=datetime.now(timezone.utc),
            raw_data=None,
        )
        db_session.add(telemetry)
        db_session.commit()

        db_session.expire_all()
        fetched = db_session.query(TelemetryRaw).filter_by(id=telemetry.id).first()

        assert fetched.raw_data is None
