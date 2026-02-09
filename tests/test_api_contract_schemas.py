"""
API Contract Tests — validate response shapes match frontend Zod schemas.

These tests ensure the Python API returns data structures that match
the TypeScript Zod schemas in receiver/frontend/src/types/schemas.ts.
"""

import os
import sys
import uuid
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

from models import (  # noqa: E402
    BatteryHealthReading,
    ChargingSession,
    TelemetryRaw,
    Trip,
)


# ── Schema shape helpers ────────────────────────────────────────────────

def _assert_keys_subset(data, required_keys, label=""):
    """Assert that all required_keys exist in data dict."""
    missing = set(required_keys) - set(data.keys())
    assert not missing, f"{label} missing keys: {missing}"


def _assert_nullable_number(value, field_name):
    """Assert value is a number or None (matches z.number().nullable())."""
    assert value is None or isinstance(value, (int, float)), (
        f"{field_name} must be number or None, got {type(value).__name__}: {value}"
    )


def _assert_nullable_string(value, field_name):
    """Assert value is a string or None (matches z.string().nullable())."""
    assert value is None or isinstance(value, str), (
        f"{field_name} must be string or None, got {type(value).__name__}: {value}"
    )


# ── Telemetry Latest ────────────────────────────────────────────────────

LIVE_TELEMETRY_KEYS = {"active", "data", "start_time", "trip_stats"}

TRIP_STATS_KEYS = {"miles_driven", "kwh_used", "kwh_per_mile", "in_gas_mode", "gas_mpg"}

TELEMETRY_POINT_REQUIRED_KEYS = {
    "timestamp", "speed_mph", "soc", "fuel_percent",
    "engine_rpm", "latitude", "longitude", "hv_battery_power_kw",
    "hv_battery_voltage_v", "hv_battery_current_a",
}


class TestLiveTelemetrySchema:
    """Validate /api/telemetry/latest matches LiveTelemetryResponseSchema."""

    def test_inactive_response_shape(self, client):
        """Inactive response has active=False (API omits other keys when inactive)."""
        resp = client.get("/api/telemetry/latest")
        assert resp.status_code == 200
        data = resp.get_json()
        assert "active" in data
        assert isinstance(data["active"], bool)
        assert data["active"] is False

    def test_active_response_shape(self, client, db_session):
        """Active response includes data and trip_stats with correct shape."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(minutes=10),
            start_odometer=50000.0,
            start_soc=80.0,
            is_closed=False,
        )
        db_session.add(trip)

        telemetry = TelemetryRaw(
            session_id=session_id,
            timestamp=now,
            speed_mph=55.0,
            state_of_charge=70.0,
            engine_rpm=0,
            odometer_miles=50010.0,
            fuel_level_percent=80.0,
            latitude=37.7749,
            longitude=-122.4194,
        )
        db_session.add(telemetry)
        db_session.commit()

        resp = client.get("/api/telemetry/latest")
        assert resp.status_code == 200
        data = resp.get_json()

        _assert_keys_subset(data, LIVE_TELEMETRY_KEYS, "LiveTelemetryResponse")
        assert data["active"] is True
        assert data["data"] is not None

        # Validate telemetry point shape
        point = data["data"]
        _assert_keys_subset(point, TELEMETRY_POINT_REQUIRED_KEYS, "TelemetryPoint")
        _assert_nullable_number(point["speed_mph"], "speed_mph")
        _assert_nullable_number(point["soc"], "soc")

        # Validate trip_stats shape if present
        if data["trip_stats"] is not None:
            _assert_keys_subset(data["trip_stats"], TRIP_STATS_KEYS, "TripStats")
            assert isinstance(data["trip_stats"]["in_gas_mode"], bool)


# ── Trip List & Detail ──────────────────────────────────────────────────

TRIP_SUMMARY_KEYS = {
    "id", "start_time", "end_time", "distance_miles",
    "electric_miles", "gas_miles", "gas_mpg", "gas_mode_entered",
    "soc_at_gas_transition",
}


class TestTripSchemas:
    """Validate /api/trips and /api/trips/<id> match TripSummarySchema / TripDetailSchema."""

    def _create_trip(self, db_session):
        """Helper to create a closed trip with telemetry."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(hours=1),
            end_time=now,
            start_odometer=50000.0,
            end_odometer=50025.0,
            distance_miles=25.0,
            start_soc=90.0,
            electric_miles=20.0,
            gas_miles=5.0,
            gas_mpg=38.5,
            gas_mode_entered=True,
            soc_at_gas_transition=17.5,
            is_closed=True,
        )
        db_session.add(trip)

        for i in range(3):
            t = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=30 - i * 10),
                speed_mph=45.0 + i,
                state_of_charge=85.0 - i * 5,
            )
            db_session.add(t)

        db_session.commit()
        return trip

    def test_trips_list_shape(self, client, db_session):
        """GET /api/trips returns list of trip summaries with correct keys."""
        self._create_trip(db_session)

        resp = client.get("/api/trips")
        assert resp.status_code == 200
        data = resp.get_json()

        # Response may be a dict with "trips" key or a list
        trips = data.get("trips", data) if isinstance(data, dict) else data
        assert isinstance(trips, list)
        assert len(trips) >= 1

        trip = trips[0]
        _assert_keys_subset(trip, TRIP_SUMMARY_KEYS, "TripSummary")
        assert isinstance(trip["id"], int)
        _assert_nullable_string(trip["start_time"], "start_time")
        _assert_nullable_string(trip["end_time"], "end_time")
        _assert_nullable_number(trip["distance_miles"], "distance_miles")
        assert isinstance(trip["gas_mode_entered"], bool)

    def test_trip_detail_shape(self, client, db_session):
        """GET /api/trips/<id> returns trip + telemetry."""
        trip = self._create_trip(db_session)

        resp = client.get(f"/api/trips/{trip.id}")
        assert resp.status_code == 200
        data = resp.get_json()

        # Detail may wrap in "trip" key or have "trip" and "telemetry"
        if "trip" in data:
            trip_data = data["trip"]
            _assert_keys_subset(trip_data, TRIP_SUMMARY_KEYS, "TripDetail.trip")
            if "telemetry" in data:
                assert isinstance(data["telemetry"], list)
        else:
            # Flat response — just trip fields
            _assert_keys_subset(data, TRIP_SUMMARY_KEYS, "TripDetail")


