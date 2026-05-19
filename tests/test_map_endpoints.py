import pytest
"""
Tests for GPS map visualization endpoints
"""

import uuid
from datetime import datetime, timezone, timedelta
from receiver.models import Trip, TelemetryRaw


class TestMapDataEndpoint:
    """Tests for /api/trips/map endpoint"""

    def test_map_data_no_trips(self, client, db_session):
        """Empty database should return empty trips list"""
        response = client.get('/api/trips/map')
        assert response.status_code == 200
        data = response.get_json()
        assert data['trips'] == []
        assert data['total_trips'] == 0

    def test_map_data_with_trips(self, client, db_session):
        """Should return trips with GPS data"""
        session_id = uuid.uuid4()
        # Create trip with GPS points
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            end_time=datetime.now(timezone.utc) + timedelta(minutes=30),
            distance_miles=15.5,
            kwh_per_mile=0.25,
            gas_mpg=85.0,
            is_closed=True
        )
        db_session.add(trip)

        # Add GPS points
        base_time = datetime.now(timezone.utc)
        for i in range(10):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=base_time + timedelta(seconds=i * 60),
                latitude=41.5 + i * 0.001,
                longitude=-81.7 + i * 0.001,
                speed_mph=35.0,
                state_of_charge=80.0 - i
            )
            db_session.add(telemetry)
        db_session.commit()

        response = client.get('/api/trips/map')
        assert response.status_code == 200
        data = response.get_json()

        assert data['total_trips'] == 1
        assert len(data['trips']) == 1

        trip_data = data['trips'][0]
        assert trip_data['distance_miles'] == pytest.approx(15.5)

        assert trip_data['kwh_per_mile'] == pytest.approx(0.25)

        assert trip_data['gas_mpg'] == pytest.approx(85.0)

        assert len(trip_data['points']) > 0
        assert 'bounds' in trip_data
        assert 'north' in trip_data['bounds']
        assert 'center' in trip_data['bounds']

        # GPS quality indicator
        assert 'gps_quality' in trip_data
        assert 'total_points' in trip_data['gps_quality']
        assert 'unique_points' in trip_data['gps_quality']
        assert 'quality' in trip_data['gps_quality']
        # 10 distinct points → quality should be good
        assert trip_data['gps_quality']['quality'] == 'good'

    def test_map_data_gps_quality_poor(self, client, db_session):
        """Trip dominated by stuck GPS points should report poor quality"""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            distance_miles=15.0,
            is_closed=True
        )
        db_session.add(trip)

        # 100 GPS points all at the same location (simulates stuck GPS),
        # plus one point that actually moved.
        base_time = datetime.now(timezone.utc)
        for i in range(100):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=base_time + timedelta(seconds=i * 60),
                latitude=41.5,
                longitude=-81.7
            )
            db_session.add(telemetry)
        telemetry2 = TelemetryRaw(
            session_id=session_id,
            timestamp=base_time + timedelta(seconds=6000),
            latitude=41.51,
            longitude=-81.7
        )
        db_session.add(telemetry2)
        db_session.commit()

        response = client.get('/api/trips/map')
        assert response.status_code == 200
        data = response.get_json()

        trip_data = data['trips'][0]
        assert trip_data['gps_quality']['total_points'] == 101
        # Less than 30% unique → poor
        assert trip_data['gps_quality']['quality'] == 'poor'
        assert trip_data['gps_quality']['unique_points'] < 10

    def test_map_data_skip_trips_without_gps(self, client, db_session):
        """Trips without GPS data should be excluded"""
        session_id_1 = uuid.uuid4()
        session_id_2 = uuid.uuid4()

        # Trip with GPS
        trip1 = Trip(
            session_id=session_id_1,
            start_time=datetime.now(timezone.utc),
            distance_miles=10.0,
            is_closed=True
        )
        db_session.add(trip1)

        telemetry = TelemetryRaw(
            session_id=session_id_1,
            timestamp=datetime.now(timezone.utc),
            latitude=41.5,
            longitude=-81.7
        )
        db_session.add(telemetry)

        # Trip without GPS
        trip2 = Trip(
            session_id=session_id_2,
            start_time=datetime.now(timezone.utc),
            distance_miles=5.0,
            is_closed=True
        )
        db_session.add(trip2)
        db_session.commit()

        response = client.get('/api/trips/map')
        assert response.status_code == 200
        data = response.get_json()

        # Should only return trip with GPS
        assert data['total_trips'] == 0  # trip1 has only 1 point, need at least 2

    def test_map_data_with_filters(self, client, db_session):
        """Should respect query filters"""
        session_id_1 = uuid.uuid4()
        session_id_2 = uuid.uuid4()

        # Create efficient trip
        trip1 = Trip(
            session_id=session_id_1,
            start_time=datetime.now(timezone.utc) - timedelta(days=5),
            distance_miles=20.0,
            kwh_per_mile=0.20,
            is_closed=True
        )
        db_session.add(trip1)

        # Add GPS points. Stagger timestamps to avoid colliding on the
        # UNIQUE(session_id, timestamp) constraint.
        base_time_1 = datetime.now(timezone.utc)
        for i in range(3):
            telemetry = TelemetryRaw(
                session_id=session_id_1,
                timestamp=base_time_1 + timedelta(seconds=i),
                latitude=41.5 + i * 0.01,
                longitude=-81.7 + i * 0.01
            )
            db_session.add(telemetry)

        # Create inefficient trip
        trip2 = Trip(
            session_id=session_id_2,
            start_time=datetime.now(timezone.utc) - timedelta(days=5),
            distance_miles=20.0,
            kwh_per_mile=0.40,
            is_closed=True
        )
        db_session.add(trip2)

        base_time_2 = datetime.now(timezone.utc)
        for i in range(3):
            telemetry = TelemetryRaw(
                session_id=session_id_2,
                timestamp=base_time_2 + timedelta(seconds=i),
                latitude=41.6 + i * 0.01,
                longitude=-81.8 + i * 0.01
            )
            db_session.add(telemetry)

        db_session.commit()

        # Filter by efficiency
        response = client.get('/api/trips/map?max_efficiency=0.30')
        assert response.status_code == 200
        data = response.get_json()

        # Should only return efficient trip
        assert data['total_trips'] == 1
        assert data['trips'][0]['kwh_per_mile'] == pytest.approx(0.20)

    def test_map_data_point_subsampling(self, client, db_session):
        """Should subsample GPS points when too many"""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            distance_miles=50.0,
            is_closed=True
        )
        db_session.add(trip)

        # Add 500 GPS points
        base_time = datetime.now(timezone.utc)
        for i in range(500):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=base_time + timedelta(seconds=i),
                latitude=41.5 + i * 0.0001,
                longitude=-81.7 + i * 0.0001
            )
            db_session.add(telemetry)
        db_session.commit()

        # Request with max 50 points
        response = client.get('/api/trips/map?max_points_per_trip=50')
        assert response.status_code == 200
        data = response.get_json()

        trip_data = data['trips'][0]
        assert len(trip_data['points']) <= 50
        assert trip_data['point_count'] == 500  # Original count

    def test_map_data_max_trips_limit(self, client, db_session):
        """Should limit number of trips returned"""
        # Create 150 trips
        for i in range(150):
            session_id = uuid.uuid4()
            trip = Trip(
                session_id=session_id,
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                distance_miles=10.0,
                is_closed=True
            )
            db_session.add(trip)

            # Add minimal GPS data. Stagger timestamps to avoid colliding on
            # the UNIQUE(session_id, timestamp) constraint.
            base_time = datetime.now(timezone.utc)
            for j in range(2):
                telemetry = TelemetryRaw(
                    session_id=session_id,
                    timestamp=base_time + timedelta(seconds=j),
                    latitude=41.5 + j * 0.01,
                    longitude=-81.7 + j * 0.01
                )
                db_session.add(telemetry)

        db_session.commit()

        # Request with default limit (100)
        response = client.get('/api/trips/map')
        assert response.status_code == 200
        data = response.get_json()

        assert data['total_trips'] <= 100

    # -----------------------------------------------------------------
    # JTN-491: negative/invalid map filters should be rejected, not
    # echoed back as part of an empty result set.
    # -----------------------------------------------------------------

    def test_map_data_rejects_negative_min_distance(self, client):
        """Negative min_distance should return 400, not a silent empty set."""
        response = client.get('/api/trips/map?min_distance=-5')
        assert response.status_code == 400
        data = response.get_json()
        assert data.get('invalid_filter') == 'min_distance'

    def test_map_data_rejects_negative_distance_range(self, client):
        """Both bounds negative — the exact curl repro from the bug report."""
        response = client.get('/api/trips/map?min_distance=-5&max_distance=-1')
        assert response.status_code == 400
        data = response.get_json()
        assert 'error' in data
        # Response must not echo the bogus values back as "applied" filters.
        assert 'filters_applied' not in data

    def test_map_data_rejects_negative_efficiency(self, client):
        """Negative min_efficiency should also be rejected."""
        response = client.get('/api/trips/map?min_efficiency=-0.1')
        assert response.status_code == 400
        data = response.get_json()
        assert data.get('invalid_filter') == 'min_efficiency'

    def test_map_data_rejects_negative_max_efficiency(self, client):
        response = client.get('/api/trips/map?max_efficiency=-0.1')
        assert response.status_code == 400
        data = response.get_json()
        assert data.get('invalid_filter') == 'max_efficiency'

    def test_map_data_rejects_negative_min_mpg(self, client):
        response = client.get('/api/trips/map?min_mpg=-10')
        assert response.status_code == 400
        data = response.get_json()
        assert data.get('invalid_filter') == 'min_mpg'

    def test_map_data_rejects_inverted_distance_range(self, client):
        """min_distance > max_distance is an impossible range."""
        response = client.get('/api/trips/map?min_distance=10&max_distance=5')
        assert response.status_code == 400
        data = response.get_json()
        assert data.get('invalid_filter') == 'min_distance'

    def test_map_data_rejects_inverted_efficiency_range(self, client):
        """min_efficiency > max_efficiency is an impossible range."""
        response = client.get('/api/trips/map?min_efficiency=0.5&max_efficiency=0.2')
        assert response.status_code == 400
        data = response.get_json()
        assert data.get('invalid_filter') == 'min_efficiency'

    def test_map_data_rejects_non_numeric_filter(self, client):
        """Non-numeric values are a client bug — return 400 not 500."""
        response = client.get('/api/trips/map?min_distance=abc')
        assert response.status_code == 400
        data = response.get_json()
        assert data.get('invalid_filter') == 'min_distance'

    def test_map_data_accepts_zero_min_distance(self, client):
        """min_distance=0 is a valid (if redundant) filter; must not 400."""
        response = client.get('/api/trips/map?min_distance=0')
        assert response.status_code == 200

    def test_map_data_accepts_valid_positive_range(self, client):
        """Valid positive bounds still work end-to-end."""
        response = client.get('/api/trips/map?min_distance=1&max_distance=100')
        assert response.status_code == 200
        data = response.get_json()
        assert data['filters_applied']['min_distance'] == 1.0
        assert data['filters_applied']['max_distance'] == 100.0


