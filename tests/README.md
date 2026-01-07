# VoltTracker Testing Guide

Comprehensive testing and debugging utilities for VoltTracker.

## Quick Start

```bash
# Activate virtual environment
source .venv/bin/activate

# Run all tests with coverage
SECRET_KEY=test pytest tests/ --cov=receiver --cov-report=term-missing

# Run specific test file
SECRET_KEY=test pytest tests/test_api.py -v

# Run with debugging enabled
SECRET_KEY=test pytest tests/ -v --tb=long

# Run with performance tracking
PYTEST_ENABLE_PERF_TRACKING=1 SECRET_KEY=test pytest tests/
```

## Test Structure

```
tests/
├── conftest.py              # Core fixtures and configuration
├── factories.py             # Test data factories
├── test_helpers.py          # Helper functions and utilities
├── debug_utils.py           # Debugging tools
├── pytest_plugins.py        # Custom pytest plugins
├── integration_helpers.py   # Integration test scenarios
└── test_*.py               # Test files
```

## Using Test Factories

Test factories make it easy to create test data with sensible defaults:

```python
from tests.factories import TripFactory, TelemetryFactory

def test_trip_creation(db_session):
    # Create with defaults
    trip = TripFactory.create(db_session=db_session)

    # Create with custom values
    trip = TripFactory.create(
        db_session=db_session,
        distance_miles=50.0,
        is_closed=True
    )

    # Create electric-only trip
    trip = TripFactory.create_electric_only(db_session=db_session)

    # Create multiple trips
    trips = TripFactory.create_batch(5, db_session=db_session)
```

### Available Factories

- `TripFactory` - Create trips
  - `create_electric_only()` - Electric-only trips
  - `create_gas_only()` - Gas-only trips
  - `create_open()` - In-progress trips

- `TelemetryFactory` - Create telemetry points
  - `create_sequence()` - Create a sequence of points
  - `create_electric_mode()` - Electric mode telemetry
  - `create_gas_mode()` - Gas mode telemetry
  - `create_charging()` - Charging telemetry

- `ChargingSessionFactory` - Create charging sessions
  - `create_l1()` - Level 1 charging
  - `create_l2()` - Level 2 charging
  - `create_dcfc()` - DC fast charging

- `FuelEventFactory` - Create fuel events
- `BatteryHealthFactory` - Create battery health readings
  - `create_degradation_series()` - Create degradation history

## Debugging Utilities

### SQL Query Logging

Track all SQL queries executed during a test:

```python
from tests.debug_utils import with_sql_logging, sql_logging

# As a decorator
@with_sql_logging
def test_something(db_session):
    # All SQL queries will be logged
    db_session.query(Trip).all()

# As a context manager
def test_something_else(db_session):
    with sql_logging() as logger:
        db_session.query(Trip).all()

    # Check query count
    print(f"Executed {len(logger.get_queries())} queries")
```

### Performance Profiling

Profile test execution time:

```python
from tests.debug_utils import profile_test, TestProfiler

# Profile entire test
@profile_test
def test_slow_operation():
    # Code to profile
    pass

# Profile specific sections
def test_with_sections(performance_profiler):
    with performance_profiler.section("database_queries"):
        # Database operations
        pass

    with performance_profiler.section("calculations"):
        # Calculation operations
        pass

    # Report will be printed automatically
```

### Enhanced Assertions

Better error messages for debugging:

```python
from tests.debug_utils import (
    assert_close_enough,
    assert_dict_subset,
    assert_json_equal,
    assert_query_count
)

# Float comparison with tolerance
assert_close_enough(actual=4.567, expected=4.5, tolerance=0.1)

# Partial dict matching
assert_dict_subset(
    actual={"a": 1, "b": 2, "c": 3},
    expected_subset={"a": 1, "b": 2}
)

# JSON comparison
assert_json_equal(
    actual='{"status": "ok"}',
    expected={"status": "ok"}
)

# Query count assertion (detect N+1 queries)
assert_query_count(
    expected_count=5,
    func=lambda: db_session.query(Trip).all()
)
```

### Database Inspection

Inspect database state during tests:

```python
from tests.debug_utils import DatabaseInspector

def test_with_inspection(db_session):
    inspector = DatabaseInspector()

    # Print row counts for all tables
    inspector.print_table_counts(db_session)

    # Print detailed trip information
    inspector.print_trip_summary(db_session, trip_id=123)

    # Dump database to JSON for debugging
    inspector.dump_database_to_json(db_session, "debug_dump.json")
```

