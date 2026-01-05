"""
Tests for battery degradation service module.

Tests the battery capacity degradation forecasting including:
- Degradation history calculation
- Linear regression for trend analysis
- Forecasting at key mileage milestones
- Comparison with typical Volt Gen 2 degradation
- Edge cases and validation
"""

from datetime import datetime, timedelta, timezone

import pytest
from models import BatteryHealthReading
from services.battery_degradation_service import (
    forecast_degradation,
    get_current_health,
    get_degradation_history,
    simple_linear_regression,
)


class TestGetDegradationHistory:
    """Tests for get_degradation_history function."""

    def test_returns_health_readings(self, app, db_session):
        """Returns list of health readings."""
        now = datetime.now(timezone.utc)

        # Add health readings
        for i in range(5):
            reading = BatteryHealthReading(
                timestamp=now - timedelta(days=i * 30),
                capacity_kwh=18.4 - (i * 0.1),  # Gradual degradation
                normalized_capacity_kwh=18.4 - (i * 0.1),
                soc_at_reading=100.0,
            )
            db_session.add(reading)
        db_session.commit()

        history = get_degradation_history(db_session)

        assert len(history) >= 5
        # Each entry is (odometer, capacity) tuple
        for odo, cap in history:
            assert isinstance(odo, (int, float))
            assert isinstance(cap, float)
            assert cap > 0

    def test_orders_by_odometer(self, app, db_session):
        """History ordered by odometer (ascending)."""
        now = datetime.now(timezone.utc)

        # Add readings with odometers
        readings = [
            (50000.0, 18.4),
            (55000.0, 18.3),
            (60000.0, 18.1),
            (65000.0, 18.0),
        ]

        for odo, cap in readings:
            reading = BatteryHealthReading(
                timestamp=now,
                capacity_kwh=cap,
                normalized_capacity_kwh=cap,
                odometer_miles=odo,
            )
            db_session.add(reading)
        db_session.commit()

        history = get_degradation_history(db_session)

        # Verify ordering
        for i in range(1, len(history)):
            assert history[i][0] >= history[i - 1][0]

    def test_uses_normalized_capacity(self, app, db_session):
        """Uses normalized_capacity_kwh when available."""
        now = datetime.now(timezone.utc)

        reading = BatteryHealthReading(
            timestamp=now,
            capacity_kwh=17.5,  # Different from normalized
            normalized_capacity_kwh=17.8,  # Should use this
            odometer_miles=55000.0,
        )
        db_session.add(reading)
        db_session.commit()

        history = get_degradation_history(db_session)

        assert len(history) == 1
        _, capacity = history[0]
        assert capacity == 17.8  # Uses normalized

    def test_falls_back_to_capacity_kwh(self, app, db_session):
        """Falls back to capacity_kwh if normalized is None."""
        now = datetime.now(timezone.utc)

        reading = BatteryHealthReading(
            timestamp=now,
            capacity_kwh=17.5,
            normalized_capacity_kwh=None,  # Not available
            odometer_miles=55000.0,
        )
        db_session.add(reading)
        db_session.commit()

        history = get_degradation_history(db_session)

        assert len(history) == 1
        _, capacity = history[0]
        assert capacity == 17.5  # Falls back

    def test_excludes_null_capacity(self, app, db_session):
        """Excludes readings with null capacity."""
        now = datetime.now(timezone.utc)

        # Reading with null capacity
        reading1 = BatteryHealthReading(
            timestamp=now,
            capacity_kwh=None,
            normalized_capacity_kwh=None,
            odometer_miles=55000.0,
        )
        db_session.add(reading1)

        # Valid reading
        reading2 = BatteryHealthReading(
            timestamp=now,
            capacity_kwh=17.5,
            odometer_miles=56000.0,
        )
        db_session.add(reading2)
        db_session.commit()

        history = get_degradation_history(db_session)

        # Should only include valid reading
        assert len(history) == 1

    def test_no_readings_returns_empty(self, app, db_session):
        """No readings returns empty list."""
        history = get_degradation_history(db_session)
        assert history == []


