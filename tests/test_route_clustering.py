"""
Tests for route clustering and similarity utilities
"""

import pytest
import uuid
from receiver.utils.route_clustering import (
    haversine_distance,
    calculate_route_similarity,
    calculate_start_end_similarity,
    calculate_route_bounds,
    find_similar_trips,
    cluster_trips_by_route
)


class TestHaversineDistance:
    """Tests for Haversine distance calculation"""

    def test_same_location(self):
        """Distance between same point should be 0"""
        distance = haversine_distance(41.5, -81.7, 41.5, -81.7)
        assert distance == 0.0

    def test_known_distance(self):
        """Test with known distance (Cleveland to Akron ~30 miles)"""
        # Cleveland: 41.4993° N, 81.6944° W
        # Akron: 41.0814° N, 81.5190° W
        distance = haversine_distance(41.4993, -81.6944, 41.0814, -81.5190)
        # Allow 10% tolerance
        assert 27 < distance < 35

    def test_international_distance(self):
        """Test with international distance (London to Paris ~214 miles)"""
        # London: 51.5074° N, 0.1278° W
        # Paris: 48.8566° N, 2.3522° E
        distance = haversine_distance(51.5074, -0.1278, 48.8566, 2.3522)
        # Allow 5% tolerance
        assert 200 < distance < 230

    def test_negative_coordinates(self):
        """Test with southern/western hemisphere coordinates"""
        # Sydney: 33.8688° S, 151.2093° E
        # Melbourne: 37.8136° S, 144.9631° E
        distance = haversine_distance(-33.8688, 151.2093, -37.8136, 144.9631)
        # ~440 miles
        assert 420 < distance < 460


class TestCalculateRouteSimilarity:
    """Tests for route similarity scoring"""

    def test_identical_routes(self):
        """Identical routes should have 100% similarity"""
        route = [(41.5, -81.7), (41.6, -81.8), (41.7, -81.9)]
        similarity = calculate_route_similarity(route, route)
        assert similarity == 100.0

    def test_very_similar_routes(self):
        """Nearly identical routes should have high similarity"""
        route1 = [(41.5, -81.7), (41.6, -81.8), (41.7, -81.9)]
        route2 = [(41.501, -81.701), (41.601, -81.801), (41.701, -81.901)]
        similarity = calculate_route_similarity(route1, route2)
        assert similarity > 90

    def test_different_routes(self):
        """Completely different routes should have low similarity"""
        route1 = [(41.5, -81.7), (41.6, -81.8), (41.7, -81.9)]
        route2 = [(40.0, -80.0), (40.1, -80.1), (40.2, -80.2)]
        similarity = calculate_route_similarity(route1, route2)
        assert similarity < 20

    def test_empty_routes(self):
        """Empty routes should return 0 similarity"""
        route1 = [(41.5, -81.7)]
        route2 = []
        similarity = calculate_route_similarity(route1, route2)
        assert similarity == 0.0

    def test_different_lengths(self):
        """Routes with different point counts should still compare"""
        route1 = [(41.5, -81.7), (41.6, -81.8)]
        route2 = [(41.5, -81.7), (41.55, -81.75), (41.6, -81.8)]
        similarity = calculate_route_similarity(route1, route2)
        assert similarity > 40  # Should be somewhat similar despite different lengths


class TestStartEndSimilarity:
    """Tests for start/end point similarity"""

    def test_identical_start_end(self):
        """Identical start/end should have 100% similarity"""
        start = (41.5, -81.7)
        end = (41.6, -81.8)
        similarity = calculate_start_end_similarity(start, end, start, end)
        assert similarity == 100.0

    def test_close_start_end(self):
        """Close start/end points should have high similarity"""
        start1 = (41.5, -81.7)
        end1 = (41.6, -81.8)
        start2 = (41.501, -81.701)
        end2 = (41.601, -81.801)
        similarity = calculate_start_end_similarity(start1, end1, start2, end2)
        assert similarity > 80

    def test_far_start_end(self):
        """Distant start/end points should have low similarity"""
        start1 = (41.5, -81.7)
        end1 = (41.6, -81.8)
        start2 = (40.0, -80.0)
        end2 = (40.1, -80.1)
        similarity = calculate_start_end_similarity(start1, end1, start2, end2)
        assert similarity < 20


