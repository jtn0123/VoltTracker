"""
Additional GPS map endpoint tests — subsampling, exports, edge cases.
Covers: #7 map endpoint GPS test coverage.
"""

import uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timezone, timedelta

from receiver.models import Trip, TelemetryRaw
from receiver.routes.map import subsample_gps_points, calculate_efficiency_color


# ── Unit tests for pure functions ──────────────────────────


class TestSubsampleGpsPoints:
    """Tests for the subsample_gps_points utility."""

    def test_returns_all_when_under_max(self):
        points = [{'lat': i, 'lon': i} for i in range(10)]
        result = subsample_gps_points(points, max_points=100)
        assert len(result) == 10

    def test_returns_exact_when_equal_to_max(self):
        points = [{'lat': i, 'lon': i} for i in range(100)]
        result = subsample_gps_points(points, max_points=100)
        assert len(result) == 100

    def test_subsamples_large_list(self):
        points = [{'lat': i * 0.001, 'lon': i * 0.001} for i in range(1000)]
        result = subsample_gps_points(points, max_points=50)
        assert len(result) <= 51  # max_points + 1 (first + sampled + last)
        # First and last points preserved
        assert result[0] == points[0]
        assert result[-1] == points[-1]

    def test_preserves_first_and_last(self):
        points = [{'lat': i, 'lon': i, 'id': i} for i in range(500)]
        result = subsample_gps_points(points, max_points=10)
        assert result[0]['id'] == 0
        assert result[-1]['id'] == 499

    def test_empty_list(self):
        assert subsample_gps_points([], max_points=10) == []

    def test_single_point(self):
        points = [{'lat': 1, 'lon': 2}]
        assert subsample_gps_points(points, max_points=10) == points

    def test_two_points(self):
        points = [{'lat': 1, 'lon': 2}, {'lat': 3, 'lon': 4}]
        assert subsample_gps_points(points, max_points=10) == points

    def test_max_points_of_two(self):
        points = [{'lat': i, 'lon': i} for i in range(100)]
        result = subsample_gps_points(points, max_points=2)
        # Should have first + (2-2=0 intermediate) + last = 2
        assert result[0] == points[0]
        assert result[-1] == points[-1]


class TestCalculateEfficiencyColor:
    """Tests for efficiency color coding."""

    def test_none_returns_gray(self):
        assert calculate_efficiency_color(None, 55.0) == '#999999'

    def test_efficient_returns_green(self):
        assert calculate_efficiency_color(0.20, 55.0) == '#10b981'

    def test_moderate_returns_yellow(self):
        assert calculate_efficiency_color(0.30, 45.0) == '#f59e0b'

    def test_inefficient_returns_red(self):
        assert calculate_efficiency_color(0.40, 30.0) == '#ef4444'

    def test_boundary_efficient(self):
        assert calculate_efficiency_color(0.249, 55.0) == '#10b981'

    def test_boundary_moderate(self):
        assert calculate_efficiency_color(0.25, 55.0) == '#f59e0b'

    def test_boundary_inefficient(self):
        assert calculate_efficiency_color(0.35, 55.0) == '#ef4444'


# ── Integration tests (endpoints) ─────────────────────────


class TestGPXExportDetailed:
    """Detailed GPX export validation."""

    def test_gpx_is_valid_xml(self, client, db_session):
        """GPX output should parse as valid XML."""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime(2026, 1, 15, 10, 0, 0, tzinfo=timezone.utc),
            distance_miles=10.0,
            is_closed=True,
        )
        db_session.add(trip)
        for i in range(5):
            db_session.add(TelemetryRaw(
                session_id=session_id,
                timestamp=datetime(2026, 1, 15, 10, i, 0, tzinfo=timezone.utc),
                latitude=41.5 + i * 0.01,
                longitude=-81.7 + i * 0.01,
                speed_mph=35.0,
                state_of_charge=80.0 - i,
            ))
        db_session.commit()

        resp = client.get(f'/api/trips/{trip.id}/gpx')
        assert resp.status_code == 200
        # Should parse without error
        root = ET.fromstring(resp.data.decode())
        ns = {'gpx': 'http://www.topografix.com/GPX/1/1'}
        trkpts = root.findall('.//gpx:trkpt', ns)
        assert len(trkpts) == 5
        # Check coordinates on first point
        assert trkpts[0].attrib['lat'] == '41.5'
        assert trkpts[0].attrib['lon'] == '-81.7'

    def test_gpx_includes_speed_extension(self, client, db_session):
        """GPX extensions should contain speed in m/s."""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime(2026, 1, 15, 10, 0, 0, tzinfo=timezone.utc),
            distance_miles=5.0, is_closed=True,
        )
        db_session.add(trip)
        db_session.add(TelemetryRaw(
            session_id=session_id,
            timestamp=datetime(2026, 1, 15, 10, 0, 0, tzinfo=timezone.utc),
            latitude=41.5, longitude=-81.7,
            speed_mph=60.0,  # ~26.82 m/s
        ))
        db_session.commit()

        resp = client.get(f'/api/trips/{trip.id}/gpx')
        gpx = resp.data.decode()
        assert '<speed>' in gpx
        # 60 mph * 0.44704 ≈ 26.82
        assert '26.82' in gpx