class TestSimpleLinearRegression:
    """Tests for simple_linear_regression function."""

    def test_perfect_linear_relationship(self):
        """Perfect linear relationship: y = 2x + 1."""
        data = [
            (0.0, 1.0),
            (1.0, 3.0),
            (2.0, 5.0),
            (3.0, 7.0),
            (4.0, 9.0),
        ]

        slope, intercept = simple_linear_regression(data)

        assert slope == pytest.approx(2.0, abs=0.01)
        assert intercept == pytest.approx(1.0, abs=0.01)

    def test_negative_slope(self):
        """Negative slope (degradation): y = -0.5x + 18.4."""
        data = [
            (0.0, 18.4),
            (10000.0, 18.3),
            (20000.0, 18.2),
            (30000.0, 18.1),
            (40000.0, 18.0),
        ]

        slope, intercept = simple_linear_regression(data)

        # Slope should be negative (degradation)
        assert slope < 0
        # Intercept should be around 18.4
        assert intercept > 18.0

    def test_horizontal_line(self):
        """Horizontal line (no degradation): y = 18.4."""
        data = [
            (0.0, 18.4),
            (10000.0, 18.4),
            (20000.0, 18.4),
            (30000.0, 18.4),
        ]

        slope, intercept = simple_linear_regression(data)

        assert slope == pytest.approx(0.0, abs=0.001)
        assert intercept == pytest.approx(18.4, abs=0.01)

    def test_single_point(self):
        """Single data point."""
        data = [(10000.0, 18.0)]

        # Should raise error or handle gracefully
        # Division by zero in denominator
        with pytest.raises(ZeroDivisionError):
            simple_linear_regression(data)

    def test_two_points(self):
        """Two points define a line."""
        data = [
            (0.0, 18.4),
            (50000.0, 17.9),  # Lost 0.5 kWh over 50k miles
        ]

        slope, intercept = simple_linear_regression(data)

        # Slope: -0.5 / 50000 = -0.00001
        assert slope < 0
        assert intercept == pytest.approx(18.4, abs=0.01)

    def test_noisy_data(self):
        """Handles noisy data with trend."""
        data = [
            (0.0, 18.4),
            (10000.0, 18.2),  # Noise
            (20000.0, 18.0),
            (30000.0, 17.9),  # Noise
            (40000.0, 17.7),
            (50000.0, 17.6),  # Noise
        ]

        slope, intercept = simple_linear_regression(data)

        # Should still capture negative trend
        assert slope < 0
        assert 18.0 < intercept < 18.5