class TestDetailedRouteEndpoint:
    """Tests for /api/trips/<id>/route endpoint"""

    def test_route_not_found(self, client):
        """Non-existent trip should return 404"""
        response = client.get('/api/trips/nonexistent-id/route')
        assert response.status_code == 404
        data = response.get_json()
        assert 'error' in data

    def test_route_no_gps_data(self, client, db_session):
        """Trip without GPS data should return 404"""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            distance_miles=10.0,
            is_closed=True
        )
        db_session.add(trip)
        db_session.commit()

        response = client.get(f'/api/trips/{trip.id}/route')
        assert response.status_code == 404
        data = response.get_json()
        assert 'No GPS data' in data['error']

    def test_route_with_gps_data(self, client, db_session):
        """Should return detailed route data"""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            end_time=datetime.now(timezone.utc) + timedelta(minutes=20),
            distance_miles=12.5,
            kwh_per_mile=0.28,
            is_closed=True
        )
        db_session.add(trip)

        # Add GPS points
        base_time = datetime.now(timezone.utc)
        for i in range(5):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=base_time + timedelta(seconds=i * 60),
                latitude=41.5 + i * 0.01,
                longitude=-81.7 + i * 0.01
            )
            db_session.add(telemetry)
        db_session.commit()

        response = client.get(f'/api/trips/{trip.id}/route')
        assert response.status_code == 200
        data = response.get_json()

        assert 'trip' in data
        assert 'route' in data
        assert data['trip']['distance_miles'] == pytest.approx(12.5)

        assert len(data['route']['points']) == 5
        assert 'bounds' in data['route']

    def test_route_with_telemetry_data(self, client, db_session):
        """Should include telemetry when requested"""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            distance_miles=10.0,
            is_closed=True
        )
        db_session.add(trip)

        # Add GPS with telemetry
        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=datetime.now(timezone.utc),
            latitude=41.5,
            longitude=-81.7,
            speed_mph=45.0,
            state_of_charge=75.0,
            hv_battery_power_kw=15.5
        )
        db_session.add(telemetry)
        db_session.commit()

        response = client.get(f'/api/trips/{trip.id}/route?include_telemetry=true')
        assert response.status_code == 200
        data = response.get_json()

        point = data['route']['points'][0]
        assert 'speed_mph' in point
        assert 'soc' in point
        assert point['speed_mph'] == pytest.approx(45.0)


