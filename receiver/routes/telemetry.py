"""
Telemetry routes for VoltTracker.

Handles Torque Pro data ingestion and real-time telemetry endpoints.
"""

import logging
import time
from datetime import timedelta
from typing import Any, Dict

from config import Config
from database import get_db
from exceptions import TelemetryParsingError
from flask import Blueprint, current_app, jsonify, request
from models import TelemetryRaw, Trip
from utils import TorqueParser, normalize_datetime, utc_now
from utils.context_enrichment import enrich_event_with_vehicle_context
from utils.error_codes import ErrorCode, StructuredError
from utils.wide_events import WideEvent

logger = logging.getLogger(__name__)

telemetry_bp = Blueprint("telemetry", __name__)


def _write_telemetry_fallback(data: dict, error: Exception) -> None:
    """Write failed telemetry to a JSONL fallback file for later replay."""
    try:
        import os as _os
        import json as _json

        fallback_dir = _os.path.join(_os.path.dirname(_os.path.dirname(__file__)), "logs")
        _os.makedirs(fallback_dir, exist_ok=True)
        fallback_path = _os.path.join(fallback_dir, "failed_telemetry.jsonl")

        # Rotate fallback file if it exceeds 10MB
        _max_fallback_bytes = 10 * 1024 * 1024
        if _os.path.exists(fallback_path) and _os.path.getsize(fallback_path) > _max_fallback_bytes:
            _rotated = fallback_path + ".1"
            if _os.path.exists(_rotated):
                _os.remove(_rotated)
            _os.rename(fallback_path, _rotated)

        fallback_record = {
            "session_id": str(data.get("session_id")),
            "timestamp": data.get("timestamp").isoformat() if data.get("timestamp") else None,
            "raw_data": data.get("raw_data"),
            "error": str(error),
            "logged_at": utc_now().isoformat(),
        }
        with open(fallback_path, "a") as fb:
            fb.write(_json.dumps(fallback_record) + "\n")
        logger.info("Wrote failed telemetry to fallback file")
    except Exception as fb_err:
        logger.error(f"Failed to write telemetry fallback: {fb_err}")


def emit_telemetry_update(socketio, data: dict):
    """Emit real-time telemetry update to all connected clients."""
    socketio.emit(
        "telemetry",
        {
            "speed": data.get("speed_mph"),
            "rpm": data.get("engine_rpm"),
            "soc": data.get("state_of_charge"),
            "fuel_percent": data.get("fuel_level_percent"),
            "hv_power": data.get("hv_battery_power_kw"),
            "latitude": data.get("latitude"),
            "longitude": data.get("longitude"),
            "odometer": data.get("odometer_miles"),
            "timestamp": utc_now().isoformat(),
        },
    )


def _validate_torque_token(token, event):
    """Validate Torque API token. Returns error response or None."""
    if not Config.TORQUE_API_TOKEN:
        return None
    if token == Config.TORQUE_API_TOKEN:
        return None
    event.add_context(auth_failed=True)
    event.add_error(StructuredError(
        ErrorCode.E001_INVALID_TOKEN, "Invalid Torque API token",
        remote_addr=request.remote_addr,
    ))
    event.mark_failure("invalid_token")
    event.emit(level="warning", force=True)
    logger.warning(f"Invalid Torque API token attempt from {request.remote_addr}")
    return ("Unauthorized", 401)


def _get_or_create_trip(db, data, event):
    """Find or create a trip for the given session. Returns trip (may be None)."""
    trip = db.query(Trip).filter(Trip.session_id == data["session_id"]).first()
    if trip:
        event.add_context(trip_id=trip.id)
        return trip

    try:
        trip = Trip(
            session_id=data["session_id"], start_time=data["timestamp"],
            start_odometer=data["odometer_miles"], start_soc=data["state_of_charge"],
        )
        db.add(trip)
        db.flush()
        event.add_business_metric("trip_created", True)
        event.add_context(trip_id=trip.id)
        logger.info(f"New trip started: {trip.session_id}")
        return trip
    except Exception as e:
        event.add_technical_metric("trip_race_condition", True)
        logger.debug(str(StructuredError(
            ErrorCode.E204_DB_RACE_CONDITION, "Trip race condition handled",
            exception=e, session_id=str(data["session_id"]),
        )))
        db.rollback()
        trip = db.query(Trip).filter(Trip.session_id == data["session_id"]).first()
        if trip is None:
            event.add_context(trip_missing_after_retry=True)
            logger.warning(f"Trip not found after race condition retry: {data['session_id']}")
        else:
            event.add_context(trip_id=trip.id)
        return trip


