"""
Analytics Routes - Web Vitals, Performance Metrics, and Advanced Analytics

Endpoints for receiving and logging client-side performance data,
plus advanced analytics features (powertrain, range prediction, etc.).
"""

import logging

from database import get_db
from flask import Blueprint, jsonify, request
from models import WebVital
from services import (
    battery_degradation_service,
    maintenance_service,
    powertrain_service,
    range_prediction_service,
    route_service,
)

logger = logging.getLogger(__name__)

analytics_bp = Blueprint("analytics", __name__)


@analytics_bp.route("/api/analytics/vitals", methods=["POST", "OPTIONS"])
def record_vitals():
    """
    Record Web Vitals performance metrics from the client.

    Accepts Core Web Vitals (CLS, FID, LCP, INP, FCP, TTFB) and logs them
    for performance monitoring and optimization tracking.

    Returns:
        JSON response with status
    """
    # Handle preflight OPTIONS request
    if request.method == "OPTIONS":
        return "", 204

    try:
        data = request.get_json(silent=True)
        if not data:
            return jsonify({"error": "No data provided"}), 400

        metric_name = data.get("name")
        metric_value = data.get("value")
        rating = data.get("rating", "unknown")
        nav_type = data.get("navigationType", "unknown")

        # Log the metric
        logger.info(
            f"Web Vital - {metric_name}: {metric_value}ms "
            f"(rating: {rating}, navigation: {nav_type}) "
            f"[{data.get('url', 'unknown')}]"
        )

        # Store in database for historical analysis
        db_stored = False
        db = None
        try:
            db = get_db()
            web_vital = WebVital.create_from_frontend(data)
            db.add(web_vital)
            db.commit()
            db_stored = True
            logger.debug(f"Stored Web Vital {metric_name} to database (id={web_vital.id})")
        except Exception as db_error:
            if db is not None:
                try:
                    db.rollback()
                except Exception:
                    pass  # Rollback failed, but we're already handling the original error
            logger.error(f"Failed to store Web Vital in database: {db_error}", exc_info=True)
            # Continue execution - logging is more important than DB storage
            # The metric was already logged, so we don't fail the request

        return jsonify({
            "status": "ok",
            "recorded": metric_name,
            "persisted": db_stored,
            "warning": None if db_stored else "Metric logged but database storage failed"
        }), 200

    except Exception as e:
        logger.error(f"Error recording web vital: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500


# Feature 5: Powertrain Mode Analysis
@analytics_bp.route("/api/analytics/powertrain/<int:trip_id>", methods=["GET"])
def get_powertrain_analysis(trip_id):
    """
    Get powertrain mode analysis for a specific trip.

    Returns timeline of operating modes and statistics.
    """
    try:
        db = get_db()
        from models import Trip

        trip = db.query(Trip).filter(Trip.id == trip_id).first()
        if not trip:
            return jsonify({"error": "Trip not found"}), 404

        analysis = powertrain_service.analyze_trip_powertrain(db, str(trip.session_id))
        return jsonify(analysis), 200

    except Exception as e:
        logger.error(f"Error analyzing powertrain: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500


@analytics_bp.route("/api/analytics/powertrain/summary/<int:trip_id>", methods=["GET"])
def get_powertrain_summary(trip_id):
    """Get simplified powertrain summary for dashboard display."""
    try:
        db = get_db()
        summary = powertrain_service.get_powertrain_summary(db, trip_id)

        if not summary:
            return jsonify({"error": "Trip not found"}), 404

        return jsonify(summary), 200

    except Exception as e:
        logger.error(f"Error getting powertrain summary: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500


# Feature 6: Range Prediction
@analytics_bp.route("/api/analytics/range-prediction", methods=["GET"])
def predict_range():
    """
    Predict electric range based on conditions.

    Query params:
        - temperature: Temperature in °F (default: current)
        - speed: Average speed in MPH (default: recent average)
        - battery_health: Battery health % (default: latest)
    """
    try:
        db = get_db()

        # Get query parameters or use current conditions
        temp = request.args.get("temperature", type=float)
        speed = request.args.get("speed", type=float)
        health = request.args.get("battery_health", type=float)

        # Get current conditions if not specified
        current = range_prediction_service.get_current_conditions(db)

        temperature = temp if temp is not None else current["temperature_f"]
        avg_speed = speed if speed is not None else current["avg_speed_mph"]
        battery_health_pct = health if health is not None else current["battery_health_pct"]
        battery_capacity = current["battery_capacity_kwh"]

        # Predict range
        prediction = range_prediction_service.predict_range_simple(
            db, temperature, battery_health_pct, avg_speed, battery_capacity
        )

        return jsonify(prediction), 200

    except Exception as e:
        logger.error(f"Error predicting range: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500


# Feature 7: Maintenance Tracker
@analytics_bp.route("/api/analytics/maintenance/summary", methods=["GET"])
def get_maintenance_summary():
    """Get maintenance status for all tracked items."""
    try:
        db = get_db()
        summary = maintenance_service.get_maintenance_summary(db)
        return jsonify(summary), 200

    except Exception as e:
        logger.error(f"Error getting maintenance summary: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500


@analytics_bp.route("/api/analytics/maintenance/engine-hours", methods=["GET"])
def get_engine_hours():
    """Get total engine hours calculated from telemetry."""
    try:
        db = get_db()
        engine_hours = maintenance_service.calculate_engine_hours(db)

        return jsonify({"total_engine_hours": round(engine_hours, 1)}), 200

    except Exception as e:
        logger.error(f"Error calculating engine hours: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500


# Feature 8: Route Analysis
@analytics_bp.route("/api/analytics/routes", methods=["GET"])
def get_routes():
    """Get detected common routes."""
    try:
        db = get_db()

        # Optional: Re-detect routes
        if request.args.get("refresh") == "true":
            min_trips = int(request.args.get("min_trips", 3))
            routes = route_service.detect_routes(db, min_trips)
        else:
            routes = route_service.get_route_summary(db)

        return jsonify(routes), 200

    except Exception as e:
        logger.error(f"Error getting routes: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500


# Feature 9: Battery Degradation Forecasting
@analytics_bp.route("/api/analytics/battery/degradation", methods=["GET"])
def get_battery_degradation_forecast():
    """Get battery degradation forecast."""
    try:
        db = get_db()
        forecast = battery_degradation_service.forecast_degradation(db)
        return jsonify(forecast), 200

    except Exception as e:
        logger.error(f"Error forecasting battery degradation: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500
