"""
Statistics and analytics routes for dashboard.

Provides enhanced statistics with:
- Quick date range filters (7/30/90 days)
- Trend indicators (vs previous period)
- Confidence intervals
- Unit conversion support
"""

import logging
import statistics as stats_module
from datetime import timedelta
from flask import Blueprint, jsonify, request
from database import get_db
from models import Trip, TelemetryRaw, ChargingSession
from sqlalchemy import func, and_
from utils.time_utils import utc_now, parse_date_shortcut, days_ago
from utils.cache_utils import cache_result

logger = logging.getLogger(__name__)

statistics_bp = Blueprint("statistics", __name__)


def calculate_confidence_interval(values, confidence=0.95):
    """
    Calculate confidence interval for a list of values.

    Args:
        values: List of numeric values
        confidence: Confidence level (default 0.95 for 95% CI)

    Returns:
        Dict with mean, ci_lower, ci_upper, margin, sample_size
    """
    if not values or len(values) < 2:
        return None

    mean = stats_module.mean(values)
    stdev = stats_module.stdev(values)
    n = len(values)

    # Use t-distribution for small samples, z for large
    if n < 30:
        # Simplified: use 2.0 as approximation for t-critical
        # In production, use scipy.stats.t.ppf((1 + confidence) / 2, n - 1)
        t_critical = 2.0
    else:
        # Z-critical for 95% CI
        t_critical = 1.96

    margin = t_critical * (stdev / (n ** 0.5))

    return {
        "mean": round(mean, 2),
        "ci_lower": round(mean - margin, 2),
        "ci_upper": round(mean + margin, 2),
        "margin": round(margin, 2),
        "sample_size": n,
        "std_dev": round(stdev, 2)
    }


def calculate_trend_vs_previous(current_value, previous_value):
    """
    Calculate trend indicator vs previous period.

    Returns:
        Dict with change_value, change_percent, direction, is_improving
    """
    # Use epsilon comparison for floating point safety
    EPSILON = 1e-9
    if previous_value is None or abs(previous_value) < EPSILON:
        return {
            "change_value": None,
            "change_percent": None,
            "direction": "neutral",
            "is_improving": None
        }

    change_value = current_value - previous_value
    change_percent = (change_value / previous_value) * 100

    # Determine direction
    if abs(change_percent) < 1:
        direction = "stable"
    elif change_value > 0:
        direction = "up"
    else:
        direction = "down"

    # "Improving" depends on metric (higher MPG = good, higher kWh/mile = bad)
    # This is determined at call site

    return {
        "change_value": round(change_value, 2),
        "change_percent": round(change_percent, 1),
        "direction": direction
    }


@statistics_bp.route("/stats/quick/<timeframe>", methods=["GET"])
@cache_result("stats:quick", ttl=300, tags=["statistics", "trips"])
def get_quick_stats(timeframe):
    """
    Get quick statistics for common timeframes.

    Path params:
        timeframe: "7d", "30d", "90d", "this_month", "last_month", "this_year"

    Query params:
        include_trend: If true, compare with previous period (default: true)
        units: "imperial" or "metric" (default: imperial)

    Returns:
        JSON with statistics and optional trend indicators
    """
    db = get_db()

    # Parse timeframe
    timeframe_map = {
        "7d": "last_7_days",
        "30d": "last_30_days",
        "90d": "last_90_days",
        "this_month": "this_month",
        "last_month": "last_month"
    }

    shortcut = timeframe_map.get(timeframe, "last_30_days")
    date_range = parse_date_shortcut(shortcut)

    if not date_range:
        return jsonify({"error": f"Invalid timeframe: {timeframe}"}), 400

    start_date, end_date = date_range
    include_trend = request.args.get("include_trend", "true").lower() == "true"
    units = request.args.get("units", "imperial").lower()

    # Get current period trips
    current_trips = db.query(Trip).filter(
        and_(
            Trip.is_closed.is_(True),
            Trip.deleted_at.is_(None),
            Trip.start_time >= start_date,
            Trip.start_time <= end_date
        )
    ).all()

    # Calculate current stats
    current_stats = calculate_period_stats(current_trips, units)

    result = {
        "timeframe": timeframe,
        "start_date": start_date.isoformat(),
        "end_date": end_date.isoformat(),
        "stats": current_stats,
        "units": units
    }

    # Add trend comparison if requested
    if include_trend:
        # Calculate previous period (same duration, shifted back)
        period_duration = end_date - start_date
        prev_start = start_date - period_duration
        prev_end = start_date

        previous_trips = db.query(Trip).filter(
            and_(
                Trip.is_closed.is_(True),
                Trip.deleted_at.is_(None),
                Trip.start_time >= prev_start,
                Trip.start_time < prev_end
            )
        ).all()

        prev_stats = calculate_period_stats(previous_trips, units)

        # Calculate trends
        trends = calculate_trends(current_stats, prev_stats)
        result["trends"] = trends
        result["previous_period"] = {
            "start_date": prev_start.isoformat(),
            "end_date": prev_end.isoformat(),
            "stats": prev_stats
        }

    return jsonify(result), 200


