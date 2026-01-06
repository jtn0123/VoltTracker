"""
Trips routes for VoltTracker.

Handles trip CRUD operations, efficiency statistics, and analysis.
"""

import logging
import statistics
from datetime import timedelta

from config import Config
from database import get_db
from flask import Blueprint, jsonify, request
from models import FuelEvent, SocTransition, TelemetryRaw, Trip
from sqlalchemy import desc, func
from utils import analyze_soc_floor, utc_now

logger = logging.getLogger(__name__)

trips_bp = Blueprint("trips", __name__)


@trips_bp.route("/trips", methods=["GET"])
def get_trips():
    """
    Get trip list with summary statistics.

    Query params:
        start_date: Filter trips after this date (ISO format)
        end_date: Filter trips before this date (ISO format)
        gas_only: If true, only return trips with gas usage
        page: Page number (default 1)
        per_page: Items per page (default 50, max 100)
    """
    db = get_db()

    # Filter for closed trips that aren't soft-deleted
    query = db.query(Trip).filter(Trip.is_closed.is_(True), Trip.deleted_at.is_(None))

    # Apply filters
    start_date = request.args.get("start_date")
    if start_date:
        query = query.filter(Trip.start_time >= start_date)

    end_date = request.args.get("end_date")
    if end_date:
        query = query.filter(Trip.start_time <= end_date)

    gas_only = request.args.get("gas_only", "").lower() == "true"
    if gas_only:
        query = query.filter(Trip.gas_mode_entered.is_(True))

    # Filter out trips with 0 or very small distance (likely GPS errors or no movement)
    # Unless explicitly requested with include_zero=true
    include_zero = request.args.get("include_zero", "").lower() == "true"
    if not include_zero:
        query = query.filter((Trip.distance_miles.isnot(None)) & (Trip.distance_miles > Config.MIN_TRIP_MILES))

    # Pagination
    try:
        page = max(1, int(request.args.get("page", 1)))
    except (ValueError, TypeError):
        page = 1

    try:
        per_page = min(Config.API_MAX_PER_PAGE, max(1, int(request.args.get("per_page", Config.API_DEFAULT_PER_PAGE))))
    except (ValueError, TypeError):
        per_page = Config.API_DEFAULT_PER_PAGE

    # Get total count for pagination info
    total_count = query.count()

    # Apply pagination
    offset = (page - 1) * per_page
    trips = query.order_by(desc(Trip.start_time)).offset(offset).limit(per_page).all()

    # Return consistent paginated response with metadata
    return jsonify(
        {
            "trips": [t.to_dict() for t in trips],
            "pagination": {
                "page": page,
                "per_page": per_page,
                "total": total_count,
                "pages": (total_count + per_page - 1) // per_page if per_page > 0 else 0,
            },
        }
    )


@trips_bp.route("/trips/<int:trip_id>", methods=["GET"])
def get_trip_detail(trip_id):
    """Get detailed trip data including telemetry points.

    Query params:
        limit: Max telemetry points to return (configurable via API_TELEMETRY_LIMIT_DEFAULT/MAX)
        offset: Skip first N telemetry points (default 0)
    """
    db = get_db()

    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if not trip:
        return jsonify({"error": "Trip not found"}), 404

    # Pagination for telemetry to avoid huge responses
    try:
        limit = min(Config.API_TELEMETRY_LIMIT_MAX, max(1, int(request.args.get("limit", Config.API_TELEMETRY_LIMIT_DEFAULT))))
    except (ValueError, TypeError):
        limit = Config.API_TELEMETRY_LIMIT_DEFAULT

    try:
        offset = max(0, int(request.args.get("offset", 0)))
    except (ValueError, TypeError):
        offset = 0

    # Get total count for pagination info
    total_count = db.query(TelemetryRaw).filter(TelemetryRaw.session_id == trip.session_id).count()

    # Get paginated telemetry for this trip
    telemetry = (
        db.query(TelemetryRaw)
        .filter(TelemetryRaw.session_id == trip.session_id)
        .order_by(TelemetryRaw.timestamp)
        .offset(offset)
        .limit(limit)
        .all()
    )

    return jsonify(
        {
            "trip": trip.to_dict(),
            "telemetry": [t.to_dict() for t in telemetry],
            "telemetry_pagination": {
                "offset": offset,
                "limit": limit,
                "total": total_count,
                "has_more": offset + len(telemetry) < total_count,
            },
        }
    )