class TestForecastDegradation:
    """Tests for forecast_degradation function."""

    def test_forecasts_at_milestones(self, app, db_session):
        """Forecasts capacity at 50k, 100k, 150k, 200k miles."""
        now = datetime.now(timezone.utc)

        # Add degradation history
        readings = [
            (50000.0, 18.3),
            (60000.0, 18.2),
            (70000.0, 18.1),
            (80000.0, 18.0),
        ]

        for odo, cap in readings:
            reading = BatteryHealthReading(
                timestamp=now,
                capacity_kwh=cap,
                normalized_capacity_kwh=cap,
                odometer_miles=odo,
            )
            db_session.add(reading)
        db_session.commit()

        forecast = forecast_degradation(db_session)

        assert forecast is not None
        assert "forecasts" in forecast
        assert "current_capacity_kwh" in forecast
        assert "degradation_rate_per_mile" in forecast

        # Should have forecasts for key milestones
        forecasts = forecast["forecasts"]
        milestones = [f["miles"] for f in forecasts]
        assert 50000 in milestones
        assert 100000 in milestones
        assert 150000 in milestones
        assert 200000 in milestones

    def test_capacity_decreases_over_mileage(self, app, db_session):
        """Forecasted capacity decreases with mileage."""
        now = datetime.now(timezone.utc)

        # Clear degradation trend
        readings = [
            (50000.0, 18.4),
            (70000.0, 18.2),
            (90000.0, 18.0),
        ]

        for odo, cap in readings:
            reading = BatteryHealthReading(
                timestamp=now,
                capacity_kwh=cap,
                odometer_miles=odo,
            )
            db_session.add(reading)
        db_session.commit()

        forecast = forecast_degradation(db_session)

        assert forecast is not None
        forecasts = forecast["forecasts"]

        # Capacity at 200k should be less than 100k
        capacity_100k = next(f["capacity_kwh"] for f in forecasts if f["miles"] == 100000)
        capacity_200k = next(f["capacity_kwh"] for f in forecasts if f["miles"] == 200000)

        assert capacity_200k < capacity_100k

    def test_compares_to_typical_volt(self, app, db_session):
        """Compares forecast to typical Volt Gen 2 degradation."""
        now = datetime.now(timezone.utc)

        # Add readings
        readings = [
            (50000.0, 18.3),
            (75000.0, 18.1),
        ]

        for odo, cap in readings:
            reading = BatteryHealthReading(
                timestamp=now,
                capacity_kwh=cap,
                odometer_miles=odo,
            )
            db_session.add(reading)
        db_session.commit()

        forecast = forecast_degradation(db_session)

        assert forecast is not None
        forecasts = forecast["forecasts"]

        # Each forecast should have comparison to typical
        for f in forecasts:
            assert "vs_typical_volt" in f
            # Should be string like "Better" or "Worse" or "Similar"

    def test_calculates_degradation_rate(self, app, db_session):
        """Calculates degradation rate per mile."""
        now = datetime.now(timezone.utc)

        readings = [
            (50000.0, 18.4),
            (100000.0, 17.9),  # Lost 0.5 kWh over 50k miles
        ]

        for odo, cap in readings:
            reading = BatteryHealthReading(
                timestamp=now,
                capacity_kwh=cap,
                odometer_miles=odo,
            )
            db_session.add(reading)
        db_session.commit()

        forecast = forecast_degradation(db_session)

        assert forecast is not None
        assert "degradation_rate_per_mile" in forecast

        rate = forecast["degradation_rate_per_mile"]
        # 0.5 kWh / 50k miles = 0.00001 kWh/mile
        assert rate < 0  # Should be negative

    def test_insufficient_data_returns_none(self, app, db_session):
        """Less than 2 readings returns None."""
        now = datetime.now(timezone.utc)

        # Only one reading
        reading = BatteryHealthReading(
            timestamp=now,
            capacity_kwh=18.3,
            odometer_miles=55000.0,
        )
        db_session.add(reading)
        db_session.commit()

        forecast = forecast_degradation(db_session)

        assert forecast is None

    def test_no_data_returns_none(self, app, db_session):
        """No health readings returns None."""
        forecast = forecast_degradation(db_session)
        assert forecast is None

    def test_includes_confidence_metric(self, app, db_session):
        """Includes confidence based on data quality."""
        now = datetime.now(timezone.utc)

        # More readings = higher confidence
        for i in range(10):
            reading = BatteryHealthReading(
                timestamp=now - timedelta(days=i * 30),
                capacity_kwh=18.4 - (i * 0.05),
                odometer_miles=50000.0 + (i * 5000),
            )
            db_session.add(reading)
        db_session.commit()

        forecast = forecast_degradation(db_session)

        assert forecast is not None
        assert "confidence" in forecast
        # More data should give higher confidence
        assert forecast["confidence"] > 0.5


class TestGetCurrentHealth:
    """Tests for get_current_health function."""

    def test_returns_most_recent_reading(self, app, db_session):
        """Returns the most recent health reading."""
        now = datetime.now(timezone.utc)

        # Add multiple readings
        readings = [
            (now - timedelta(days=60), 18.0),
            (now - timedelta(days=30), 18.1),
            (now - timedelta(days=5), 18.2),  # Most recent
        ]

        for timestamp, cap in readings:
            reading = BatteryHealthReading(
                timestamp=timestamp,
                capacity_kwh=cap,
                normalized_capacity_kwh=cap,
            )
            db_session.add(reading)
        db_session.commit()

        current = get_current_health(db_session)

        assert current is not None
        # Should return most recent (18.2)
        assert current["capacity_kwh"] == 18.2

    def test_uses_normalized_capacity(self, app, db_session):
        """Prefers normalized_capacity_kwh."""
        now = datetime.now(timezone.utc)

        reading = BatteryHealthReading(
            timestamp=now,
            capacity_kwh=17.5,
            normalized_capacity_kwh=17.8,
            odometer_miles=55000.0,
        )
        db_session.add(reading)
        db_session.commit()

        current = get_current_health(db_session)

        assert current is not None
        assert current["capacity_kwh"] == 17.8

    def test_calculates_percent_of_new(self, app, db_session):
        """Calculates percentage of new capacity."""
        now = datetime.now(timezone.utc)

        reading = BatteryHealthReading(
            timestamp=now,
            normalized_capacity_kwh=16.56,  # 90% of 18.4
            odometer_miles=55000.0,
        )
        db_session.add(reading)
        db_session.commit()

        current = get_current_health(db_session)

        assert current is not None
        assert "percent_of_new" in current
        # Should be around 90%
        assert 89 < current["percent_of_new"] < 91

    def test_includes_odometer(self, app, db_session):
        """Includes odometer reading."""
        now = datetime.now(timezone.utc)

        reading = BatteryHealthReading(
            timestamp=now,
            capacity_kwh=18.0,
            odometer_miles=75000.0,
        )
        db_session.add(reading)
        db_session.commit()

        current = get_current_health(db_session)

        assert current is not None
        assert "odometer_miles" in current
        assert current["odometer_miles"] == 75000.0

    def test_no_readings_returns_none(self, app, db_session):
        """No health readings returns None."""
        current = get_current_health(db_session)
        assert current is None


