"""
Tests for SQLAlchemy models and serialization.
"""

import pytest
import sys
import os
import uuid
from datetime import datetime, timezone

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'receiver'))

from models import (
    TelemetryRaw, Trip, FuelEvent, SocTransition,
    ChargingSession, BatteryHealthReading, BatteryCellReading
)


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


class TestChargingSessionModel:
    """Tests for ChargingSession model."""

    def test_to_dict_all_fields(self, db_session):
        """All ChargingSession fields serialize correctly."""
        start_time = datetime.now(timezone.utc)
        end_time = start_time + timedelta(hours=2)

        session = ChargingSession(
            start_time=start_time,
            end_time=end_time,
            start_soc=30.0,
            end_soc=80.0,
            kwh_added=9.2,
            peak_power_kw=6.6,
            avg_power_kw=5.5,
            latitude=37.7749,
            longitude=-122.4194,
            location_name='Home Garage',
            charge_type='L2',
            cost=1.10,
            cost_per_kwh=0.12,
            notes='Full charge',
            is_complete=True,
        )
        db_session.add(session)
        db_session.commit()

        result = session.to_dict()

        assert result['start_soc'] == 30.0
        assert result['end_soc'] == 80.0
        assert result['kwh_added'] == 9.2
        assert result['peak_power_kw'] == 6.6
        assert result['charge_type'] == 'L2'
        assert result['location_name'] == 'Home Garage'
        assert result['is_complete'] is True

    def test_to_dict_null_times(self, db_session):
        """NULL start/end times handled in to_dict."""
        session = ChargingSession(
            start_time=datetime.now(timezone.utc),
            end_time=None,
            start_soc=30.0,
            is_complete=False,
        )
        db_session.add(session)
        db_session.commit()

        result = session.to_dict()

        assert result['end_time'] is None
        assert result['duration_minutes'] is None

    def test_duration_minutes_calculation(self, db_session):
        """duration_minutes calculated from start_time and end_time."""
        start_time = datetime.now(timezone.utc)
        end_time = start_time + timedelta(hours=2, minutes=30)

        session = ChargingSession(
            start_time=start_time,
            end_time=end_time,
            is_complete=True,
        )
        db_session.add(session)
        db_session.commit()

        result = session.to_dict()

        assert result['duration_minutes'] == 150.0

    def test_soc_gained_calculation(self, db_session):
        """soc_gained calculated from start_soc and end_soc."""
        session = ChargingSession(
            start_time=datetime.now(timezone.utc),
            start_soc=25.0,
            end_soc=85.0,
            is_complete=True,
        )
        db_session.add(session)
        db_session.commit()

        result = session.to_dict()

        assert result['soc_gained'] == 60.0

    def test_charging_curve_json_serialization(self, db_session):
        """charging_curve JSON field stores/retrieves correctly."""
        curve_data = [
            {'timestamp': '2024-01-01T10:00:00', 'power_kw': 3.3, 'soc': 30},
            {'timestamp': '2024-01-01T11:00:00', 'power_kw': 3.3, 'soc': 50},
            {'timestamp': '2024-01-01T12:00:00', 'power_kw': 3.3, 'soc': 70},
        ]

        session = ChargingSession(
            start_time=datetime.now(timezone.utc),
            charging_curve=curve_data,
            is_complete=False,
        )
        db_session.add(session)
        db_session.commit()

        db_session.expire_all()
        fetched = db_session.query(ChargingSession).filter_by(id=session.id).first()

        assert fetched.charging_curve == curve_data
        assert len(fetched.charging_curve) == 3

    def test_default_is_complete_false(self, db_session):
        """is_complete defaults to False for new sessions."""
        session = ChargingSession(
            start_time=datetime.now(timezone.utc),
        )
        db_session.add(session)
        db_session.commit()

        assert session.is_complete is False


