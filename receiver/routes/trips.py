"""
Trips routes for VoltTracker.

Handles trip CRUD operations, efficiency statistics, and analysis.
"""

import logging
import statistics
from datetime import timedelta


from config import Config
from database import get_db
from flask import Blueprint, Response, jsonify, request
from models import FuelEvent, SocTransition, TelemetryRaw, Trip
from sqlalchemy import desc, func
from utils import analyze_soc_floor
from utils.query_cache import invalidate_cache_pattern as invalidate_query_cache
from utils.time_utils import utc_now, parse_query_date_range, parse_date_shortcut

_ERR_TRIP_NOT_FOUND = "Trip not found"


def _apply_numeric_filter(query, param_name: str, column, op: str = ">="):
    """Apply a numeric filter from request args. Returns (query, error_response) or (query, None)."""
    value = request.args.get(param_name)
    if not value:
        return query, None
    try:
        num = float(value)
    except (ValueError, TypeError):
        logger.warning("Invalid %s parameter: %s", param_name, value)
        return query, (jsonify({"error": f"Invalid {param_name} value: {value}. Must be a number."}), 400)
    if op == ">=":
        return query.filter(column >= num), None
    return query.filter(column <= num), None


def _apply_date_filters(query):
    """Apply date filters from request args."""
    date_range_shortcut = request.args.get("date_range")
    if date_range_shortcut:
        date_range = parse_date_shortcut(date_range_shortcut)
        if date_range:
            start_dt, end_dt = date_range
            query = query.filter(Trip.start_time >= start_dt, Trip.start_time <= end_dt)
        return query

    start_date = request.args.get("start_date")
    end_date = request.args.get("end_date")
    if start_date or end_date:
        start_dt, end_dt = parse_query_date_range(request.args, default_days=365)
        if start_date:
            query = query.filter(Trip.start_time >= start_dt)
        if end_date:
            query = query.filter(Trip.start_time <= end_dt)
    return query


def _apply_mode_filters(query):
    """Apply gas/EV/weather mode filters from request args."""
    if request.args.get("gas_only", "").lower() == "true":
        query = query.filter(Trip.gas_mode_entered.is_(True))
    if request.args.get("ev_only", "").lower() == "true":
        query = query.filter(Trip.gas_mode_entered.is_(False))
    if request.args.get("extreme_weather", "").lower() == "true":
        query = query.filter(Trip.extreme_weather.is_(True))
    return query


def _apply_all_numeric_filters(query):
    """Apply all numeric range filters. Returns (query, error_response)."""
    filters = [
        ("min_temp", Trip.weather_temp_f, ">="),
        ("max_temp", Trip.weather_temp_f, "<="),
        ("min_efficiency", Trip.kwh_per_mile, ">="),
        ("max_efficiency", Trip.kwh_per_mile, "<="),
        ("min_mpg", Trip.gas_mpg, ">="),
        ("min_distance", Trip.distance_miles, ">="),
        ("max_distance", Trip.distance_miles, "<="),
        ("min_elevation", Trip.elevation_gain_m, ">="),
        ("max_elevation", Trip.elevation_gain_m, "<="),
    ]
    for param_name, column, op in filters:
        query, err = _apply_numeric_filter(query, param_name, column, op)
        if err:
            return query, err
    return query, None


def _trend_direction(recent: float, early: float) -> str:
    """Return trend direction string."""
    if recent > early:
        return "increasing"
    if recent == early:
        return "stable"
    return "decreasing"


logger = logging.getLogger(__name__)

trips_bp = Blueprint("trips", __name__)


