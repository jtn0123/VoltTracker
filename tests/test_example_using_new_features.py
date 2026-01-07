"""
Example Test File Demonstrating New Testing Features

This file shows how to use all the new testing utilities.
Run with: SECRET_KEY=test pytest tests/test_example_using_new_features.py -v
"""

import pytest
from datetime import datetime, timezone

# Import our new utilities
from tests.factories import (
    TripFactory,
    TelemetryFactory,
    ChargingSessionFactory,
    create_complete_trip_with_telemetry,
)
from tests.debug_utils import (
    assert_close_enough,
    assert_dict_subset,
    with_sql_logging,
)
from tests.test_helpers import APITestHelper, TorqueDataBuilder
from tests.integration_helpers import TripScenario, DailyDrivingScenario


# ============================================================================
# Example 1: Using Factories
# ============================================================================


class TestUsingFactories:
    """Examples of using test data factories."""

    def test_create_trip_with_factory(self, db_session):
        """Create a trip using the factory."""
        # Simple creation with defaults
        trip = TripFactory.create(db_session=db_session)

        assert trip.id is not None
        assert trip.distance_miles == 25.0  # Default value

    def test_create_custom_trip(self, db_session):
        """Create a trip with custom values."""
        trip = TripFactory.create(
            db_session=db_session,
            distance_miles=100.0,
            electric_miles=50.0,
            gas_miles=50.0,
        )

        assert trip.distance_miles == 100.0
        assert trip.electric_miles == 50.0

    def test_create_electric_only_trip(self, db_session):
        """Create an electric-only trip."""
        trip = TripFactory.create_electric_only(db_session=db_session)

        assert trip.gas_miles == 0.0
        assert trip.electric_miles > 0

    def test_create_multiple_trips(self, db_session):
        """Create multiple trips at once."""
        trips = TripFactory.create_batch(5, db_session=db_session)

        assert len(trips) == 5
        assert all(t.id is not None for t in trips)

    def test_create_telemetry_sequence(self, db_session):
        """Create a sequence of telemetry points."""
        telemetry = TelemetryFactory.create_sequence(
            count=10,
            interval_seconds=60,
            db_session=db_session,
        )

        assert len(telemetry) == 10
        # SOC should be draining
        assert telemetry[0].state_of_charge > telemetry[-1].state_of_charge

    def test_create_complete_trip_with_telemetry(self, db_session):
        """Use convenience function to create trip with telemetry."""
        trip, telemetry = create_complete_trip_with_telemetry(
            db_session=db_session,
            telemetry_points=15,
            distance_miles=50.0,
        )

        assert trip.id is not None
        assert len(telemetry) == 15
        assert all(t.session_id == trip.session_id for t in telemetry)


# ============================================================================
# Example 2: Using Debug Utilities
# ============================================================================


class TestDebugUtilities:
    """Examples of using debugging utilities."""

    def test_assert_close_enough(self):
        """Use close enough assertion for floats."""
        calculated_mpg = 42.567
        expected_mpg = 42.5

        # This would fail with regular equality
        # assert calculated_mpg == expected_mpg

        # But passes with tolerance
        assert_close_enough(calculated_mpg, expected_mpg, tolerance=0.1)

    def test_assert_dict_subset(self, client):
        """Check that response contains expected fields."""
        response = client.get("/api/status")
        data = response.get_json()

        # Check that response has required fields
        # (without caring about extra fields)
        # Note: status can be "online" or "inactive"
        assert "status" in data
        assert data["status"] in ["online", "inactive"]

    @pytest.mark.skip(reason="Example - requires SQL logging setup")
    @with_sql_logging
    def test_with_sql_logging(self, db_session):
        """Log all SQL queries in this test."""
        # All queries will be logged to stdout
        from models import Trip
        trips = db_session.query(Trip).all()

        # At the end, you'll see total query count


# ============================================================================
# Example 3: Using API Test Helpers
# ============================================================================


class TestAPIHelpers:
    """Examples of using API test helpers."""

    def test_api_helper_get(self, client, db_session):
        """Use APITestHelper for GET requests."""
        # Create some test data
        TripFactory.create_batch(3, db_session=db_session)

        # Use helper to make request
        api = APITestHelper(client)
        response_data = api.get("/api/trips")

        # Helper automatically asserts success and parses JSON
        assert "trips" in response_data

    def test_api_helper_expect_error(self, client):
        """Expect an error response."""
        api = APITestHelper(client)

        # This won't raise an exception because we expect 404
        api.expect_error("GET", "/api/trips/999999", expected_status=404)

    def test_torque_data_builder(self, client):
        """Build Torque Pro data easily."""
        torque_data = (TorqueDataBuilder()
            .with_location(37.7749, -122.4194)
            .with_speed(45.0)
            .with_soc(80.0)
            .with_odometer(50000.0)
            .electric_mode()
            .build())

        response = client.post("/torque/upload", data=torque_data)
        assert response.status_code == 200


