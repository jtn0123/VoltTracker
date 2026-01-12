"""
Tests for weather analytics service.

Tests weather-efficiency correlation analysis, temperature band grouping,
precipitation impact, wind impact, and seasonal trends.
"""

import os
import sys
import uuid
from datetime import datetime, timedelta

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

from models import Base, Trip  # noqa: E402
from receiver.calculations import (  # noqa: E402
    BASELINE_KWH_PER_MILE,
    calculate_efficiency_impact_percent,
)
from services.weather_analytics_service import (  # noqa: E402
    get_best_driving_conditions,
    get_efficiency_by_precipitation,
    get_efficiency_by_temperature_bands,
    get_efficiency_by_wind,
    get_seasonal_trends,
    get_weather_efficiency_correlation,
)


@pytest.fixture
def db_session():
    """Create an in-memory SQLite database for testing."""
    from sqlalchemy import create_engine
    from sqlalchemy.orm import sessionmaker

    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine)
    session = Session()
    yield session
    session.close()


@pytest.fixture
def sample_trips(db_session):
    """Create sample trips with various weather conditions."""
    trips = []
    now = datetime.utcnow()

    # Create trips in different temperature bands
    trip_data = [
        # Freezing conditions
        {"weather_temp_f": 20.0, "kwh_per_mile": 0.45, "electric_miles": 10.0, "days_ago": 5},
        {"weather_temp_f": 28.0, "kwh_per_mile": 0.42, "electric_miles": 15.0, "days_ago": 10},
        # Cold conditions
        {"weather_temp_f": 38.0, "kwh_per_mile": 0.38, "electric_miles": 12.0, "days_ago": 15},
        {"weather_temp_f": 42.0, "kwh_per_mile": 0.36, "electric_miles": 8.0, "days_ago": 20},
        # Ideal conditions
        {"weather_temp_f": 65.0, "kwh_per_mile": 0.30, "electric_miles": 20.0, "days_ago": 25},
        {"weather_temp_f": 70.0, "kwh_per_mile": 0.28, "electric_miles": 25.0, "days_ago": 30},
        {"weather_temp_f": 72.0, "kwh_per_mile": 0.29, "electric_miles": 18.0, "days_ago": 35},
        # Hot conditions
        {"weather_temp_f": 90.0, "kwh_per_mile": 0.34, "electric_miles": 15.0, "days_ago": 40},
        {"weather_temp_f": 98.0, "kwh_per_mile": 0.38, "electric_miles": 10.0, "days_ago": 45},
    ]

    for i, data in enumerate(trip_data):
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=data["days_ago"]),
            end_time=now - timedelta(days=data["days_ago"]) + timedelta(hours=1),
            is_closed=True,
            deleted_at=None,
            kwh_per_mile=data["kwh_per_mile"],
            electric_miles=data["electric_miles"],
            electric_kwh_used=data["kwh_per_mile"] * data["electric_miles"],
            weather_temp_f=data["weather_temp_f"],
            weather_precipitation_in=0.0,
            weather_wind_mph=10.0,
            weather_impact_factor=1.0,
        )
        trips.append(trip)
        db_session.add(trip)

    db_session.commit()
    return trips


@pytest.fixture
def precipitation_trips(db_session):
    """Create trips with various precipitation levels."""
    trips = []
    now = datetime.utcnow()

    precip_data = [
        # Dry conditions
        {"precipitation_in": 0.0, "kwh_per_mile": 0.30, "days_ago": 5},
        {"precipitation_in": 0.0, "kwh_per_mile": 0.31, "days_ago": 10},
        {"precipitation_in": 0.0, "kwh_per_mile": 0.29, "days_ago": 15},
        # Light rain
        {"precipitation_in": 0.05, "kwh_per_mile": 0.33, "days_ago": 20},
        {"precipitation_in": 0.08, "kwh_per_mile": 0.34, "days_ago": 25},
        # Moderate rain
        {"precipitation_in": 0.15, "kwh_per_mile": 0.36, "days_ago": 30},
        {"precipitation_in": 0.20, "kwh_per_mile": 0.37, "days_ago": 35},
        # Heavy rain
        {"precipitation_in": 0.30, "kwh_per_mile": 0.40, "days_ago": 40},
        {"precipitation_in": 0.50, "kwh_per_mile": 0.42, "days_ago": 45},
    ]

    for data in precip_data:
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=data["days_ago"]),
            end_time=now - timedelta(days=data["days_ago"]) + timedelta(hours=1),
            is_closed=True,
            deleted_at=None,
            kwh_per_mile=data["kwh_per_mile"],
            electric_miles=10.0,
            electric_kwh_used=data["kwh_per_mile"] * 10.0,
            weather_temp_f=70.0,
            weather_precipitation_in=data["precipitation_in"],
            weather_wind_mph=10.0,
            weather_impact_factor=1.0,
        )
        trips.append(trip)
        db_session.add(trip)

    db_session.commit()
    return trips


