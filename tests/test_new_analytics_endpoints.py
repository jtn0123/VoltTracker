"""
Integration tests for new analytics API endpoints.

Tests the HTTP endpoints for:
- Powertrain analysis (features 5)
- Range prediction (feature 6)
- Maintenance tracking (feature 7)
- Route analysis (feature 8)
- Battery degradation (feature 9)
"""

import json
import uuid
from datetime import datetime, timedelta, timezone

from models import BatteryHealthReading, MaintenanceRecord, Route, TelemetryRaw, Trip, WebVital


class TestVitalsEndpoints:
    """Tests for web vitals API endpoints."""

    def test_vitals_post_success(self, client, db_session):
        """POST /api/analytics/vitals records web vital."""
        vital_data = {
            "name": "LCP",
            "value": 1234.5,
            "rating": "good",
            "navigationType": "navigate",
            "url": "/dashboard",
        }

        response = client.post(
            "/api/analytics/vitals",
            json=vital_data,
            content_type="application/json",
        )

        assert response.status_code == 200
        data = json.loads(response.data)
        assert data["status"] == "ok"
        assert data["recorded"] == "LCP"

        # Verify it was stored
        vitals = db_session.query(WebVital).all()
        assert len(vitals) == 1
        assert vitals[0].name == "LCP"

    def test_vitals_post_no_data(self, client, db_session):
        """POST /api/analytics/vitals with no data returns 400."""
        response = client.post(
            "/api/analytics/vitals",
            data="",
            content_type="application/json",
        )

        assert response.status_code == 400
        data = json.loads(response.data)
        assert "error" in data

    def test_vitals_options_request(self, client, db_session):
        """OPTIONS /api/analytics/vitals returns 204."""
        response = client.options("/api/analytics/vitals")
        assert response.status_code == 204


class TestPowertrainEndpoints:
    """Tests for powertrain API endpoints."""

    def test_powertrain_analysis_endpoint(self, client, db_session):
        """GET /api/analytics/powertrain/<trip_id> returns analysis."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        # Create trip
        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(minutes=30),
            end_time=now,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.flush()

        # Add telemetry
        for i in range(10):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=30 - i * 3),
                motor_a_rpm=1500.0,
                motor_b_rpm=1000.0,
                generator_rpm=0.0,
                engine_rpm=0.0,
                hv_battery_power_kw=-8.0,
            )
            db_session.add(telemetry)
        db_session.commit()

        response = client.get(f"/api/analytics/powertrain/{trip.id}")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "timeline" in data
        assert "mode_percentages" in data
        assert "total_samples" in data

    def test_powertrain_summary_endpoint(self, client, db_session):
        """GET /api/analytics/powertrain/summary/<trip_id> returns summary."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(minutes=30),
            end_time=now,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.flush()

        # Add telemetry
        for i in range(5):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=25 - i * 5),
                motor_a_rpm=1500.0,
                motor_b_rpm=1000.0,
                generator_rpm=0.0,
                engine_rpm=0.0,
            )
            db_session.add(telemetry)
        db_session.commit()

        response = client.get(f"/api/analytics/powertrain/summary/{trip.id}")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "mode_percentages" in data

    def test_powertrain_nonexistent_trip(self, client, db_session):
        """Nonexistent trip returns 404."""
        fake_id = "00000000-0000-0000-0000-000000000000"
        response = client.get(f"/api/analytics/powertrain/{fake_id}")

        assert response.status_code == 404


class TestRangePredictionEndpoints:
    """Tests for range prediction API endpoints."""

    def test_range_prediction_endpoint_default_params(self, client, db_session):
        """GET /api/analytics/range-prediction works with defaults."""
        # Add historical data
        for i in range(10):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                electric_miles=25.0,
                electric_kwh_used=5.0,
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        response = client.get("/api/analytics/range-prediction")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "predicted_range_miles" in data
        assert "confidence" in data
        assert "factors" in data

    def test_range_prediction_with_parameters(self, client, db_session):
        """Range prediction accepts query parameters."""
        # Add historical data
        for i in range(10):
            trip = Trip(
                session_id=uuid.uuid4(),
                start_time=datetime.now(timezone.utc) - timedelta(days=i),
                electric_miles=25.0,
                electric_kwh_used=5.0,
                is_closed=True,
            )
            db_session.add(trip)
        db_session.commit()

        response = client.get("/api/analytics/range-prediction?temperature=30&speed=70&battery_health=90")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "predicted_range_miles" in data
        # Should account for cold temp, high speed, degraded battery

    def test_range_prediction_no_historical_data(self, client, db_session):
        """Works with no historical data (uses defaults)."""
        response = client.get("/api/analytics/range-prediction")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "predicted_range_miles" in data

    def test_range_prediction_invalid_params(self, client, db_session):
        """Invalid parameters handled gracefully."""
        response = client.get("/api/analytics/range-prediction?temperature=invalid&speed=-100")

        # Should either return 400 or use defaults
        assert response.status_code in [200, 400]