## Test Helpers

### API Testing

```python
from tests.test_helpers import APITestHelper, make_api_request

def test_api_endpoint(client):
    api = APITestHelper(client)

    # GET request
    data = api.get("/api/trips")

    # POST request
    new_trip = api.post("/api/trips", data={
        "start_time": "2024-01-01T00:00:00Z"
    })

    # Expect error
    api.expect_error("GET", "/api/trips/999999", expected_status=404)
```

### Mocking External APIs

```python
from tests.test_helpers import MockWeatherAPI, MockElevationAPI

def test_with_weather_mock():
    with MockWeatherAPI.mock_response(temperature=75.0, precipitation=0.5):
        # Code that calls weather API
        pass

    # Mock API failure
    with MockWeatherAPI.mock_failure():
        # Code that should handle weather API failure
        pass
```

### Torque Data Builder

```python
from tests.test_helpers import TorqueDataBuilder

def test_torque_upload(client):
    torque_data = (TorqueDataBuilder()
        .with_location(37.7749, -122.4194)
        .with_speed(45.0)
        .with_soc(80.0)
        .electric_mode()
        .build())

    response = client.post("/upload", data=torque_data)
```

## Integration Testing

### Trip Scenario

```python
from tests.integration_helpers import TripScenario

def test_complete_trip_workflow(client, db_session):
    scenario = TripScenario(client, db_session)

    # Execute workflow
    (scenario
        .start_trip()
        .add_telemetry_points(count=10, mode="electric")
        .end_trip()
        .verify_trip_created()
        .verify_telemetry_count(expected_count=10))

    # Get trip via API
    trip_data = scenario.get_trip_via_api()
    assert trip_data["is_closed"] is True
```

### Charging Scenario

```python
from tests.integration_helpers import ChargingScenario

def test_charging_workflow(client, db_session):
    scenario = ChargingScenario(client, db_session)

    (scenario
        .start_charging(charge_type="L2", start_soc=20.0)
        .add_charging_telemetry(points=10, target_soc=90.0)
        .complete_charging(end_soc=95.0)
        .verify_session_created())
```

### Daily Driving Scenario

```python
from tests.integration_helpers import DailyDrivingScenario

def test_daily_driving(client, db_session):
    scenario = DailyDrivingScenario(client, db_session)

    (scenario
        .morning_commute(distance_miles=25.0)
        .evening_commute(distance_miles=25.0)
        .overnight_charge()
        .verify_daily_stats())
```

### Database Seeding

```python
from tests.integration_helpers import DatabaseSeeder

def test_with_realistic_data(db_session):
    seeder = DatabaseSeeder(db_session)

    # Seed a month of trips
    trips = seeder.seed_month_of_trips(trips_per_day=2, days=30)

    # Seed charging history
    charging = seeder.seed_charging_history(sessions=30)

    # Seed complete dataset
    data = seeder.seed_complete_dataset()
```

## Custom Pytest Plugins

### Performance Tracking

Enable to see slow test report:

```bash
PYTEST_ENABLE_PERF_TRACKING=1 SECRET_KEY=test pytest tests/
```

Shows tests slower than threshold (default 1.0s):

```
Slow Tests
Tests slower than 1.0s:
   2.45s  tests/test_integration.py::test_complete_workflow
   1.32s  tests/test_api.py::test_export_all_data
```

### Test Categorization

Enable to see test categories:

```bash
PYTEST_ENABLE_CATEGORIZATION=1 SECRET_KEY=test pytest tests/
```

Shows breakdown:

```
Test Categories
  api              245 tests ( 25.0%)
  service          180 tests ( 18.3%)
  unit             420 tests ( 42.8%)
  integration       92 tests (  9.4%)
  model             45 tests (  4.6%)
```

### Failure Analysis

Enable to see failure patterns:

```bash
PYTEST_ENABLE_FAILURE_ANALYSIS=1 SECRET_KEY=test pytest tests/
```

## Test Markers

Use markers to categorize and selectively run tests:

```python
import pytest

@pytest.mark.slow
def test_long_running_operation():
    """This test takes a long time."""
    pass

@pytest.mark.integration
def test_multiple_components():
    """Tests multiple components together."""
    pass

@pytest.mark.requires_network
def test_external_api():
    """Requires network access."""
    pass
```

Run tests by marker:

```bash
# Run only fast tests (exclude slow)
pytest -m "not slow"

# Run only integration tests
pytest -m integration

# Run unit tests only
pytest -m unit
```

