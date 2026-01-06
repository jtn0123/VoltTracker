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


@telemetry_bp.route("/torque/upload", methods=["GET", "POST"])
@telemetry_bp.route("/torque/upload/<token>", methods=["GET", "POST"])
def torque_upload(token=None):
    """
    Receive data from Torque Pro app.

    Torque sends data as either GET query params or POST form data.
    Must respond with "OK!" exactly.

    URL formats:
        - /torque/upload (no auth, works if TORQUE_API_TOKEN not set)
        - /torque/upload/<token> (token must match TORQUE_API_TOKEN)
    """
    start_time = time.time()

    # Initialize wide event for comprehensive logging with request/trace IDs
    # Note: trace_id will be set to session_id after parsing to link all telemetry for this trip
    event = WideEvent("telemetry_upload")
    event.add_context(
        method=request.method,
        remote_addr=request.remote_addr,
        has_token=bool(token),
    )

    # Add feature flags to track which features are enabled
    event.add_feature_flags(
        weather_integration=Config.FEATURE_WEATHER_INTEGRATION,
        enhanced_route_detection=Config.FEATURE_ENHANCED_ROUTE_DETECTION,
        predictive_range=Config.FEATURE_PREDICTIVE_RANGE,
    )

    # Validate API token if configured
    if Config.TORQUE_API_TOKEN:
        if token != Config.TORQUE_API_TOKEN:
            event.add_context(auth_failed=True)
            structured_error = StructuredError(
                ErrorCode.E001_INVALID_TOKEN,
                "Invalid Torque API token",
                remote_addr=request.remote_addr,
            )
            event.add_error(structured_error)
            event.mark_failure("invalid_token")
            event.emit(level="warning", force=True)
            logger.warning(f"Invalid Torque API token attempt from {request.remote_addr}")
            return "Unauthorized", 401

    try:
        # Handle both GET (query params) and POST (form data)
        if request.method == "GET":
            form_data = request.args
        else:
            form_data = request.form

        event.add_business_metric("raw_params_count", len(form_data))

        # Parse telemetry data with performance timing
        with event.timer("parse_telemetry"):
            data = TorqueParser.parse(form_data)

        db = get_db()

        # Set trace_id to session_id to link all telemetry uploads for this trip
        event.context["trace_id"] = str(data["session_id"])

        # Add high-cardinality context
        event.add_context(
            session_id=str(data["session_id"]),
            odometer_miles=data.get("odometer_miles"),
            state_of_charge=data.get("state_of_charge"),
            has_gps=bool(data.get("latitude") and data.get("longitude")),
            engine_running=(data.get("engine_rpm") or 0) > 400,
            charger_connected=data.get("charger_connected", False),
        )

        # Create or get trip (handle race condition) with performance timing
        with event.timer("db_trip_query"):
            trip = db.query(Trip).filter(Trip.session_id == data["session_id"]).first()

        if not trip:
            try:
                trip = Trip(
                    session_id=data["session_id"],
                    start_time=data["timestamp"],
                    start_odometer=data["odometer_miles"],
                    start_soc=data["state_of_charge"],
                )
                db.add(trip)
                db.flush()
                event.add_business_metric("trip_created", True)
                event.add_context(trip_id=trip.id)
                logger.info(f"New trip started: {trip.session_id}")
            except Exception as e:
                # Race condition - trip was created by another request
                event.add_technical_metric("trip_race_condition", True)
                structured_error = StructuredError(
                    ErrorCode.E204_DB_RACE_CONDITION,
                    "Trip race condition handled",
                    exception=e,
                    session_id=str(data["session_id"]),
                )
                logger.debug(str(structured_error))
                db.rollback()
                trip = db.query(Trip).filter(Trip.session_id == data["session_id"]).first()
                # If trip is still None after retry (rare - possibly deleted), skip trip updates
                if trip is None:
                    event.add_context(trip_missing_after_retry=True)
                    logger.warning(f"Trip not found after race condition retry: {data['session_id']}")
                else:
                    event.add_context(trip_id=trip.id)
        else:
            event.add_context(trip_id=trip.id)

        # Update trip start values if they were null initially
        if trip is not None:
            if trip.start_soc is None and data["state_of_charge"] is not None:
                trip.start_soc = data["state_of_charge"]
            if trip.start_odometer is None and data["odometer_miles"] is not None:
                trip.start_odometer = data["odometer_miles"]

        # Store telemetry with performance timing
        with event.timer("db_telemetry_insert"):
            telemetry = TelemetryRaw(
                session_id=data["session_id"],
                timestamp=data["timestamp"],
                latitude=data["latitude"],
                longitude=data["longitude"],
                speed_mph=data["speed_mph"],
                engine_rpm=data["engine_rpm"],
                throttle_position=data["throttle_position"],
                coolant_temp_f=data["coolant_temp_f"],
                intake_air_temp_f=data["intake_air_temp_f"],
                fuel_level_percent=data["fuel_level_percent"],
                fuel_remaining_gallons=data["fuel_remaining_gallons"],
                state_of_charge=data["state_of_charge"],
                battery_voltage=data["battery_voltage"],
                ambient_temp_f=data["ambient_temp_f"],
                odometer_miles=data["odometer_miles"],
                hv_battery_power_kw=data["hv_battery_power_kw"],
                hv_battery_current_a=data["hv_battery_current_a"],
                hv_battery_voltage_v=data["hv_battery_voltage_v"],
                charger_ac_power_kw=data["charger_ac_power_kw"],
                charger_connected=data["charger_connected"],
                raw_data=data["raw_data"],
            )
            db.add(telemetry)
            db.commit()

        # Emit real-time update to WebSocket clients if socketio is available
        socketio = current_app.extensions.get("socketio")
        if socketio:
            emit_telemetry_update(socketio, data)
            event.add_technical_metric("websocket_emitted", True)

        # Enrich event with vehicle context (loggingsucks.com progressive enrichment pattern)
        # This adds: total_trips, total_miles, account_age_days, usage_tier, avg_efficiency
        with event.timer("context_enrichment"):
            enrich_event_with_vehicle_context(event, db, include_battery_health=False)

        # Calculate duration and mark success
        duration_ms = (time.time() - start_time) * 1000
        event.context["duration_ms"] = round(duration_ms, 2)
        event.mark_success()
        event.add_business_metric("telemetry_stored", True)

        # Emit wide event with configurable tail sampling
        # Always logs: errors, slow requests (>1s), new trips
        # Samples: 5% of fast successful uploads (configurable)
        if event.should_emit(
            sample_rate=Config.LOGGING_SAMPLE_RATE_TELEMETRY,
            slow_threshold_ms=Config.LOGGING_SLOW_THRESHOLD_MS,
        ):
            event.emit()

        return "OK!"

    except Exception as e:
        # Add error to wide event with appropriate error code
        duration_ms = (time.time() - start_time) * 1000
        event.context["duration_ms"] = round(duration_ms, 2)

        # Determine error code based on exception type
        if isinstance(e, (ValueError, KeyError, TypeError)):
            error_code = ErrorCode.E300_TORQUE_PARSE_FAILED
        elif hasattr(e, "__module__") and "sqlalchemy" in e.__module__:
            error_code = ErrorCode.E200_DB_CONNECTION_FAILED
        else:
            error_code = ErrorCode.E500_INTERNAL_SERVER_ERROR

        structured_error = StructuredError(
            error_code,
            f"Error processing Torque upload: {type(e).__name__}",
            exception=e,
        )
        event.add_error(structured_error)
        event.mark_failure(f"parsing_error: {type(e).__name__}")

        # Log raw request data for debugging
        if request.method == "GET":
            event.add_context(failed_params=dict(request.args))
        else:
            event.add_context(failed_params=dict(request.form))

        # Emit wide event (always emitted on errors)
        event.emit(level="error", force=True)

        # Log full exception details for debugging (data is often malformed from Torque)
        error = TelemetryParsingError(f"Error processing Torque upload: {e}")
        logger.error(str(error), exc_info=True)

        # Return OK to avoid Torque retries (Torque doesn't handle errors gracefully)
        # Data loss is logged above for later investigation
        return "OK!"


