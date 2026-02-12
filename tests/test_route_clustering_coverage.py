import pytest
"""
Tests for route clustering utilities - coverage supplement.
Imports via utils.route_clustering to match coverage measurement path.
"""

import os
import sys
import uuid
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

from utils.route_clustering import (
    haversine_distance,
    calculate_route_similarity,
    calculate_start_end_similarity,
    calculate_route_bounds,
    get_trip_gps_points,
    find_similar_trips,
    cluster_trips_by_route,
)
from models import Trip, TelemetryRaw


class TestHaversineDistance:
    """Tests for haversine_distance function."""

    def test_same_point(self):
        assert haversine_distance(40.0, -74.0, 40.0, -74.0) == pytest.approx(0.0)

    def test_known_distance_nyc_to_la(self):
        # NYC to LA is ~2451 miles
        d = haversine_distance(40.7128, -74.0060, 34.0522, -118.2437)
        assert 2400 < d < 2500

    def test_short_distance(self):
        d = haversine_distance(37.7749, -122.4194, 37.7750, -122.4195)
        assert d < 0.1  # Very close points

    def test_antipodal_points(self):
        d = haversine_distance(0, 0, 0, 180)
        assert 12400 < d < 12500  # Half circumference in miles


class TestCalculateRouteSimilarity:
    """Tests for calculate_route_similarity."""

    def test_identical_routes(self):
        route = [(37.0 + i * 0.01, -122.0 + i * 0.01) for i in range(25)]
        score = calculate_route_similarity(route, route)
        assert score >= 95

    def test_empty_route1(self):
        assert calculate_route_similarity([], [(37.0, -122.0)]) == pytest.approx(0.0)

    def test_empty_route2(self):
        assert calculate_route_similarity([(37.0, -122.0)], []) == pytest.approx(0.0)

    def test_similar_routes(self):
        route1 = [(37.0 + i * 0.01, -122.0 + i * 0.01) for i in range(25)]
        route2 = [(37.001 + i * 0.01, -122.001 + i * 0.01) for i in range(25)]
        score = calculate_route_similarity(route1, route2)
        assert score > 70

    def test_very_different_routes(self):
        route1 = [(37.0, -122.0)] * 10
        route2 = [(40.0, -74.0)] * 10
        score = calculate_route_similarity(route1, route2)
        assert score < 20

    def test_moderately_different_routes(self):
        route1 = [(37.0 + i * 0.01, -122.0) for i in range(20)]
        route2 = [(37.0 + i * 0.01, -122.02) for i in range(20)]
        score = calculate_route_similarity(route1, route2)
        assert 20 < score < 95

    def test_short_routes(self):
        route1 = [(37.0, -122.0), (37.01, -122.01)]
        route2 = [(37.0, -122.0), (37.01, -122.01)]
        score = calculate_route_similarity(route1, route2)
        assert score >= 95

    def test_different_length_routes(self):
        route1 = [(37.0 + i * 0.001, -122.0) for i in range(50)]
        route2 = [(37.0 + i * 0.001, -122.0) for i in range(10)]
        score = calculate_route_similarity(route1, route2, sample_size=5)
        assert score > 0


class TestCalculateStartEndSimilarity:
    """Tests for calculate_start_end_similarity."""

    def test_identical_start_end(self):
        score = calculate_start_end_similarity(
            (37.0, -122.0), (37.1, -122.1),
            (37.0, -122.0), (37.1, -122.1)
        )
        assert score == 100

    def test_close_start_end(self):
        score = calculate_start_end_similarity(
            (37.0, -122.0), (37.1, -122.1),
            (37.005, -122.005), (37.105, -122.105)
        )
        assert score > 50

    def test_far_start_end(self):
        score = calculate_start_end_similarity(
            (37.0, -122.0), (37.1, -122.1),
            (40.0, -74.0), (40.1, -74.1)
        )
        assert score < 10

    def test_medium_distance(self):
        # ~1-3 miles apart
        score = calculate_start_end_similarity(
            (37.0, -122.0), (37.1, -122.1),
            (37.02, -122.02), (37.12, -122.12)
        )
        assert 0 < score < 100


class TestCalculateRouteBounds:
    """Tests for calculate_route_bounds."""

    def test_empty_points(self):
        result = calculate_route_bounds([])
        assert result['north'] == 0
        assert result['center_lat'] == 0

    def test_single_point(self):
        result = calculate_route_bounds([(37.0, -122.0)])
        assert result['north'] == pytest.approx(37.0)

        assert result['south'] == pytest.approx(37.0)

        assert result['east'] == pytest.approx(-122.0)

        assert result['west'] == pytest.approx(-122.0)

    def test_multiple_points(self):
        points = [(36.0, -123.0), (38.0, -121.0), (37.0, -122.0)]
        result = calculate_route_bounds(points)
        assert result['north'] == pytest.approx(38.0)

        assert result['south'] == pytest.approx(36.0)

        assert result['east'] == pytest.approx(-121.0)

        assert result['west'] == pytest.approx(-123.0)

        assert abs(result['center_lat'] - 37.0) < 0.01


