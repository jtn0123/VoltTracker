"""
Charging routes for VoltTracker.

Handles charging session CRUD operations and charging statistics.
"""

import logging
from datetime import datetime, timedelta

from config import Config
from database import get_db
from flask import Blueprint, jsonify, request
from models import ChargingSession, Trip
from sqlalchemy import desc, func
from utils import utc_now

logger = logging.getLogger(__name__)

charging_bp = Blueprint("charging", __name__)


@charging_bp.route("/charging/history", methods=["GET"])
def get_charging_history():
    """Get charging session history."""
    db = get_db()

    sessions = db.query(ChargingSession).order_by(desc(ChargingSession.start_time)).limit(50).all()

    return jsonify([s.to_dict() for s in sessions])


@charging_bp.route("/charging/add", methods=["POST"])
def add_charging_session():
    """
    Manually add a charging session.

    Request body:
        start_time: ISO datetime (required)
        end_time: ISO datetime
        start_soc: Starting SOC percentage
        end_soc: Ending SOC percentage
        kwh_added: kWh added during session
        charge_type: 'L1', 'L2', or 'DCFC'
        location_name: Location description
        cost: Total cost
        notes: Optional notes
    """
    db = get_db()

    data = request.get_json()
    if not data:
        return jsonify({"error": "No data provided"}), 400

    try:
        start_time = datetime.fromisoformat(data.get("start_time", ""))
    except (ValueError, TypeError):
        return jsonify({"error": "Invalid or missing start_time"}), 400

    end_time = None
    if data.get("end_time"):
        try:
            end_time = datetime.fromisoformat(data["end_time"])
        except (ValueError, TypeError):
            pass

    session = ChargingSession(
        start_time=start_time,
        end_time=end_time,
        start_soc=data.get("start_soc"),
        end_soc=data.get("end_soc"),
        kwh_added=data.get("kwh_added"),
        peak_power_kw=data.get("peak_power_kw"),
        avg_power_kw=data.get("avg_power_kw"),
        latitude=data.get("latitude"),
        longitude=data.get("longitude"),
        location_name=data.get("location_name"),
        charge_type=data.get("charge_type"),
        cost=data.get("cost"),
        cost_per_kwh=data.get("cost_per_kwh"),
        notes=data.get("notes"),
        is_complete=end_time is not None,
    )
    db.add(session)
    db.commit()

    return jsonify(session.to_dict()), 201


@charging_bp.route("/charging/<int:session_id>", methods=["GET"])
def get_charging_session(session_id):
    """Get details of a specific charging session."""
    db = get_db()

    session = db.query(ChargingSession).filter(ChargingSession.id == session_id).first()
    if not session:
        return jsonify({"error": "Charging session not found"}), 404

    return jsonify(session.to_dict())


@charging_bp.route("/charging/<int:session_id>/curve", methods=["GET"])
def get_charging_curve(session_id):
    """
    Get charging curve data for visualization.

    Returns time-series power and SOC data for the charging session.
    If charging_curve is not stored, attempts to reconstruct from telemetry.
    """
    from models import TelemetryRaw

    db = get_db()

    session = db.query(ChargingSession).filter(ChargingSession.id == session_id).first()
    if not session:
        return jsonify({"error": "Charging session not found"}), 404

    # Check if we have stored curve data
    if session.charging_curve and len(session.charging_curve) > 0:
        return jsonify({"session_id": session_id, "curve": session.charging_curve, "source": "stored"})

    # Try to reconstruct from telemetry data
    if session.start_time and session.end_time:
        telemetry = (
            db.query(TelemetryRaw)
            .filter(
                TelemetryRaw.timestamp >= session.start_time,
                TelemetryRaw.timestamp <= session.end_time,
                TelemetryRaw.charger_connected.is_(True),
            )
            .order_by(TelemetryRaw.timestamp)
            .all()
        )

        if telemetry:
            curve_data = []
            for t in telemetry:
                # Use charger AC power or HV battery power (negative during charging)
                power = t.charger_ac_power_kw
                if power is None and t.hv_battery_power_kw is not None:
                    # HV power is negative during charging
                    power = abs(t.hv_battery_power_kw) if t.hv_battery_power_kw < 0 else None

                if power is not None:
                    curve_data.append(
                        {
                            "timestamp": t.timestamp.isoformat() if t.timestamp else None,
                            "power_kw": round(power, 2),
                            "soc": t.state_of_charge,
                        }
                    )

            if curve_data:
                return jsonify({"session_id": session_id, "curve": curve_data, "source": "telemetry"})

    # No curve data available
    return jsonify(
        {
            "session_id": session_id,
            "curve": [],
            "source": "none",
            "message": "No charging curve data available for this session",
        }
    )


@charging_bp.route("/charging/<int:session_id>", methods=["DELETE"])
def delete_charging_session(session_id):
    """Delete a charging session."""
    db = get_db()

    session = db.query(ChargingSession).filter(ChargingSession.id == session_id).first()
    if not session:
        return jsonify({"error": "Charging session not found"}), 404

    db.delete(session)
    db.commit()

    logger.info(f"Deleted charging session {session_id}")
    return jsonify({"message": f"Charging session {session_id} deleted successfully"})


