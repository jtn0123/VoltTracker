"""
Pytest fixtures for VoltTracker tests.
"""

import os
import sys
import uuid
from datetime import datetime, timezone

import pytest

# Add receiver to path for imports
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

# Set environment variables BEFORE importing app to use SQLite for tests
os.environ["DATABASE_URL"] = "sqlite:///:memory:"
os.environ["FLASK_TESTING"] = "true"
os.environ["FLASK_ENV"] = "development"  # Avoid SECRET_KEY requirement

from datetime import timedelta  # noqa: E402

# Import models and create test engine BEFORE importing database or app
# This ensures StaticPool is used so all connections share the same in-memory database
from models import Base, MaintenanceRecord, TelemetryRaw, Trip, get_engine  # noqa: E402
from sqlalchemy.orm import scoped_session, sessionmaker  # noqa: E402

# Create the test engine with StaticPool
engine = get_engine(os.environ["DATABASE_URL"])
Session = scoped_session(sessionmaker(bind=engine))

# Import database module and patch BEFORE app import
import database  # noqa: E402
database.engine = engine
database.SessionLocal = Session

# Now import app (which will use our patched database module)
from app import app as flask_app  # noqa: E402
from app import init_cache  # noqa: E402


@pytest.fixture(autouse=True)
def clear_caches():
    """Clear all caches before each test (autouse=True means it runs automatically)."""
    # Clear in-memory weather cache
    from utils import weather
    weather._weather_cache.clear()

    # Reset rate limiter
    from extensions import limiter
    try:
        limiter.reset()
    except Exception:
        try:
            limiter.storage.reset()
        except Exception:
            pass

    yield

    # Clean up after test as well
    weather._weather_cache.clear()


@pytest.fixture(autouse=True)
def clean_test_tables(app):
    """Clean database tables that commonly cause test pollution.

    Uses engine.execute to bypass session scoping issues.
    """
    from sqlalchemy import text
    with engine.begin() as conn:
        try:
            # Clear tables that commonly cause test isolation issues
            conn.execute(text("DELETE FROM telemetry_raw"))
            conn.execute(text("DELETE FROM maintenance_records"))
        except Exception:
            pass
    yield


@pytest.fixture
def app():
    """Create application for testing."""
    flask_app.config["TESTING"] = True

    # Reinitialize cache to ensure it uses NullCache
    init_cache(flask_app)

    # Create all tables in the test database
    Base.metadata.create_all(engine)

    # Clear database weather cache
    try:
        from models import WeatherCache
        session = Session()
        session.query(WeatherCache).delete()
        session.commit()
        session.close()
    except Exception:
        pass

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
        "eml": "test@example.com",
        "v": "1.0",
        "session": str(uuid.uuid4()),
        "id": "test-device",
        "time": str(timestamp_ms),
        "kff1006": "37.7749",  # Latitude
        "kff1005": "-122.4194",  # Longitude
        "kff1001": "45.5",  # Speed (mph)
        "kc": "0",  # RPM (electric mode)
        "k11": "15.0",  # Throttle
        "k5": "21.0",  # Coolant temp (C)
        "kf": "18.0",  # Intake temp (C)
        "k22002f": "75.5",  # Fuel level %
        "k22005b": "85.0",  # SOC %
        "k42": "12.6",  # Battery voltage
        "kff1010": "22.0",  # Ambient temp (C)
        "kff1271": "50123.4",  # Odometer
    }


@pytest.fixture
def sample_torque_data_gas_mode(sample_torque_data):
    """Sample Torque Pro data with engine running (gas mode)."""
    data = sample_torque_data.copy()
    data["kc"] = "1500"  # Engine running
    data["k22005b"] = "15.0"  # Low SOC
    return data


