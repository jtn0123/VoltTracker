"""
Pytest fixtures for VoltTracker tests.
"""

import os
import sys
import pytest
from datetime import datetime, timezone
import uuid

# Add receiver to path for imports
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'receiver'))

# Set DATABASE_URL BEFORE importing app to use SQLite for tests
os.environ['DATABASE_URL'] = 'sqlite:///:memory:'
os.environ['FLASK_TESTING'] = 'true'

from app import app as flask_app, Session, engine, cache, init_cache
from models import Base, TelemetryRaw, Trip, FuelEvent, SocTransition, ChargingSession, BatteryCellReading
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from datetime import timedelta


@pytest.fixture
def app():
    """Create application for testing."""
    flask_app.config['TESTING'] = True

    # Reinitialize cache to ensure it uses NullCache
    init_cache(flask_app)

    # Create all tables in the test database
    Base.metadata.create_all(engine)

    yield flask_app

    # Clean up tables after test
    Base.metadata.drop_all(engine)


@pytest.fixture
def client(app):
    """Create test client."""
    return app.test_client()


@pytest.fixture
def db_session(app):
    """Provide a database session for tests."""
    session = Session()
    yield session
    session.rollback()
    Session.remove()


@pytest.fixture
def sample_torque_data():
    """Sample Torque Pro POST data."""
    timestamp_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    return {
        'eml': 'test@example.com',
        'v': '1.0',
        'session': str(uuid.uuid4()),
        'id': 'test-device',
        'time': str(timestamp_ms),
        'kff1006': '37.7749',      # Latitude
        'kff1005': '-122.4194',    # Longitude
        'kff1001': '45.5',         # Speed (mph)
        'kc': '0',                 # RPM (electric mode)
        'k11': '15.0',             # Throttle
        'k5': '21.0',              # Coolant temp (C)
        'kf': '18.0',              # Intake temp (C)
        'k22002f': '75.5',         # Fuel level %
        'k22005b': '85.0',         # SOC %
        'k42': '12.6',             # Battery voltage
        'kff1010': '22.0',         # Ambient temp (C)
        'kff1271': '50123.4',      # Odometer
    }


@pytest.fixture
def sample_torque_data_gas_mode(sample_torque_data):
    """Sample Torque Pro data with engine running (gas mode)."""
    data = sample_torque_data.copy()
    data['kc'] = '1500'            # Engine running
    data['k22005b'] = '15.0'       # Low SOC
    return data


@pytest.fixture
def sample_telemetry_points():
    """Generate a list of telemetry points simulating a trip."""
    points = []
    session_id = uuid.uuid4()
    base_time = datetime.now(timezone.utc)

    # Electric portion (SOC draining)
    for i in range(30):
        points.append({
            'session_id': session_id,
            'timestamp': base_time,
            'speed_mph': 45.0 + (i % 10) - 5,
            'engine_rpm': 0,
            'state_of_charge': 100 - (i * 2.5),  # Drain from 100 to 25
            'fuel_level_percent': 80.0,
            'odometer_miles': 50000 + (i * 0.5),
            'ambient_temp_f': 70.0,
        })

    # Gas portion (engine running)
    for i in range(20):
        points.append({
            'session_id': session_id,
            'timestamp': base_time,
            'speed_mph': 55.0 + (i % 10) - 5,
            'engine_rpm': 1200 + (i * 50),
            'state_of_charge': 18.0,  # Stays around 18% in gas mode
            'fuel_level_percent': 80.0 - (i * 0.3),
            'odometer_miles': 50015 + (i * 0.6),
            'ambient_temp_f': 70.0,
        })

    return points


@pytest.fixture
def sample_soc_transitions():
    """Sample SOC transition data for analysis."""
    return [
        {'soc_at_transition': 17.5, 'ambient_temp_f': 72.0, 'timestamp': datetime.now(timezone.utc)},
        {'soc_at_transition': 16.8, 'ambient_temp_f': 75.0, 'timestamp': datetime.now(timezone.utc)},
        {'soc_at_transition': 18.2, 'ambient_temp_f': 68.0, 'timestamp': datetime.now(timezone.utc)},
        {'soc_at_transition': 19.5, 'ambient_temp_f': 32.0, 'timestamp': datetime.now(timezone.utc)},  # Cold
        {'soc_at_transition': 20.1, 'ambient_temp_f': 28.0, 'timestamp': datetime.now(timezone.utc)},  # Cold
        {'soc_at_transition': 17.0, 'ambient_temp_f': 70.0, 'timestamp': datetime.now(timezone.utc)},
        {'soc_at_transition': 16.5, 'ambient_temp_f': 78.0, 'timestamp': datetime.now(timezone.utc)},
        {'soc_at_transition': 17.8, 'ambient_temp_f': 65.0, 'timestamp': datetime.now(timezone.utc)},
        {'soc_at_transition': 18.0, 'ambient_temp_f': 55.0, 'timestamp': datetime.now(timezone.utc)},
        {'soc_at_transition': 17.2, 'ambient_temp_f': 80.0, 'timestamp': datetime.now(timezone.utc)},
    ]


