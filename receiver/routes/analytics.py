"""
Analytics Routes - Web Vitals and Performance Metrics

Endpoints for receiving and logging client-side performance data.
"""

import logging

from flask import Blueprint, jsonify, request

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

        # TODO: Store in database for historical analysis
        # Example:
        # WebVital.create(
        #     name=metric_name,
        #     value=metric_value,
        #     rating=rating,
        #     url=data.get('url'),
        #     user_agent=data.get('userAgent'),
        #     timestamp=data.get('timestamp')
        # )

        return jsonify({"status": "ok", "recorded": metric_name}), 200

    except Exception as e:
        logger.error(f"Error recording web vital: {e}", exc_info=True)
        return jsonify({"error": "Internal server error"}), 500
