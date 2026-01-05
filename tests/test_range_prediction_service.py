"""
Tests for range prediction service module.

Tests the predictive range estimation including:
- Simple statistical model for range prediction
- Temperature adjustment factors
- Speed adjustment factors
- Battery health adjustments
- Historical efficiency calculations
- Edge cases and validation
"""

import uuid
from datetime import datetime, timedelta, timezone

import pytest
from models import Trip
from services.range_prediction_service import get_historical_efficiency, predict_range_simple


class TestPredictRangeSimple:
    """Tests for predict_range_simple function."""

    def test_predicts_range_with_default_params(self, app, db_session):
        """Predict range with default parameters (70°F, 30 mph, 100% health)."""
        # Add some historical data
        # session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        for i in range(10):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=now - timedelta(days=i),
                end_time=now - timedelta(days=i) + timedelta(hours=1),
                electric_miles=25.0,
                electric_kwh_used=5.0,  # 5 mi/kWh efficiency
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        result = predict_range_simple(db_session)

        assert result is not None
        assert "predicted_range_miles" in result
        assert "confidence" in result
        assert "factors" in result
        assert result["predicted_range_miles"] > 0
        assert 0.0 <= result["confidence"] <= 1.0

    def test_freezing_temperature_penalty(self, app, db_session):
        """Freezing temperature (< 32°F) applies 35% penalty."""
        # Add historical data
        for i in range(10):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                electric_miles=30.0,
                electric_kwh_used=6.0,  # 5 mi/kWh
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        result = predict_range_simple(
            db_session,
            temperature=20.0,  # Freezing
            battery_health_pct=100.0,
            avg_speed=30.0,
        )

        assert result is not None
        assert result["factors"]["temperature_factor"] == pytest.approx(0.65, abs=0.01)
        # Range should be significantly reduced
        assert result["predicted_range_miles"] < 50  # Much less than ideal ~82 miles

    def test_cold_temperature_penalty(self, app, db_session):
        """Cold temperature (32-50°F) applies 20% penalty."""
        for i in range(10):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                electric_miles=30.0,
                electric_kwh_used=6.0,
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        result = predict_range_simple(
            db_session,
            temperature=40.0,  # Cold
        )

        assert result is not None
        assert result["factors"]["temperature_factor"] == pytest.approx(0.80, abs=0.01)

    def test_hot_temperature_penalty(self, app, db_session):
        """Hot temperature (> 85°F) applies 10% penalty."""
        for i in range(10):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                electric_miles=30.0,
                electric_kwh_used=6.0,
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        result = predict_range_simple(
            db_session,
            temperature=95.0,  # Hot
        )

        assert result is not None
        assert result["factors"]["temperature_factor"] == pytest.approx(0.90, abs=0.01)

    def test_ideal_temperature_no_penalty(self, app, db_session):
        """Ideal temperature (50-85°F) has no penalty."""
        for i in range(10):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                electric_miles=30.0,
                electric_kwh_used=6.0,
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        result = predict_range_simple(
            db_session,
            temperature=70.0,  # Ideal
        )

        assert result is not None
        assert result["factors"]["temperature_factor"] == 1.0

    def test_highway_speed_penalty(self, app, db_session):
        """Highway speed (> 65 mph) applies 25% penalty."""
        for i in range(10):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                electric_miles=30.0,
                electric_kwh_used=6.0,
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        result = predict_range_simple(
            db_session,
            avg_speed=75.0,  # Highway
        )

        assert result is not None
        assert result["factors"]["speed_factor"] == pytest.approx(0.75, abs=0.01)

    def test_moderate_speed_penalty(self, app, db_session):
        """Moderate highway speed (50-65 mph) applies 15% penalty."""
        for i in range(10):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                electric_miles=30.0,
                electric_kwh_used=6.0,
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        result = predict_range_simple(
            db_session,
            avg_speed=60.0,  # Moderate highway
        )

        assert result is not None
        assert result["factors"]["speed_factor"] == pytest.approx(0.85, abs=0.01)

    def test_city_speed_bonus(self, app, db_session):
        """City speed (< 30 mph) applies 10% bonus."""
        for i in range(10):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                electric_miles=30.0,
                electric_kwh_used=6.0,
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        result = predict_range_simple(
            db_session,
            avg_speed=25.0,  # City
        )

        assert result is not None
        assert result["factors"]["speed_factor"] == pytest.approx(1.10, abs=0.01)

    def test_ideal_speed_no_adjustment(self, app, db_session):
        """Ideal speed (30-50 mph) has no adjustment."""
        for i in range(10):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                electric_miles=30.0,
                electric_kwh_used=6.0,
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        result = predict_range_simple(
            db_session,
            avg_speed=40.0,  # Ideal
        )

        assert result is not None
        assert result["factors"]["speed_factor"] == 1.0

    def test_battery_health_adjustment(self, app, db_session):
        """Battery health affects range linearly."""
        for i in range(10):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                electric_miles=30.0,
                electric_kwh_used=6.0,
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        # 90% health
        result_90 = predict_range_simple(
            db_session,
            battery_health_pct=90.0,
            temperature=70.0,
            avg_speed=40.0,
        )

        # 100% health
        result_100 = predict_range_simple(
            db_session,
            battery_health_pct=100.0,
            temperature=70.0,
            avg_speed=40.0,
        )

        assert result_90 is not None
        assert result_100 is not None
        assert result_90["factors"]["health_factor"] == pytest.approx(0.90, abs=0.01)
        assert result_100["factors"]["health_factor"] == 1.0
        # 90% health should give ~90% of range
        assert result_90["predicted_range_miles"] < result_100["predicted_range_miles"]

    def test_worst_case_scenario(self, app, db_session):
        """Worst case: freezing, highway, degraded battery."""
        for i in range(10):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                electric_miles=30.0,
                electric_kwh_used=6.0,
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        result = predict_range_simple(
            db_session,
            temperature=20.0,  # Freezing (0.65x)
            avg_speed=75.0,  # Highway (0.75x)
            battery_health_pct=85.0,  # Degraded (0.85x)
        )

        assert result is not None
        # Range should be significantly reduced
        # expected_factor = 0.65 * 0.75 * 0.85
        assert result["factors"]["temperature_factor"] == pytest.approx(0.65, abs=0.01)
        assert result["factors"]["speed_factor"] == pytest.approx(0.75, abs=0.01)
        assert result["factors"]["health_factor"] == pytest.approx(0.85, abs=0.01)

    def test_best_case_scenario(self, app, db_session):
        """Best case: ideal temp, city speed, new battery."""
        for i in range(10):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                electric_miles=30.0,
                electric_kwh_used=6.0,
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        result = predict_range_simple(
            db_session,
            temperature=70.0,  # Ideal (1.0x)
            avg_speed=25.0,  # City (1.1x)
            battery_health_pct=100.0,  # New (1.0x)
        )

        assert result is not None
        assert result["factors"]["temperature_factor"] == 1.0
        assert result["factors"]["speed_factor"] == pytest.approx(1.10, abs=0.01)
        assert result["factors"]["health_factor"] == 1.0

    def test_no_historical_data_uses_default(self, app, db_session):
        """No historical data uses default efficiency (5.0 mi/kWh)."""
        # No trips in database
        result = predict_range_simple(db_session)

        assert result is not None
        assert "predicted_range_miles" in result
        # With default 5.0 mi/kWh and 16.5 kWh capacity
        # Ideal conditions: 5.0 * 16.5 = 82.5 miles
        assert result["predicted_range_miles"] > 0

    def test_insufficient_historical_data(self, app, db_session):
        """Less than 3 trips uses default efficiency with low confidence."""
        # Add only 2 trips
        for i in range(2):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                electric_miles=30.0,
                electric_kwh_used=6.0,
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        result = predict_range_simple(db_session)

        assert result is not None
        # Confidence should be reduced with limited data
        assert result["confidence"] < 0.9

    def test_custom_battery_capacity(self, app, db_session):
        """Custom battery capacity affects range proportionally."""
        for i in range(10):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                electric_miles=30.0,
                electric_kwh_used=6.0,  # 5 mi/kWh
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        # Smaller capacity
        result_small = predict_range_simple(
            db_session,
            battery_capacity_kwh=10.0,
            temperature=70.0,
            avg_speed=40.0,
        )

        # Larger capacity
        result_large = predict_range_simple(
            db_session,
            battery_capacity_kwh=20.0,
            temperature=70.0,
            avg_speed=40.0,
        )

        assert result_small is not None
        assert result_large is not None
        # Larger capacity should give proportionally more range
        assert result_large["predicted_range_miles"] > result_small["predicted_range_miles"]


