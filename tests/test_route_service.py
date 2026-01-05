"""
Tests for route service module.

Tests the route detection and analysis including:
- Haversine distance calculation
- Route matching by GPS endpoints
- Route statistics tracking
- Trip-to-route association
- Edge cases and validation
"""

import uuid
from datetime import datetime, timedelta, timezone

import pytest
from models import Route, TelemetryRaw, Trip
from services.route_service import (
    find_matching_route,
    get_all_routes,
    get_route_stats,
    haversine_distance,
    process_trip_route,
)


class TestHaversineDistance:
    """Tests for haversine_distance function."""

    def test_zero_distance_same_point(self):
        """Distance between same point is zero."""
        lat, lon = 37.7749, -122.4194  # San Francisco

        distance = haversine_distance(lat, lon, lat, lon)

        assert distance == 0.0

    def test_known_distance_sf_to_la(self):
        """Known distance: SF to LA is ~347 miles."""
        sf_lat, sf_lon = 37.7749, -122.4194
        la_lat, la_lon = 34.0522, -118.2437

        distance = haversine_distance(sf_lat, sf_lon, la_lat, la_lon)

        # Allow 10% tolerance due to great circle approximation
        assert 310 < distance < 385

    def test_known_distance_ny_to_boston(self):
        """Known distance: NYC to Boston is ~190 miles."""
        ny_lat, ny_lon = 40.7128, -74.0060
        boston_lat, boston_lon = 42.3601, -71.0589

        distance = haversine_distance(ny_lat, ny_lon, boston_lat, boston_lon)

        # Allow tolerance
        assert 170 < distance < 210

    def test_short_distance(self):
        """Short distance (1 mile) calculated correctly."""
        # Points approximately 1 mile apart
        lat1, lon1 = 37.7749, -122.4194
        lat2, lon2 = 37.7893, -122.4194  # ~1 mile north

        distance = haversine_distance(lat1, lon1, lat2, lon2)

        # Should be close to 1 mile
        assert 0.9 < distance < 1.1

    def test_negative_coordinates(self):
        """Handles negative coordinates correctly."""
        # Southern hemisphere
        lat1, lon1 = -33.8688, 151.2093  # Sydney
        lat2, lon2 = -37.8136, 144.9631  # Melbourne

        distance = haversine_distance(lat1, lon1, lat2, lon2)

        # Known distance ~440 miles
        assert 400 < distance < 480

    def test_crossing_prime_meridian(self):
        """Handles crossing prime meridian."""
        lat1, lon1 = 51.5074, -0.1278  # London (west of PM)
        lat2, lon2 = 48.8566, 2.3522  # Paris (east of PM)

        distance = haversine_distance(lat1, lon1, lat2, lon2)

        # Known distance ~213 miles
        assert 200 < distance < 230

    def test_crossing_international_date_line(self):
        """Handles crossing international date line."""
        lat1, lon1 = 21.3099, -157.8581  # Honolulu
        lat2, lon2 = 35.6762, 139.6503  # Tokyo

        distance = haversine_distance(lat1, lon1, lat2, lon2)

        # Known distance ~3800 miles
        assert 3600 < distance < 4000