@pytest.fixture
def sample_telemetry_points():
    """Generate a list of telemetry points simulating a trip."""
    points = []
    session_id = uuid.uuid4()
    base_time = datetime.now(timezone.utc)

    # Electric portion (SOC draining)
    for i in range(30):
        points.append(
            {
                "session_id": session_id,
                "timestamp": base_time,
                "speed_mph": 45.0 + (i % 10) - 5,
                "engine_rpm": 0,
                "state_of_charge": 100 - (i * 2.5),  # Drain from 100 to 25
                "fuel_level_percent": 80.0,
                "odometer_miles": 50000 + (i * 0.5),
                "ambient_temp_f": 70.0,
            }
        )

    # Gas portion (engine running)
    for i in range(20):
        points.append(
            {
                "session_id": session_id,
                "timestamp": base_time,
                "speed_mph": 55.0 + (i % 10) - 5,
                "engine_rpm": 1200 + (i * 50),
                "state_of_charge": 18.0,  # Stays around 18% in gas mode
                "fuel_level_percent": 80.0 - (i * 0.3),
                "odometer_miles": 50015 + (i * 0.6),
                "ambient_temp_f": 70.0,
            }
        )

    return points


@pytest.fixture
def sample_soc_transitions():
    """Sample SOC transition data for analysis."""
    return [
        {"soc_at_transition": 17.5, "ambient_temp_f": 72.0, "timestamp": datetime.now(timezone.utc)},
        {"soc_at_transition": 16.8, "ambient_temp_f": 75.0, "timestamp": datetime.now(timezone.utc)},
        {"soc_at_transition": 18.2, "ambient_temp_f": 68.0, "timestamp": datetime.now(timezone.utc)},
        {"soc_at_transition": 19.5, "ambient_temp_f": 32.0, "timestamp": datetime.now(timezone.utc)},  # Cold
        {"soc_at_transition": 20.1, "ambient_temp_f": 28.0, "timestamp": datetime.now(timezone.utc)},  # Cold
        {"soc_at_transition": 17.0, "ambient_temp_f": 70.0, "timestamp": datetime.now(timezone.utc)},
        {"soc_at_transition": 16.5, "ambient_temp_f": 78.0, "timestamp": datetime.now(timezone.utc)},
        {"soc_at_transition": 17.8, "ambient_temp_f": 65.0, "timestamp": datetime.now(timezone.utc)},
        {"soc_at_transition": 18.0, "ambient_temp_f": 55.0, "timestamp": datetime.now(timezone.utc)},
        {"soc_at_transition": 17.2, "ambient_temp_f": 80.0, "timestamp": datetime.now(timezone.utc)},
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

    return {"before": before, "after": after}


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
    monkeypatch.setattr("config.Config.DASHBOARD_PASSWORD", "test_password")
    monkeypatch.setattr("config.Config.DASHBOARD_USER", "test_user")
    return app


@pytest.fixture
def app_with_token(app, monkeypatch):
    """App configured with Torque API token."""
    monkeypatch.setattr("config.Config.TORQUE_API_TOKEN", "test_token_12345")
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


# ============================================================================
# Weather Test Fixtures
# ============================================================================


@pytest.fixture
def mock_weather_response():
    """Standard weather API response from Open-Meteo."""
    now = datetime.now(timezone.utc)
    hours = [(now - timedelta(hours=i)).strftime("%Y-%m-%dT%H:00") for i in range(24)]
    hours.reverse()

    return {
        "hourly": {
            "time": hours,
            "temperature_2m": [65.0 + i * 0.5 for i in range(24)],
            "precipitation": [0.0] * 20 + [0.1, 0.2, 0.0, 0.0],
            "wind_speed_10m": [10.0 + i for i in range(24)],
            "weather_code": [0] * 20 + [61, 61, 0, 0],
        }
    }


@pytest.fixture
def mock_weather_extreme():
    """Weather response with extreme conditions."""
    now = datetime.now(timezone.utc)
    current_hour = now.strftime("%Y-%m-%dT%H:00")

    return {
        "hourly": {
            "time": [current_hour],
            "temperature_2m": [15.0],  # Very cold
            "precipitation": [0.8],  # Heavy rain
            "wind_speed_10m": [35.0],  # Strong wind
            "weather_code": [95],  # Thunderstorm
        }
    }


# ============================================================================
# Power Telemetry Fixtures
# ============================================================================


@pytest.fixture
def power_telemetry_points(db_session):
    """Telemetry with HV battery power data for kWh calculations."""
    session_id = uuid.uuid4()
    now = datetime.now(timezone.utc)

    points = []
    for i in range(10):
        point = TelemetryRaw(
            session_id=session_id,
            timestamp=now - timedelta(minutes=(10 - i) * 6),  # Every 6 minutes
            hv_battery_power_kw=8.0 + (i % 3),  # 8-10 kW draw
            state_of_charge=100.0 - (i * 3),  # Draining
            speed_mph=45.0 + i,
            odometer_miles=50000.0 + i,
        )
        points.append(point)
        db_session.add(point)

    db_session.commit()
    return points


@pytest.fixture
def charging_telemetry_with_power(db_session):
    """Charging session with detailed power readings."""
    from models import ChargingSession

    session_id = uuid.uuid4()
    now = datetime.now(timezone.utc)

    # Create charging session
    charging = ChargingSession(
        start_time=now - timedelta(hours=4),
        end_time=now,
        start_soc=20.0,
        end_soc=95.0,
        kwh_added=13.8,
        charge_type="L2",
        peak_power_kw=6.8,
        avg_power_kw=6.6,
        is_complete=True,
    )
    db_session.add(charging)

    # Create telemetry during charging
    points = []
    for i in range(24):  # 24 points over 4 hours
        point = TelemetryRaw(
            session_id=session_id,
            timestamp=now - timedelta(minutes=(24 - i) * 10),
            charger_connected=True,
            charger_power_kw=6.6 + (i % 3) * 0.1,  # Varying 6.6-6.8 kW
            charger_ac_power_kw=6.6 + (i % 3) * 0.1,
            state_of_charge=20.0 + (i * 3.125),  # 20% to 95%
            speed_mph=0.0,
        )
        points.append(point)
        db_session.add(point)

    db_session.commit()

    return {"session": charging, "telemetry": points}


# ============================================================================
# Battery Health Fixtures
# ============================================================================


@pytest.fixture
def battery_health_readings(db_session):
    """12 months of battery health degradation data."""
    from models import BatteryHealthReading

    readings = []
    now = datetime.now(timezone.utc)

    # Simulate gradual degradation over 12 months
    for month in range(12):
        reading = BatteryHealthReading(
            timestamp=now - timedelta(days=month * 30),
            capacity_kwh=18.4 - (month * 0.05),  # Lose ~0.05 kWh per month
            normalized_capacity_kwh=18.4 - (month * 0.05),
            soc_at_reading=100.0,
            ambient_temp_f=70.0,
        )
        readings.append(reading)
        db_session.add(reading)

    db_session.commit()
    return readings


# ============================================================================
# Trip with Telemetry Fixtures
# ============================================================================


@pytest.fixture
def trips_with_telemetry(db_session):
    """Multiple trips with associated telemetry data."""
    trips = []
    now = datetime.now(timezone.utc)

    for trip_num in range(3):
        session_id = uuid.uuid4()
        trip_start = now - timedelta(days=trip_num, hours=2)

        # Create trip
        trip = Trip(
            session_id=session_id,
            start_time=trip_start,
            end_time=trip_start + timedelta(hours=1),
            start_odometer=50000.0 + (trip_num * 30),
            end_odometer=50000.0 + (trip_num * 30) + 25.0,
            distance_miles=25.0,
            start_soc=90.0 - (trip_num * 5),
            end_soc=70.0 - (trip_num * 5),
            electric_miles=20.0,
            gas_miles=5.0,
            is_closed=True,
        )
        db_session.add(trip)

        # Create telemetry for trip
        for point_num in range(10):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=trip_start + timedelta(minutes=point_num * 6),
                speed_mph=35.0 + point_num,
                state_of_charge=90.0 - (trip_num * 5) - point_num * 2,
                odometer_miles=50000.0 + (trip_num * 30) + (point_num * 2.5),
                engine_rpm=0 if point_num < 7 else 1200,
            )
            db_session.add(telemetry)

        trips.append(trip)

    db_session.commit()
    return trips
