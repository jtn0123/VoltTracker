"""
Weather Analytics Routes - Weather-Efficiency Correlation Analysis

Endpoints for analyzing the relationship between weather conditions
and trip efficiency.
"""

import logging
from datetime import datetime

from database import get_db
from flask import Blueprint, jsonify, request
from services import weather_analytics_service

logger = logging.getLogger(__name__)

weather_analytics_bp = Blueprint("weather_analytics", __name__)


def _parse_date(date_str: str) -> datetime | None:
    """Parse ISO date string to datetime."""
    if not date_str:
        return None
    try:
        return datetime.fromisoformat(date_str.replace("Z", "+00:00"))
    except (ValueError, AttributeError):
        return None


@weather_analytics_bp.route("/api/analytics/weather/efficiency-correlation", methods=["GET"])
def get_efficiency_correlation():
    """
    Get overall weather-efficiency correlation statistics.

    Query params:
        start_date: ISO date string (optional)
        end_date: ISO date string (optional)

    Returns:
        JSON with correlation analysis summary
    """
    try:
        db = get_db()
        start_date = _parse_date(request.args.get("start_date"))
        end_date = _parse_date(request.args.get("end_date"))

        result = weather_analytics_service.get_weather_efficiency_correlation(
            db, start_date=start_date, end_date=end_date
        )
        return jsonify(result), 200

    except Exception as e:
        logger.error(f"Error getting weather correlation: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500


@weather_analytics_bp.route("/api/analytics/weather/temperature-bands", methods=["GET"])
def get_temperature_bands():
    """
    Get efficiency statistics grouped by temperature bands.

    Temperature bands:
        - freezing: <32°F
        - cold: 32-45°F
        - cool: 45-55°F
        - ideal: 55-75°F
        - warm: 75-85°F
        - hot: 85-95°F
        - very_hot: >95°F

    Query params:
        start_date: ISO date string (optional)
        end_date: ISO date string (optional)

    Returns:
        JSON with efficiency by temperature band
    """
    try:
        db = get_db()
        start_date = _parse_date(request.args.get("start_date"))
        end_date = _parse_date(request.args.get("end_date"))

        result = weather_analytics_service.get_efficiency_by_temperature_bands(
            db, start_date=start_date, end_date=end_date
        )
        return jsonify(result), 200

    except Exception as e:
        logger.error(f"Error getting temperature band analysis: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500


@weather_analytics_bp.route("/api/analytics/weather/precipitation-impact", methods=["GET"])
def get_precipitation_impact():
    """
    Get efficiency comparison for rain vs dry conditions.

    Precipitation categories:
        - dry: No precipitation
        - light_rain: ≤0.1"
        - moderate_rain: 0.1-0.25"
        - heavy_rain: >0.25"

    Query params:
        start_date: ISO date string (optional)
        end_date: ISO date string (optional)

    Returns:
        JSON with precipitation impact analysis
    """
    try:
        db = get_db()
        start_date = _parse_date(request.args.get("start_date"))
        end_date = _parse_date(request.args.get("end_date"))

        result = weather_analytics_service.get_efficiency_by_precipitation(
            db, start_date=start_date, end_date=end_date
        )
        return jsonify(result), 200

    except Exception as e:
        logger.error(f"Error getting precipitation impact: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500


@weather_analytics_bp.route("/api/analytics/weather/wind-impact", methods=["GET"])
def get_wind_impact():
    """
    Get efficiency statistics grouped by wind speed bands.

    Wind bands:
        - calm: <5 mph
        - light: 5-15 mph
        - moderate: 15-25 mph
        - strong: >25 mph

    Query params:
        start_date: ISO date string (optional)
        end_date: ISO date string (optional)

    Returns:
        JSON with wind impact analysis
    """
    try:
        db = get_db()
        start_date = _parse_date(request.args.get("start_date"))
        end_date = _parse_date(request.args.get("end_date"))

        result = weather_analytics_service.get_efficiency_by_wind(db, start_date=start_date, end_date=end_date)
        return jsonify(result), 200

    except Exception as e:
        logger.error(f"Error getting wind impact: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500


@weather_analytics_bp.route("/api/analytics/weather/seasonal-trends", methods=["GET"])
def get_seasonal_trends():
    """
    Get efficiency trends by month/season over time.

    Query params:
        months: Number of months to look back (default: 24)

    Returns:
        JSON with monthly trends and seasonal averages
    """
    try:
        db = get_db()
        months_back = request.args.get("months", default=24, type=int)

        # Limit to reasonable range
        months_back = max(1, min(months_back, 60))

        result = weather_analytics_service.get_seasonal_trends(db, months_back=months_back)
        return jsonify(result), 200

    except Exception as e:
        logger.error(f"Error getting seasonal trends: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500


@weather_analytics_bp.route("/api/analytics/weather/best-conditions", methods=["GET"])
def get_best_conditions():
    """
    Identify optimal driving conditions based on historical data.

    Analyzes the most efficient trips to determine ideal weather conditions.

    Returns:
        JSON with optimal condition recommendations
    """
    try:
        db = get_db()
        result = weather_analytics_service.get_best_driving_conditions(db)
        return jsonify(result), 200

    except Exception as e:
        logger.error(f"Error getting best conditions: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500