def _calculate_trip_stats(first: TelemetryRaw | None, latest: TelemetryRaw | None, trip: Trip | None) -> dict:
    """
    Calculate real-time trip efficiency statistics.

    Returns stats for display in the live trip card:
    - miles_driven: Distance traveled this trip
    - kwh_used: Electric energy consumed
    - kwh_per_mile: Electric efficiency
    - in_gas_mode: Whether engine is running on gas
    - gas_miles: Miles driven in gas mode
    - gas_mpg: Fuel efficiency if in gas mode
    """
    stats: Dict[str, Any] = {
        "miles_driven": None,
        "kwh_used": None,
        "kwh_per_mile": None,
        "in_gas_mode": False,
        "electric_miles": None,
        "gas_miles": None,
        "gas_mpg": None,
        "fuel_used_gallons": None,
    }

    if not first or not latest:
        return stats

    # Calculate miles driven from odometer
    if first.odometer_miles and latest.odometer_miles:
        stats["miles_driven"] = float(latest.odometer_miles - first.odometer_miles)

    # Get start SOC (prefer trip's stored value, fall back to first telemetry)
    start_soc = None
    if trip and trip.start_soc:
        start_soc = float(trip.start_soc)
    elif first.state_of_charge:
        start_soc = float(first.state_of_charge)

    current_soc = float(latest.state_of_charge) if latest.state_of_charge else None

    # Calculate kWh used from SOC change
    if start_soc is not None and current_soc is not None:
        soc_change = start_soc - current_soc
        if soc_change >= 0:  # Only count discharge, not regen gains
            stats["kwh_used"] = soc_change / 100.0 * Config.BATTERY_CAPACITY_KWH

    # Detect gas mode: engine running AND low SOC
    current_rpm = float(latest.engine_rpm) if latest.engine_rpm else 0
    in_gas_mode = (
        current_rpm > Config.RPM_THRESHOLD and current_soc is not None and current_soc < Config.SOC_GAS_THRESHOLD
    )
    stats["in_gas_mode"] = in_gas_mode

    # Calculate kWh/mile for electric portion
    if stats["kwh_used"] and stats["miles_driven"] and stats["miles_driven"] > 0:
        # For simplicity, use total miles for now
        # A more accurate version would track when gas mode started
        stats["kwh_per_mile"] = stats["kwh_used"] / stats["miles_driven"]

    # Calculate gas usage if fuel data available
    start_fuel = None
    if first.fuel_level_percent:
        start_fuel = float(first.fuel_level_percent)

    current_fuel = float(latest.fuel_level_percent) if latest.fuel_level_percent else None

    if start_fuel is not None and current_fuel is not None:
        fuel_percent_used = start_fuel - current_fuel
        if fuel_percent_used > 0.5:  # Only count if meaningful fuel was used
            fuel_gallons_used = fuel_percent_used / 100.0 * Config.TANK_CAPACITY_GALLONS
            stats["fuel_used_gallons"] = fuel_gallons_used

            # Estimate gas miles (rough: if fuel used, assume some portion was gas driving)
            if stats["miles_driven"] and stats["miles_driven"] > 0:
                # Calculate gas MPG from fuel consumption
                stats["gas_mpg"] = stats["miles_driven"] / fuel_gallons_used

    return stats