@statistics_bp.route("/stats/detailed", methods=["GET"])
def get_detailed_stats():
    """
    Get detailed statistics with confidence intervals.

    Query params:
        date_range: Date shortcut or custom
        start_date, end_date: Custom date range
        metric: Specific metric to analyze ("mpg", "kwh_per_mile", "distance")
        include_ci: Include confidence intervals (default: true)
        units: "imperial" or "metric"

    Returns:
        Detailed statistics with confidence intervals
    """
    db = get_db()

    # Parse date range
    date_range_shortcut = request.args.get("date_range")
    if date_range_shortcut:
        date_range = parse_date_shortcut(date_range_shortcut)
        if not date_range:
            return jsonify({"error": "Invalid date_range"}), 400
        start_date, end_date = date_range
    else:
        from utils.time_utils import parse_query_date_range
        start_date, end_date = parse_query_date_range(request.args, default_days=30)

    metric = request.args.get("metric", "all")
    include_ci = request.args.get("include_ci", "true").lower() == "true"
    units = request.args.get("units", "imperial").lower()

    # Get trips
    trips = db.query(Trip).filter(
        and_(
            Trip.is_closed.is_(True),
            Trip.deleted_at.is_(None),
            Trip.start_time >= start_date,
            Trip.start_time <= end_date
        )
    ).all()

    if not trips:
        return jsonify({
            "message": "No trips found in date range",
            "trip_count": 0
        }), 200

    # Calculate detailed stats with confidence intervals
    result = {
        "date_range": {
            "start": start_date.isoformat(),
            "end": end_date.isoformat()
        },
        "trip_count": len(trips),
        "units": units
    }

    # MPG analysis
    if metric in ["mpg", "all"]:
        gas_trips = [t for t in trips if t.gas_mode_entered and t.gas_mpg]
        if gas_trips:
            mpg_values = [t.gas_mpg for t in gas_trips]
            mpg_ci = calculate_confidence_interval(mpg_values) if include_ci else None

            result["mpg_analysis"] = {
                "trip_count": len(gas_trips),
                "mean": round(stats_module.mean(mpg_values), 2),
                "median": round(stats_module.median(mpg_values), 2),
                "min": round(min(mpg_values), 2),
                "max": round(max(mpg_values), 2),
                "confidence_interval": mpg_ci
            }

    # kWh/mile analysis
    if metric in ["kwh_per_mile", "all"]:
        ev_trips = [t for t in trips if t.kwh_per_mile and t.kwh_per_mile > 0]
        if ev_trips:
            kwh_values = [t.kwh_per_mile for t in ev_trips]
            kwh_ci = calculate_confidence_interval(kwh_values) if include_ci else None

            result["kwh_per_mile_analysis"] = {
                "trip_count": len(ev_trips),
                "mean": round(stats_module.mean(kwh_values), 3),
                "median": round(stats_module.median(kwh_values), 3),
                "min": round(min(kwh_values), 3),
                "max": round(max(kwh_values), 3),
                "confidence_interval": kwh_ci
            }

    # Distance analysis
    if metric in ["distance", "all"]:
        distance_values = [t.distance_miles for t in trips if t.distance_miles]
        if distance_values:
            distance_ci = calculate_confidence_interval(distance_values) if include_ci else None

            # Convert to metric if needed
            if units == "metric":
                distance_values = [d * 1.60934 for d in distance_values]
                unit_label = "km"
            else:
                unit_label = "miles"

            result["distance_analysis"] = {
                "trip_count": len(distance_values),
                "total": round(sum(distance_values), 2),
                "mean": round(stats_module.mean(distance_values), 2),
                "median": round(stats_module.median(distance_values), 2),
                "confidence_interval": distance_ci,
                "unit": unit_label
            }

    return jsonify(result), 200