class TestFindMatchingRoute:
    """Tests for find_matching_route function."""

    def test_finds_exact_match(self, app, db_session):
        """Finds route with exact endpoint match."""
        # Create route
        route = Route(
            name="Route 1",
            start_lat=37.7749,
            start_lon=-122.4194,
            end_lat=37.8044,
            end_lon=-122.2712,
            trip_count=5,
        )
        db_session.add(route)
        db_session.commit()

        # Search with exact coordinates
        found = find_matching_route(
            db_session,
            start_lat=37.7749,
            start_lon=-122.4194,
            end_lat=37.8044,
            end_lon=-122.2712,
        )

        assert found is not None
        assert found.id == route.id

    def test_finds_close_match(self, app, db_session):
        """Finds route within threshold distance."""
        # Create route
        route = Route(
            name="Route 2",
            start_lat=37.7749,
            start_lon=-122.4194,
            end_lat=37.8044,
            end_lon=-122.2712,
            trip_count=3,
        )
        db_session.add(route)
        db_session.commit()

        # Search with slightly different coordinates (within 0.5 mile)
        found = find_matching_route(
            db_session,
            start_lat=37.7760,  # Slightly different
            start_lon=-122.4200,
            end_lat=37.8050,
            end_lon=-122.2720,
            threshold_miles=0.5,
        )

        assert found is not None
        assert found.id == route.id

    def test_no_match_outside_threshold(self, app, db_session):
        """Returns None when no route within threshold."""
        # Create route
        route = Route(
            name="Route 3",
            start_lat=37.7749,
            start_lon=-122.4194,
            end_lat=37.8044,
            end_lon=-122.2712,
            trip_count=2,
        )
        db_session.add(route)
        db_session.commit()

        # Search with far coordinates
        found = find_matching_route(
            db_session,
            start_lat=38.0,  # Much different
            start_lon=-122.5,
            end_lat=38.1,
            end_lon=-122.4,
            threshold_miles=0.5,
        )

        assert found is None

    def test_no_routes_returns_none(self, app, db_session):
        """Returns None when no routes exist."""
        found = find_matching_route(
            db_session,
            start_lat=37.7749,
            start_lon=-122.4194,
            end_lat=37.8044,
            end_lon=-122.2712,
        )

        assert found is None

    def test_multiple_routes_returns_closest(self, app, db_session):
        """Returns closest matching route."""
        # Create two routes
        route1 = Route(
            name="Route 1",
            start_lat=37.7749,
            start_lon=-122.4194,
            end_lat=37.8044,
            end_lon=-122.2712,
            trip_count=1,
        )
        route2 = Route(
            name="Route 2",
            start_lat=37.7750,  # Closer to search point
            start_lon=-122.4195,
            end_lat=37.8045,
            end_lon=-122.2713,
            trip_count=1,
        )
        db_session.add(route1)
        db_session.add(route2)
        db_session.commit()

        # Search with point closest to route2
        found = find_matching_route(
            db_session,
            start_lat=37.7751,
            start_lon=-122.4196,
            end_lat=37.8046,
            end_lon=-122.2714,
            threshold_miles=0.5,
        )

        assert found is not None
        assert found.name == "Route 2"


