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

from app import app as flask_app
from models import Base, TelemetryRaw, Trip, FuelEvent, SocTransition
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker


@pytest.fixture
def app():
    """Create application for testing."""
    flask_app.config['TESTING'] = True
    flask_app.config['DATABASE_URL'] = 'sqlite:///:memory:'
    return flask_app


@pytest.fixture
def client(app):
    """Create test client."""
    return app.test_client()


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