@pytest.fixture
def wind_trips(db_session):
    """Create trips with various wind speeds."""
    trips = []
    now = datetime.utcnow()

    wind_data = [
        # Calm
        {"wind_mph": 3.0, "kwh_per_mile": 0.28, "days_ago": 5},
        {"wind_mph": 4.0, "kwh_per_mile": 0.29, "days_ago": 10},
        # Light wind
        {"wind_mph": 8.0, "kwh_per_mile": 0.30, "days_ago": 15},
        {"wind_mph": 12.0, "kwh_per_mile": 0.31, "days_ago": 20},
        # Moderate wind
        {"wind_mph": 18.0, "kwh_per_mile": 0.34, "days_ago": 25},
        {"wind_mph": 22.0, "kwh_per_mile": 0.36, "days_ago": 30},
        # Strong wind
        {"wind_mph": 28.0, "kwh_per_mile": 0.40, "days_ago": 35},
        {"wind_mph": 35.0, "kwh_per_mile": 0.44, "days_ago": 40},
    ]

    for data in wind_data:
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=data["days_ago"]),
            end_time=now - timedelta(days=data["days_ago"]) + timedelta(hours=1),
            is_closed=True,
            deleted_at=None,
            kwh_per_mile=data["kwh_per_mile"],
            electric_miles=10.0,
            electric_kwh_used=data["kwh_per_mile"] * 10.0,
            weather_temp_f=70.0,
            weather_precipitation_in=0.0,
            weather_wind_mph=data["wind_mph"],
            weather_impact_factor=1.0,
        )
        trips.append(trip)
        db_session.add(trip)

    db_session.commit()
    return trips


class TestCalculateEfficiencyImpact:
    """Tests for calculate_efficiency_impact_percent helper."""

    def test_baseline_efficiency_returns_zero(self):
        """Baseline efficiency returns 0% impact."""
        result = calculate_efficiency_impact_percent(BASELINE_KWH_PER_MILE)
        assert result == 0.0

    def test_worse_efficiency_returns_positive(self):
        """Worse efficiency (higher kWh/mile) returns positive impact."""
        result = calculate_efficiency_impact_percent(0.40)  # 25% worse than 0.32
        assert result > 0
        assert result == pytest.approx(25.0, rel=0.1)

    def test_better_efficiency_returns_negative(self):
        """Better efficiency (lower kWh/mile) returns negative impact."""
        result = calculate_efficiency_impact_percent(0.24)  # 25% better than 0.32
        assert result < 0
        assert result == pytest.approx(-25.0, rel=0.1)

    def test_none_returns_zero(self):
        """None efficiency returns 0% impact."""
        result = calculate_efficiency_impact_percent(None)
        assert result == 0.0


class TestGetEfficiencyByTemperatureBands:
    """Tests for get_efficiency_by_temperature_bands function."""

    def test_returns_temperature_bands_list(self, db_session, sample_trips):
        """Result contains temperature_bands list."""
        result = get_efficiency_by_temperature_bands(db_session)
        assert "temperature_bands" in result
        assert isinstance(result["temperature_bands"], list)

    def test_includes_baseline_info(self, db_session, sample_trips):
        """Result includes baseline efficiency info."""
        result = get_efficiency_by_temperature_bands(db_session)
        assert "baseline_kwh_per_mile" in result
        assert result["baseline_kwh_per_mile"] == BASELINE_KWH_PER_MILE

    def test_bands_have_required_fields(self, db_session, sample_trips):
        """Each band has required fields."""
        result = get_efficiency_by_temperature_bands(db_session)
        required_fields = [
            "range",
            "avg_kwh_per_mile",
            "sample_count",
            "efficiency_impact_percent",
            "best_efficiency",
            "worst_efficiency",
        ]
        for band in result["temperature_bands"]:
            for field in required_fields:
                assert field in band

    def test_freezing_band_has_worst_efficiency(self, db_session, sample_trips):
        """Freezing band should have worst efficiency."""
        result = get_efficiency_by_temperature_bands(db_session)
        bands = {b["band"]: b for b in result["temperature_bands"]}

        if "freezing" in bands and "ideal" in bands:
            assert bands["freezing"]["avg_kwh_per_mile"] > bands["ideal"]["avg_kwh_per_mile"]

    def test_ideal_band_has_best_efficiency(self, db_session, sample_trips):
        """Ideal temperature band should have best efficiency."""
        result = get_efficiency_by_temperature_bands(db_session)
        bands = {b["band"]: b for b in result["temperature_bands"]}

        if "ideal" in bands:
            ideal_eff = bands["ideal"]["avg_kwh_per_mile"]
            for band_name, band_data in bands.items():
                if band_name != "ideal" and band_data["avg_kwh_per_mile"]:
                    assert ideal_eff <= band_data["avg_kwh_per_mile"]

    def test_date_filter_works(self, db_session, sample_trips):
        """Date filtering reduces results appropriately."""
        now = datetime.utcnow()
        result_all = get_efficiency_by_temperature_bands(db_session)
        result_recent = get_efficiency_by_temperature_bands(
            db_session, start_date=now - timedelta(days=20)
        )

        total_all = result_all["total_trips_analyzed"]
        total_recent = result_recent["total_trips_analyzed"]
        assert total_recent <= total_all

    def test_empty_database_returns_empty_bands(self, db_session):
        """Empty database returns empty bands list."""
        result = get_efficiency_by_temperature_bands(db_session)
        assert result["temperature_bands"] == []
        assert result["total_trips_analyzed"] == 0