class TestBatteryDegradationValidation:
    """Tests for validation and edge cases."""

    def test_capacity_increasing_over_time(self, app, db_session):
        """Handles anomalous case where capacity increases."""
        now = datetime.now(timezone.utc)

        # Anomalous readings (capacity increasing)
        readings = [
            (50000.0, 17.5),
            (60000.0, 17.8),  # Increased?
            (70000.0, 18.0),
        ]

        for odo, cap in readings:
            reading = BatteryHealthReading(
                timestamp=now,
                capacity_kwh=cap,
                odometer_miles=odo,
            )
            db_session.add(reading)
        db_session.commit()

        forecast = forecast_degradation(db_session)

        # Should still work, but may give positive slope
        assert forecast is not None
        # Slope might be positive (unusual)

    def test_very_degraded_battery(self, app, db_session):
        """Handles severely degraded battery."""
        now = datetime.now(timezone.utc)

        readings = [
            (150000.0, 16.0),
            (160000.0, 15.8),
            (170000.0, 15.5),
        ]

        for odo, cap in readings:
            reading = BatteryHealthReading(
                timestamp=now,
                capacity_kwh=cap,
                odometer_miles=odo,
            )
            db_session.add(reading)
        db_session.commit()

        forecast = forecast_degradation(db_session)

        assert forecast is not None
        # Should still forecast, even with low capacity

    def test_forecasts_dont_go_negative(self, app, db_session):
        """Forecasts should not predict negative capacity."""
        now = datetime.now(timezone.utc)

        # Severe degradation
        readings = [
            (50000.0, 18.0),
            (100000.0, 14.0),  # Extreme degradation
        ]

        for odo, cap in readings:
            reading = BatteryHealthReading(
                timestamp=now,
                capacity_kwh=cap,
                odometer_miles=odo,
            )
            db_session.add(reading)
        db_session.commit()

        forecast = forecast_degradation(db_session)

        assert forecast is not None

        # All forecasts should be positive
        for f in forecast["forecasts"]:
            assert f["capacity_kwh"] > 0

    def test_zero_mileage_readings(self, app, db_session):
        """Handles readings with zero or null mileage."""
        now = datetime.now(timezone.utc)

        reading1 = BatteryHealthReading(
            timestamp=now - timedelta(days=30),
            capacity_kwh=18.3,
            odometer_miles=None,  # Missing
        )
        reading2 = BatteryHealthReading(
            timestamp=now,
            capacity_kwh=18.2,
            odometer_miles=55000.0,  # Valid
        )
        db_session.add(reading1)
        db_session.add(reading2)
        db_session.commit()

        # Should exclude reading without mileage
        history = get_degradation_history(db_session)

        # Should only have one reading
        assert len(history) <= 1

    def test_duplicate_mileage_readings(self, app, db_session):
        """Handles multiple readings at same mileage."""
        now = datetime.now(timezone.utc)

        # Two readings at same mileage
        reading1 = BatteryHealthReading(
            timestamp=now - timedelta(days=1),
            capacity_kwh=18.2,
            odometer_miles=55000.0,
        )
        reading2 = BatteryHealthReading(
            timestamp=now,
            capacity_kwh=18.3,  # Different reading
            odometer_miles=55000.0,  # Same mileage
        )
        db_session.add(reading1)
        db_session.add(reading2)
        db_session.commit()

        history = get_degradation_history(db_session)

        # Should include both or handle duplicates gracefully
        assert len(history) >= 1
