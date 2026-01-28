"""
Tests for trips routes.

Tests trip route endpoints including:
- Trip listing with filters
- Date range filters (shortcuts and explicit dates)
- Mode filters (gas_only, ev_only)
- Weather filters
- Efficiency and distance filters
- Sorting
- Trip comparison endpoint
"""

import json
import uuid
from datetime import datetime, timedelta, timezone


class TestTripListingFilters:
    """Tests for trip listing with various filters."""

    def test_get_trips_basic(self, client, db_session):
        """Get trips without filters."""
        from models import Trip

        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            distance_miles=25.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.get("/api/trips")

        assert response.status_code == 200
        data = response.get_json()
        assert "trips" in data
        assert "pagination" in data

    def test_get_trips_date_range_shortcut(self, client, db_session):
        """Get trips with date range shortcut."""
        from models import Trip

        # Create trip in the last 7 days
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc) - timedelta(days=3),
            start_odometer=50000.0,
            distance_miles=20.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.get("/api/trips?date_range=last_7_days")

        assert response.status_code == 200
        data = response.get_json()
        assert data["pagination"]["total"] >= 1

    def test_get_trips_gas_only_filter(self, client, db_session):
        """Get only trips that used gas."""
        from models import Trip

        # Gas trip
        gas_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            distance_miles=30.0,
            gas_mode_entered=True,
            gas_mpg=42.0,
            is_closed=True,
        )

        # EV trip
        ev_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc) - timedelta(hours=1),
            start_odometer=50030.0,
            distance_miles=20.0,
            gas_mode_entered=False,
            kwh_per_mile=0.35,
            is_closed=True,
        )

        db_session.add(gas_trip)
        db_session.add(ev_trip)
        db_session.commit()

        response = client.get("/api/trips?gas_only=true")

        assert response.status_code == 200
        data = response.get_json()
        # Should only include gas trip
        for trip in data["trips"]:
            assert trip["gas_mode_entered"] is True

    def test_get_trips_ev_only_filter(self, client, db_session):
        """Get only pure EV trips."""
        from models import Trip

        # Gas trip
        gas_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            distance_miles=30.0,
            gas_mode_entered=True,
            is_closed=True,
        )

        # EV trip
        ev_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc) - timedelta(hours=1),
            start_odometer=50030.0,
            distance_miles=20.0,
            gas_mode_entered=False,
            is_closed=True,
        )

        db_session.add(gas_trip)
        db_session.add(ev_trip)
        db_session.commit()

        response = client.get("/api/trips?ev_only=true")

        assert response.status_code == 200
        data = response.get_json()
        # Should only include EV trip
        for trip in data["trips"]:
            assert trip["gas_mode_entered"] is False

    def test_get_trips_extreme_weather_filter(self, client, db_session):
        """Get trips with extreme weather."""
        from models import Trip

        extreme_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            distance_miles=25.0,
            extreme_weather=True,
            weather_temp_f=15.0,
            is_closed=True,
        )

        normal_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc) - timedelta(hours=1),
            start_odometer=50025.0,
            distance_miles=20.0,
            extreme_weather=False,
            weather_temp_f=68.0,
            is_closed=True,
        )

        db_session.add(extreme_trip)
        db_session.add(normal_trip)
        db_session.commit()

        response = client.get("/api/trips?extreme_weather=true")

        assert response.status_code == 200
        data = response.get_json()
        # Should only include extreme weather trip
        for trip in data["trips"]:
            assert trip["extreme_weather"] is True

    def test_get_trips_min_temp_filter(self, client, db_session):
        """Get trips with minimum temperature filter."""
        from models import Trip

        cold_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            distance_miles=20.0,
            weather_temp_f=20.0,
            is_closed=True,
        )

        warm_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc) - timedelta(hours=1),
            start_odometer=50020.0,
            distance_miles=25.0,
            weather_temp_f=75.0,
            is_closed=True,
        )

        db_session.add(cold_trip)
        db_session.add(warm_trip)
        db_session.commit()

        response = client.get("/api/trips?min_temp=50")

        assert response.status_code == 200
        data = response.get_json()
        # Should only include warm trip
        for trip in data["trips"]:
            if trip["weather_temp_f"]:
                assert trip["weather_temp_f"] >= 50

    def test_get_trips_max_temp_filter(self, client, db_session):
        """Get trips with maximum temperature filter."""
        from models import Trip

        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            distance_miles=20.0,
            weather_temp_f=95.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        response = client.get("/api/trips?max_temp=50")

        assert response.status_code == 200
        data = response.get_json()
        # Should not include trip with 95°F
        assert data["pagination"]["total"] == 0

    def test_get_trips_min_efficiency_filter(self, client, db_session):
        """Get trips with minimum efficiency filter."""
        from models import Trip

        efficient_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            distance_miles=25.0,
            kwh_per_mile=0.28,
            is_closed=True,
        )

        inefficient_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc) - timedelta(hours=1),
            start_odometer=50025.0,
            distance_miles=20.0,
            kwh_per_mile=0.45,
            is_closed=True,
        )

        db_session.add(efficient_trip)
        db_session.add(inefficient_trip)
        db_session.commit()

        response = client.get("/api/trips?min_efficiency=0.4")

        assert response.status_code == 200
        data = response.get_json()
        # Should only include inefficient trip (>= 0.4)
        for trip in data["trips"]:
            if trip["kwh_per_mile"]:
                assert trip["kwh_per_mile"] >= 0.4

    def test_get_trips_max_efficiency_filter(self, client, db_session):
        """Get trips with maximum efficiency filter."""
        from models import Trip

        efficient_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            distance_miles=25.0,
            kwh_per_mile=0.28,
            is_closed=True,
        )
        db_session.add(efficient_trip)
        db_session.commit()

        response = client.get("/api/trips?max_efficiency=0.35")

        assert response.status_code == 200
        data = response.get_json()
        # Should include efficient trip (<= 0.35)
        assert data["pagination"]["total"] >= 1

    def test_get_trips_min_mpg_filter(self, client, db_session):
        """Get trips with minimum MPG filter."""
        from models import Trip

        high_mpg_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            distance_miles=30.0,
            gas_mode_entered=True,
            gas_mpg=50.0,
            is_closed=True,
        )

        low_mpg_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc) - timedelta(hours=1),
            start_odometer=50030.0,
            distance_miles=25.0,
            gas_mode_entered=True,
            gas_mpg=35.0,
            is_closed=True,
        )

        db_session.add(high_mpg_trip)
        db_session.add(low_mpg_trip)
        db_session.commit()

        response = client.get("/api/trips?min_mpg=45")

        assert response.status_code == 200
        data = response.get_json()
        # Should only include high MPG trip
        for trip in data["trips"]:
            if trip.get("gas_mpg"):
                assert trip["gas_mpg"] >= 45

    def test_get_trips_min_distance_filter(self, client, db_session):
        """Get trips with minimum distance filter."""
        from models import Trip

        long_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            distance_miles=50.0,
            is_closed=True,
        )

        short_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc) - timedelta(hours=1),
            start_odometer=50050.0,
            distance_miles=5.0,
            is_closed=True,
        )

        db_session.add(long_trip)
        db_session.add(short_trip)
        db_session.commit()

        response = client.get("/api/trips?min_distance=30")

        assert response.status_code == 200
        data = response.get_json()
        # Should only include long trip
        for trip in data["trips"]:
            assert trip["distance_miles"] >= 30

    def test_get_trips_max_distance_filter(self, client, db_session):
        """Get trips with maximum distance filter."""
        from models import Trip

        short_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            distance_miles=10.0,
            is_closed=True,
        )
        db_session.add(short_trip)
        db_session.commit()

        response = client.get("/api/trips?max_distance=15")

        assert response.status_code == 200
        data = response.get_json()
        assert data["pagination"]["total"] >= 1

    def test_get_trips_min_elevation_filter(self, client, db_session):
        """Get trips with minimum elevation gain filter."""
        from models import Trip

        hilly_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            distance_miles=25.0,
            elevation_gain_m=500.0,
            is_closed=True,
        )

        flat_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc) - timedelta(hours=1),
            start_odometer=50025.0,
            distance_miles=20.0,
            elevation_gain_m=50.0,
            is_closed=True,
        )

        db_session.add(hilly_trip)
        db_session.add(flat_trip)
        db_session.commit()

        response = client.get("/api/trips?min_elevation=300")

        assert response.status_code == 200
        data = response.get_json()
        # Should only include hilly trip
        for trip in data["trips"]:
            if trip.get("elevation_gain_m"):
                assert trip["elevation_gain_m"] >= 300

    def test_get_trips_max_elevation_filter(self, client, db_session):
        """Get trips with maximum elevation gain filter."""
        from models import Trip

        flat_trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            distance_miles=20.0,
            elevation_gain_m=30.0,
            is_closed=True,
        )
        db_session.add(flat_trip)
        db_session.commit()

        response = client.get("/api/trips?max_elevation=100")

        assert response.status_code == 200
        data = response.get_json()
        assert data["pagination"]["total"] >= 1

    def test_get_trips_invalid_filter_values(self, client, db_session):
        """Invalid filter values return 400 with error message."""
        from models import Trip

        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=datetime.now(timezone.utc),
            start_odometer=50000.0,
            distance_miles=25.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        # Invalid numeric values now return 400 with error message
        response = client.get("/api/trips?min_distance=invalid")

        assert response.status_code == 400
        data = json.loads(response.data)
        assert "error" in data