def _update_trip_start_values(trip, data):
    """Backfill trip start SOC/odometer if they were null."""
    if trip is None:
        return
    if trip.start_soc is None and data["state_of_charge"] is not None:
        trip.start_soc = data["state_of_charge"]
    if trip.start_odometer is None and data["odometer_miles"] is not None:
        trip.start_odometer = data["odometer_miles"]


def _store_telemetry(db, data, event):
    """Create and commit TelemetryRaw record. Returns 'OK!' on commit failure."""
    telemetry = TelemetryRaw(
        session_id=data["session_id"], timestamp=data["timestamp"],
        latitude=data["latitude"], longitude=data["longitude"],
        speed_mph=data["speed_mph"], engine_rpm=data["engine_rpm"],
        throttle_position=data["throttle_position"], coolant_temp_f=data["coolant_temp_f"],
        intake_air_temp_f=data["intake_air_temp_f"],
        fuel_level_percent=data["fuel_level_percent"],
        fuel_remaining_gallons=data["fuel_remaining_gallons"],
        state_of_charge=data["state_of_charge"], battery_voltage=data["battery_voltage"],
        ambient_temp_f=data["ambient_temp_f"], odometer_miles=data["odometer_miles"],
        hv_battery_power_kw=data["hv_battery_power_kw"],
        hv_battery_current_a=data["hv_battery_current_a"],
        hv_battery_voltage_v=data["hv_battery_voltage_v"],
        charger_ac_power_kw=data["charger_ac_power_kw"],
        charger_connected=data["charger_connected"], raw_data=data["raw_data"],
    )
    db.add(telemetry)
    try:
        db.commit()
        return None
    except Exception as commit_error:
        db.rollback()
        logger.error(f"Failed to commit telemetry: {commit_error}", exc_info=True)
        _write_telemetry_fallback(data, commit_error)
        event.add_error(StructuredError(
            ErrorCode.E200_DB_CONNECTION_FAILED, "Failed to commit telemetry",
            exception=commit_error,
        ))
        event.mark_failure("db_commit_failed")
        event.emit(level="error", force=True)
        return "OK!"


def _invalidate_telemetry_cache():
    """Invalidate cached query results with debouncing."""
    from utils.cache_utils import get_redis_cache as _get_redis
    _redis = _get_redis()
    should_invalidate = True
    if _redis:
        try:
            if _redis.get("cache_invalidation_debounce"):
                should_invalidate = False
            else:
                _redis.setex("cache_invalidation_debounce", 5, "1")
        except Exception:
            pass

    if should_invalidate:
        from utils.query_cache import invalidate_cache_pattern as invalidate_query_cache
        invalidate_query_cache("telemetry")

    if _redis:
        try:
            _redis.delete("latest_telemetry")
        except Exception:
            pass


def _handle_upload_error(e, event, start_time):
    """Handle telemetry upload exceptions and emit error event."""
    duration_ms = (time.time() - start_time) * 1000
    event.context["duration_ms"] = round(duration_ms, 2)

    if isinstance(e, (ValueError, KeyError, TypeError)):
        error_code = ErrorCode.E300_TORQUE_PARSE_FAILED
    elif hasattr(e, "__module__") and "sqlalchemy" in e.__module__:
        error_code = ErrorCode.E200_DB_CONNECTION_FAILED
    else:
        error_code = ErrorCode.E500_INTERNAL_SERVER_ERROR

    event.add_error(StructuredError(
        error_code, f"Error processing Torque upload: {type(e).__name__}", exception=e,
    ))
    event.mark_failure(f"parsing_error: {type(e).__name__}")
    failed_params = dict(request.args) if request.method == "GET" else dict(request.form)
    event.add_context(failed_params=failed_params)
    event.emit(level="error", force=True)

    logger.error(str(TelemetryParsingError(f"Error processing Torque upload: {e}")), exc_info=True)