@trips_bp.route("/trips", methods=["GET"])
def get_trips() -> Response | tuple[Response, int]:
    """
    Get trip list with summary statistics and advanced filtering.

    Query params:
        # Date filters
        start_date: Filter trips after this date (ISO format or shortcut)
        end_date: Filter trips before this date (ISO format)
        date_range: Date shortcut (today, yesterday, last_7_days, last_30_days, last_90_days)

        # Mode filters
        gas_only: If true, only return trips with gas usage
        ev_only: If true, only return pure EV trips (no gas)

        # Weather filters
        extreme_weather: If true, only return trips with extreme weather
        min_temp: Minimum temperature (°F)
        max_temp: Maximum temperature (°F)

        # Efficiency filters
        min_efficiency: Minimum kWh/mile
        max_efficiency: Maximum kWh/mile
        min_mpg: Minimum gas MPG

        # Distance/Elevation filters
        min_distance: Minimum distance in miles
        max_distance: Maximum distance in miles
        min_elevation: Minimum elevation gain (meters)
        max_elevation: Maximum elevation gain (meters)

        # Location filters (requires GPS data)
        near_lat: Latitude for proximity search
        near_lon: Longitude for proximity search
        radius_miles: Search radius in miles (default 5, max 50)

        # Pagination
        page: Page number (default 1)
        per_page: Items per page (default 50, max 100)

        # Sorting
        sort_by: Field to sort by (start_time, distance_miles, kwh_per_mile, mpg)
        sort_order: asc or desc (default desc)
    """
    db = get_db()

    # Filter for closed trips that aren't soft-deleted
    query = db.query(Trip).filter(Trip.is_closed.is_(True), Trip.deleted_at.is_(None))

    # Apply filters
    query = _apply_date_filters(query)
    query = _apply_mode_filters(query)
    query, err = _apply_all_numeric_filters(query)
    if err:
        return err

    # Filter out trips with 0 or very small distance unless explicitly requested
    include_zero = request.args.get("include_zero", "").lower() == "true"
    if not include_zero:
        query = query.filter((Trip.distance_miles.isnot(None)) & (Trip.distance_miles > Config.MIN_TRIP_MILES))

    # Sorting
    sort_by = request.args.get("sort_by", "start_time")
    sort_order = request.args.get("sort_order", "desc").lower()

    # Map sort fields to Trip model attributes
    sort_fields = {
        "start_time": Trip.start_time,
        "distance_miles": Trip.distance_miles,
        "kwh_per_mile": Trip.kwh_per_mile,
        "gas_mpg": Trip.gas_mpg,
        "elevation_gain_m": Trip.elevation_gain_m,
        "weather_temp_f": Trip.weather_temp_f,
    }

    if sort_by in sort_fields:
        sort_column = sort_fields[sort_by]
        if sort_order == "asc":
            query = query.order_by(sort_column.asc())
        else:
            query = query.order_by(sort_column.desc())
    else:
        # Default sort
        query = query.order_by(desc(Trip.start_time))

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
    trips = query.offset(offset).limit(per_page).all()

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
def get_trip_detail(trip_id: int) -> Response | tuple[Response, int]:
    """Get detailed trip data including telemetry points.

    Query params:
        limit: Max telemetry points to return (configurable via API_TELEMETRY_LIMIT_DEFAULT/MAX)
        offset: Skip first N telemetry points (default 0)
    """
    db = get_db()

    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if not trip:
        return jsonify({"error": _ERR_TRIP_NOT_FOUND}), 404

    # Pagination for telemetry to avoid huge responses
    try:
        raw_limit = int(request.args.get("limit", Config.API_TELEMETRY_LIMIT_DEFAULT))
        limit = min(Config.API_TELEMETRY_LIMIT_MAX, max(1, raw_limit))
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
def delete_trip(trip_id: int) -> Response | tuple[Response, int]:
    """Delete a trip and its associated data.

    Imported trips (from CSV) are soft-deleted and can be restored.
    Real-time trips are permanently deleted.
    """
    db = get_db()

    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if not trip:
        return jsonify({"error": _ERR_TRIP_NOT_FOUND}), 404

    # Soft delete for imported trips (protect CSV imports)
    if trip.is_imported:
        try:
            trip.deleted_at = utc_now()
            db.commit()
            logger.info(f"Soft-deleted imported trip {trip_id}")
            return jsonify({"message": f"Trip {trip_id} archived (can be restored)"})
        except Exception as e:
            db.rollback()
            logger.error(f"Failed to soft-delete trip {trip_id}: {e}", exc_info=True)
            return jsonify({"error": "Failed to archive trip"}), 500

    # Hard delete for real-time trips
    try:
        db.query(SocTransition).filter(SocTransition.trip_id == trip_id).delete()
        db.query(TelemetryRaw).filter(TelemetryRaw.session_id == trip.session_id).delete()
        db.delete(trip)
        db.commit()

        invalidate_query_cache("trip")
        invalidate_query_cache("stats")
        logger.info(f"Deleted trip {trip_id}")
        return jsonify({"message": f"Trip {trip_id} deleted successfully"})
    except Exception as e:
        db.rollback()
        logger.error(f"Failed to delete trip {trip_id}: {e}", exc_info=True)
        return jsonify({"error": "Failed to delete trip"}), 500