@trips_bp.route("/trips/<int:trip_id>", methods=["DELETE"])
def delete_trip(trip_id: int):
    """Delete a trip and its associated data.

    Imported trips (from CSV) are soft-deleted and can be restored.
    Real-time trips are permanently deleted.
    """
    db = get_db()

    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if not trip:
        return jsonify({"error": "Trip not found"}), 404

    # Soft delete for imported trips (protect CSV imports)
    if trip.is_imported:
        trip.deleted_at = utc_now()
        db.commit()
        logger.info(f"Soft-deleted imported trip {trip_id}")
        return jsonify({"message": f"Trip {trip_id} archived (can be restored)"})

    # Hard delete for real-time trips
    db.query(SocTransition).filter(SocTransition.trip_id == trip_id).delete()
    db.query(TelemetryRaw).filter(TelemetryRaw.session_id == trip.session_id).delete()
    db.delete(trip)
    db.commit()

    logger.info(f"Deleted trip {trip_id}")
    return jsonify({"message": f"Trip {trip_id} deleted successfully"})


@trips_bp.route("/trips/<int:trip_id>/restore", methods=["POST"])
def restore_trip(trip_id: int):
    """Restore a soft-deleted trip."""
    db = get_db()

    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if not trip:
        return jsonify({"error": "Trip not found"}), 404

    if not trip.deleted_at:
        return jsonify({"error": "Trip is not deleted"}), 400

    trip.deleted_at = None
    db.commit()

    logger.info(f"Restored trip {trip_id}")
    return jsonify({"message": f"Trip {trip_id} restored successfully"})


@trips_bp.route("/trips/<int:trip_id>", methods=["PATCH"])
def update_trip(trip_id):
    """
    Update trip fields (for manual corrections).

    Allowed fields:
        - gas_mpg: Override calculated MPG
        - gas_miles: Override gas miles
        - electric_miles: Override electric miles
        - fuel_used_gallons: Override fuel used
    """
    db = get_db()

    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if not trip:
        return jsonify({"error": "Trip not found"}), 404

    data = request.get_json()
    if not data:
        return jsonify({"error": "No data provided"}), 400

    # Only allow specific fields to be updated
    allowed_fields = ["gas_mpg", "gas_miles", "electric_miles", "fuel_used_gallons"]

    # Validation rules for numeric fields (must be non-negative if provided)
    validation_rules = {
        "gas_mpg": {"min": 0, "max": 100, "type": (int, float)},
        "gas_miles": {"min": 0, "max": 10000, "type": (int, float)},
        "electric_miles": {"min": 0, "max": 10000, "type": (int, float)},
        "fuel_used_gallons": {"min": 0, "max": 50, "type": (int, float)},
    }

    for field in allowed_fields:
        if field in data:
            value = data[field]
            # Allow null values to clear the field
            if value is None:
                setattr(trip, field, None)
                continue

            rules = validation_rules.get(field)
            if rules:
                # Type check
                if not isinstance(value, rules["type"]):
                    return jsonify({"error": f"{field} must be a number"}), 400
                # Range check
                if value < rules["min"] or value > rules["max"]:
                    return jsonify({"error": f'{field} must be between {rules["min"]} and {rules["max"]}'}), 400

            setattr(trip, field, value)

    db.commit()

    logger.info(f"Updated trip {trip_id}: {data}")
    return jsonify(trip.to_dict())


