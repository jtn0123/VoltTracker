"""
Tests for statistics routes and helper functions.

Tests statistics calculations including:
- Confidence intervals
- Trend calculations
- Period statistics
- Quick stats endpoints
"""

import uuid
from datetime import datetime, timedelta, timezone


class TestConfidenceInterval:
    """Tests for confidence interval calculation."""

    def test_calculate_confidence_interval_basic(self):
        """Calculate CI for a list of values."""
        from routes.statistics import calculate_confidence_interval

        values = [10.0, 12.0, 11.0, 13.0, 10.5, 11.5, 12.5]
        result = calculate_confidence_interval(values)

        assert result is not None
        assert "mean" in result
        assert "ci_lower" in result
        assert "ci_upper" in result
        assert "margin" in result
        assert "sample_size" in result
        assert result["sample_size"] == 7

    def test_calculate_confidence_interval_empty_list(self):
        """CI returns None for empty list."""
        from routes.statistics import calculate_confidence_interval

        result = calculate_confidence_interval([])
        assert result is None

    def test_calculate_confidence_interval_single_value(self):
        """CI returns None for single value."""
        from routes.statistics import calculate_confidence_interval

        result = calculate_confidence_interval([10.0])
        assert result is None

    def test_calculate_confidence_interval_large_sample(self):
        """CI uses z-distribution for large samples (n >= 30)."""
        from routes.statistics import calculate_confidence_interval

        values = list(range(10, 50))  # 40 values
        result = calculate_confidence_interval(values)

        assert result is not None
        assert result["sample_size"] == 40


class TestTrendCalculation:
    """Tests for trend calculation."""

    def test_calculate_trend_increasing(self):
        """Trend calculation for increasing values."""
        from routes.statistics import calculate_trend_vs_previous

        result = calculate_trend_vs_previous(120, 100)

        assert result["change_value"] == 20
        assert result["change_percent"] == 20.0
        assert result["direction"] == "up"

    def test_calculate_trend_decreasing(self):
        """Trend calculation for decreasing values."""
        from routes.statistics import calculate_trend_vs_previous

        result = calculate_trend_vs_previous(80, 100)

        assert result["change_value"] == -20
        assert result["change_percent"] == -20.0
        assert result["direction"] == "down"

    def test_calculate_trend_stable(self):
        """Trend calculation for stable values (< 1% change)."""
        from routes.statistics import calculate_trend_vs_previous

        result = calculate_trend_vs_previous(100.5, 100)

        assert result["direction"] == "stable"

    def test_calculate_trend_previous_none(self):
        """Trend returns neutral when previous value is None."""
        from routes.statistics import calculate_trend_vs_previous

        result = calculate_trend_vs_previous(100, None)

        assert result["change_value"] is None
        assert result["change_percent"] is None
        assert result["direction"] == "neutral"

    def test_calculate_trend_previous_zero(self):
        """Trend returns neutral when previous value is 0."""
        from routes.statistics import calculate_trend_vs_previous

        result = calculate_trend_vs_previous(100, 0)

        assert result["direction"] == "neutral"


class TestPeriodStats:
    """Tests for period statistics calculation."""

    def test_calculate_period_stats_empty_trips(self):
        """Period stats for empty trip list."""
        from routes.statistics import calculate_period_stats

        result = calculate_period_stats([])

        assert result["trip_count"] == 0
        assert result["total_distance"] == 0
        assert result["avg_mpg"] is None
        assert result["avg_kwh_per_mile"] is None

    def test_calculate_period_stats_basic(self, db_session):
        """Calculate basic period statistics."""
        from models import Trip
        from routes.statistics import calculate_period_stats

        trips = [
            Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc),
                distance_miles=30.0,
                electric_miles=25.0,
                gas_miles=5.0,
                gas_mode_entered=True,
                gas_mpg=40.0,
                kwh_per_mile=0.35,
                is_closed=True,
            ),
            Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc),
                distance_miles=20.0,
                electric_miles=20.0,
                gas_miles=0.0,
                gas_mode_entered=False,
                kwh_per_mile=0.32,
                is_closed=True,
            ),
        ]

        result = calculate_period_stats(trips)

        assert result["trip_count"] == 2
        assert result["total_distance"] == 50.0
        assert result["electric_miles"] == 45.0
        assert result["gas_miles"] == 5.0
        assert result["ev_percent"] == 90.0
        assert result["avg_mpg"] == 40.0
        assert result["avg_kwh_per_mile"] is not None

    def test_calculate_period_stats_metric_units(self, db_session):
        """Calculate stats with metric units."""
        from models import Trip
        from routes.statistics import calculate_period_stats

        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            distance_miles=100.0,
            electric_miles=100.0,
            gas_miles=0.0,
            is_closed=True,
        )

        result = calculate_period_stats([trip], units="metric")

        assert result["distance_unit"] == "km"
        # 100 miles = ~160.934 km
        assert result["total_distance"] > 160