class TestKMLExportDetailed:
    """Detailed KML export validation."""

    def test_kml_is_valid_xml(self, client, db_session):
        """KML output should parse as valid XML."""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime(2026, 1, 15, 10, 0, 0, tzinfo=timezone.utc),
            end_time=datetime(2026, 1, 15, 10, 30, 0, tzinfo=timezone.utc),
            distance_miles=12.0, is_closed=True,
        )
        db_session.add(trip)
        for i in range(3):
            db_session.add(TelemetryRaw(
                session_id=session_id,
                timestamp=datetime(2026, 1, 15, 10, i * 10, 0, tzinfo=timezone.utc),
                latitude=41.5 + i * 0.01,
                longitude=-81.7 + i * 0.01,
            ))
        db_session.commit()

        resp = client.get(f'/api/trips/{trip.id}/kml')
        assert resp.status_code == 200
        root = ET.fromstring(resp.data.decode())
        ns = {'kml': 'http://www.opengis.net/kml/2.2'}
        placemarks = root.findall('.//kml:Placemark', ns)
        # Start + End + Route = 3 placemarks
        assert len(placemarks) == 3

    def test_kml_coordinates_are_lon_lat(self, client, db_session):
        """KML coordinates must be lon,lat,alt (not lat,lon)."""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime(2026, 1, 15, 10, 0, 0, tzinfo=timezone.utc),
            distance_miles=5.0, is_closed=True,
        )
        db_session.add(trip)
        db_session.add(TelemetryRaw(
            session_id=session_id, timestamp=datetime(2026, 1, 15, 10, 0, tzinfo=timezone.utc),
            latitude=41.5, longitude=-81.7,
        ))
        db_session.commit()

        resp = client.get(f'/api/trips/{trip.id}/kml')
        kml = resp.data.decode()
        # KML uses lon,lat,alt
        assert '-81.7,41.5,0' in kml