class TestGPXExport:
    """Tests for GPX export endpoint"""

    def test_gpx_not_found(self, client):
        """Non-existent trip should return 404"""
        response = client.get('/api/trips/nonexistent-id/gpx')
        assert response.status_code == 404

    def test_gpx_no_gps_data(self, client, db_session):
        """Trip without GPS should return 404"""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            distance_miles=10.0,
            is_closed=True
        )
        db_session.add(trip)
        db_session.commit()

        response = client.get(f'/api/trips/{trip.id}/gpx')
        assert response.status_code == 404

    def test_gpx_export_format(self, client, db_session):
        """Should return valid GPX XML"""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime(2026, 1, 12, 10, 30, 0, tzinfo=timezone.utc),
            distance_miles=15.0,
            kwh_per_mile=0.25,
            gas_mpg=90.0,
            is_closed=True
        )
        db_session.add(trip)

        # Add GPS points
        for i in range(3):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=datetime(2026, 1, 12, 10, 30 + i, 0, tzinfo=timezone.utc),
                latitude=41.5 + i * 0.01,
                longitude=-81.7 + i * 0.01,
                elevation_meters=305.0,  # ~1000 ft
                speed_mph=35.0,
                state_of_charge=80.0
            )
            db_session.add(telemetry)
        db_session.commit()

        response = client.get(f'/api/trips/{trip.id}/gpx')
        assert response.status_code == 200
        assert response.mimetype == 'application/gpx+xml'

        # Check GPX content
        gpx_content = response.data.decode('utf-8')
        assert '<?xml version="1.0"' in gpx_content
        assert '<gpx version="1.1"' in gpx_content
        assert '<trkpt lat="41.5" lon="-81.7">' in gpx_content
        assert 'Distance: 15.00 mi' in gpx_content

    def test_gpx_filename(self, client, db_session):
        """Should have correct filename in headers"""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime(2026, 1, 12, 14, 30, 0, tzinfo=timezone.utc),
            distance_miles=10.0,
            is_closed=True
        )
        db_session.add(trip)

        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=datetime.now(timezone.utc),
            latitude=41.5,
            longitude=-81.7
        )
        db_session.add(telemetry)
        db_session.commit()

        response = client.get(f'/api/trips/{trip.id}/gpx')
        assert response.status_code == 200

        content_disposition = response.headers.get('Content-Disposition')
        assert 'volttracker_trip_2026-01-12_14-30.gpx' in content_disposition