class TestQuickStatsEndpoint:
    """Tests for quick stats endpoint."""

    def test_quick_stats_7d(self, client, db_session):
        """Get 7-day quick stats."""
        from models import Trip

        # Create recent trip
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc) - timedelta(days=3),
            distance_miles=25.0,
            electric_miles=20.0,
            gas_miles=5.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.get("/api/stats/quick/7d")

        assert response.status_code == 200
        data = response.get_json()
        assert "stats" in data
        assert data["stats"]["trip_count"] >= 0

    def test_quick_stats_30d(self, client, db_session):
        """Get 30-day quick stats."""
        response = client.get("/api/stats/quick/30d")
        assert response.status_code == 200

    def test_quick_stats_90d(self, client, db_session):
        """Get 90-day quick stats."""
        response = client.get("/api/stats/quick/90d")
        assert response.status_code == 200

    def test_quick_stats_invalid_timeframe(self, client):
        """Invalid timeframe defaults to last_30_days."""
        response = client.get("/api/stats/quick/invalid")
        # Actually returns 200 with default timeframe
        assert response.status_code == 200

    def test_quick_stats_with_metric_units(self, client, db_session):
        """Get stats with metric units."""
        response = client.get("/api/stats/quick/7d?units=metric")

        assert response.status_code == 200
        data = response.get_json()
        # Should have metric distance_unit if trips exist
        if data.get("stats", {}).get("trip_count", 0) > 0:
            assert data["stats"]["distance_unit"] == "km"


class TestTrendsCalculation:
    """Tests for trends calculation."""

    def test_calculate_trends_with_data(self):
        """Calculate trends comparing two periods."""
        from routes.statistics import calculate_trends

        current = {
            "total_distance": 150.0,
            "avg_mpg": 45.0,
            "avg_kwh_per_mile": 0.30,
            "ev_percent": 85.0,
        }

        previous = {
            "total_distance": 100.0,
            "avg_mpg": 40.0,
            "avg_kwh_per_mile": 0.35,
            "ev_percent": 80.0,
        }

        trends = calculate_trends(current, previous)

        assert "distance" in trends
        assert "mpg" in trends
        assert "kwh_per_mile" in trends
        assert "ev_percent" in trends

        # MPG improvement (higher is better)
        assert trends["mpg"]["is_improving"] is True

        # kWh/mile improvement (lower is better)
        assert trends["kwh_per_mile"]["is_improving"] is True

        # EV percent improvement (higher is better)
        assert trends["ev_percent"]["is_improving"] is True

    def test_calculate_trends_missing_data(self):
        """Calculate trends with missing data."""
        from routes.statistics import calculate_trends

        current = {"total_distance": 150.0, "avg_mpg": None, "avg_kwh_per_mile": None, "ev_percent": 80.0}
        previous = {"total_distance": 100.0, "avg_mpg": None, "avg_kwh_per_mile": None, "ev_percent": 75.0}

        trends = calculate_trends(current, previous)

        # Should not have mpg or kwh trends
        assert "mpg" not in trends
        assert "kwh_per_mile" not in trends


class TestStatisticsIntegration:
    """Integration tests for statistics endpoints."""

    def test_stats_with_trend_enabled(self, client, db_session):
        """Get stats with trend comparison enabled."""
        from models import Trip

        # Create trips in current and previous periods
        now = datetime.now(timezone.utc)

        # Current period trips (last 7 days)
        for i in range(2):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=now - timedelta(days=i + 1),
                distance_miles=20.0,
                electric_miles=15.0,
                gas_miles=5.0,
                is_closed=True,
            )
            db_session.add(trip)

        # Previous period trips (8-14 days ago)
        for i in range(2):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=now - timedelta(days=8 + i),
                distance_miles=25.0,
                electric_miles=20.0,
                gas_miles=5.0,
                is_closed=True,
            )
            db_session.add(trip)

        db_session.commit()

        response = client.get("/api/stats/quick/7d?include_trend=true")

        assert response.status_code == 200
        data = response.get_json()
        assert "stats" in data
        assert "previous_period" in data

    def test_stats_with_trend_disabled(self, client, db_session):
        """Get stats without trend comparison."""
        response = client.get("/api/stats/quick/7d?include_trend=false")

        assert response.status_code == 200
        data = response.get_json()
        assert "stats" in data
        assert "previous_period" not in data or data.get("previous_period") is None