class TestGetHistoricalEfficiency:
    """Tests for get_historical_efficiency function."""

    def test_returns_efficiency_data(self, app, db_session):
        """Returns list of efficiency tuples."""
        # Add trips with efficiency data
        for i in range(5):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                electric_miles=25.0,
                electric_kwh_used=5.0,  # 5 mi/kWh
                ambient_temp_avg_f=70.0,
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        result = get_historical_efficiency(db_session, days=30)

        assert len(result) == 5
        # Each tuple: (temp, speed, soc_change, efficiency)
        for temp, speed, soc, eff in result:
            assert isinstance(eff, float)
            assert eff > 0

    def test_filters_by_days(self, app, db_session):
        """Only returns trips within specified days."""
        now = datetime.now(timezone.utc)

        # Add old trip (beyond 30 days)
        old_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=40),
            electric_miles=25.0,
            electric_kwh_used=5.0,
            is_closed=True,
        )
        db_session.add(old_trip)

        # Add recent trip
        recent_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=10),
            electric_miles=25.0,
            electric_kwh_used=5.0,
            is_closed=True,
        )
        db_session.add(recent_trip)
        db_session.commit()

        result = get_historical_efficiency(db_session, days=30)

        # Should only include recent trip
        assert len(result) == 1

    def test_excludes_trips_without_kwh_data(self, app, db_session):
        """Trips without electric_kwh_used are excluded."""
        # Trip without kWh data
        trip_no_kwh = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc) - timedelta(days=1),
            electric_miles=25.0,
            electric_kwh_used=None,  # Missing
            is_closed=True,
        )
        db_session.add(trip_no_kwh)

        # Trip with kWh data
        trip_with_kwh = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc) - timedelta(days=2),
            electric_miles=25.0,
            electric_kwh_used=5.0,
            is_closed=True,
        )
        db_session.add(trip_with_kwh)
        db_session.commit()

        result = get_historical_efficiency(db_session, days=30)

        # Should only include trip with kWh data
        assert len(result) == 1

    def test_handles_no_trips(self, app, db_session):
        """No trips returns empty list."""
        result = get_historical_efficiency(db_session, days=90)
        assert result == []