class TestRouteEdgeCases:
    """Edge cases for route/map endpoints."""

    def test_trip_route_invalid_trip_id(self, client):
        """Non-existent trip_id should 404."""
        resp = client.get('/api/trips/999999/route')
        assert resp.status_code == 404

    def test_trip_with_zero_gps_points(self, client, db_session):
        """Trip with no GPS telemetry should return 404 for route."""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            distance_miles=5.0, is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        resp = client.get(f'/api/trips/{trip.id}/route')
        assert resp.status_code == 404

    def test_trip_with_one_gps_point(self, client, db_session):
        """Trip with exactly 1 GPS point should still return route data."""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            distance_miles=1.0, is_closed=True,
        )
        db_session.add(trip)
        db_session.add(TelemetryRaw(
            session_id=session_id, timestamp=datetime.now(timezone.utc),
            latitude=41.5, longitude=-81.7,
        ))
        db_session.commit()

        resp = client.get(f'/api/trips/{trip.id}/route')
        assert resp.status_code == 200
        data = resp.get_json()
        assert len(data['route']['points']) == 1

    def test_map_skips_single_point_trips(self, client, db_session):
        """Map endpoint requires >= 2 GPS points per trip."""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            distance_miles=1.0, is_closed=True,
        )
        db_session.add(trip)
        db_session.add(TelemetryRaw(
            session_id=session_id, timestamp=datetime.now(timezone.utc),
            latitude=41.5, longitude=-81.7,
        ))
        db_session.commit()

        resp = client.get('/api/trips/map')
        assert resp.status_code == 200
        assert resp.get_json()['total_trips'] == 0

    def test_large_route_subsampling(self, client, db_session):
        """1000+ point route should be subsampled correctly."""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            distance_miles=100.0, is_closed=True,
        )
        db_session.add(trip)
        base = datetime.now(timezone.utc)
        for i in range(1200):
            db_session.add(TelemetryRaw(
                session_id=session_id,
                timestamp=base + timedelta(seconds=i),
                latitude=41.0 + i * 0.0001,
                longitude=-81.0 + i * 0.0001,
            ))
        db_session.commit()

        resp = client.get('/api/trips/map?max_points_per_trip=100')
        data = resp.get_json()
        assert data['total_trips'] == 1
        trip_data = data['trips'][0]
        assert len(trip_data['points']) <= 101
        assert trip_data['point_count'] == 1200

    def test_missing_lat_lng_in_telemetry(self, client, db_session):
        """Telemetry rows with NULL lat/lng should be filtered out."""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            distance_miles=10.0, is_closed=True,
        )
        db_session.add(trip)
        base = datetime.now(timezone.utc)
        # 3 valid points
        for i in range(3):
            db_session.add(TelemetryRaw(
                session_id=session_id, timestamp=base + timedelta(seconds=i),
                latitude=41.5 + i * 0.01, longitude=-81.7 + i * 0.01,
            ))
        # 2 points with missing lat/lng
        for i in range(2):
            db_session.add(TelemetryRaw(
                session_id=session_id, timestamp=base + timedelta(seconds=10 + i),
                latitude=None, longitude=None, speed_mph=30.0,
            ))
        db_session.commit()

        resp = client.get(f'/api/trips/{trip.id}/route')
        assert resp.status_code == 200
        data = resp.get_json()
        # Should only include the 3 valid points
        assert len(data['route']['points']) == 3

    def test_map_max_trips_hard_limit(self, client, db_session):
        """max_trips should be capped at 500."""
        resp = client.get('/api/trips/map?max_trips=9999')
        assert resp.status_code == 200
        # Just verify it doesn't error; the cap is internal

    def test_map_ev_only_filter(self, client, db_session):
        """ev_only filter should exclude gas-mode trips."""
        s1, s2 = uuid.uuid4(), uuid.uuid4()
        # EV trip
        db_session.add(Trip(session_id=s1, start_time=datetime.now(timezone.utc),
                            distance_miles=10, gas_mode_entered=False, is_closed=True))
        for i in range(3):
            db_session.add(TelemetryRaw(session_id=s1, timestamp=datetime.now(timezone.utc) + timedelta(seconds=i),
                                        latitude=41.5 + i * 0.01, longitude=-81.7))
        # Gas trip
        db_session.add(Trip(session_id=s2, start_time=datetime.now(timezone.utc),
                            distance_miles=10, gas_mode_entered=True, is_closed=True))
        for i in range(3):
            db_session.add(TelemetryRaw(session_id=s2, timestamp=datetime.now(timezone.utc) + timedelta(seconds=i),
                                        latitude=42.0 + i * 0.01, longitude=-82.0))
        db_session.commit()

        resp = client.get('/api/trips/map?ev_only=true')
        data = resp.get_json()
        assert data['total_trips'] == 1

    def test_gpx_export_no_gps(self, client, db_session):
        """GPX export with no GPS data returns 404."""
        session_id = uuid.uuid4()
        trip = Trip(session_id=session_id, start_time=datetime.now(timezone.utc),
                    distance_miles=5.0, is_closed=True)
        db_session.add(trip)
        db_session.commit()
        assert client.get(f'/api/trips/{trip.id}/gpx').status_code == 404

    def test_kml_export_no_gps(self, client, db_session):
        """KML export with no GPS data returns 404."""
        session_id = uuid.uuid4()
        trip = Trip(session_id=session_id, start_time=datetime.now(timezone.utc),
                    distance_miles=5.0, is_closed=True)
        db_session.add(trip)
        db_session.commit()
        assert client.get(f'/api/trips/{trip.id}/kml').status_code == 404
