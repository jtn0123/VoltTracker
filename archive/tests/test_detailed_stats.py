"""
Tests for the /api/stats/detailed endpoint in statistics routes.
"""

import os
import sys
import uuid
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

from models import Trip


class TestDetailedStatsEndpoint:
    """Tests for /api/stats/detailed endpoint."""

    def _create_trips(self, db_session, count=5, with_gas=True, with_ev=True):
        """Helper to create test trips."""
        now = datetime.now(timezone.utc)
        trips = []
        for i in range(count):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=now - timedelta(days=i + 1),
                end_time=now - timedelta(days=i + 1) + timedelta(hours=1),
                distance_miles=20.0 + i,
                electric_miles=15.0 + i if with_ev else 0,
                gas_miles=5.0 if with_gas else 0,
                start_soc=90.0,
                gas_mode_entered=with_gas,
                gas_mpg=38.0 + i if with_gas else None,
                kwh_per_mile=0.30 + i * 0.01 if with_ev else None,
                is_closed=True,
            )
            db_session.add(trip)
            trips.append(trip)
        db_session.commit()
        return trips

    def test_detailed_stats_no_trips(self, client, db_session):
        """Detailed stats with no trips returns empty result."""
        response = client.get("/api/stats/detailed")
        assert response.status_code == 200
        data = response.get_json()
        assert data["trip_count"] == 0

    def test_detailed_stats_all_metrics(self, client, db_session):
        """Detailed stats with all metrics."""
        self._create_trips(db_session, count=5)
        response = client.get("/api/stats/detailed")
        assert response.status_code == 200
        data = response.get_json()
        assert data["trip_count"] == 5
        assert "mpg_analysis" in data
        assert "kwh_per_mile_analysis" in data
        assert "distance_analysis" in data

    def test_detailed_stats_mpg_only(self, client, db_session):
        """Detailed stats filtering to MPG metric only."""
        self._create_trips(db_session, count=3)
        response = client.get("/api/stats/detailed?metric=mpg")
        assert response.status_code == 200
        data = response.get_json()
        assert "mpg_analysis" in data
        assert "kwh_per_mile_analysis" not in data
        assert "distance_analysis" not in data

    def test_detailed_stats_kwh_only(self, client, db_session):
        """Detailed stats filtering to kWh/mile metric only."""
        self._create_trips(db_session, count=3)
        response = client.get("/api/stats/detailed?metric=kwh_per_mile")
        assert response.status_code == 200
        data = response.get_json()
        assert "kwh_per_mile_analysis" in data
        assert "mpg_analysis" not in data

    def test_detailed_stats_distance_only(self, client, db_session):
        """Detailed stats filtering to distance metric only."""
        self._create_trips(db_session, count=3)
        response = client.get("/api/stats/detailed?metric=distance")
        assert response.status_code == 200
        data = response.get_json()
        assert "distance_analysis" in data
        assert data["distance_analysis"]["unit"] == "miles"

    def test_detailed_stats_metric_units(self, client, db_session):
        """Detailed stats with metric units."""
        self._create_trips(db_session, count=3)
        response = client.get("/api/stats/detailed?metric=distance&units=metric")
        assert response.status_code == 200
        data = response.get_json()
        assert data["distance_analysis"]["unit"] == "km"

    def test_detailed_stats_no_ci(self, client, db_session):
        """Detailed stats without confidence intervals."""
        self._create_trips(db_session, count=5)
        response = client.get("/api/stats/detailed?include_ci=false")
        assert response.status_code == 200
        data = response.get_json()
        if "mpg_analysis" in data:
            assert data["mpg_analysis"]["confidence_interval"] is None

    def test_detailed_stats_with_ci(self, client, db_session):
        """Detailed stats with confidence intervals."""
        self._create_trips(db_session, count=5)
        response = client.get("/api/stats/detailed?include_ci=true")
        assert response.status_code == 200
        data = response.get_json()
        if "mpg_analysis" in data:
            ci = data["mpg_analysis"]["confidence_interval"]
            # CI needs at least 2 values
            assert ci is not None or data["mpg_analysis"]["trip_count"] < 2

    def test_detailed_stats_date_range_shortcut(self, client, db_session):
        """Detailed stats with date_range shortcut."""
        self._create_trips(db_session, count=3)
        response = client.get("/api/stats/detailed?date_range=last_7_days")
        assert response.status_code == 200
        data = response.get_json()
        assert "date_range" in data

    def test_detailed_stats_invalid_date_range(self, client, db_session):
        """Detailed stats with invalid date_range returns error."""
        response = client.get("/api/stats/detailed?date_range=invalid_shortcut")
        assert response.status_code == 400
        data = response.get_json()
        assert "error" in data

    def test_detailed_stats_custom_date_range(self, client, db_session):
        """Detailed stats with custom start/end dates."""
        self._create_trips(db_session, count=3)
        now = datetime.now(timezone.utc)
        start = (now - timedelta(days=30)).strftime("%Y-%m-%d")
        end = now.strftime("%Y-%m-%d")
        response = client.get(f"/api/stats/detailed?start_date={start}&end_date={end}")
        assert response.status_code == 200

    def test_detailed_stats_mpg_analysis_fields(self, client, db_session):
        """Check MPG analysis has all required fields."""
        self._create_trips(db_session, count=5)
        response = client.get("/api/stats/detailed?metric=mpg")
        assert response.status_code == 200
        data = response.get_json()
        mpg = data.get("mpg_analysis", {})
        if mpg:
            assert "trip_count" in mpg
            assert "mean" in mpg
            assert "median" in mpg
            assert "min" in mpg
            assert "max" in mpg

    def test_detailed_stats_ev_only_trips(self, client, db_session):
        """Detailed stats with EV-only trips (no gas)."""
        self._create_trips(db_session, count=3, with_gas=False)
        response = client.get("/api/stats/detailed")
        assert response.status_code == 200
        data = response.get_json()
        # No gas trips, so no mpg_analysis
        assert "mpg_analysis" not in data