class TestProcessTripRoute:
    """Tests for process_trip_route function."""

    def test_creates_new_route_for_new_trip(self, app, db_session):
        """Creates new route for unmatched trip."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        # Create trip
        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(hours=1),
            end_time=now,
            distance_miles=10.0,
            electric_miles=10.0,
            electric_kwh_used=2.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.flush()

        # Add telemetry with GPS
        telemetry_start = TelemetryRaw(
            session_id=session_id,
            timestamp=now - timedelta(hours=1),
            latitude=37.7749,
            longitude=-122.4194,
        )
        telemetry_end = TelemetryRaw(
            session_id=session_id,
            timestamp=now,
            latitude=37.8044,
            longitude=-122.2712,
        )
        db_session.add(telemetry_start)
        db_session.add(telemetry_end)
        db_session.commit()

        # Process trip
        route = process_trip_route(db_session, trip.id)

        assert route is not None
        assert route.name == "Route 1"
        assert route.start_lat == 37.7749
        assert route.end_lat == 37.8044
        assert route.trip_count == 1
        assert route.total_distance_miles == 10.0

    def test_updates_existing_route(self, app, db_session):
        """Updates existing route when matched."""
        # Create existing route
        existing_route = Route(
            name="Work Commute",
            start_lat=37.7749,
            start_lon=-122.4194,
            end_lat=37.8044,
            end_lon=-122.2712,
            trip_count=5,
            total_distance_miles=50.0,
            avg_efficiency_kwh_per_mile=0.2,
        )
        db_session.add(existing_route)
        db_session.flush()

        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        # Create new trip matching route
        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(hours=1),
            end_time=now,
            distance_miles=10.0,
            electric_miles=10.0,
            electric_kwh_used=2.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.flush()

        # Add telemetry matching existing route
        telemetry_start = TelemetryRaw(
            session_id=session_id,
            timestamp=now - timedelta(hours=1),
            latitude=37.7750,  # Close to existing
            longitude=-122.4195,
        )
        telemetry_end = TelemetryRaw(
            session_id=session_id,
            timestamp=now,
            latitude=37.8045,
            longitude=-122.2713,
        )
        db_session.add(telemetry_start)
        db_session.add(telemetry_end)
        db_session.commit()

        # Process trip
        route = process_trip_route(db_session, trip.id)

        assert route is not None
        assert route.id == existing_route.id
        assert route.trip_count == 6  # Incremented
        assert route.total_distance_miles == 60.0  # Added

    def test_handles_trip_without_gps(self, app, db_session):
        """Returns None for trip without GPS data."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(hours=1),
            end_time=now,
            distance_miles=10.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        # No telemetry with GPS
        route = process_trip_route(db_session, trip.id)

        assert route is None

    def test_calculates_efficiency(self, app, db_session):
        """Calculates average efficiency correctly."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(hours=1),
            end_time=now,
            distance_miles=20.0,
            electric_miles=20.0,
            electric_kwh_used=4.0,  # 0.2 kWh/mile
            is_closed=True,
        )
        db_session.add(trip)
        db_session.flush()

        telemetry_start = TelemetryRaw(
            session_id=session_id,
            timestamp=now - timedelta(hours=1),
            latitude=37.7749,
            longitude=-122.4194,
        )
        telemetry_end = TelemetryRaw(
            session_id=session_id,
            timestamp=now,
            latitude=37.8044,
            longitude=-122.2712,
        )
        db_session.add(telemetry_start)
        db_session.add(telemetry_end)
        db_session.commit()

        route = process_trip_route(db_session, trip.id)

        assert route is not None
        assert route.avg_efficiency_kwh_per_mile == pytest.approx(0.2, abs=0.01)

    def test_handles_invalid_trip_id(self, app, db_session):
        """Returns None for invalid trip ID."""
        route = process_trip_route(db_session, 99999)
        assert route is None


class TestGetAllRoutes:
    """Tests for get_all_routes function."""

    def test_returns_all_routes(self, app, db_session):
        """Returns all routes in database."""
        # Create multiple routes
        for i in range(5):
            route = Route(
                name=f"Route {i + 1}",
                start_lat=37.7749 + i * 0.01,
                start_lon=-122.4194,
                end_lat=37.8044,
                end_lon=-122.2712 + i * 0.01,
                trip_count=i + 1,
            )
            db_session.add(route)
        db_session.commit()

        routes = get_all_routes(db_session)

        assert len(routes) == 5

    def test_sorted_by_trip_count(self, app, db_session):
        """Routes sorted by trip count (descending)."""
        # Create routes with different trip counts
        route1 = Route(
            name="Route 1",
            start_lat=37.7749,
            start_lon=-122.4194,
            end_lat=37.8044,
            end_lon=-122.2712,
            trip_count=5,
        )
        route2 = Route(
            name="Route 2",
            start_lat=37.7750,
            start_lon=-122.4195,
            end_lat=37.8045,
            end_lon=-122.2713,
            trip_count=10,  # Most trips
        )
        route3 = Route(
            name="Route 3",
            start_lat=37.7751,
            start_lon=-122.4196,
            end_lat=37.8046,
            end_lon=-122.2714,
            trip_count=3,
        )
        db_session.add(route1)
        db_session.add(route2)
        db_session.add(route3)
        db_session.commit()

        routes = get_all_routes(db_session)

        assert routes[0]["name"] == "Route 2"  # Most trips first
        assert routes[1]["name"] == "Route 1"
        assert routes[2]["name"] == "Route 3"

    def test_includes_all_fields(self, app, db_session):
        """Returned data includes all route fields."""
        route = Route(
            name="Test Route",
            start_lat=37.7749,
            start_lon=-122.4194,
            end_lat=37.8044,
            end_lon=-122.2712,
            trip_count=5,
            total_distance_miles=50.0,
            avg_efficiency_kwh_per_mile=0.2,
            last_trip_date=datetime.now(timezone.utc),
        )
        db_session.add(route)
        db_session.commit()

        routes = get_all_routes(db_session)

        assert len(routes) == 1
        route_data = routes[0]
        assert "name" in route_data
        assert "start_lat" in route_data
        assert "trip_count" in route_data
        assert "avg_efficiency_kwh_per_mile" in route_data

    def test_no_routes_returns_empty(self, app, db_session):
        """No routes returns empty list."""
        routes = get_all_routes(db_session)
        assert routes == []


class TestGetRouteStats:
    """Tests for get_route_stats function."""

    def test_returns_route_statistics(self, app, db_session):
        """Returns statistics for specific route."""
        route = Route(
            name="Test Route",
            start_lat=37.7749,
            start_lon=-122.4194,
            end_lat=37.8044,
            end_lon=-122.2712,
            trip_count=10,
            total_distance_miles=100.0,
            avg_efficiency_kwh_per_mile=0.2,
        )
        db_session.add(route)
        db_session.commit()

        stats = get_route_stats(db_session, route.id)

        assert stats is not None
        assert stats["name"] == "Test Route"
        assert stats["trip_count"] == 10
        assert stats["total_distance_miles"] == 100.0
        assert stats["avg_efficiency_kwh_per_mile"] == 0.2

    def test_calculates_average_distance(self, app, db_session):
        """Calculates average distance per trip."""
        route = Route(
            name="Test Route",
            start_lat=37.7749,
            start_lon=-122.4194,
            end_lat=37.8044,
            end_lon=-122.2712,
            trip_count=5,
            total_distance_miles=50.0,
        )
        db_session.add(route)
        db_session.commit()

        stats = get_route_stats(db_session, route.id)

        assert stats is not None
        assert "avg_distance_per_trip" in stats
        assert stats["avg_distance_per_trip"] == pytest.approx(10.0, abs=0.01)

    def test_handles_zero_trips(self, app, db_session):
        """Handles route with zero trips."""
        route = Route(
            name="New Route",
            start_lat=37.7749,
            start_lon=-122.4194,
            end_lat=37.8044,
            end_lon=-122.2712,
            trip_count=0,
            total_distance_miles=0.0,
        )
        db_session.add(route)
        db_session.commit()

        stats = get_route_stats(db_session, route.id)

        assert stats is not None
        # Should not crash on division by zero

    def test_invalid_route_id_returns_none(self, app, db_session):
        """Invalid route ID returns None."""
        stats = get_route_stats(db_session, 99999)
        assert stats is None


class TestRouteValidation:
    """Tests for validation and edge cases."""

    def test_haversine_with_null_coordinates(self):
        """Handles None/null coordinates gracefully."""
        # This would typically raise an error, but test robustness
        # In actual code, we'd validate before calling
        with pytest.raises((TypeError, AttributeError)):
            haversine_distance(None, None, 37.7749, -122.4194)

    def test_very_short_route(self, app, db_session):
        """Handles very short routes (< 0.1 mile)."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(minutes=5),
            end_time=now,
            distance_miles=0.05,  # Very short
            is_closed=True,
        )
        db_session.add(trip)
        db_session.flush()

        # Almost same start/end
        telemetry_start = TelemetryRaw(
            session_id=session_id,
            timestamp=now - timedelta(minutes=5),
            latitude=37.7749,
            longitude=-122.4194,
        )
        telemetry_end = TelemetryRaw(
            session_id=session_id,
            timestamp=now,
            latitude=37.7750,  # Very close
            longitude=-122.4195,
        )
        db_session.add(telemetry_start)
        db_session.add(telemetry_end)
        db_session.commit()

        route = process_trip_route(db_session, trip.id)

        # Should still create route
        assert route is not None

    def test_route_with_missing_efficiency(self, app, db_session):
        """Handles trip without efficiency data."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(hours=1),
            end_time=now,
            distance_miles=10.0,
            electric_kwh_used=None,  # Missing
            is_closed=True,
        )
        db_session.add(trip)
        db_session.flush()

        telemetry_start = TelemetryRaw(
            session_id=session_id,
            timestamp=now - timedelta(hours=1),
            latitude=37.7749,
            longitude=-122.4194,
        )
        telemetry_end = TelemetryRaw(
            session_id=session_id,
            timestamp=now,
            latitude=37.8044,
            longitude=-122.2712,
        )
        db_session.add(telemetry_start)
        db_session.add(telemetry_end)
        db_session.commit()

        route = process_trip_route(db_session, trip.id)

        # Should still create route, efficiency will be None or calculated differently
        assert route is not None

    def test_duplicate_route_names_handled(self, app, db_session):
        """Multiple routes can have auto-generated names."""
        # Create multiple routes in different locations
        for i in range(3):
            session_id = uuid.uuid4()
            now = datetime.now(timezone.utc)

            trip = Trip(
                session_id=session_id,
                start_time=now - timedelta(hours=1),
                end_time=now,
                distance_miles=10.0,
                is_closed=True,
            )
            db_session.add(trip)
            db_session.flush()

            # Different endpoints for each route
            telemetry_start = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(hours=1),
                latitude=37.7749 + i,
                longitude=-122.4194,
            )
            telemetry_end = TelemetryRaw(
                session_id=session_id,
                timestamp=now,
                latitude=37.8044 + i,
                longitude=-122.2712,
            )
            db_session.add(telemetry_start)
            db_session.add(telemetry_end)
            db_session.commit()

            process_trip_route(db_session, trip.id)

        routes = get_all_routes(db_session)

        # Should have 3 distinct routes with auto-generated names
        assert len(routes) == 3
        names = [r["name"] for r in routes]
        assert len(set(names)) == 3  # All unique