class TestKMLExport:
    """Tests for KML export endpoint"""

    def test_kml_not_found(self, client):
        """Non-existent trip should return 404"""
        response = client.get('/api/trips/nonexistent-id/kml')
        assert response.status_code == 404

    def test_kml_export_format(self, client, db_session):
        """Should return valid KML XML"""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime(2026, 1, 12, 10, 30, 0, tzinfo=timezone.utc),
            distance_miles=20.0,
            kwh_per_mile=0.30,
            ambient_temp_avg_f=45.0,
            is_closed=True
        )
        db_session.add(trip)

        # Add GPS points
        for i in range(3):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=datetime(2026, 1, 12, 10, 30 + i, 0, tzinfo=timezone.utc),
                latitude=41.5 + i * 0.01,
                longitude=-81.7 + i * 0.01,
                elevation_meters=305.0
            )
            db_session.add(telemetry)
        db_session.commit()

        response = client.get(f'/api/trips/{trip.id}/kml')
        assert response.status_code == 200
        assert 'kml+xml' in response.mimetype

        # Check KML content
        kml_content = response.data.decode('utf-8')
        assert '<?xml version="1.0"' in kml_content
        assert '<kml xmlns="http://www.opengis.net/kml/2.2">' in kml_content
        assert '<Placemark>' in kml_content
        assert '<LineString>' in kml_content
        assert '<coordinates>' in kml_content
        assert 'Distance: 20.00 mi' in kml_content

    def test_kml_start_end_markers(self, client, db_session):
        """KML should include start and end placemarks"""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            distance_miles=10.0,
            is_closed=True
        )
        db_session.add(trip)

        # Add start and end points
        telemetry_start = TelemetryRaw(
            session_id=session_id,
            timestamp=datetime.now(timezone.utc),
            latitude=41.5,
            longitude=-81.7
        )
        telemetry_end = TelemetryRaw(
            session_id=session_id,
            timestamp=datetime.now(timezone.utc) + timedelta(minutes=20),
            latitude=41.6,
            longitude=-81.8
        )
        db_session.add_all([telemetry_start, telemetry_end])
        db_session.commit()

        response = client.get(f'/api/trips/{trip.id}/kml')
        kml_content = response.data.decode('utf-8')

        assert '<name>Start</name>' in kml_content
        assert '<name>End</name>' in kml_content
        assert '#startPoint' in kml_content
        assert '#endPoint' in kml_content


