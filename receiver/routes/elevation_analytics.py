"""
Elevation Analytics Routes - Elevation-Efficiency Correlation Analysis

Endpoints for analyzing the relationship between elevation changes
and trip efficiency.
"""

import logging
from datetime import datetime

from database import get_db
from flask import Blueprint, jsonify, request
from services import elevation_analytics_service

logger = logging.getLogger(__name__)

elevation_analytics_bp = Blueprint("elevation_analytics", __name__)


def _parse_date(date_str: str) -> datetime | None:
    """Parse ISO date string to datetime."""
    if not date_str:
        return None
    try:
        return datetime.fromisoformat(date_str.replace("Z", "+00:00"))
    except (ValueError, AttributeError):
        return None


@elevation_analytics_bp.route("/api/analytics/elevation/efficiency-correlation", methods=["GET"])
def get_efficiency_by_elevation():
    """
    Get efficiency statistics grouped by net elevation change.

    Categories:
        - steep_downhill: <-50m net change
        - moderate_downhill: -50 to -10m net change
        - flat: -10 to +10m net change
        - moderate_uphill: +10 to +50m net change
        - steep_uphill: >+50m net change

    Query params:
        start_date: ISO date string (optional)
        end_date: ISO date string (optional)

    Returns:
        JSON with elevation category analysis
    """
    try:
        db = get_db()
        start_date = _parse_date(request.args.get("start_date"))
        end_date = _parse_date(request.args.get("end_date"))

        result = elevation_analytics_service.get_efficiency_by_elevation_change(
            db, start_date=start_date, end_date=end_date
        )
        return jsonify(result), 200

    except Exception as e:
        logger.error(f"Error getting elevation correlation: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500


@elevation_analytics_bp.route("/api/analytics/elevation/gradient", methods=["GET"])
def get_efficiency_by_gradient():
    """
    Get efficiency statistics by average gradient (meters gained per mile).

    Gradient bands:
        - very_flat: <10m/mi
        - gentle: 10-30m/mi
        - moderate: 30-60m/mi
        - hilly: 60-100m/mi
        - mountainous: >100m/mi

    Query params:
        start_date: ISO date string (optional)
        end_date: ISO date string (optional)

    Returns:
        JSON with gradient analysis
    """
    try:
        db = get_db()
        start_date = _parse_date(request.args.get("start_date"))
        end_date = _parse_date(request.args.get("end_date"))

        result = elevation_analytics_service.get_efficiency_by_gradient(
            db, start_date=start_date, end_date=end_date
        )
        return jsonify(result), 200

    except Exception as e:
        logger.error(f"Error getting gradient analysis: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500


@elevation_analytics_bp.route("/api/analytics/elevation/summary", methods=["GET"])
def get_elevation_summary():
    """
    Get overall elevation statistics summary.

    Returns:
        JSON with elevation data coverage and statistics
    """
    try:
        db = get_db()
        result = elevation_analytics_service.get_elevation_summary(db)
        return jsonify(result), 200

    except Exception as e:
        logger.error(f"Error getting elevation summary: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500


@elevation_analytics_bp.route("/api/analytics/elevation/route-comparison", methods=["GET"])
def get_route_comparison():
    """
    Compare routes by their elevation characteristics.

    Returns routes sorted by elevation gain, showing efficiency correlation.

    Returns:
        JSON with route elevation comparison
    """
    try:
        db = get_db()
        result = elevation_analytics_service.get_route_elevation_comparison(db)
        return jsonify({"routes": result, "count": len(result)}), 200

    except Exception as e:
        logger.error(f"Error getting route comparison: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500


@elevation_analytics_bp.route("/api/analytics/elevation/trip/<int:trip_id>", methods=["GET"])
def get_trip_elevation(trip_id):
    """
    Get detailed elevation data for a specific trip.

    Returns:
        JSON with trip elevation profile
    """
    try:
        db = get_db()
        from models import Trip

        trip = db.query(Trip).filter(Trip.id == trip_id).first()
        if not trip:
            return jsonify({"error": "Trip not found"}), 404

        return jsonify(
            {
                "trip_id": trip.id,
                "elevation": {
                    "start_m": trip.elevation_start_m,
                    "end_m": trip.elevation_end_m,
                    "gain_m": trip.elevation_gain_m,
                    "loss_m": trip.elevation_loss_m,
                    "net_change_m": trip.elevation_net_change_m,
                    "max_m": trip.elevation_max_m,
                    "min_m": trip.elevation_min_m,
                },
                "efficiency": {
                    "kwh_per_mile": trip.kwh_per_mile,
                    "electric_miles": trip.electric_miles,
                },
                "distance_miles": trip.distance_miles,
            }
        ), 200

    except Exception as e:
        logger.error(f"Error getting trip elevation: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500