@trips_bp.route("/trips/<int:trip_id>/restore", methods=["POST"])
def restore_trip(trip_id: int):
    """Restore a soft-deleted trip."""
    db = get_db()

    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if not trip:
        return jsonify({"error": _ERR_TRIP_NOT_FOUND}), 404

    if not trip.deleted_at:
        return jsonify({"error": "Trip is not deleted"}), 400

    try:
        trip.deleted_at = None
        db.commit()

        logger.info(f"Restored trip {trip_id}")
        return jsonify({"message": f"Trip {trip_id} restored successfully"})
    except Exception as e:
        db.rollback()
        logger.error(f"Failed to restore trip {trip_id}: {e}", exc_info=True)
        return jsonify({"error": "Failed to restore trip"}), 500


_TRIP_ALLOWED_FIELDS = ["gas_mpg", "gas_miles", "electric_miles", "fuel_used_gallons"]
_TRIP_VALIDATION_RULES = {
    "gas_mpg": {"min": 0, "max": 100, "type": (int, float)},
    "gas_miles": {"min": 0, "max": 10000, "type": (int, float)},
    "electric_miles": {"min": 0, "max": 10000, "type": (int, float)},
    "fuel_used_gallons": {"min": 0, "max": 50, "type": (int, float)},
}


def _validate_and_apply_trip_fields(trip, data):
    """Validate and apply allowed fields to a trip. Returns an error response or None."""
    for field in _TRIP_ALLOWED_FIELDS:
        if field not in data:
            continue
        value = data[field]
        if value is None:
            setattr(trip, field, None)
            continue
        rules = _TRIP_VALIDATION_RULES.get(field)
        if rules:
            if not isinstance(value, rules["type"]):
                return jsonify({"error": f"{field} must be a number"}), 400
            if value < rules["min"] or value > rules["max"]:
                return jsonify({"error": f'{field} must be between {rules["min"]} and {rules["max"]}'}), 400
        setattr(trip, field, value)
    return None


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
        return jsonify({"error": _ERR_TRIP_NOT_FOUND}), 404

    data = request.get_json()
    if not data:
        return jsonify({"error": "No data provided"}), 400

    err = _validate_and_apply_trip_fields(trip, data)
    if err:
        return err

    try:
        db.commit()

        invalidate_query_cache("trip")
        invalidate_query_cache("stats")
        logger.info(f"Updated trip {trip_id}: {data}")
        return jsonify(trip.to_dict())
    except Exception as e:
        db.rollback()
        logger.error(f"Failed to update trip {trip_id}: {e}", exc_info=True)
        return jsonify({"error": "Failed to update trip"}), 500