@charging_bp.route("/charging/<int:session_id>", methods=["PATCH"])
def update_charging_session(session_id):
    """Update a charging session."""
    db = get_db()

    session = db.query(ChargingSession).filter(ChargingSession.id == session_id).first()
    if not session:
        return jsonify({"error": "Charging session not found"}), 404

    data = request.get_json()
    if not data:
        return jsonify({"error": "No data provided"}), 400

    allowed_fields = [
        "end_time",
        "end_soc",
        "kwh_added",
        "peak_power_kw",
        "avg_power_kw",
        "location_name",
        "charge_type",
        "cost",
        "cost_per_kwh",
        "notes",
        "is_complete",
    ]

    for field in allowed_fields:
        if field in data:
            if field == "end_time" and data[field]:
                try:
                    setattr(session, field, datetime.fromisoformat(data[field]))
                except (ValueError, TypeError):
                    return jsonify({"error": f"Invalid datetime format for {field}"}), 400
            else:
                setattr(session, field, data[field])

    db.commit()

    logger.info(f"Updated charging session {session_id}: {data}")
    return jsonify(session.to_dict())


@charging_bp.route("/charging/summary", methods=["GET"])
def get_charging_summary():
    """Get charging statistics summary with cost analysis."""
    db = get_db()

    sessions = db.query(ChargingSession).filter(ChargingSession.is_complete.is_(True)).all()

    # Get trip data using SQL aggregation (much faster than loading all trips)
    trip_stats = (
        db.query(
            func.coalesce(func.sum(Trip.distance_miles), 0).label("total_miles"),
            func.coalesce(func.sum(Trip.electric_miles), 0).label("electric_miles"),
            func.coalesce(func.sum(Trip.gas_miles), 0).label("gas_miles"),
            func.coalesce(func.sum(Trip.fuel_used_gallons), 0).label("fuel_used"),
        )
        .filter(Trip.is_closed.is_(True))
        .first()
    )

    total_miles = float(trip_stats.total_miles or 0)
    total_electric_miles = float(trip_stats.electric_miles or 0)
    total_gas_miles = float(trip_stats.gas_miles or 0)
    total_fuel_used = float(trip_stats.fuel_used or 0)

    # Calculate EV ratio
    ev_ratio = None
    if total_miles > 0:
        ev_ratio = round((total_electric_miles / total_miles) * 100, 1)

    # Get configured rates
    electricity_rate = Config.ELECTRICITY_COST_PER_KWH
    gas_rate = Config.GAS_COST_PER_GALLON

    if not sessions:
        return jsonify(
            {
                "total_sessions": 0,
                "total_kwh": 0,
                "total_cost": None,
                "estimated_cost": None,
                "avg_kwh_per_session": None,
                "by_charge_type": {},
                "total_electric_miles": round(total_electric_miles, 1) if total_electric_miles else None,
                "ev_ratio": ev_ratio,
                "l1_sessions": 0,
                "l2_sessions": 0,
                "cost_per_mile_electric": None,
                "cost_per_mile_gas": None,
                "electricity_rate": electricity_rate,
                "gas_rate": gas_rate,
            }
        )

    total_kwh = sum(s.kwh_added or 0 for s in sessions)
    # Sum explicit costs
    explicit_cost = sum(s.cost or 0 for s in sessions if s.cost)
    # Estimate cost for sessions without explicit cost
    estimated_cost = total_kwh * electricity_rate
    # Use explicit if available, otherwise estimated
    total_cost = explicit_cost if explicit_cost > 0 else estimated_cost

    # Calculate cost per mile (electric)
    cost_per_mile_electric = None
    if total_electric_miles > 0 and total_kwh > 0:
        cost_per_mile_electric = round((total_kwh * electricity_rate) / total_electric_miles, 3)

    # Calculate cost per mile (gas)
    cost_per_mile_gas = None
    if total_gas_miles > 0 and total_fuel_used > 0:
        cost_per_mile_gas = round((total_fuel_used * gas_rate) / total_gas_miles, 3)

    # Group by charge type and count L1/L2
    by_type = {}
    l1_count = 0
    l2_count = 0
    for s in sessions:
        ctype = s.charge_type or "Unknown"
        if ctype not in by_type:
            by_type[ctype] = {"count": 0, "kwh": 0}
        by_type[ctype]["count"] += 1
        by_type[ctype]["kwh"] += s.kwh_added or 0

        if ctype == "L1":
            l1_count += 1
        elif ctype == "L2":
            l2_count += 1

    # Calculate monthly stats (last 30 days)
    # Use naive datetime for comparison since database stores naive datetimes
    month_ago = utc_now() - timedelta(days=30)
    monthly_sessions = [s for s in sessions if s.start_time and s.start_time.replace(tzinfo=None) >= month_ago]
    monthly_kwh = sum(s.kwh_added or 0 for s in monthly_sessions)
    monthly_cost = monthly_kwh * electricity_rate

    return jsonify(
        {
            "total_sessions": len(sessions),
            "total_kwh": round(total_kwh, 2),
            "total_cost": round(total_cost, 2) if total_cost else None,
            "estimated_cost": round(estimated_cost, 2),
            "has_explicit_costs": explicit_cost > 0,
            "avg_kwh_per_session": round(total_kwh / len(sessions), 2) if sessions else None,
            "by_charge_type": by_type,
            "total_electric_miles": round(total_electric_miles, 1) if total_electric_miles else None,
            "total_gas_miles": round(total_gas_miles, 1) if total_gas_miles else None,
            "ev_ratio": ev_ratio,
            "l1_sessions": l1_count,
            "l2_sessions": l2_count,
            "cost_per_mile_electric": cost_per_mile_electric,
            "cost_per_mile_gas": cost_per_mile_gas,
            "electricity_rate": electricity_rate,
            "gas_rate": gas_rate,
            "monthly_kwh": round(monthly_kwh, 2),
            "monthly_cost": round(monthly_cost, 2),
            "monthly_sessions": len(monthly_sessions),
        }
    )