# ── Charging Sessions ───────────────────────────────────────────────────

CHARGING_SESSION_KEYS = {
    "id", "start_time", "end_time", "start_soc", "end_soc",
    "kwh_added", "charge_type", "cost", "cost_per_kwh",
    "electricity_rate", "location_name", "notes",
    "peak_power_kw", "avg_power_kw",
}

CHARGING_SUMMARY_KEYS = {
    "total_kwh", "total_sessions", "avg_kwh_per_session",
    "l1_sessions", "l2_sessions", "cost_per_mile_electric",
    "cost_per_mile_gas", "electricity_rate",
}


class TestChargingSchemas:
    """Validate charging endpoints match ChargingSessionSchema / ChargingSummarySchema."""

    def _create_charging_session(self, db_session):
        session = ChargingSession(
            start_time=datetime.now(timezone.utc) - timedelta(hours=4),
            end_time=datetime.now(timezone.utc),
            start_soc=20.0,
            end_soc=95.0,
            kwh_added=13.8,
            charge_type="L2",
            peak_power_kw=6.8,
            avg_power_kw=6.6,
            is_complete=True,
            cost=1.85,
            cost_per_kwh=0.134,
            electricity_rate=0.134,
        )
        db_session.add(session)
        db_session.commit()
        return session

    def test_charging_history_shape(self, client, db_session):
        """GET /api/charging/history returns list matching ChargingSessionSchema."""
        self._create_charging_session(db_session)

        resp = client.get("/api/charging/history")
        assert resp.status_code == 200
        data = resp.get_json()

        assert "sessions" in data
        assert "pagination" in data
        assert isinstance(data["sessions"], list)
        assert len(data["sessions"]) >= 1

        session = data["sessions"][0]
        _assert_keys_subset(session, CHARGING_SESSION_KEYS, "ChargingSession")
        assert isinstance(session["id"], int)
        _assert_nullable_string(session["start_time"], "start_time")
        _assert_nullable_number(session["kwh_added"], "kwh_added")

    def test_charging_summary_shape(self, client, db_session):
        """GET /api/charging/summary returns ChargingSummarySchema shape."""
        self._create_charging_session(db_session)

        resp = client.get("/api/charging/summary")
        assert resp.status_code == 200
        data = resp.get_json()

        _assert_keys_subset(data, CHARGING_SUMMARY_KEYS, "ChargingSummary")
        assert isinstance(data["total_sessions"], int)
        assert isinstance(data["l1_sessions"], int)
        assert isinstance(data["l2_sessions"], int)


# ── Battery Health ──────────────────────────────────────────────────────

BATTERY_HEALTH_KEYS = {
    "has_data", "current_capacity_kwh", "original_capacity_kwh",
    "health_percent", "health_status",
}


class TestBatteryHealthSchema:
    """Validate /api/battery/health matches BatteryHealthResponseSchema."""

    def test_no_data_shape(self, client):
        """Empty state returns correct schema shape."""
        resp = client.get("/api/battery/health")
        assert resp.status_code == 200
        data = resp.get_json()

        _assert_keys_subset(data, BATTERY_HEALTH_KEYS, "BatteryHealthResponse")
        assert isinstance(data["has_data"], bool)
        assert data["has_data"] is False

    def test_with_data_shape(self, client, db_session):
        """With readings, returns numeric fields."""
        now = datetime.now(timezone.utc)
        for i in range(3):
            reading = BatteryHealthReading(
                timestamp=now - timedelta(days=i * 30),
                capacity_kwh=18.4 - i * 0.05,
                normalized_capacity_kwh=18.4 - i * 0.05,
                soc_at_reading=100.0,
                ambient_temp_f=70.0,
            )
            db_session.add(reading)
        db_session.commit()

        resp = client.get("/api/battery/health")
        assert resp.status_code == 200
        data = resp.get_json()

        _assert_keys_subset(data, BATTERY_HEALTH_KEYS, "BatteryHealthResponse")
        assert data["has_data"] is True
        _assert_nullable_number(data["current_capacity_kwh"], "current_capacity_kwh")
        _assert_nullable_number(data["health_percent"], "health_percent")
        _assert_nullable_string(data["health_status"], "health_status")