@telemetry_bp.route("/torque/upload", methods=["GET", "POST"])
@telemetry_bp.route("/torque/upload/<token>", methods=["GET", "POST"])
def torque_upload(token=None):
    """Receive data from Torque Pro app. Must respond with 'OK!' exactly."""
    start_time = time.time()
    event = WideEvent("telemetry_upload")
    event.add_context(method=request.method, remote_addr=request.remote_addr, has_token=bool(token))
    event.add_feature_flags(
        weather_integration=Config.FEATURE_WEATHER_INTEGRATION,
        enhanced_route_detection=Config.FEATURE_ENHANCED_ROUTE_DETECTION,
        predictive_range=Config.FEATURE_PREDICTIVE_RANGE,
    )

    auth_error = _validate_torque_token(token, event)
    if auth_error:
        return auth_error

    try:
        form_data = request.args if request.method == "GET" else request.form
        event.add_business_metric("raw_params_count", len(form_data))

        with event.timer("parse_telemetry"):
            data = TorqueParser.parse(form_data)

        db = get_db()
        event.context["trace_id"] = str(data["session_id"])
        event.add_context(
            session_id=str(data["session_id"]), odometer_miles=data.get("odometer_miles"),
            state_of_charge=data.get("state_of_charge"),
            has_gps=bool(data.get("latitude") and data.get("longitude")),
            engine_running=(data.get("engine_rpm") or 0) > 400,
            charger_connected=data.get("charger_connected", False),
        )

        with event.timer("db_trip_query"):
            trip = _get_or_create_trip(db, data, event)
        _update_trip_start_values(trip, data)

        # Deduplicate
        existing = db.query(TelemetryRaw.id).filter(
            TelemetryRaw.session_id == data["session_id"],
            TelemetryRaw.timestamp == data["timestamp"],
        ).first()
        if existing:
            db.commit()
            return "OK!"

        with event.timer("db_telemetry_insert"):
            commit_err = _store_telemetry(db, data, event)
        if commit_err:
            return commit_err

        _invalidate_telemetry_cache()

        socketio = current_app.extensions.get("socketio")
        if socketio:
            emit_telemetry_update(socketio, data)
            event.add_technical_metric("websocket_emitted", True)

        with event.timer("context_enrichment"):
            enrich_event_with_vehicle_context(event, db, include_battery_health=False)

        duration_ms = (time.time() - start_time) * 1000
        event.context["duration_ms"] = round(duration_ms, 2)
        event.mark_success()
        event.add_business_metric("telemetry_stored", True)

        if event.should_emit(
            sample_rate=Config.LOGGING_SAMPLE_RATE_TELEMETRY,
            slow_threshold_ms=Config.LOGGING_SLOW_THRESHOLD_MS,
        ):
            event.emit()

        return "OK!"

    except Exception as e:
        _handle_upload_error(e, event, start_time)
        return "OK!"


def _calc_kwh_used(start_soc, current_soc):
    """Calculate kWh used from SOC change. Returns None if insufficient data."""
    if start_soc is None or current_soc is None:
        return None
    from utils import soc_to_kwh
    soc_change = start_soc - current_soc
    return soc_to_kwh(soc_change) if soc_change >= 0 else None


def _calc_gas_usage(first, latest, trip, stats):
    """Calculate fuel usage and gas MPG, updating stats in-place."""
    start_fuel = float(first.fuel_level_percent) if first.fuel_level_percent else None
    current_fuel = float(latest.fuel_level_percent) if latest.fuel_level_percent else None
    if start_fuel is None or current_fuel is None:
        return

    fuel_percent_used = start_fuel - current_fuel
    if fuel_percent_used <= 0.5:
        return

    from utils import fuel_percent_to_gallons
    fuel_gallons_used = fuel_percent_to_gallons(fuel_percent_used)
    stats["fuel_used_gallons"] = fuel_gallons_used

    gas_miles = None
    if trip and trip.gas_miles:
        gas_miles = float(trip.gas_miles)
    elif stats["miles_driven"] and stats.get("electric_miles"):
        gas_miles = stats["miles_driven"] - stats["electric_miles"]
    if gas_miles and gas_miles > 0:
        stats["gas_mpg"] = gas_miles / fuel_gallons_used


def _calculate_trip_stats(first: TelemetryRaw | None, latest: TelemetryRaw | None, trip: Trip | None) -> dict:
    """Calculate real-time trip efficiency statistics."""
    stats: Dict[str, Any] = {
        "miles_driven": None, "kwh_used": None, "kwh_per_mile": None,
        "in_gas_mode": False, "electric_miles": None, "gas_miles": None,
        "gas_mpg": None, "fuel_used_gallons": None,
    }

    if not first or not latest:
        return stats

    if first.odometer_miles and latest.odometer_miles:
        stats["miles_driven"] = float(latest.odometer_miles - first.odometer_miles)

    start_soc = float(trip.start_soc) if trip and trip.start_soc else None
    if start_soc is None and first.state_of_charge:
        start_soc = float(first.state_of_charge)
    current_soc = float(latest.state_of_charge) if latest.state_of_charge else None

    stats["kwh_used"] = _calc_kwh_used(start_soc, current_soc)

    current_rpm = float(latest.engine_rpm) if latest.engine_rpm else 0
    stats["in_gas_mode"] = (
        current_rpm > Config.RPM_THRESHOLD and current_soc is not None and current_soc < Config.SOC_GAS_THRESHOLD
    )

    electric_miles = stats.get("electric_miles") or stats.get("miles_driven")
    if stats["kwh_used"] and electric_miles and electric_miles > 0:
        stats["kwh_per_mile"] = stats["kwh_used"] / electric_miles

    _calc_gas_usage(first, latest, trip, stats)
    return stats


