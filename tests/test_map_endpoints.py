"""
Tests for GPS map visualization endpoints
"""

import pytest
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
        assert trip_data['distance_miles'] == 15.5
        assert trip_data['kwh_per_mile'] == 0.25
        assert trip_data['gas_mpg'] == 85.0
        assert len(trip_data['points']) > 0
        assert 'bounds' in trip_data
        assert 'north' in trip_data['bounds']
        assert 'center' in trip_data['bounds']

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

        # Add GPS points
        for i in range(3):
            telemetry = TelemetryRaw(
                session_id=session_id_1,
                timestamp=datetime.now(timezone.utc),
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

        for i in range(3):
            telemetry = TelemetryRaw(
                session_id=session_id_2,
                timestamp=datetime.now(timezone.utc),
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
        assert data['trips'][0]['kwh_per_mile'] == 0.20

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

            # Add minimal GPS data
            for j in range(2):
                telemetry = TelemetryRaw(
                    session_id=session_id,
                    timestamp=datetime.now(timezone.utc),
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
        assert data['trip']['distance_miles'] == 12.5
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
        assert point['speed_mph'] == 45.0


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