class TestCalculateRouteBounds:
    """Tests for route bounds calculation"""

    def test_single_point(self):
        """Single point should have bounds at that point"""
        points = [(41.5, -81.7)]
        bounds = calculate_route_bounds(points)
        assert bounds['north'] == 41.5
        assert bounds['south'] == 41.5
        assert bounds['east'] == -81.7
        assert bounds['west'] == -81.7
        assert bounds['center_lat'] == 41.5
        assert bounds['center_lon'] == -81.7

    def test_multiple_points(self):
        """Multiple points should calculate correct bounds"""
        points = [
            (41.5, -81.7),
            (41.6, -81.8),
            (41.4, -81.6)
        ]
        bounds = calculate_route_bounds(points)
        assert bounds['north'] == 41.6
        assert bounds['south'] == 41.4
        assert bounds['east'] == -81.6
        assert bounds['west'] == -81.8
        assert bounds['center_lat'] == pytest.approx(41.5, rel=0.01)
        assert bounds['center_lon'] == pytest.approx(-81.7, rel=0.01)

    def test_empty_points(self):
        """Empty points list should return zeros"""
        bounds = calculate_route_bounds([])
        assert bounds['north'] == 0
        assert bounds['south'] == 0
        assert bounds['east'] == 0
        assert bounds['west'] == 0
        assert bounds['center_lat'] == 0
        assert bounds['center_lon'] == 0

    def test_global_bounds(self):
        """Points spanning globe should calculate correctly"""
        points = [
            (85, 170),   # Near north pole, eastern hemisphere
            (-85, -170)  # Near south pole, western hemisphere
        ]
        bounds = calculate_route_bounds(points)
        assert bounds['north'] == 85
        assert bounds['south'] == -85
        assert bounds['east'] == 170
        assert bounds['west'] == -170


class TestFindSimilarTrips:
    """Tests for finding similar trips (requires database)"""

    def test_find_similar_trips_no_gps_data(self, app, db_session):
        """Trip without GPS data should return empty list"""
        from receiver.models import Trip
        from datetime import datetime, timezone

        session_id = uuid.uuid4()
        # Create trip without GPS data
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            distance_miles=10.0,
            is_closed=True
        )
        db_session.add(trip)
        db_session.commit()

        similar = find_similar_trips(db_session, trip)
        assert similar == []

    def test_find_similar_trips_with_insufficient_points(self, app, db_session):
        """Trip with < 2 GPS points should return empty list"""
        from receiver.models import Trip, TelemetryRaw
        from datetime import datetime, timezone

        session_id = uuid.uuid4()
        # Create trip
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            distance_miles=10.0,
            is_closed=True
        )
        db_session.add(trip)

        # Add single GPS point
        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=datetime.now(timezone.utc),
            latitude=41.5,
            longitude=-81.7
        )
        db_session.add(telemetry)
        db_session.commit()

        similar = find_similar_trips(db_session, trip)
        assert similar == []


class TestClusterTripsByRoute:
    """Tests for trip clustering"""

    def test_cluster_empty_trips(self, db_session):
        """Empty trip list should return empty clusters"""
        clusters = cluster_trips_by_route(db_session, [])
        assert clusters == []

    def test_cluster_single_trip(self, app, db_session):
        """Single trip should form single cluster"""
        from receiver.models import Trip, TelemetryRaw
        from datetime import datetime, timezone

        session_id = uuid.uuid4()
        # Create trip with GPS data
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            distance_miles=10.0,
            is_closed=True
        )
        db_session.add(trip)

        # Add GPS points
        for i in range(5):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=datetime.now(timezone.utc),
                latitude=41.5 + i * 0.01,
                longitude=-81.7 + i * 0.01
            )
            db_session.add(telemetry)
        db_session.commit()

        clusters = cluster_trips_by_route(db_session, [trip])
        assert len(clusters) == 1
        assert clusters[0] == [trip.id]


class TestEdgeCases:
    """Tests for edge cases and error handling"""

    def test_haversine_with_poles(self):
        """Distance calculation at poles"""
        # North pole to near north pole
        distance = haversine_distance(90, 0, 89, 0)
        assert distance > 0  # Should be ~69 miles (1 degree of latitude)
        assert distance < 100

    def test_haversine_with_dateline(self):
        """Distance calculation across international date line"""
        # Just west of dateline to just east
        distance = haversine_distance(0, 179, 0, -179)
        # Should be ~138 miles (2 degrees at equator)
        assert distance > 100
        assert distance < 200

    def test_route_similarity_single_point(self):
        """Similarity with single-point routes"""
        route1 = [(41.5, -81.7)]
        route2 = [(41.5, -81.7)]
        similarity = calculate_route_similarity(route1, route2, sample_size=1)
        assert similarity == 100.0

    def test_bounds_with_single_coordinate(self):
        """Bounds calculation for point at 0,0"""
        bounds = calculate_route_bounds([(0, 0)])
        assert bounds['north'] == 0
        assert bounds['south'] == 0
        assert bounds['east'] == 0
        assert bounds['west'] == 0