@telemetry_bp.route("/api/telemetry/latest", methods=["GET"])
def get_latest_telemetry():
    """Get latest telemetry for real-time dashboard display."""
    from sqlalchemy import desc

    db = get_db()

    # Find the most recent telemetry point to identify the active trip
    latest_telemetry = db.query(TelemetryRaw).order_by(desc(TelemetryRaw.timestamp)).first()

    if not latest_telemetry:
        return jsonify({"active": False})

    # Check if the latest data is recent (within timeout period)
    cutoff_time = utc_now() - timedelta(seconds=Config.TRIP_TIMEOUT_SECONDS)
    latest_ts = normalize_datetime(latest_telemetry.timestamp)
    if latest_ts < cutoff_time:
        return jsonify({"active": False})

    # Get the trip for this session
    active_trip = db.query(Trip).filter(Trip.session_id == latest_telemetry.session_id).first()

    # Get last 10 telemetry points for this session
    recent = (
        db.query(TelemetryRaw)
        .filter(TelemetryRaw.session_id == latest_telemetry.session_id)
        .order_by(desc(TelemetryRaw.timestamp))
        .limit(10)
        .all()
    )

    latest = recent[0] if recent else latest_telemetry

    # Get the first telemetry point for this session to calculate trip stats
    first_telemetry = (
        db.query(TelemetryRaw)
        .filter(TelemetryRaw.session_id == latest_telemetry.session_id)
        .order_by(TelemetryRaw.timestamp)
        .first()
    )

    # Calculate trip efficiency stats
    trip_stats = _calculate_trip_stats(first_telemetry, latest, active_trip)

    return jsonify(
        {
            "active": True,
            "session_id": str(latest_telemetry.session_id),
            "start_time": active_trip.start_time.isoformat() if active_trip else None,
            "start_soc": float(active_trip.start_soc) if active_trip and active_trip.start_soc else None,
            "data": {
                "timestamp": latest.timestamp.isoformat(),
                "soc": float(latest.state_of_charge) if latest.state_of_charge else None,
                "fuel_percent": float(latest.fuel_level_percent) if latest.fuel_level_percent else None,
                "speed_mph": float(latest.speed_mph) if latest.speed_mph else None,
                "engine_rpm": float(latest.engine_rpm) if latest.engine_rpm else None,
                "latitude": float(latest.latitude) if latest.latitude else None,
                "longitude": float(latest.longitude) if latest.longitude else None,
                "odometer": float(latest.odometer_miles) if latest.odometer_miles else None,
                # Power flow data
                "hv_battery_power_kw": float(latest.hv_battery_power_kw) if latest.hv_battery_power_kw else None,
                "hv_battery_voltage_v": float(latest.hv_battery_voltage_v) if latest.hv_battery_voltage_v else None,
                "hv_battery_current_a": float(latest.hv_battery_current_a) if latest.hv_battery_current_a else None,
                # Motor/Generator
                "motor_a_rpm": float(latest.motor_a_rpm) if latest.motor_a_rpm else None,
                "motor_b_rpm": float(latest.motor_b_rpm) if latest.motor_b_rpm else None,
                "generator_rpm": float(latest.generator_rpm) if latest.generator_rpm else None,
                "motor_temp_max_f": float(latest.motor_temp_max_f) if latest.motor_temp_max_f else None,
                # Engine
                "engine_running": latest.engine_running
                if latest.engine_running is not None
                else (latest.engine_rpm and latest.engine_rpm > 500),
                "engine_oil_temp_f": float(latest.engine_oil_temp_f) if latest.engine_oil_temp_f else None,
                # Battery health
                "battery_capacity_kwh": float(latest.battery_capacity_kwh) if latest.battery_capacity_kwh else None,
                "battery_temp_f": float(latest.battery_temp_f) if latest.battery_temp_f else None,
                # Charging
                "charger_power_kw": float(latest.charger_power_kw) if latest.charger_power_kw else None,
                "charger_connected": latest.charger_connected,
            },
            "trip_stats": trip_stats,
            "point_count": len(recent),
        }
    )