class TestGetTripGpsPoints:
    """Tests for get_trip_gps_points."""

    def test_trip_with_gps_data(self, app, db_session):
        """Get GPS points for a trip with telemetry."""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            is_closed=True,
        )
        db_session.add(trip)

        for i in range(5):
            t = TelemetryRaw(
                session_id=session_id,
                timestamp=datetime.now(timezone.utc) + timedelta(minutes=i),
                latitude=37.0 + i * 0.01,
                longitude=-122.0 + i * 0.01,
            )
            db_session.add(t)
        db_session.commit()

        points = get_trip_gps_points(db_session, trip)
        assert len(points) == 5
        assert points[0] == pytest.approx((37.0, -122.0))

    def test_trip_with_no_gps(self, app, db_session):
        """Trip with no GPS telemetry returns empty list."""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        points = get_trip_gps_points(db_session, trip)
        assert points == []

    def test_trip_with_partial_gps(self, app, db_session):
        """Only points with both lat and lon are included."""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            is_closed=True,
        )
        db_session.add(trip)

        # Point with lat only
        db_session.add(TelemetryRaw(
            session_id=session_id,
            timestamp=datetime.now(timezone.utc),
            latitude=37.0,
            longitude=None,
        ))
        # Point with both
        db_session.add(TelemetryRaw(
            session_id=session_id,
            timestamp=datetime.now(timezone.utc) + timedelta(minutes=1),
            latitude=37.1,
            longitude=-122.1,
        ))
        db_session.commit()

        points = get_trip_gps_points(db_session, trip)
        assert len(points) == 1


class TestFindSimilarTrips:
    """Tests for find_similar_trips."""

    def _create_trip_with_gps(self, db_session, lat_start, lon_start, n_points=10):
        """Helper to create a trip with GPS points."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)
        trip = Trip(
            session_id=session_id,
            start_time=now,
            end_time=now + timedelta(hours=1),
            distance_miles=10.0,
            is_closed=True,
        )
        db_session.add(trip)

        for i in range(n_points):
            db_session.add(TelemetryRaw(
                session_id=session_id,
                timestamp=now + timedelta(minutes=i),
                latitude=lat_start + i * 0.01,
                longitude=lon_start + i * 0.01,
            ))
        db_session.commit()
        return trip

    def test_find_similar_trips_no_gps(self, app, db_session):
        """Reference trip with no GPS returns empty."""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        result = find_similar_trips(db_session, trip)
        assert result == []

    def test_find_similar_trips_with_matches(self, app, db_session):
        """Find similar trips returns matching trips."""
        ref = self._create_trip_with_gps(db_session, 37.0, -122.0)
        # Similar trip
        self._create_trip_with_gps(db_session, 37.001, -122.001)
        # Different trip
        self._create_trip_with_gps(db_session, 40.0, -74.0)

        result = find_similar_trips(db_session, ref, min_similarity=50.0)
        # Should find the similar one
        assert len(result) >= 1
        assert result[0]['similarity_score'] > 50

    def test_find_similar_trips_excludes_self(self, app, db_session):
        """Reference trip is not included in results."""
        ref = self._create_trip_with_gps(db_session, 37.0, -122.0)
        result = find_similar_trips(db_session, ref)
        for r in result:
            assert r['trip_id'] != ref.id


class TestClusterTripsByRoute:
    """Tests for cluster_trips_by_route."""

    def test_cluster_empty_list(self, app, db_session):
        result = cluster_trips_by_route(db_session, [])
        assert result == []

    def test_cluster_single_trip(self, app, db_session):
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)
        trip = Trip(
            session_id=session_id,
            start_time=now,
            is_closed=True,
        )
        db_session.add(trip)
        for i in range(5):
            db_session.add(TelemetryRaw(
                session_id=session_id,
                timestamp=now + timedelta(minutes=i),
                latitude=37.0 + i * 0.01,
                longitude=-122.0 + i * 0.01,
            ))
        db_session.commit()

        result = cluster_trips_by_route(db_session, [trip])
        assert len(result) == 1
        assert trip.id in result[0]

    def test_cluster_similar_trips_grouped(self, app, db_session):
        """Similar trips should be clustered together."""
        now = datetime.now(timezone.utc)
        trips = []
        for t in range(3):
            session_id = uuid.uuid4()
            trip = Trip(
                session_id=session_id,
                start_time=now - timedelta(days=t),
                is_closed=True,
            )
            db_session.add(trip)
            for i in range(10):
                db_session.add(TelemetryRaw(
                    session_id=session_id,
                    timestamp=now - timedelta(days=t) + timedelta(minutes=i),
                    latitude=37.0 + i * 0.01 + t * 0.0001,  # Very slightly different
                    longitude=-122.0 + i * 0.01 + t * 0.0001,
                ))
            trips.append(trip)
        db_session.commit()

        result = cluster_trips_by_route(db_session, trips, similarity_threshold=50.0)
        # All similar trips should be in one cluster
        assert len(result) == 1
        assert len(result[0]) == 3

    def test_cluster_different_trips_separate(self, app, db_session):
        """Different trips should be in separate clusters."""
        now = datetime.now(timezone.utc)
        trips = []
        locations = [(37.0, -122.0), (40.0, -74.0)]
        for idx, (lat, lon) in enumerate(locations):
            session_id = uuid.uuid4()
            trip = Trip(
                session_id=session_id,
                start_time=now - timedelta(days=idx),
                is_closed=True,
            )
            db_session.add(trip)
            for i in range(10):
                db_session.add(TelemetryRaw(
                    session_id=session_id,
                    timestamp=now - timedelta(days=idx) + timedelta(minutes=i),
                    latitude=lat + i * 0.01,
                    longitude=lon + i * 0.01,
                ))
            trips.append(trip)
        db_session.commit()

        result = cluster_trips_by_route(db_session, trips)
        assert len(result) == 2

    def test_cluster_trips_no_gps(self, app, db_session):
        """Trips with no GPS data return empty clusters."""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        result = cluster_trips_by_route(db_session, [trip])
        assert result == []