class TestMaintenanceEndpoints:
    """Tests for maintenance tracking API endpoints."""

    def test_maintenance_summary_endpoint(self, client, db_session):
        """GET /api/analytics/maintenance/summary returns all items."""
        # Add some engine time
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        for i in range(12):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=60 - i * 5),
                engine_rpm=1500.0,
            )
            db_session.add(telemetry)
        db_session.commit()

        response = client.get("/api/analytics/maintenance/summary")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "maintenance_items" in data
        assert len(data["maintenance_items"]) == 8  # 8 maintenance items
        # Check structure
        assert "type" in data["maintenance_items"][0]
        assert "description" in data["maintenance_items"][0]
        assert "current_odometer" in data
        assert "total_engine_hours" in data

    def test_maintenance_engine_hours_endpoint(self, client, db_session):
        """GET /api/analytics/maintenance/engine-hours returns hours."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        # Add 1 hour of engine time
        for i in range(12):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=60 - i * 5),
                engine_rpm=1500.0,
            )
            db_session.add(telemetry)
        db_session.commit()

        response = client.get("/api/analytics/maintenance/engine-hours")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "total_engine_hours" in data
        assert data["total_engine_hours"] > 0

    def test_maintenance_with_records(self, client, db_session):
        """Maintenance summary includes last service dates."""
        now = datetime.now(timezone.utc)

        # Add maintenance record
        record = MaintenanceRecord(
            maintenance_type="oil_change",
            service_date=now - timedelta(days=200),
        )
        db_session.add(record)
        db_session.commit()

        response = client.get("/api/analytics/maintenance/summary")

        assert response.status_code == 200
        data = json.loads(response.data)

        # Find oil change item in maintenance_items
        oil_item = next((item for item in data["maintenance_items"] if item["type"] == "oil_change"), None)
        assert oil_item is not None
        assert oil_item["last_service_date"] is not None


class TestRouteEndpoints:
    """Tests for route analysis API endpoints."""

    def test_routes_endpoint_empty(self, client, db_session):
        """GET /api/analytics/routes returns empty dict with message."""
        response = client.get("/api/analytics/routes")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "total_routes" in data
        assert data["total_routes"] == 0
        assert "message" in data

    def test_routes_endpoint_with_routes(self, client, db_session):
        """Returns all routes."""
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

        response = client.get("/api/analytics/routes")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "routes" in data
        assert len(data["routes"]) == 3
        # Check structure
        assert "name" in data["routes"][0]
        assert "start" in data["routes"][0]
        assert "lat" in data["routes"][0]["start"]
        assert "trip_count" in data["routes"][0]

    def test_routes_sorted_by_trip_count(self, client, db_session):
        """Routes sorted by trip count (descending)."""
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
        db_session.add(route1)
        db_session.add(route2)
        db_session.commit()

        response = client.get("/api/analytics/routes")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "routes" in data
        assert data["routes"][0]["name"] == "Route 2"  # Most trips first


class TestBatteryDegradationEndpoints:
    """Tests for battery degradation API endpoints."""

    def test_degradation_endpoint_insufficient_data(self, client, db_session):
        """Returns error message with insufficient data."""
        # Only one reading
        reading = BatteryHealthReading(
            timestamp=datetime.now(timezone.utc),
            capacity_kwh=18.3,
            odometer_miles=55000.0,
        )
        db_session.add(reading)
        db_session.commit()

        response = client.get("/api/analytics/battery/degradation")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "error" in data
        assert data["current_readings"] == 1

    def test_degradation_endpoint_with_data(self, client, db_session):
        """Returns forecast with sufficient data."""
        now = datetime.now(timezone.utc)

        # Add health readings
        readings = [
            (50000.0, 18.3),
            (70000.0, 18.1),
            (90000.0, 17.9),
        ]

        for odo, cap in readings:
            reading = BatteryHealthReading(
                timestamp=now,
                capacity_kwh=cap,
                odometer_miles=odo,
            )
            db_session.add(reading)
        db_session.commit()

        response = client.get("/api/analytics/battery/degradation")

        assert response.status_code == 200
        data = json.loads(response.data)
        assert "forecasts" in data
        assert "current_status" in data
        assert "capacity_kwh" in data["current_status"]
        assert "degradation_rate" in data
        assert "percent_per_10k_miles" in data["degradation_rate"]

    def test_degradation_forecasts_structure(self, client, db_session):
        """Forecast data has expected structure."""
        now = datetime.now(timezone.utc)

        for i in range(5):
            reading = BatteryHealthReading(
                timestamp=now - timedelta(days=i * 30),
                capacity_kwh=18.4 - (i * 0.1),
                odometer_miles=50000.0 + (i * 10000),
            )
            db_session.add(reading)
        db_session.commit()

        response = client.get("/api/analytics/battery/degradation")

        assert response.status_code == 200
        data = json.loads(response.data)

        # Check forecasts structure
        forecasts = data["forecasts"]
        assert len(forecasts) > 0

        for forecast in forecasts:
            assert "odometer_miles" in forecast
            assert "predicted_capacity_kwh" in forecast
            assert "predicted_capacity_pct" in forecast


class TestEndpointErrorHandling:
    """Tests for error handling across endpoints."""

    def test_invalid_trip_id_format(self, client, db_session):
        """Invalid UUID format returns 400 or 404."""
        response = client.get("/api/analytics/powertrain/not-a-uuid")

        assert response.status_code in [400, 404, 500]

    def test_missing_required_data(self, client, db_session):
        """Endpoints handle missing data gracefully."""
        # No data in database
        response = client.get("/api/analytics/maintenance/summary")

        # Should still work
        assert response.status_code == 200

    def test_malformed_query_params(self, client, db_session):
        """Malformed query parameters handled."""
        response = client.get("/api/analytics/range-prediction?temperature=abc&speed=xyz")

        # Should either use defaults or return 400
        assert response.status_code in [200, 400]


class TestEndpointAuthentication:
    """Tests for authentication if required."""

    def test_endpoints_accessible(self, client, db_session):
        """Endpoints are accessible (no auth required for analytics)."""
        endpoints = [
            "/api/analytics/maintenance/summary",
            "/api/analytics/maintenance/engine-hours",
            "/api/analytics/routes",
            "/api/analytics/range-prediction",
        ]

        for endpoint in endpoints:
            response = client.get(endpoint)
            # Should not be 401/403
            assert response.status_code not in [401, 403]


class TestEndpointPerformance:
    """Tests for performance with large datasets."""

    def test_powertrain_large_trip(self, client, db_session):
        """Handles trip with many telemetry points."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(hours=2),
            end_time=now,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.flush()

        # Add 100 telemetry points
        for i in range(100):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(hours=2) + timedelta(minutes=i * 1.2),
                motor_a_rpm=1500.0,
                motor_b_rpm=1000.0,
                generator_rpm=0.0,
                engine_rpm=0.0,
            )
            db_session.add(telemetry)
        db_session.commit()

        response = client.get(f"/api/analytics/powertrain/{trip.id}")

        assert response.status_code == 200
        # Should complete in reasonable time

    def test_maintenance_summary_large_history(self, client, db_session):
        """Handles large maintenance history."""
        now = datetime.now(timezone.utc)

        # Add 50 maintenance records
        for i in range(50):
            record = MaintenanceRecord(
                maintenance_type="oil_change" if i % 2 == 0 else "tire_rotation",
                service_date=now - timedelta(days=i * 30),
            )
            db_session.add(record)
        db_session.commit()

        response = client.get("/api/analytics/maintenance/summary")

        assert response.status_code == 200


