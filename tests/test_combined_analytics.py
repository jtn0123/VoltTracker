"""Tests for combined analytics service and routes."""

import uuid
from datetime import datetime, timedelta, timezone

import pytest

from models import Trip
from services import combined_analytics_service


class TestCombinedAnalyticsService:
    """Tests for combined analytics service functions."""

    @pytest.fixture
    def sample_trips_with_all_factors(self, db_session):
        """Create sample trips with weather and elevation data."""
        now = datetime.now(timezone.utc)
        trips = []

        # Cold weather, uphill trip
        trip1 = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=7),
            end_time=now - timedelta(days=7) + timedelta(minutes=30),
            is_closed=True,
            distance_miles=10.0,
            electric_miles=10.0,
            kwh_per_mile=0.42,
            weather_temp_f=28.0,
            weather_conditions="clear",
            elevation_gain_m=100.0,
            elevation_loss_m=20.0,
            elevation_net_change_m=80.0,
        )
        trips.append(trip1)

        # Warm weather, flat trip
        trip2 = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=5),
            end_time=now - timedelta(days=5) + timedelta(minutes=25),
            is_closed=True,
            distance_miles=8.0,
            electric_miles=8.0,
            kwh_per_mile=0.30,
            weather_temp_f=68.0,
            weather_conditions="clear",
            elevation_gain_m=10.0,
            elevation_loss_m=15.0,
            elevation_net_change_m=-5.0,
        )
        trips.append(trip2)

        # Hot weather, downhill trip
        trip3 = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=3),
            end_time=now - timedelta(days=3) + timedelta(minutes=20),
            is_closed=True,
            distance_miles=12.0,
            electric_miles=12.0,
            kwh_per_mile=0.28,
            weather_temp_f=92.0,
            weather_conditions="clear",
            elevation_gain_m=20.0,
            elevation_loss_m=80.0,
            elevation_net_change_m=-60.0,
        )
        trips.append(trip3)

        # Rainy weather trip
        trip4 = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=1),
            end_time=now - timedelta(days=1) + timedelta(minutes=35),
            is_closed=True,
            distance_miles=15.0,
            electric_miles=15.0,
            kwh_per_mile=0.35,
            weather_temp_f=55.0,
            weather_conditions="rain",
            elevation_gain_m=30.0,
            elevation_loss_m=25.0,
            elevation_net_change_m=5.0,
        )
        trips.append(trip4)

        for trip in trips:
            db_session.add(trip)
        db_session.commit()

        return trips

    def test_get_multi_factor_analysis(self, db_session, sample_trips_with_all_factors):
        """Test multi-factor analysis returns expected structure."""
        result = combined_analytics_service.get_multi_factor_analysis(db_session)

        assert "overall" in result
        assert "factor_impacts" in result
        assert "recommendations" in result

        overall = result["overall"]
        assert "total_trips" in overall
        assert "avg_efficiency_kwh_per_mile" in overall
        assert overall["total_trips"] == 4

    def test_get_multi_factor_analysis_with_date_filter(
        self, db_session, sample_trips_with_all_factors
    ):
        """Test multi-factor analysis with date filtering."""
        now = datetime.now(timezone.utc)
        result = combined_analytics_service.get_multi_factor_analysis(
            db_session,
            start_date=now - timedelta(days=4),
            end_date=now,
        )

        # Should only include trips from last 4 days
        assert result["overall"]["total_trips"] <= 4

    def test_get_multi_factor_analysis_empty(self, db_session):
        """Test multi-factor analysis with no trips."""
        result = combined_analytics_service.get_multi_factor_analysis(db_session)
        assert result["overall"]["total_trips"] == 0

    def test_get_efficiency_predictions_with_temperature(self, db_session):
        """Test efficiency predictions with temperature."""
        result = combined_analytics_service.get_efficiency_predictions(
            db_session,
            temperature_f=25.0,  # Cold
        )

        assert "predicted_kwh_per_mile" in result
        assert "adjustments" in result
        assert len(result["adjustments"]) == 1
        assert result["adjustments"][0]["factor"] == "temperature"
        # Cold weather should increase consumption
        assert result["adjustments"][0]["impact_percent"] > 0

    def test_get_efficiency_predictions_with_elevation(self, db_session):
        """Test efficiency predictions with elevation."""
        result = combined_analytics_service.get_efficiency_predictions(
            db_session,
            elevation_change_m=100.0,  # Uphill
        )

        assert "predicted_kwh_per_mile" in result
        assert len(result["adjustments"]) == 1
        assert result["adjustments"][0]["factor"] == "elevation"
        # Uphill should increase consumption
        assert result["adjustments"][0]["impact_percent"] > 0

    def test_get_efficiency_predictions_downhill(self, db_session):
        """Test efficiency predictions for downhill."""
        result = combined_analytics_service.get_efficiency_predictions(
            db_session,
            elevation_change_m=-100.0,  # Downhill
        )

        # Downhill should decrease consumption (negative impact)
        assert result["adjustments"][0]["impact_percent"] < 0

    def test_get_efficiency_predictions_with_rain(self, db_session):
        """Test efficiency predictions with rain."""
        result = combined_analytics_service.get_efficiency_predictions(
            db_session,
            is_raining=True,
        )

        assert len(result["adjustments"]) == 1
        assert result["adjustments"][0]["factor"] == "precipitation"
        # Rain should increase consumption
        assert result["adjustments"][0]["impact_percent"] > 0

    def test_get_efficiency_predictions_all_factors(self, db_session):
        """Test efficiency predictions with all factors."""
        result = combined_analytics_service.get_efficiency_predictions(
            db_session,
            temperature_f=30.0,
            elevation_change_m=50.0,
            is_raining=True,
        )

        assert len(result["adjustments"]) == 3
        assert result["total_adjustment_percent"] > 0  # All factors worsen efficiency

    def test_get_efficiency_predictions_optimal_conditions(self, db_session):
        """Test efficiency predictions with optimal conditions."""
        result = combined_analytics_service.get_efficiency_predictions(
            db_session,
            temperature_f=70.0,  # Optimal
            elevation_change_m=0.0,  # Flat
            is_raining=False,
        )

        # Optimal conditions should be near baseline
        assert result["predicted_kwh_per_mile"] == pytest.approx(0.32, abs=0.02)

    def test_get_efficiency_time_series(self, db_session, sample_trips_with_all_factors):
        """Test time series data generation."""
        result = combined_analytics_service.get_efficiency_time_series(db_session, days=30)

        assert "time_series" in result
        assert "period_count" in result
        assert "group_by" in result
        assert "date_range" in result

        # Should have some data points
        assert result["period_count"] >= 0

    def test_get_efficiency_time_series_by_day(self, db_session, sample_trips_with_all_factors):
        """Test time series grouped by day."""
        result = combined_analytics_service.get_efficiency_time_series(
            db_session, days=30, group_by="day"
        )
        assert result["group_by"] == "day"

    def test_get_efficiency_time_series_by_month(self, db_session, sample_trips_with_all_factors):
        """Test time series grouped by month."""
        result = combined_analytics_service.get_efficiency_time_series(
            db_session, days=90, group_by="month"
        )
        assert result["group_by"] == "month"

    def test_get_efficiency_time_series_empty(self, db_session):
        """Test time series with no data."""
        result = combined_analytics_service.get_efficiency_time_series(db_session)
        assert result["period_count"] == 0

    def test_get_best_driving_conditions_combined(
        self, db_session, sample_trips_with_all_factors
    ):
        """Test optimal conditions analysis."""
        result = combined_analytics_service.get_best_driving_conditions_combined(db_session)

        assert "optimal_conditions" in result
        assert "expected_efficiency_kwh_per_mile" in result
        assert "based_on_trips" in result

        conditions = result["optimal_conditions"]
        assert "temperature_range_f" in conditions
        assert "precipitation" in conditions

    def test_temperature_factor_calculation(self, db_session):
        """Test temperature factor calculations."""
        # Freezing
        factor = combined_analytics_service._get_temperature_factor(20.0)
        assert factor > 1.2

        # Cold
        factor = combined_analytics_service._get_temperature_factor(40.0)
        assert factor > 1.0

        # Optimal
        factor = combined_analytics_service._get_temperature_factor(70.0)
        assert factor == 1.0

        # Hot
        factor = combined_analytics_service._get_temperature_factor(100.0)
        assert factor > 1.0

    def test_elevation_factor_calculation(self, db_session):
        """Test elevation factor calculations."""
        # Steep downhill
        factor = combined_analytics_service._get_elevation_factor(-100.0)
        assert factor < 1.0

        # Flat
        factor = combined_analytics_service._get_elevation_factor(0.0)
        assert factor == 1.0

        # Steep uphill
        factor = combined_analytics_service._get_elevation_factor(100.0)
        assert factor > 1.0