class TestSimilarTripsEndpoint:
    """Tests for similar trips finder endpoint"""

    def test_similar_trips_not_found(self, client):
        """Non-existent reference trip should return 404"""
        response = client.get('/api/trips/similar/nonexistent-id')
        assert response.status_code == 404

    def test_similar_trips_no_gps(self, client, db_session):
        """Reference trip without GPS should return empty list"""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            distance_miles=10.0,
            is_closed=True
        )
        db_session.add(trip)
        db_session.commit()

        response = client.get(f'/api/trips/similar/{trip.id}')
        assert response.status_code == 200
        data = response.get_json()
        assert data['similar_trips'] == []


class TestRouteSubsampling:
    """Tests for stationary-point filtering, RDP simplification, and subsampling."""

    def test_filter_stationary_removes_duplicates(self):
        """filter_stationary_points should collapse consecutive duplicate locations."""
        from receiver.routes.map import filter_stationary_points

        # GPS-stuck simulation: 100 points at one location, then a real move,
        # then 50 more stuck points, then another move.
        points = [{'lat': 41.5, 'lon': -81.7} for _ in range(100)]
        points.append({'lat': 41.501, 'lon': -81.7})  # ~111m north
        points.extend([{'lat': 41.501, 'lon': -81.7} for _ in range(50)])
        points.append({'lat': 41.502, 'lon': -81.7})

        filtered = filter_stationary_points(points, min_distance_meters=5.0)

        assert len(filtered) <= 5, f"Should collapse duplicates, got {len(filtered)}"
        assert len(filtered) >= 3, "Should preserve actual movements"

    def test_filter_stationary_keeps_movements(self):
        """Points that move >5m between samples should all be kept."""
        from receiver.routes.map import filter_stationary_points

        points = [
            {'lat': 41.5 + i * 0.0001, 'lon': -81.7}  # ~11m apart
            for i in range(100)
        ]

        filtered = filter_stationary_points(points, min_distance_meters=5.0)

        assert len(filtered) == 100

    def test_filter_stationary_handles_gps_jitter(self):
        """Sub-threshold jitter should collapse to far fewer points."""
        from receiver.routes.map import filter_stationary_points

        # ~1m apart — well below the 5m threshold
        points = [{'lat': 41.5 + i * 0.00001, 'lon': -81.7} for i in range(50)]

        filtered = filter_stationary_points(points, min_distance_meters=5.0)

        assert len(filtered) < 20, f"Should collapse GPS jitter, got {len(filtered)}"

    def test_rdp_preserves_turn_points(self):
        """RDP should preserve corner/turn points."""
        from receiver.routes.map import rdp_simplify

        # L-shaped route: horizontal then vertical
        points = []
        for i in range(20):
            points.append({'lat': 41.5, 'lon': -81.7 + i * 0.001})
        for i in range(20):
            points.append({'lat': 41.5 + i * 0.001, 'lon': -81.68})

        simplified = rdp_simplify(points, epsilon=0.0001)

        assert len(simplified) >= 3
        assert simplified[0]['lat'] == 41.5
        assert simplified[0]['lon'] == -81.7
        assert simplified[-1]['lat'] == pytest.approx(41.519, abs=0.001)
        assert simplified[-1]['lon'] == -81.68

        corner_found = any(
            abs(p['lat'] - 41.5) < 0.001 and abs(p['lon'] - (-81.68)) < 0.001
            for p in simplified
        )
        assert corner_found, "Corner point at L-junction should be preserved"

    def test_rdp_removes_collinear_points(self):
        """A perfectly straight line should collapse to its endpoints."""
        from receiver.routes.map import rdp_simplify

        points = [{'lat': 41.5 + i * 0.001, 'lon': -81.7} for i in range(100)]
        simplified = rdp_simplify(points, epsilon=0.0001)

        assert len(simplified) == 2
        assert simplified[0] == points[0]
        assert simplified[-1] == points[-1]

    def test_rdp_preserves_curves(self):
        """A curved path should retain enough points to represent the curve."""
        import math
        from receiver.routes.map import rdp_simplify

        points = []
        for i in range(100):
            angle = (i / 99) * (math.pi / 2)
            points.append({
                'lat': 41.5 + 0.01 * math.sin(angle),
                'lon': -81.7 + 0.01 * math.cos(angle),
            })

        simplified = rdp_simplify(points, epsilon=0.00005)

        assert len(simplified) >= 5
        assert len(simplified) < 50

        mid_point_found = any(
            0.003 < abs(p['lat'] - 41.5) < 0.008
            for p in simplified[1:-1]
        )
        assert mid_point_found, "Should preserve intermediate curve points"

    def test_subsample_respects_max_points(self, client, db_session):
        """API subsampling must never exceed max_points_per_trip."""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            distance_miles=100.0,
            is_closed=True,
        )
        db_session.add(trip)

        base_time = datetime.now(timezone.utc)
        for i in range(2000):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=base_time + timedelta(seconds=i * 2),
                latitude=41.5 + (i % 100) * 0.0001 + (i // 100) * 0.01,
                longitude=-81.7 + (i % 100) * 0.0001,
            )
            db_session.add(telemetry)
        db_session.commit()

        response = client.get('/api/trips/map?max_points_per_trip=100')
        assert response.status_code == 200
        data = response.get_json()

        points = data['trips'][0]['points']
        assert len(points) <= 100
        assert data['trips'][0]['point_count'] == 2000  # Original count preserved

    def test_subsample_preserves_shape_in_api(self, client, db_session):
        """The API endpoint should keep route shape (e.g. corner of an L route)."""
        session_id = uuid.uuid4()
        trip = Trip(
            session_id=session_id,
            start_time=datetime.now(timezone.utc),
            distance_miles=30.0,
            is_closed=True,
        )
        db_session.add(trip)

        base_time = datetime.now(timezone.utc)
        for i in range(300):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=base_time + timedelta(seconds=i),
                latitude=41.5,
                longitude=-81.7 + i * 0.0003,
            )
            db_session.add(telemetry)
        for i in range(300):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=base_time + timedelta(seconds=300 + i),
                latitude=41.5 + i * 0.0003,
                longitude=-81.61,
            )
            db_session.add(telemetry)
        db_session.commit()

        response = client.get('/api/trips/map?max_points_per_trip=50')
        assert response.status_code == 200
        data = response.get_json()

        points = data['trips'][0]['points']
        assert len(points) <= 50
        assert points[0]['lat'] == pytest.approx(41.5, abs=0.001)
        assert points[0]['lon'] == pytest.approx(-81.7, abs=0.001)
        assert points[-1]['lat'] == pytest.approx(41.59, abs=0.01)
        assert points[-1]['lon'] == pytest.approx(-81.61, abs=0.001)

        corner_region = any(
            abs(p['lat'] - 41.5) < 0.01 and abs(p['lon'] - (-81.61)) < 0.01
            for p in points
        )
        assert corner_region, "Corner region should be represented in subsampled points"