class TestBatteryHealthReadingModel:
    """Tests for BatteryHealthReading model."""

    def test_to_dict_all_fields(self, db_session):
        """All fields serialize correctly."""
        timestamp = datetime.now(timezone.utc)

        reading = BatteryHealthReading(
            timestamp=timestamp,
            capacity_kwh=17.5,
            normalized_capacity_kwh=17.8,
            soc_at_reading=80.0,
            ambient_temp_f=72.0,
            odometer_miles=55000.0,
        )
        db_session.add(reading)
        db_session.commit()

        result = reading.to_dict()

        assert result['capacity_kwh'] == 17.5
        assert result['normalized_capacity_kwh'] == 17.8
        assert result['soc_at_reading'] == 80.0
        assert result['ambient_temp_f'] == 72.0
        assert result['odometer_miles'] == 55000.0

    def test_degradation_percent_property(self, db_session):
        """degradation_percent calculates correctly from 18.4 kWh baseline."""
        reading = BatteryHealthReading(
            timestamp=datetime.now(timezone.utc),
            normalized_capacity_kwh=16.56,  # 90% of 18.4
        )
        db_session.add(reading)
        db_session.commit()

        # Should be about 10% degraded
        degradation = reading.degradation_percent
        assert degradation is not None
        assert abs(degradation - 10.0) < 0.1

    def test_degradation_percent_with_null_capacity(self, db_session):
        """degradation_percent returns None when capacity is None."""
        reading = BatteryHealthReading(
            timestamp=datetime.now(timezone.utc),
            capacity_kwh=None,
            normalized_capacity_kwh=None,
        )
        db_session.add(reading)
        db_session.commit()

        assert reading.degradation_percent is None

    def test_normalized_capacity_optional(self, db_session):
        """normalized_capacity_kwh can be None."""
        reading = BatteryHealthReading(
            timestamp=datetime.now(timezone.utc),
            capacity_kwh=17.5,
            normalized_capacity_kwh=None,
        )
        db_session.add(reading)
        db_session.commit()

        result = reading.to_dict()
        assert result['normalized_capacity_kwh'] is None


class TestBatteryCellReadingModel:
    """Tests for BatteryCellReading model."""

    def test_from_cell_voltages_creates_reading(self, db_session):
        """from_cell_voltages creates a reading with calculated stats."""
        # Generate 96 cell voltages around 3.7V
        cell_voltages = [3.7 + (i % 10) * 0.01 for i in range(96)]

        reading = BatteryCellReading.from_cell_voltages(
            timestamp=datetime.now(timezone.utc),
            cell_voltages=cell_voltages,
            ambient_temp_f=70.0,
            state_of_charge=75.0,
            is_charging=False,
        )

        assert reading is not None
        assert reading.min_voltage is not None
        assert reading.max_voltage is not None
        assert reading.avg_voltage is not None
        assert reading.voltage_delta is not None

    def test_from_cell_voltages_calculates_module_averages(self, db_session):
        """Module averages calculated for 32-cell modules."""
        cell_voltages = [3.7] * 96  # All same voltage

        reading = BatteryCellReading.from_cell_voltages(
            timestamp=datetime.now(timezone.utc),
            cell_voltages=cell_voltages,
        )

        assert reading.module1_avg == 3.7
        assert reading.module2_avg == 3.7
        assert reading.module3_avg == 3.7

    def test_from_cell_voltages_handles_empty_list(self, db_session):
        """Empty cell voltage list returns None."""
        reading = BatteryCellReading.from_cell_voltages(
            timestamp=datetime.now(timezone.utc),
            cell_voltages=[],
        )

        assert reading is None

    def test_from_cell_voltages_handles_none_values(self, db_session):
        """None values in cell voltages are filtered out."""
        cell_voltages = [3.7, None, 3.8, None, 3.75] + [None] * 91

        reading = BatteryCellReading.from_cell_voltages(
            timestamp=datetime.now(timezone.utc),
            cell_voltages=cell_voltages,
        )

        assert reading is not None
        assert reading.min_voltage == 3.7
        assert reading.max_voltage == 3.8

    def test_to_dict_all_fields(self, db_session):
        """All fields serialize correctly."""
        cell_voltages = [3.7 + i * 0.001 for i in range(96)]

        reading = BatteryCellReading.from_cell_voltages(
            timestamp=datetime.now(timezone.utc),
            cell_voltages=cell_voltages,
            ambient_temp_f=72.0,
            state_of_charge=80.0,
            is_charging=True,
        )
        db_session.add(reading)
        db_session.commit()

        result = reading.to_dict()

        assert result['cell_voltages'] is not None
        assert len(result['cell_voltages']) == 96
        assert result['ambient_temp_f'] == 72.0
        assert result['state_of_charge'] == 80.0
        assert result['is_charging'] is True
        assert result['voltage_delta'] is not None