class TestGetEfficiencyByPrecipitation:
    """Tests for get_efficiency_by_precipitation function."""

    def test_returns_precipitation_conditions(self, db_session, precipitation_trips):
        """Result contains precipitation_conditions list."""
        result = get_efficiency_by_precipitation(db_session)
        assert "precipitation_conditions" in result
        assert isinstance(result["precipitation_conditions"], list)

    def test_dry_has_best_efficiency(self, db_session, precipitation_trips):
        """Dry conditions should have best efficiency."""
        result = get_efficiency_by_precipitation(db_session)
        conditions = {c["condition"]: c for c in result["precipitation_conditions"]}

        if "dry" in conditions and "heavy_rain" in conditions:
            assert conditions["dry"]["avg_kwh_per_mile"] < conditions["heavy_rain"]["avg_kwh_per_mile"]

    def test_heavy_rain_has_worst_efficiency(self, db_session, precipitation_trips):
        """Heavy rain should have worst efficiency."""
        result = get_efficiency_by_precipitation(db_session)
        conditions = {c["condition"]: c for c in result["precipitation_conditions"]}

        if "heavy_rain" in conditions:
            heavy_eff = conditions["heavy_rain"]["avg_kwh_per_mile"]
            for cond_name, cond_data in conditions.items():
                if cond_name != "heavy_rain" and cond_data["avg_kwh_per_mile"]:
                    assert heavy_eff >= cond_data["avg_kwh_per_mile"]


class TestGetEfficiencyByWind:
    """Tests for get_efficiency_by_wind function."""

    def test_returns_wind_bands(self, db_session, wind_trips):
        """Result contains wind_bands list."""
        result = get_efficiency_by_wind(db_session)
        assert "wind_bands" in result
        assert isinstance(result["wind_bands"], list)

    def test_calm_has_best_efficiency(self, db_session, wind_trips):
        """Calm wind should have best efficiency."""
        result = get_efficiency_by_wind(db_session)
        bands = {b["band"]: b for b in result["wind_bands"]}

        if "calm" in bands and "strong" in bands:
            assert bands["calm"]["avg_kwh_per_mile"] < bands["strong"]["avg_kwh_per_mile"]

    def test_strong_wind_has_worst_efficiency(self, db_session, wind_trips):
        """Strong wind should have worst efficiency."""
        result = get_efficiency_by_wind(db_session)
        bands = {b["band"]: b for b in result["wind_bands"]}

        if "strong" in bands:
            strong_eff = bands["strong"]["avg_kwh_per_mile"]
            for band_name, band_data in bands.items():
                if band_name != "strong" and band_data["avg_kwh_per_mile"]:
                    assert strong_eff >= band_data["avg_kwh_per_mile"]


class TestGetSeasonalTrends:
    """Tests for get_seasonal_trends function."""

    def test_returns_monthly_trends(self, db_session, sample_trips):
        """Result contains monthly_trends list."""
        result = get_seasonal_trends(db_session)
        assert "monthly_trends" in result
        assert isinstance(result["monthly_trends"], list)

    def test_returns_seasonal_averages(self, db_session, sample_trips):
        """Result contains seasonal_averages dict."""
        result = get_seasonal_trends(db_session)
        assert "seasonal_averages" in result
        assert isinstance(result["seasonal_averages"], dict)

    def test_months_back_parameter_works(self, db_session, sample_trips):
        """months_back parameter limits results."""
        result_full = get_seasonal_trends(db_session, months_back=24)
        result_short = get_seasonal_trends(db_session, months_back=1)

        assert result_full["months_analyzed"] >= result_short["months_analyzed"]

    def test_monthly_data_has_required_fields(self, db_session, sample_trips):
        """Monthly data has required fields."""
        result = get_seasonal_trends(db_session)
        required_fields = ["month", "avg_kwh_per_mile", "trip_count"]

        for month_data in result["monthly_trends"]:
            for field in required_fields:
                assert field in month_data