class TestEndpointCORS:
    """Tests for CORS headers if required."""

    def test_options_request(self, client, db_session):
        """OPTIONS requests handled for CORS."""
        response = client.options("/api/analytics/maintenance/summary")

        # Should not be 405
        assert response.status_code != 405


class TestEndpointResponseFormat:
    """Tests for consistent response format."""

    def test_json_content_type(self, client, db_session):
        """Responses have JSON content type."""
        response = client.get("/api/analytics/maintenance/summary")

        assert response.status_code == 200
        assert "application/json" in response.content_type.lower()

    def test_utf8_encoding(self, client, db_session):
        """Responses use UTF-8 encoding."""
        # Add route with unicode name
        route = Route(
            name="Route 路线 маршрут",  # Mixed scripts
            start_lat=37.7749,
            start_lon=-122.4194,
            end_lat=37.8044,
            end_lon=-122.2712,
        )
        db_session.add(route)
        db_session.commit()

        response = client.get("/api/analytics/routes")

        assert response.status_code == 200
        data = json.loads(response.data)
        # Should handle unicode correctly
        assert "routes" in data
        assert len(data["routes"]) == 1
        assert "路线" in data["routes"][0]["name"]  # Check unicode is preserved


class TestEndpointCaching:
    """Tests for caching behavior."""

    def test_no_cache_headers(self, client, db_session):
        """Analytics endpoints should not cache (or have appropriate cache headers)."""
        response = client.get("/api/analytics/maintenance/summary")

        assert response.status_code == 200
        # Check for cache control headers (implementation dependent)