def calculate_period_stats(trips, units="imperial"):
    """Calculate aggregate statistics for a period."""
    if not trips:
        return {
            "trip_count": 0,
            "total_distance": 0,
            "avg_mpg": None,
            "avg_kwh_per_mile": None,
            "electric_miles": 0,
            "gas_miles": 0,
            "ev_percent": 0
        }

    total_distance = sum(t.distance_miles or 0 for t in trips)
    electric_miles = sum(t.electric_miles or 0 for t in trips)
    gas_miles = sum(t.gas_miles or 0 for t in trips)

    # MPG (gas trips only)
    gas_trips = [t for t in trips if t.gas_mode_entered and t.gas_mpg]
    avg_mpg = round(stats_module.mean([t.gas_mpg for t in gas_trips]), 2) if gas_trips else None

    # kWh/mile (EV trips only)
    ev_trips = [t for t in trips if t.kwh_per_mile and t.kwh_per_mile > 0]
    avg_kwh = round(stats_module.mean([t.kwh_per_mile for t in ev_trips]), 3) if ev_trips else None

    # EV percentage
    ev_percent = round((electric_miles / total_distance * 100), 1) if total_distance > 0 else 0

    # Convert to metric if needed
    if units == "metric":
        total_distance = round(total_distance * 1.60934, 2)
        electric_miles = round(electric_miles * 1.60934, 2)
        gas_miles = round(gas_miles * 1.60934, 2)
        distance_unit = "km"
    else:
        distance_unit = "miles"

    return {
        "trip_count": len(trips),
        "total_distance": round(total_distance, 2),
        "distance_unit": distance_unit,
        "avg_mpg": avg_mpg,
        "avg_kwh_per_mile": avg_kwh,
        "electric_miles": round(electric_miles, 2),
        "gas_miles": round(gas_miles, 2),
        "ev_percent": ev_percent,
        "gas_trip_count": len(gas_trips),
        "ev_trip_count": len(ev_trips)
    }


def calculate_trends(current_stats, previous_stats):
    """Calculate trend indicators comparing two periods."""
    trends = {}

    # Distance trend
    if current_stats["total_distance"] and previous_stats["total_distance"]:
        trends["distance"] = calculate_trend_vs_previous(
            current_stats["total_distance"],
            previous_stats["total_distance"]
        )

    # MPG trend (higher is better)
    if current_stats["avg_mpg"] and previous_stats["avg_mpg"]:
        trend = calculate_trend_vs_previous(
            current_stats["avg_mpg"],
            previous_stats["avg_mpg"]
        )
        trend["is_improving"] = trend["direction"] == "up"
        trends["mpg"] = trend

    # kWh/mile trend (lower is better)
    if current_stats["avg_kwh_per_mile"] and previous_stats["avg_kwh_per_mile"]:
        trend = calculate_trend_vs_previous(
            current_stats["avg_kwh_per_mile"],
            previous_stats["avg_kwh_per_mile"]
        )
        trend["is_improving"] = trend["direction"] == "down"
        trends["kwh_per_mile"] = trend

    # EV percentage trend (higher is better)
    if "ev_percent" in current_stats and "ev_percent" in previous_stats:
        trend = calculate_trend_vs_previous(
            current_stats["ev_percent"],
            previous_stats["ev_percent"]
        )
        trend["is_improving"] = trend["direction"] == "up"
        trends["ev_percent"] = trend

    return trends