def _opt_float(val):
    """Convert to float if not None, else None."""
    return float(val) if val else None


def _build_telemetry_data(latest):
    """Build the 'data' dict for latest telemetry response."""
    engine_running = latest.engine_running
    if engine_running is None:
        engine_running = latest.engine_rpm and latest.engine_rpm > Config.RPM_THRESHOLD
    return {
        "timestamp": latest.timestamp.isoformat(),
        "soc": _opt_float(latest.state_of_charge),
        "fuel_percent": _opt_float(latest.fuel_level_percent),
        "speed_mph": _opt_float(latest.speed_mph),
        "engine_rpm": _opt_float(latest.engine_rpm),
        "latitude": _opt_float(latest.latitude),
        "longitude": _opt_float(latest.longitude),
        "odometer": _opt_float(latest.odometer_miles),
        "hv_battery_power_kw": _opt_float(latest.hv_battery_power_kw),
        "hv_battery_voltage_v": _opt_float(latest.hv_battery_voltage_v),
        "hv_battery_current_a": _opt_float(latest.hv_battery_current_a),
        "motor_a_rpm": _opt_float(latest.motor_a_rpm),
        "motor_b_rpm": _opt_float(latest.motor_b_rpm),
        "generator_rpm": _opt_float(latest.generator_rpm),
        "motor_temp_max_f": _opt_float(latest.motor_temp_max_f),
        "engine_running": engine_running,
        "engine_oil_temp_f": _opt_float(latest.engine_oil_temp_f),
        "battery_capacity_kwh": _opt_float(latest.battery_capacity_kwh),
        "battery_temp_f": _opt_float(latest.battery_temp_f),
        "charger_power_kw": _opt_float(latest.charger_power_kw),
        "charger_connected": latest.charger_connected,
    }


@telemetry_bp.route("/api/telemetry/latest", methods=["GET"])
def get_latest_telemetry():
    """Get latest telemetry for real-time dashboard display."""
    from sqlalchemy import desc
    from utils.cache_utils import get_redis_cache
    import json as _json

    db = get_db()
    redis = get_redis_cache()
    cache_key = "latest_telemetry"

    if redis:
        try:
            cached = redis.get(cache_key)
            if cached is not None:
                return jsonify(_json.loads(cached))
        except Exception:
            pass

    latest_telemetry = db.query(TelemetryRaw).order_by(desc(TelemetryRaw.timestamp)).first()
    if not latest_telemetry:
        return jsonify({"active": False})

    cutoff_time = utc_now() - timedelta(seconds=Config.TRIP_TIMEOUT_SECONDS)
    if normalize_datetime(latest_telemetry.timestamp) < cutoff_time:
        return jsonify({"active": False})

    active_trip = db.query(Trip).filter(Trip.session_id == latest_telemetry.session_id).first()
    recent = (
        db.query(TelemetryRaw)
        .filter(TelemetryRaw.session_id == latest_telemetry.session_id)
        .order_by(desc(TelemetryRaw.timestamp)).limit(10).all()
    )
    latest = recent[0] if recent else latest_telemetry
    first_telemetry = (
        db.query(TelemetryRaw)
        .filter(TelemetryRaw.session_id == latest_telemetry.session_id)
        .order_by(TelemetryRaw.timestamp).first()
    )

    result = {
        "active": True,
        "session_id": str(latest_telemetry.session_id),
        "start_time": active_trip.start_time.isoformat() if active_trip else None,
        "start_soc": float(active_trip.start_soc) if active_trip and active_trip.start_soc else None,
        "data": _build_telemetry_data(latest),
        "trip_stats": _calculate_trip_stats(first_telemetry, latest, active_trip),
        "point_count": len(recent),
    }

    if redis:
        try:
            redis.setex(cache_key, 5, _json.dumps(result, default=str))
        except Exception:
            pass

    return jsonify(result)