# ============================================================================
# Example 4: Using Integration Test Scenarios
# ============================================================================


@pytest.mark.integration
class TestIntegrationScenarios:
    """Examples of using integration test scenarios."""

    def test_trip_scenario(self, client, db_session):
        """Simulate a complete trip workflow."""
        scenario = TripScenario(client, db_session)

        # Chain operations fluently
        (scenario
            .start_trip()
            .add_telemetry_points(count=10, mode="electric")
            .end_trip()
            .verify_trip_created()
            .verify_telemetry_count(expected_count=10))

    def test_mixed_mode_trip(self, client, db_session):
        """Test a trip that uses both electric and gas."""
        scenario = TripScenario(client, db_session)

        (scenario
            .start_trip()
            .add_telemetry_points(count=20, mode="mixed")
            .end_trip()
            .verify_trip_created())

    def test_daily_driving_scenario(self, client, db_session):
        """Simulate a complete day of driving."""
        scenario = DailyDrivingScenario(client, db_session)

        (scenario
            .morning_commute(distance_miles=25.0)
            .evening_commute(distance_miles=25.0, use_gas=False)
            .overnight_charge()
            .verify_daily_stats())

        # Verify we created trips and charging
        assert len(scenario.trips) == 2
        assert len(scenario.charging_sessions) == 1


# ============================================================================
# Example 5: Using New Fixtures from Plugins
# ============================================================================


class TestNewFixtures:
    """Examples of using new fixtures from pytest plugins."""

    def test_with_api_helper_fixture(self, api_helper, db_session):
        """Use the api_helper fixture."""
        # Create test data
        TripFactory.create(db_session=db_session)

        # Helper is automatically available
        data = api_helper.get("/api/trips")
        assert "trips" in data

    def test_with_torque_builder_fixture(self, client, torque_builder):
        """Use the torque_builder fixture."""
        # Builder is automatically available
        torque_data = (torque_builder
            .with_speed(50.0)
            .with_soc(85.0)
            .electric_mode()
            .build())

        response = client.post("/torque/upload", data=torque_data)
        assert response.status_code == 200


# ============================================================================
# Example 6: Using Test Markers
# ============================================================================


@pytest.mark.slow
def test_slow_operation():
    """This test is marked as slow."""
    # Simulate slow operation
    import time
    time.sleep(0.1)  # Would be longer in real test


@pytest.mark.integration
def test_integration_scenario(client, db_session):
    """This test is marked as integration."""
    # Integration test code
    pass


@pytest.mark.unit
def test_unit_calculation():
    """This test is marked as unit test."""
    from utils.calculations import smooth_fuel_level

    result = smooth_fuel_level([75.0, 75.5, 76.0])
    assert result == 75.5


# ============================================================================
# Example 7: Parametrized Tests with Factories
# ============================================================================


class TestParametrized:
    """Examples of parametrized tests with factories."""

    @pytest.mark.parametrize("distance,expected_category", [
        (5.0, "short"),
        (25.0, "medium"),
        (100.0, "long"),
    ])
    def test_trip_distance_categories(self, db_session, distance, expected_category):
        """Test trip categorization by distance."""
        trip = TripFactory.create(
            db_session=db_session,
            distance_miles=distance
        )

        # You could add categorization logic to the Trip model
        if distance < 10:
            category = "short"
        elif distance < 50:
            category = "medium"
        else:
            category = "long"

        assert category == expected_category

    @pytest.mark.parametrize("charge_type,expected_power", [
        ("L1", 1.4),
        ("L2", 6.6),
        ("DCFC", 50.0),
    ])
    def test_charging_power_levels(self, db_session, charge_type, expected_power):
        """Test different charging power levels."""
        if charge_type == "L1":
            session = ChargingSessionFactory.create_l1(db_session=db_session)
        elif charge_type == "L2":
            session = ChargingSessionFactory.create_l2(db_session=db_session)
        else:
            session = ChargingSessionFactory.create_dcfc(db_session=db_session)

        assert abs(session.avg_power_kw - expected_power) <= 10.0  # Allow variance


# ============================================================================
# Tips for Running These Examples
# ============================================================================

"""
# Run all examples
SECRET_KEY=test pytest tests/test_example_using_new_features.py -v

# Run only integration tests
SECRET_KEY=test pytest tests/test_example_using_new_features.py -v -m integration

# Run with performance tracking
PYTEST_ENABLE_PERF_TRACKING=1 SECRET_KEY=test pytest tests/test_example_using_new_features.py -v

# Run with categorization
PYTEST_ENABLE_CATEGORIZATION=1 SECRET_KEY=test pytest tests/test_example_using_new_features.py -v

# Run excluding slow tests
SECRET_KEY=test pytest tests/test_example_using_new_features.py -v -m "not slow"
"""