class TestTripSorting:
    """Tests for trip sorting."""

    def test_get_trips_sort_by_distance_asc(self, client, db_session):
        """Sort trips by distance ascending."""
        from models import Trip

        trips = [
            Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(hours=i),
                start_odometer=50000.0 + (i * 30),
                distance_miles=10.0 + (i * 15),
                is_closed=True,
            )
            for i in range(3)
        ]
        for trip in trips:
            db_session.add(trip)
        db_session.commit()

        response = client.get("/api/trips?sort_by=distance_miles&sort_order=asc")

        assert response.status_code == 200
        data = response.get_json()
        distances = [t["distance_miles"] for t in data["trips"]]
        # Should be sorted ascending
        assert distances == sorted(distances)

    def test_get_trips_sort_by_distance_desc(self, client, db_session):
        """Sort trips by distance descending."""
        from models import Trip

        trips = [
            Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(hours=i),
                start_odometer=50000.0 + (i * 30),
                distance_miles=10.0 + (i * 15),
                is_closed=True,
            )
            for i in range(3)
        ]
        for trip in trips:
            db_session.add(trip)
        db_session.commit()

        response = client.get("/api/trips?sort_by=distance_miles&sort_order=desc")

        assert response.status_code == 200
        data = response.get_json()
        distances = [t["distance_miles"] for t in data["trips"]]
        # Should be sorted descending
        assert distances == sorted(distances, reverse=True)