class TestModelValidation:
    """Tests for field validation edge cases."""

    def test_trip_negative_distance(self, db_session):
        """Negative distance_miles can be stored (validation at app layer)."""
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            distance_miles=-5.0,  # Invalid but model allows it
        )
        db_session.add(trip)
        db_session.commit()

        fetched = db_session.query(Trip).filter_by(id=trip.id).first()
        assert fetched.distance_miles == -5.0

    def test_soc_over_100(self, db_session):
        """SOC values over 100 can be stored (sensor quirks)."""
        telemetry = TelemetryRaw(
            session_id=uuid.uuid4(),
            timestamp=datetime.now(timezone.utc),
            state_of_charge=102.5,  # Sensor can report > 100%
        )
        db_session.add(telemetry)
        db_session.commit()

        fetched = db_session.query(TelemetryRaw).filter_by(id=telemetry.id).first()
        assert fetched.state_of_charge == 102.5

    def test_telemetry_requires_session_id(self, db_session):
        """TelemetryRaw without session_id should fail."""
        from sqlalchemy.exc import IntegrityError

        telemetry = TelemetryRaw(
            session_id=None,  # Required field
            timestamp=datetime.now(timezone.utc),
        )
        db_session.add(telemetry)

        with pytest.raises(IntegrityError):
            db_session.commit()

    def test_telemetry_requires_timestamp(self, db_session):
        """TelemetryRaw without timestamp should fail."""
        from sqlalchemy.exc import IntegrityError

        telemetry = TelemetryRaw(
            session_id=uuid.uuid4(),
            timestamp=None,  # Required field
        )
        db_session.add(telemetry)

        with pytest.raises(IntegrityError):
            db_session.commit()


class TestModelRelationships:
    """Tests for SQLAlchemy relationships."""

    def test_trip_has_many_soc_transitions(self, db_session):
        """Trip can have multiple SocTransition records."""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
        )
        db_session.add(trip)
        db_session.commit()

        # Add multiple transitions
        for i in range(5):
            transition = SocTransition(
                trip_id=trip.id,
                timestamp=datetime.now(timezone.utc),
                soc_at_transition=20.0 - i,
            )
            db_session.add(transition)
        db_session.commit()

        db_session.expire_all()
        fetched_trip = db_session.query(Trip).filter_by(id=trip.id).first()
        assert len(fetched_trip.soc_transitions) == 5

    def test_soc_transition_belongs_to_trip(self, db_session):
        """SocTransition.trip returns parent Trip."""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
        )
        db_session.add(trip)
        db_session.commit()

        transition = SocTransition(
            trip_id=trip.id,
            timestamp=datetime.now(timezone.utc),
            soc_at_transition=18.0,
        )
        db_session.add(transition)
        db_session.commit()

        db_session.expire_all()
        fetched = db_session.query(SocTransition).filter_by(id=transition.id).first()
        assert fetched.trip is not None
        assert fetched.trip.id == trip.id
        assert str(fetched.trip.session_id) == str(session_id)


# Import timedelta for ChargingSession tests
from datetime import timedelta