class TestRangePredictionValidation:
    """Tests for validation and edge cases."""

    def test_negative_temperature(self, app, db_session):
        """Negative temperature is handled."""
        for i in range(10):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                electric_miles=30.0,
                electric_kwh_used=6.0,
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        result = predict_range_simple(
            db_session,
            temperature=-10.0,  # Extreme cold
        )

        assert result is not None
        # Should still apply freezing penalty
        assert result["factors"]["temperature_factor"] == pytest.approx(0.65, abs=0.01)

    def test_zero_speed(self, app, db_session):
        """Zero speed is handled."""
        for i in range(10):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                electric_miles=30.0,
                electric_kwh_used=6.0,
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        result = predict_range_simple(
            db_session,
            avg_speed=0.0,
        )

        assert result is not None
        # Should apply city speed bonus
        assert result["factors"]["speed_factor"] == pytest.approx(1.10, abs=0.01)

    def test_invalid_battery_health(self, app, db_session):
        """Battery health > 100% is clamped to 100%."""
        for i in range(10):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                electric_miles=30.0,
                electric_kwh_used=6.0,
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        result = predict_range_simple(
            db_session,
            battery_health_pct=120.0,  # Invalid
        )

        assert result is not None
        # Should be clamped to 1.0
        assert result["factors"]["health_factor"] == 1.0