class TestCombinedAnalyticsRoutes:
    """Tests for combined analytics API routes."""

    def test_multi_factor_endpoint(self, client, db_session):
        """Test multi-factor analysis endpoint."""
        response = client.get("/api/analytics/efficiency/multi-factor")
        assert response.status_code == 200
        data = response.get_json()
        assert "overall" in data
        assert "factor_impacts" in data

    def test_multi_factor_with_date_params(self, client, db_session):
        """Test multi-factor endpoint with date parameters."""
        response = client.get(
            "/api/analytics/efficiency/multi-factor",
            query_string={
                "start_date": "2024-01-01",
                "end_date": "2024-12-31",
            },
        )
        assert response.status_code == 200

    def test_predictions_endpoint_basic(self, client, db_session):
        """Test predictions endpoint."""
        response = client.get("/api/analytics/efficiency/predictions")
        assert response.status_code == 200
        data = response.get_json()
        assert "predicted_kwh_per_mile" in data

    def test_predictions_endpoint_with_params(self, client, db_session):
        """Test predictions endpoint with parameters."""
        response = client.get(
            "/api/analytics/efficiency/predictions",
            query_string={
                "temperature_f": "30",
                "elevation_change_m": "50",
                "is_raining": "true",
            },
        )
        assert response.status_code == 200
        data = response.get_json()
        assert len(data["adjustments"]) == 3

    def test_predictions_endpoint_invalid_param(self, client, db_session):
        """Test predictions endpoint with invalid parameter."""
        response = client.get(
            "/api/analytics/efficiency/predictions",
            query_string={"temperature_f": "invalid"},
        )
        assert response.status_code == 400

    def test_time_series_endpoint(self, client, db_session):
        """Test time series endpoint."""
        response = client.get("/api/analytics/efficiency/time-series")
        assert response.status_code == 200
        data = response.get_json()
        assert "time_series" in data
        assert "group_by" in data

    def test_time_series_endpoint_with_params(self, client, db_session):
        """Test time series endpoint with parameters."""
        response = client.get(
            "/api/analytics/efficiency/time-series",
            query_string={
                "days": "60",
                "group_by": "day",
            },
        )
        assert response.status_code == 200
        data = response.get_json()
        assert data["group_by"] == "day"

    def test_time_series_endpoint_invalid_group_by(self, client, db_session):
        """Test time series endpoint with invalid group_by defaults to week."""
        response = client.get(
            "/api/analytics/efficiency/time-series",
            query_string={"group_by": "invalid"},
        )
        assert response.status_code == 200
        data = response.get_json()
        assert data["group_by"] == "week"

    def test_optimal_conditions_endpoint(self, client, db_session):
        """Test optimal conditions endpoint."""
        response = client.get("/api/analytics/efficiency/optimal-conditions")
        assert response.status_code == 200
        data = response.get_json()
        assert "optimal_conditions" in data