@trips_bp.route("/efficiency/summary", methods=["GET"])
def get_efficiency_summary() -> Response | tuple[Response, int]:
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
def get_soc_analysis() -> Response | tuple[Response, int]:
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
            "direction": _trend_direction(statistics.mean(last_10), statistics.mean(first_10)),
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
    # Support explicit start_date/end_date params (from date picker)
    start_date_param = request.args.get("start_date")
    end_date_param = request.args.get("end_date")

    filters = [
        Trip.gas_mode_entered.is_(True),
        Trip.gas_mpg.isnot(None),
        Trip.deleted_at.is_(None),
        Trip.is_closed.is_(True),
    ]

    if start_date_param:
        from datetime import datetime as dt
        filters.append(Trip.start_time >= dt.fromisoformat(start_date_param))
        if end_date_param:
            end_dt = dt.fromisoformat(
                end_date_param + "T23:59:59" if "T" not in end_date_param else end_date_param
            )
            filters.append(Trip.start_time <= end_dt)
    elif days > 0:
        filters.append(Trip.start_time >= utc_now() - timedelta(days=days))
    # days <= 0 means "all time" — no date filter

    trips = (
        db.query(Trip)
        .filter(*filters)
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


def _trip_to_comparison_dict(trip):
    """Extract comparison metrics from a trip."""
    return {
        "id": trip.id,
        "start_time": trip.start_time.isoformat() if trip.start_time else None,
        "distance_miles": trip.distance_miles,
        "electric_miles": trip.electric_miles,
        "gas_miles": trip.gas_miles,
        "kwh_per_mile": trip.kwh_per_mile,
        "gas_mpg": trip.gas_mpg,
        "weather_temp_f": trip.weather_temp_f,
        "weather_conditions": trip.weather_conditions,
        "extreme_weather": trip.extreme_weather,
        "elevation_gain_m": trip.elevation_gain_m,
        "gas_mode_entered": trip.gas_mode_entered,
        "weather_impact_factor": trip.weather_impact_factor,
    }


def _safe_stats(values, decimals):
    """Calculate min/max/avg/total for a list of values, rounding to decimals."""
    if not values:
        return {"min": None, "max": None, "avg": None, "total": None}
    return {
        "min": round(min(values), decimals),
        "max": round(max(values), decimals),
        "avg": round(statistics.mean(values), decimals),
        "total": round(sum(values), decimals),
    }


def _calculate_comparison_statistics(trips):
    """Calculate aggregate statistics for trip comparison."""
    distances = [t.distance_miles for t in trips if t.distance_miles is not None]
    efficiencies = [t.kwh_per_mile for t in trips if t.kwh_per_mile is not None]
    temps = [t.weather_temp_f for t in trips if t.weather_temp_f is not None]
    elevations = [t.elevation_gain_m for t in trips if t.elevation_gain_m is not None]

    dist_stats = _safe_stats(distances, 1)
    eff_stats = _safe_stats(efficiencies, 3)
    elev_stats = _safe_stats(elevations, 0)

    return {
        "distance": dist_stats,
        "efficiency": {
            "best": eff_stats["min"],
            "worst": eff_stats["max"],
            "avg": eff_stats["avg"],
            "variance": round(statistics.variance(efficiencies), 3) if len(efficiencies) > 1 else None,
        },
        "weather": {
            "coldest": round(min(temps), 1) if temps else None,
            "warmest": round(max(temps), 1) if temps else None,
            "avg_temp": round(statistics.mean(temps), 1) if temps else None,
            "extreme_weather_count": sum(1 for t in trips if t.extreme_weather),
        },
        "elevation": {
            "min_gain": elev_stats["min"],
            "max_gain": elev_stats["max"],
            "avg_gain": elev_stats["avg"],
            "total_gain": elev_stats["total"],
        },
        "modes": {
            "ev_only": sum(1 for t in trips if not t.gas_mode_entered),
            "gas_used": sum(1 for t in trips if t.gas_mode_entered),
        },
    }


def _generate_comparison_insights(trips):
    """Generate insights from trip comparison."""
    insights = []
    efficiencies = [t.kwh_per_mile for t in trips if t.kwh_per_mile is not None]
    temps = [t.weather_temp_f for t in trips if t.weather_temp_f is not None]

    if len(efficiencies) > 1 and statistics.variance(efficiencies) > 0.01:
        insights.append("High efficiency variance detected - consider analyzing conditions causing differences")

    if len(temps) > 1:
        temp_range = max(temps) - min(temps)
        if temp_range > 30:
            insights.append(f"Wide temperature range ({temp_range:.0f}\u00b0F) - expect efficiency variations")

    extreme_count = sum(1 for t in trips if t.extreme_weather)
    if extreme_count > 0:
        insights.append(f"{extreme_count}/{len(trips)} trips had extreme weather conditions")

    return insights


@trips_bp.route("/trips/compare", methods=["POST"])
def compare_trips():
    """
    Compare multiple trips side-by-side.

    Request body:
        {
            "trip_ids": [1, 2, 3, ...],  # List of trip IDs to compare (max 10)
            "metrics": ["efficiency", "weather", "elevation"]  # Optional: specific metrics
        }

    Returns:
        Side-by-side comparison with statistical analysis
    """
    db = get_db()

    try:
        data = request.get_json()
        if not data or "trip_ids" not in data:
            return jsonify({"error": "trip_ids required in request body"}), 400

        trip_ids = data.get("trip_ids", [])
        if not trip_ids:
            return jsonify({"error": "At least one trip_id required"}), 400

        if len(trip_ids) > 10:
            return jsonify({"error": "Maximum 10 trips can be compared at once"}), 400

        trips = db.query(Trip).filter(Trip.id.in_(trip_ids), Trip.is_closed.is_(True), Trip.deleted_at.is_(None)).all()

        if not trips:
            return jsonify({"error": "No trips found with provided IDs"}), 404

        comparison = {
            "trip_count": len(trips),
            "trips": [_trip_to_comparison_dict(t) for t in trips],
            "statistics": _calculate_comparison_statistics(trips),
            "insights": _generate_comparison_insights(trips),
        }

        return jsonify(comparison)

    except Exception as e:
        logger.error(f"Error comparing trips: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500
