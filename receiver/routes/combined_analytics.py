"""
Combined Analytics Routes - Multi-Factor Efficiency Analysis

Endpoints for comprehensive efficiency analysis combining weather,
elevation, and other factors.
"""

import logging
from datetime import datetime

from database import get_db
from flask import Blueprint, jsonify, request
from services import combined_analytics_service

logger = logging.getLogger(__name__)

combined_analytics_bp = Blueprint("combined_analytics", __name__)


def _parse_date(date_str: str) -> datetime | None:
    """Parse ISO date string to datetime."""
    if not date_str:
        return None
    try:
        return datetime.fromisoformat(date_str.replace("Z", "+00:00"))
    except (ValueError, AttributeError):
        return None


@combined_analytics_bp.route("/api/analytics/efficiency/multi-factor", methods=["GET"])
def get_multi_factor_analysis():
    """
    Get comprehensive multi-factor efficiency analysis.

    Combines weather, elevation, and other factors to show
    their individual and combined impacts.

    Query params:
        start_date: ISO date string (optional)
        end_date: ISO date string (optional)

    Returns:
        JSON with factor impacts and recommendations
    """
    try:
        db = get_db()
        start_date = _parse_date(request.args.get("start_date"))
        end_date = _parse_date(request.args.get("end_date"))

        result = combined_analytics_service.get_multi_factor_analysis(
            db, start_date=start_date, end_date=end_date
        )
        return jsonify(result), 200

    except Exception as e:
        logger.error(f"Error getting multi-factor analysis: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500


@combined_analytics_bp.route("/api/analytics/efficiency/predictions", methods=["GET"])
def get_efficiency_predictions():
    """
    Predict efficiency for given conditions.

    Query params:
        temperature_f: Expected temperature in Fahrenheit (optional)
        elevation_change_m: Expected net elevation change in meters (optional)
        is_raining: Whether precipitation is expected (optional, default false)

    Returns:
        JSON with predicted efficiency and adjustments
    """
    try:
        db = get_db()

        # Parse parameters
        temp_str = request.args.get("temperature_f")
        temperature_f = float(temp_str) if temp_str else None

        elev_str = request.args.get("elevation_change_m")
        elevation_change_m = float(elev_str) if elev_str else None

        is_raining = request.args.get("is_raining", "false").lower() == "true"

        result = combined_analytics_service.get_efficiency_predictions(
            db,
            temperature_f=temperature_f,
            elevation_change_m=elevation_change_m,
            is_raining=is_raining,
        )
        return jsonify(result), 200

    except ValueError as e:
        return jsonify({"error": f"Invalid parameter: {e}"}), 400
    except Exception as e:
        logger.error(f"Error getting predictions: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500


@combined_analytics_bp.route("/api/analytics/efficiency/time-series", methods=["GET"])
def get_efficiency_time_series():
    """
    Get efficiency data formatted for time series charts.

    Query params:
        days: Number of days to include (default 90)
        group_by: Grouping period - "day", "week", or "month" (default "week")

    Returns:
        JSON with time series data
    """
    try:
        db = get_db()

        days_str = request.args.get("days", "90")
        try:
            days = int(days_str)
            days = max(7, min(365, days))  # Clamp between 7 and 365
        except ValueError:
            days = 90

        group_by = request.args.get("group_by", "week")
        if group_by not in ("day", "week", "month"):
            group_by = "week"

        result = combined_analytics_service.get_efficiency_time_series(
            db, days=days, group_by=group_by
        )
        return jsonify(result), 200

    except Exception as e:
        logger.error(f"Error getting time series: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500


@combined_analytics_bp.route("/api/analytics/efficiency/optimal-conditions", methods=["GET"])
def get_optimal_conditions():
    """
    Get the best combined conditions for efficiency.

    Returns:
        JSON with optimal driving conditions
    """
    try:
        db = get_db()
        result = combined_analytics_service.get_best_driving_conditions_combined(db)
        return jsonify(result), 200

    except Exception as e:
        logger.error(f"Error getting optimal conditions: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500