@trips_bp.route("/efficiency/summary", methods=["GET"])
def get_efficiency_summary():
    """
    Get efficiency statistics.

    Calculates from available trip data using SQL aggregation for efficiency.

    Returns:
        - Lifetime gas MPG average
        - Last 30 days gas MPG average
        - Current tank MPG (since last fill)
        - Total miles tracked
        - Electric stats (miles, kWh, efficiency)
    """
    db = get_db()

    # Get lifetime totals using SQL aggregation (single query instead of loading all trips)
    # Exclude soft-deleted trips to match /trips endpoint behavior
    lifetime_stats = (
        db.query(
            func.coalesce(func.sum(Trip.distance_miles), 0).label("total_miles"),
            func.coalesce(func.sum(Trip.electric_miles), 0).label("electric_miles"),
            func.coalesce(func.sum(Trip.gas_miles), 0).label("gas_miles"),
            func.coalesce(func.sum(Trip.fuel_used_gallons), 0).label("fuel_used"),
            func.coalesce(func.sum(Trip.electric_kwh_used), 0).label("kwh_used"),
        )
        .filter(Trip.is_closed.is_(True), Trip.deleted_at.is_(None))
        .first()
    )

    total_miles = float(lifetime_stats.total_miles or 0)
    total_electric_miles = float(lifetime_stats.electric_miles or 0)
    total_gas_miles = float(lifetime_stats.gas_miles or 0)
    total_fuel_used = float(lifetime_stats.fuel_used or 0)
    total_kwh_used = float(lifetime_stats.kwh_used or 0)

    # Calculate lifetime gas MPG
    lifetime_mpg = None
    if total_gas_miles > 0 and total_fuel_used > 0:
        lifetime_mpg = round(total_gas_miles / total_fuel_used, 1)

    # Calculate average kWh/mile for electric driving
    avg_kwh_per_mile = None
    mi_per_kwh = None
    if total_electric_miles > 0 and total_kwh_used > 0:
        avg_kwh_per_mile = round(total_kwh_used / total_electric_miles, 3)
        mi_per_kwh = round(total_electric_miles / total_kwh_used, 2)

    # Calculate EV ratio
    ev_ratio = None
    if total_miles > 0:
        ev_ratio = round(total_electric_miles / total_miles * 100, 1)

    # Last 30 days stats using SQL aggregation
    thirty_days_ago = utc_now() - timedelta(days=30)
    recent_stats = (
        db.query(
            func.coalesce(func.sum(Trip.gas_miles), 0).label("gas_miles"),
            func.coalesce(func.sum(Trip.fuel_used_gallons), 0).label("fuel_used"),
        )
        .filter(Trip.is_closed.is_(True), Trip.deleted_at.is_(None), Trip.start_time >= thirty_days_ago)
        .first()
    )

    recent_gas_miles = float(recent_stats.gas_miles or 0)
    recent_fuel = float(recent_stats.fuel_used or 0)

    recent_mpg = None
    if recent_gas_miles > 0 and recent_fuel > 0:
        recent_mpg = round(recent_gas_miles / recent_fuel, 1)

    # Current tank (since last refuel) using SQL aggregation
    last_refuel = db.query(FuelEvent).order_by(desc(FuelEvent.timestamp)).first()

    current_tank_mpg = None
    current_tank_miles = None
    if last_refuel:
        tank_stats = (
            db.query(
                func.coalesce(func.sum(Trip.gas_miles), 0).label("gas_miles"),
                func.coalesce(func.sum(Trip.fuel_used_gallons), 0).label("fuel_used"),
            )
            .filter(Trip.is_closed.is_(True), Trip.deleted_at.is_(None), Trip.start_time >= last_refuel.timestamp)
            .first()
        )

        tank_gas_miles = float(tank_stats.gas_miles or 0)
        tank_fuel = float(tank_stats.fuel_used or 0)

        if tank_gas_miles > 0 and tank_fuel > 0:
            current_tank_mpg = round(tank_gas_miles / tank_fuel, 1)
            current_tank_miles = round(tank_gas_miles, 1)

    return jsonify(
        {
            "lifetime_gas_mpg": lifetime_mpg,
            "lifetime_gas_miles": round(total_gas_miles, 1),
            "recent_30d_mpg": recent_mpg,
            "current_tank_mpg": current_tank_mpg,
            "current_tank_miles": current_tank_miles,
            "total_miles_tracked": round(total_miles, 1),
            "total_electric_miles": round(total_electric_miles, 1),
            "total_kwh_used": round(total_kwh_used, 2),
            "avg_kwh_per_mile": avg_kwh_per_mile,
            "mi_per_kwh": mi_per_kwh,
            "ev_ratio": ev_ratio,
        }
    )


@trips_bp.route("/soc/analysis", methods=["GET"])
def get_soc_analysis():
    """
    Get SOC floor analysis.

    Returns:
        - Average SOC at gas transition
        - SOC transition histogram
        - Trend over time
        - Temperature correlation
    """
    db = get_db()

    # Limit to most recent 500 transitions to prevent memory issues
    # This is enough data for meaningful analysis while keeping response fast
    transitions = db.query(SocTransition).order_by(SocTransition.timestamp.desc()).limit(500).all()
    transitions.reverse()  # Put back in chronological order for analysis

    transition_dicts = [t.to_dict() for t in transitions]
    analysis = analyze_soc_floor(transition_dicts)

    # Add recent trends (last 10 vs first 10)
    if len(transition_dicts) >= 20:
        first_10 = [t["soc_at_transition"] for t in transition_dicts[:10]]
        last_10 = [t["soc_at_transition"] for t in transition_dicts[-10:]]

        analysis["trend"] = {
            "early_avg": round(statistics.mean(first_10), 1),
            "recent_avg": round(statistics.mean(last_10), 1),
            "direction": "increasing" if statistics.mean(last_10) > statistics.mean(first_10) else "decreasing",
        }
    else:
        analysis["trend"] = None

    return jsonify(analysis)


@trips_bp.route("/mpg/trend", methods=["GET"])
def get_mpg_trend():
    """
    Get MPG trend data for charting.

    Query params:
        days: Number of days to include (default 30)
    """
    db = get_db()

    try:
        days = int(request.args.get("days", 30))
    except (ValueError, TypeError):
        days = 30
    start_date = utc_now() - timedelta(days=days)

    trips = (
        db.query(Trip)
        .filter(Trip.start_time >= start_date, Trip.gas_mode_entered.is_(True), Trip.gas_mpg.isnot(None))
        .order_by(Trip.start_time)
        .all()
    )

    return jsonify(
        [
            {
                "date": t.start_time.isoformat(),
                "mpg": t.gas_mpg,
                "gas_miles": t.gas_miles,
                "ambient_temp": t.ambient_temp_avg_f,
            }
            for t in trips
        ]
    )
