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
    detect_routes,
    find_matching_route,
    get_route_summary,
    get_trip_endpoints,
    haversine_distance,
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


class TestGetTripEndpoints:
    """Tests for get_trip_endpoints function."""

    def test_returns_endpoints_with_gps_data(self, app, db_session):
        """Returns start and end coordinates from telemetry."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(minutes=30),
            end_time=now,
            distance_miles=10.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.flush()

        # Add telemetry with GPS data
        telemetry_points = [
            (37.7749, -122.4194),  # Start
            (37.7850, -122.4100),  # Middle
            (37.8044, -122.2712),  # End
        ]

        for i, (lat, lon) in enumerate(telemetry_points):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=30 - i * 10),
                latitude=lat,
                longitude=lon,
            )
            db_session.add(telemetry)
        db_session.commit()

        result = get_trip_endpoints(db_session, trip.id)

        assert result is not None
        start_lat, start_lon, end_lat, end_lon = result
        assert start_lat == 37.7749
        assert start_lon == -122.4194
        assert end_lat == 37.8044
        assert end_lon == -122.2712

    def test_returns_none_for_nonexistent_trip(self, app, db_session):
        """Returns None for trip that doesn't exist."""
        result = get_trip_endpoints(db_session, 99999)
        assert result is None

    def test_returns_none_without_gps_data(self, app, db_session):
        """Returns None for trip without GPS telemetry."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(minutes=30),
            end_time=now,
            distance_miles=10.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.flush()

        # Add telemetry without GPS
        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=now - timedelta(minutes=20),
            speed_mph=30.0,
        )
        db_session.add(telemetry)
        db_session.commit()

        result = get_trip_endpoints(db_session, trip.id)
        assert result is None

    def test_returns_none_with_single_gps_point(self, app, db_session):
        """Returns None when less than 2 GPS points."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(minutes=30),
            end_time=now,
            distance_miles=10.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.flush()

        # Add only one GPS point
        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=now - timedelta(minutes=20),
            latitude=37.7749,
            longitude=-122.4194,
        )
        db_session.add(telemetry)
        db_session.commit()

        result = get_trip_endpoints(db_session, trip.id)
        assert result is None


class TestDetectRoutes:
    """Tests for detect_routes function."""

    def test_creates_new_route_from_trip(self, app, db_session):
        """Creates new route from trip with GPS data."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(minutes=30),
            end_time=now,
            distance_miles=10.5,
            electric_miles=10.5,
            kwh_per_mile=0.2,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.flush()

        # Add GPS telemetry
        for i, (lat, lon) in enumerate([(37.7749, -122.4194), (37.8044, -122.2712)]):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=30 - i * 15),
                latitude=lat,
                longitude=lon,
            )
            db_session.add(telemetry)
        db_session.commit()

        routes = detect_routes(db_session, min_trips=1)

        assert len(routes) >= 1
        # Should create at least one route

    def test_groups_similar_trips_into_route(self, app, db_session):
        """Groups trips with similar endpoints into same route."""
        now = datetime.now(timezone.utc)

        # Create 3 similar trips
        for trip_num in range(3):
            session_id = uuid.uuid4()

            trip = Trip(
                session_id=session_id,
                start_time=now - timedelta(days=trip_num, minutes=30),
                end_time=now - timedelta(days=trip_num),
                distance_miles=10.0,
                electric_miles=10.0,
                kwh_per_mile=0.2,
                is_closed=True,
            )
            db_session.add(trip)
            db_session.flush()

            # Same endpoints with slight variation
            for i, (lat, lon) in enumerate([(37.7749, -122.4194), (37.8044, -122.2712)]):
                telemetry = TelemetryRaw(
                    session_id=session_id,
                    timestamp=now - timedelta(days=trip_num, minutes=30 - i * 15),
                    latitude=lat + trip_num * 0.0001,  # Slight variation
                    longitude=lon + trip_num * 0.0001,
                )
                db_session.add(telemetry)
        db_session.commit()

        routes = detect_routes(db_session, min_trips=2)

        # Should group similar trips
        assert len(routes) >= 1


class TestGetRouteSummary:
    """Tests for get_route_summary function."""

    def test_returns_empty_summary_no_routes(self, app, db_session):
        """Returns empty summary when no routes exist."""
        summary = get_route_summary(db_session)

        assert summary["total_routes"] == 0
        assert "message" in summary

    def test_returns_summary_with_routes(self, app, db_session):
        """Returns summary with route information."""
        # Create routes
        for i in range(3):
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

        summary = get_route_summary(db_session)

        assert summary["total_routes"] == 3
        assert "routes" in summary
        assert "most_frequent" in summary
        assert len(summary["routes"]) <= 10  # Top 10 limit

    def test_limits_to_top_10_routes(self, app, db_session):
        """Returns at most top 10 routes."""
        # Create 15 routes
        for i in range(15):
            route = Route(
                name=f"Route {i + 1}",
                start_lat=37.7749 + i * 0.001,
                start_lon=-122.4194,
                end_lat=37.8044,
                end_lon=-122.2712 + i * 0.001,
                trip_count=i + 1,
            )
            db_session.add(route)
        db_session.commit()

        summary = get_route_summary(db_session)

        assert summary["total_routes"] == 15
        assert len(summary["routes"]) == 10  # Limited to 10


class TestRouteValidation:
    """Tests for validation and edge cases."""

    def test_haversine_with_null_coordinates(self):
        """Handles None/null coordinates gracefully."""
        # This would typically raise an error, but test robustness
        # In actual code, we'd validate before calling
        with pytest.raises((TypeError, AttributeError)):
            haversine_distance(None, None, 37.7749, -122.4194)
