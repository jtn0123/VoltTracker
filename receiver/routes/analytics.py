"""
Analytics Routes - Web Vitals and Performance Metrics

Endpoints for receiving and logging client-side performance data.
"""

import logging

from database import get_db
from flask import Blueprint, jsonify, request
from models import WebVital

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
        try:
            db = get_db()
            web_vital = WebVital.create_from_frontend(data)
            db.add(web_vital)
            db.commit()

            logger.debug(f"Stored Web Vital {metric_name} to database (id={web_vital.id})")
        except Exception as db_error:
            logger.error(f"Failed to store Web Vital in database: {db_error}", exc_info=True)
            # Continue execution - logging is more important than DB storage
            # The metric was already logged, so we don't fail the request

        return jsonify({"status": "ok", "recorded": metric_name}), 200

    except Exception as e:
        logger.error(f"Error recording web vital: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500