class TestGetBestDrivingConditions:
    """Tests for get_best_driving_conditions function."""

    def test_returns_optimal_conditions(self, db_session, sample_trips):
        """Result contains optimal_conditions."""
        result = get_best_driving_conditions(db_session)
        assert "optimal_conditions" in result

    def test_returns_best_efficiency_achieved(self, db_session, sample_trips):
        """Result contains best_efficiency_achieved."""
        result = get_best_driving_conditions(db_session)
        assert "best_efficiency_achieved" in result

    def test_optimal_temp_in_reasonable_range(self, db_session, sample_trips):
        """Optimal temperature should be in reasonable range."""
        result = get_best_driving_conditions(db_session)
        if result.get("optimal_conditions", {}).get("temperature", {}).get("avg_f"):
            avg_temp = result["optimal_conditions"]["temperature"]["avg_f"]
            assert 40 <= avg_temp <= 90

    def test_empty_database_returns_message(self, db_session):
        """Empty database returns message about insufficient data."""
        result = get_best_driving_conditions(db_session)
        assert "message" in result or "optimal_conditions" in result


class TestGetWeatherEfficiencyCorrelation:
    """Tests for get_weather_efficiency_correlation function."""

    def test_returns_summary(self, db_session, sample_trips):
        """Result contains summary section."""
        result = get_weather_efficiency_correlation(db_session)
        assert "summary" in result

    def test_returns_efficiency_stats(self, db_session, sample_trips):
        """Result contains efficiency stats."""
        result = get_weather_efficiency_correlation(db_session)
        assert "efficiency" in result
        assert "avg_kwh_per_mile" in result["efficiency"]

    def test_returns_weather_impact(self, db_session, sample_trips):
        """Result contains weather_impact section."""
        result = get_weather_efficiency_correlation(db_session)
        assert "weather_impact" in result

    def test_coverage_percent_calculated(self, db_session, sample_trips):
        """Coverage percent is calculated correctly."""
        result = get_weather_efficiency_correlation(db_session)
        coverage = result["summary"]["coverage_percent"]
        assert 0 <= coverage <= 100

    def test_date_filter_works(self, db_session, sample_trips):
        """Date filtering works correctly."""
        now = datetime.utcnow()
        result_all = get_weather_efficiency_correlation(db_session)
        result_recent = get_weather_efficiency_correlation(
            db_session, start_date=now - timedelta(days=20)
        )

        assert result_recent["summary"]["total_trips"] <= result_all["summary"]["total_trips"]


class TestInvalidDataHandling:
    """Tests for handling invalid/edge case data."""

    def test_excludes_invalid_efficiency(self, db_session):
        """Trips with invalid efficiency are excluded."""
        now = datetime.utcnow()

        # Create trip with invalid efficiency (> 1.0)
        invalid_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=1),
            end_time=now,
            is_closed=True,
            deleted_at=None,
            kwh_per_mile=1.5,  # Invalid - too high
            electric_miles=10.0,
            weather_temp_f=70.0,
        )
        db_session.add(invalid_trip)
        db_session.commit()

        result = get_efficiency_by_temperature_bands(db_session)
        assert result["total_trips_analyzed"] == 0

    def test_excludes_deleted_trips(self, db_session):
        """Soft-deleted trips are excluded."""
        now = datetime.utcnow()

        deleted_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=1),
            end_time=now,
            is_closed=True,
            deleted_at=now,  # Soft deleted
            kwh_per_mile=0.30,
            electric_miles=10.0,
            weather_temp_f=70.0,
        )
        db_session.add(deleted_trip)
        db_session.commit()

        result = get_efficiency_by_temperature_bands(db_session)
        assert result["total_trips_analyzed"] == 0

    def test_excludes_open_trips(self, db_session):
        """Open (not closed) trips are excluded."""
        now = datetime.utcnow()

        open_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=1),
            is_closed=False,  # Not closed
            kwh_per_mile=0.30,
            electric_miles=10.0,
            weather_temp_f=70.0,
        )
        db_session.add(open_trip)
        db_session.commit()

        result = get_efficiency_by_temperature_bands(db_session)
        assert result["total_trips_analyzed"] == 0

    def test_excludes_trips_without_weather(self, db_session):
        """Trips without weather data are excluded from weather analysis."""
        now = datetime.utcnow()

        no_weather_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=now - timedelta(days=1),
            end_time=now,
            is_closed=True,
            deleted_at=None,
            kwh_per_mile=0.30,
            electric_miles=10.0,
            weather_temp_f=None,  # No weather data
        )
        db_session.add(no_weather_trip)
        db_session.commit()

        result = get_efficiency_by_temperature_bands(db_session)
        assert result["total_trips_analyzed"] == 0