# ============================================================================
# Scheduler Test Fixtures
# ============================================================================

@pytest.fixture
def stale_trip(db_session):
    """Create a trip with telemetry older than TRIP_TIMEOUT_SECONDS."""
    from config import Config

    session_id = uuid.uuid4()
    old_time = datetime.now(timezone.utc) - timedelta(seconds=Config.TRIP_TIMEOUT_SECONDS + 120)

    trip = Trip(
        session_id=session_id,
        start_time=old_time,
        start_odometer=50000.0,
        start_soc=80.0,
        is_closed=False,
    )
    db_session.add(trip)

    # Add old telemetry
    telemetry = TelemetryRaw(
        session_id=session_id,
        timestamp=old_time,
        odometer_miles=50000.0,
        state_of_charge=80.0,
        speed_mph=45.0,
    )
    db_session.add(telemetry)
    db_session.commit()

    return trip


@pytest.fixture
def active_trip(db_session):
    """Create a trip with recent telemetry (still active)."""
    session_id = uuid.uuid4()
    recent_time = datetime.now(timezone.utc) - timedelta(seconds=30)

    trip = Trip(
        session_id=session_id,
        start_time=recent_time,
        start_odometer=50000.0,
        start_soc=85.0,
        is_closed=False,
    )
    db_session.add(trip)

    telemetry = TelemetryRaw(
        session_id=session_id,
        timestamp=recent_time,
        odometer_miles=50010.0,
        state_of_charge=75.0,
        speed_mph=55.0,
    )
    db_session.add(telemetry)
    db_session.commit()

    return trip


@pytest.fixture
def refuel_telemetry(db_session):
    """Create telemetry data showing a fuel level jump (refuel event)."""
    session_id = uuid.uuid4()
    now = datetime.now(timezone.utc)

    # Before refuel
    before = TelemetryRaw(
        session_id=session_id,
        timestamp=now - timedelta(hours=2),
        fuel_level_percent=25.0,
        odometer_miles=50000.0,
    )

    # After refuel (60% jump)
    after = TelemetryRaw(
        session_id=session_id,
        timestamp=now - timedelta(hours=1),
        fuel_level_percent=85.0,
        odometer_miles=50000.0,
    )

    db_session.add(before)
    db_session.add(after)
    db_session.commit()

    return {'before': before, 'after': after}


@pytest.fixture
def charging_telemetry(db_session):
    """Create telemetry data showing active charging."""
    session_id = uuid.uuid4()
    now = datetime.now(timezone.utc)

    telemetry_points = []
    for i in range(10):
        point = TelemetryRaw(
            session_id=session_id,
            timestamp=now - timedelta(minutes=i * 10),
            charger_connected=True,
            charger_power_kw=6.6,
            state_of_charge=30.0 + (i * 5),
            latitude=37.7749,
            longitude=-122.4194,
        )
        telemetry_points.append(point)
        db_session.add(point)

    db_session.commit()

    return telemetry_points


# ============================================================================
# Security Test Fixtures
# ============================================================================

@pytest.fixture
def app_with_auth(app, monkeypatch):
    """App configured with authentication enabled."""
    monkeypatch.setattr('config.Config.DASHBOARD_PASSWORD', 'test_password')
    monkeypatch.setattr('config.Config.DASHBOARD_USER', 'test_user')
    return app


@pytest.fixture
def app_with_token(app, monkeypatch):
    """App configured with Torque API token."""
    monkeypatch.setattr('config.Config.TORQUE_API_TOKEN', 'test_token_12345')
    return app


# ============================================================================
# Battery Test Fixtures
# ============================================================================

@pytest.fixture
def sample_cell_voltages():
    """Generate sample cell voltages for 96-cell pack."""
    # Normal voltages around 3.7V with slight variation
    return [3.7 + (i % 10) * 0.005 for i in range(96)]


@pytest.fixture
def imbalanced_cell_voltages():
    """Cell voltages with one weak cell."""
    voltages = [3.75] * 96
    voltages[42] = 3.60  # One weak cell
    return voltages