class TestTripComparison:
    """Tests for trip comparison endpoint."""

    def test_compare_trips_basic(self, client, db_session):
        """Compare multiple trips."""
        from models import Trip

        trips = []
        for i in range(3):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(hours=i),
                start_odometer=50000.0 + (i * 25),
                distance_miles=20.0 + (i * 5),
                electric_miles=15.0,
                gas_miles=5.0 + (i * 2),
                kwh_per_mile=0.30 + (i * 0.05),
                gas_mpg=40.0 + (i * 2),
                weather_temp_f=65.0 + (i * 5),
                elevation_gain_m=100.0 + (i * 50),
                gas_mode_entered=True,
                is_closed=True,
            )
            db_session.add(trip)
            trips.append(trip)
        db_session.commit()

        trip_ids = [t.id for t in trips]

        response = client.post("/api/trips/compare", json={"trip_ids": trip_ids})

        assert response.status_code == 200
        data = response.get_json()
        assert "trips" in data
        assert "statistics" in data
        assert "insights" in data
        assert data["trip_count"] == 3
        assert len(data["trips"]) == 3

    def test_compare_trips_statistics(self, client, db_session):
        """Compare trips includes correct statistics."""
        from models import Trip

        trips = [
            Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(hours=1),
                start_odometer=50000.0,
                distance_miles=20.0,
                kwh_per_mile=0.30,
                weather_temp_f=60.0,
                elevation_gain_m=100.0,
                gas_mode_entered=False,
                is_closed=True,
            ),
            Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(hours=2),
                start_odometer=50020.0,
                distance_miles=30.0,
                kwh_per_mile=0.35,
                weather_temp_f=75.0,
                elevation_gain_m=200.0,
                gas_mode_entered=True,
                is_closed=True,
            ),
        ]
        for trip in trips:
            db_session.add(trip)
        db_session.commit()

        trip_ids = [t.id for t in trips]

        response = client.post("/api/trips/compare", json={"trip_ids": trip_ids})

        assert response.status_code == 200
        data = response.get_json()

        # Check distance stats
        assert data["statistics"]["distance"]["min"] == 20.0
        assert data["statistics"]["distance"]["max"] == 30.0
        assert data["statistics"]["distance"]["total"] == 50.0

        # Check efficiency stats
        assert data["statistics"]["efficiency"]["best"] == 0.30
        assert data["statistics"]["efficiency"]["worst"] == 0.35

        # Check weather stats
        assert data["statistics"]["weather"]["coldest"] == 60.0
        assert data["statistics"]["weather"]["warmest"] == 75.0

        # Check mode stats
        assert data["statistics"]["modes"]["ev_only"] == 1
        assert data["statistics"]["modes"]["gas_used"] == 1

    def test_compare_trips_missing_trip_ids(self, client):
        """Compare without trip_ids returns error."""
        response = client.post("/api/trips/compare", json={})

        assert response.status_code == 400
        assert "trip_ids required" in response.get_json()["error"]

    def test_compare_trips_empty_list(self, client):
        """Compare with empty trip_ids returns error."""
        response = client.post("/api/trips/compare", json={"trip_ids": []})

        assert response.status_code == 400
        assert "At least one trip_id required" in response.get_json()["error"]

    def test_compare_trips_too_many(self, client):
        """Compare with >10 trips returns error."""
        trip_ids = list(range(11))
        response = client.post("/api/trips/compare", json={"trip_ids": trip_ids})

        assert response.status_code == 400
        assert "Maximum 10 trips" in response.get_json()["error"]

    def test_compare_trips_nonexistent(self, client):
        """Compare nonexistent trips returns 404."""
        response = client.post("/api/trips/compare", json={"trip_ids": [999999, 999998]})

        assert response.status_code == 404
        assert "No trips found" in response.get_json()["error"]

    def test_compare_trips_high_efficiency_variance(self, client, db_session):
        """Compare trips with high efficiency variance includes insight."""
        from models import Trip

        trips = [
            Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(hours=i),
                start_odometer=50000.0 + (i * 25),
                distance_miles=20.0,
                kwh_per_mile=0.25 + (i * 0.2),  # High variance: 0.25, 0.45, 0.65
                is_closed=True,
            )
            for i in range(3)
        ]
        for trip in trips:
            db_session.add(trip)
        db_session.commit()

        trip_ids = [t.id for t in trips]

        response = client.post("/api/trips/compare", json={"trip_ids": trip_ids})

        assert response.status_code == 200
        data = response.get_json()

        # Should include variance insight
        insights = data.get("insights", [])
        assert any("variance" in insight.lower() for insight in insights)

    def test_compare_trips_wide_temperature_range(self, client, db_session):
        """Compare trips with wide temperature range includes insight."""
        from models import Trip

        trips = [
            Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(hours=1),
                start_odometer=50000.0,
                distance_miles=20.0,
                weather_temp_f=20.0,  # Cold
                is_closed=True,
            ),
            Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(hours=2),
                start_odometer=50020.0,
                distance_miles=20.0,
                weather_temp_f=85.0,  # Hot (65°F difference)
                is_closed=True,
            ),
        ]
        for trip in trips:
            db_session.add(trip)
        db_session.commit()

        trip_ids = [t.id for t in trips]

        response = client.post("/api/trips/compare", json={"trip_ids": trip_ids})

        assert response.status_code == 200
        data = response.get_json()

        # Should include temperature range insight
        insights = data.get("insights", [])
        assert any("temperature" in insight.lower() for insight in insights)

    def test_compare_trips_extreme_weather(self, client, db_session):
        """Compare trips with extreme weather includes insight."""
        from models import Trip

        trips = [
            Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(hours=i),
                start_odometer=50000.0 + (i * 20),
                distance_miles=20.0,
                extreme_weather=(i == 0),  # First trip has extreme weather
                is_closed=True,
            )
            for i in range(2)
        ]
        for trip in trips:
            db_session.add(trip)
        db_session.commit()

        trip_ids = [t.id for t in trips]

        response = client.post("/api/trips/compare", json={"trip_ids": trip_ids})

        assert response.status_code == 200
        data = response.get_json()

        # Should include extreme weather insight
        insights = data.get("insights", [])
        assert any("extreme weather" in insight.lower() for insight in insights)