## Coverage Reports

### Terminal Report

```bash
SECRET_KEY=test pytest tests/ --cov=receiver --cov-report=term-missing
```

### HTML Report

```bash
SECRET_KEY=test pytest tests/ --cov=receiver --cov-report=html
# Open htmlcov/index.html in browser
```

### Coverage by Module

Check coverage for specific modules:

```bash
# Just services
SECRET_KEY=test pytest tests/ --cov=receiver/services --cov-report=term-missing

# Just routes
SECRET_KEY=test pytest tests/ --cov=receiver/routes --cov-report=term-missing
```

## Best Practices

### 1. Use Factories

**Good:**
```python
def test_trip_distance(db_session):
    trip = TripFactory.create(db_session=db_session, distance_miles=50.0)
    assert trip.distance_miles == 50.0
```

**Bad:**
```python
def test_trip_distance(db_session):
    trip = Trip(
        session_id=uuid.uuid4(),
        start_time=datetime.now(timezone.utc),
        # ... 20 more fields
        distance_miles=50.0
    )
    db_session.add(trip)
    db_session.commit()
```

### 2. Use Integration Scenarios for Workflows

**Good:**
```python
def test_complete_trip(client, db_session):
    scenario = TripScenario(client, db_session)
    scenario.start_trip().add_telemetry_points(10).end_trip()
```

**Bad:**
```python
def test_complete_trip(client, db_session):
    # 50 lines of manual setup...
```

### 3. Use Helpers for API Testing

**Good:**
```python
def test_api(client):
    api = APITestHelper(client)
    data = api.get("/api/trips")
    assert len(data["trips"]) > 0
```

**Bad:**
```python
def test_api(client):
    response = client.get("/api/trips")
    assert response.status_code == 200
    data = json.loads(response.data)
    # ...
```

### 4. Profile Slow Tests

```python
@profile_test
def test_complex_calculation():
    # Automatically get performance report
    pass
```

### 5. Use Markers Appropriately

```python
@pytest.mark.slow
@pytest.mark.integration
def test_end_to_end_workflow():
    pass
```

## Troubleshooting

### Tests Running Slowly

1. Enable performance tracking:
   ```bash
   PYTEST_ENABLE_PERF_TRACKING=1 SECRET_KEY=test pytest tests/
   ```

2. Profile specific tests:
   ```python
   @profile_test
   def test_slow_one():
       pass
   ```

3. Check for N+1 queries:
   ```python
   @with_sql_logging
   def test_queries():
       pass
   ```

### Test Failures

1. Enable verbose output:
   ```bash
   SECRET_KEY=test pytest tests/test_file.py -vv --tb=long
   ```

2. Run specific test:
   ```bash
   SECRET_KEY=test pytest tests/test_file.py::test_function -vv
   ```

3. Enable failure analysis:
   ```bash
   PYTEST_ENABLE_FAILURE_ANALYSIS=1 SECRET_KEY=test pytest tests/
   ```

### Database Issues

1. Inspect database state:
   ```python
   def test_debug(db_session):
       DatabaseInspector.print_table_counts(db_session)
       DatabaseInspector.dump_database_to_json(db_session, "debug.json")
   ```

2. Check data integrity:
   ```python
   from tests.integration_helpers import DataVerifier

   verifier = DataVerifier(db_session)
   results = verifier.verify_data_integrity()
   ```

## Environment Variables

- `SECRET_KEY=test` - Required for running tests
- `PYTEST_ENABLE_PERF_TRACKING=1` - Enable performance tracking
- `PYTEST_ENABLE_CATEGORIZATION=1` - Enable test categorization
- `PYTEST_ENABLE_FAILURE_ANALYSIS=1` - Enable failure analysis
- `PYTEST_ENABLE_QUERY_COUNTING=1` - Enable query counting
- `SLOW_TEST_THRESHOLD=1.0` - Threshold for slow tests (seconds)
- `QUERY_COUNT_THRESHOLD=50` - Threshold for high query count

## Current Test Coverage

Run to see current coverage:

```bash
SECRET_KEY=test pytest tests/ --cov=receiver --cov-report=term-missing
```

Target: **90%+ coverage** (minimum 80% enforced by CI)

## Additional Resources

- pytest documentation: https://docs.pytest.org/
- pytest-cov documentation: https://pytest-cov.readthedocs.io/
- VoltTracker CLAUDE.md for project-specific testing requirements
