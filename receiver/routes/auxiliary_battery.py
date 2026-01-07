"""
12V Auxiliary Battery routes for VoltTracker.

Handles 12V battery health, voltage trends, events, and forecasting endpoints.
"""

import logging
from datetime import datetime, timedelta

from database import get_db
from flask import Blueprint, jsonify, request
from models import AuxBatteryEvent, AuxBatteryHealthReading
from services import auxiliary_battery_service
from sqlalchemy import desc
from utils import utc_now

logger = logging.getLogger(__name__)

auxiliary_battery_bp = Blueprint("auxiliary_battery", __name__)


@auxiliary_battery_bp.route("/battery/auxiliary/health", methods=["GET"])
def get_auxiliary_battery_health():
    """
    Get 12V auxiliary battery health status.

    Returns current voltage, health percentage, status, trends, and recommendations.

    Query params:
        - None

    Returns:
        JSON with health data:
        - current_voltage: Current 12V battery voltage
        - health_status: 'healthy', 'warning', or 'critical'
        - health_percentage: Health estimate (0-100%)
        - voltage_trend: 'improving', 'stable', or 'declining'
        - recommendations: List of action items
    """
    db = get_db()

    try:
        health_data = auxiliary_battery_service.calculate_battery_health(db)
        return jsonify(health_data)
    except Exception as e:
        logger.error(f"Error calculating 12V battery health: {str(e)}", exc_info=True)
        return jsonify({"error": "Failed to calculate battery health", "details": str(e)}), 500


@auxiliary_battery_bp.route("/battery/auxiliary/voltage/history", methods=["GET"])
def get_voltage_history():
    """
    Get historical 12V battery voltage readings.

    Query params:
        - days: Number of days to look back (default: 30, max: 365)
        - at_rest: If 'true', only return readings when not charging/engine off

    Returns:
        JSON with voltage history:
        - readings: List of voltage readings with timestamps
        - statistics: Min/max/avg voltage data
    """
    db = get_db()

    try:
        # Parse query params
        days = int(request.args.get("days", 30))
        days = min(days, 365)  # Cap at 1 year
        at_rest_only = request.args.get("at_rest", "false").lower() == "true"

        # Get voltage history
        if at_rest_only:
            readings = auxiliary_battery_service.get_voltage_at_rest_history(db, days=days)
        else:
            readings = auxiliary_battery_service.get_voltage_history(db, days=days)

        # Calculate statistics
        stats = auxiliary_battery_service.get_voltage_statistics(db, days=days)

        return jsonify(
            {
                "period_days": days,
                "at_rest_only": at_rest_only,
                "readings": [r.to_dict() for r in readings],
                "count": len(readings),
                "statistics": stats,
            }
        )
    except ValueError as e:
        return jsonify({"error": "Invalid query parameter", "details": str(e)}), 400
    except Exception as e:
        logger.error(f"Error fetching voltage history: {str(e)}", exc_info=True)
        return jsonify({"error": "Failed to fetch voltage history", "details": str(e)}), 500


@auxiliary_battery_bp.route("/battery/auxiliary/forecast", methods=["GET"])
def get_replacement_forecast():
    """
    Get 12V battery replacement timing forecast.

    Uses both time-based (battery age) and voltage-based (degradation rate)
    forecasting to predict when replacement will be needed.

    Query params:
        - None

    Returns:
        JSON with forecast data:
        - time_based_forecast: Estimate based on typical AGM lifespan (3-5 years)
        - voltage_based_forecast: Estimate based on voltage degradation rate
        - recommendation: Overall replacement recommendation
        - urgency: 'low', 'medium', or 'high'
    """
    db = get_db()

    try:
        forecast = auxiliary_battery_service.forecast_replacement_timing(db)
        return jsonify(forecast)
    except Exception as e:
        logger.error(f"Error forecasting replacement timing: {str(e)}", exc_info=True)
        return jsonify({"error": "Failed to forecast replacement timing", "details": str(e)}), 500


@auxiliary_battery_bp.route("/battery/auxiliary/events", methods=["GET"])
def get_battery_events():
    """
    Get recent 12V battery events (anomalies, warnings, issues).

    Query params:
        - days: Number of days to look back (default: 7)
        - severity: Filter by severity ('info', 'warning', 'critical', or 'all')
        - unresolved_only: If 'true', only return unresolved events

    Returns:
        JSON with events list:
        - events: List of battery events
        - count: Total number of events
        - unresolved_count: Number of unresolved events
    """
    db = get_db()

    try:
        # Parse query params
        days = int(request.args.get("days", 7))
        severity = request.args.get("severity", "all")
        unresolved_only = request.args.get("unresolved_only", "false").lower() == "true"

        # Get events
        if severity == "all":
            severity = None

        events = auxiliary_battery_service.get_recent_events(db, days=days, severity=severity)

        # Filter unresolved if requested
        if unresolved_only:
            events = [e for e in events if e.resolved_at is None]

        # Count unresolved
        unresolved_count = sum(1 for e in events if e.resolved_at is None)

        return jsonify(
            {
                "period_days": days,
                "severity_filter": severity or "all",
                "unresolved_only": unresolved_only,
                "events": [e.to_dict() for e in events],
                "count": len(events),
                "unresolved_count": unresolved_count,
            }
        )
    except ValueError as e:
        return jsonify({"error": "Invalid query parameter", "details": str(e)}), 400
    except Exception as e:
        logger.error(f"Error fetching battery events: {str(e)}", exc_info=True)
        return jsonify({"error": "Failed to fetch battery events", "details": str(e)}), 500


@auxiliary_battery_bp.route("/battery/auxiliary/events/<int:event_id>/resolve", methods=["POST"])
def resolve_battery_event(event_id):
    """
    Mark a battery event as resolved.

    Path params:
        - event_id: ID of the event to resolve

    Body (JSON):
        - resolution_notes: Optional notes about the resolution

    Returns:
        JSON with updated event data
    """
    db = get_db()

    try:
        # Find event
        event = db.query(AuxBatteryEvent).filter(AuxBatteryEvent.id == event_id).first()

        if not event:
            return jsonify({"error": "Event not found"}), 404

        if event.resolved_at:
            return jsonify({"error": "Event already resolved"}), 400

        # Parse request body
        data = request.get_json() or {}
        resolution_notes = data.get("resolution_notes", "")

        # Mark as resolved
        event.resolved_at = utc_now()
        event.resolution_notes = resolution_notes

        db.commit()
        db.refresh(event)

        logger.info(f"Resolved 12V battery event {event_id}: {resolution_notes}")

        return jsonify(
            {
                "message": "Event resolved successfully",
                "event": event.to_dict(),
            }
        )
    except Exception as e:
        db.rollback()
        logger.error(f"Error resolving battery event: {str(e)}", exc_info=True)
        return jsonify({"error": "Failed to resolve event", "details": str(e)}), 500


@auxiliary_battery_bp.route("/battery/auxiliary/events/log", methods=["POST"])
def log_manual_event():
    """
    Manually log a battery event (for user-reported issues).

    Body (JSON):
        - event_type: Type of event (e.g., 'user_reported', 'low_voltage')
        - severity: 'info', 'warning', or 'critical'
        - description: Description of the event
        - voltage_v: Optional voltage reading
        - timestamp: Optional ISO timestamp (defaults to now)

    Returns:
        JSON with created event data
    """
    db = get_db()

    try:
        data = request.get_json()

        if not data:
            return jsonify({"error": "Request body required"}), 400

        # Required fields
        event_type = data.get("event_type")
        severity = data.get("severity")
        description = data.get("description")

        if not all([event_type, severity, description]):
            return jsonify({"error": "event_type, severity, and description are required"}), 400

        if severity not in ["info", "warning", "critical"]:
            return jsonify({"error": "severity must be 'info', 'warning', or 'critical'"}), 400

        # Optional fields
        voltage_v = data.get("voltage_v")
        timestamp_str = data.get("timestamp")

        if timestamp_str:
            try:
                timestamp = datetime.fromisoformat(timestamp_str.replace("Z", "+00:00"))
            except ValueError:
                return jsonify({"error": "Invalid timestamp format, use ISO 8601"}), 400
        else:
            timestamp = utc_now()

        # Create event
        event = auxiliary_battery_service.log_battery_event(
            db=db,
            event_type=event_type,
            severity=severity,
            voltage_v=voltage_v or 0.0,
            timestamp=timestamp,
            description=description,
            voltage_change_v=data.get("voltage_change_v"),
            duration_seconds=data.get("duration_seconds"),
            is_charging=data.get("is_charging", False),
            charger_connected=data.get("charger_connected", False),
            engine_running=data.get("engine_running", False),
            ambient_temp_f=data.get("ambient_temp_f"),
            odometer_miles=data.get("odometer_miles"),
        )

        return jsonify(
            {
                "message": "Event logged successfully",
                "event": event.to_dict(),
            }
        ), 201
    except Exception as e:
        db.rollback()
        logger.error(f"Error logging battery event: {str(e)}", exc_info=True)
        return jsonify({"error": "Failed to log event", "details": str(e)}), 500


@auxiliary_battery_bp.route("/battery/auxiliary/latest", methods=["GET"])
def get_latest_reading():
    """
    Get the most recent 12V battery voltage reading.

    Query params:
        - None

    Returns:
        JSON with latest reading data
    """
    db = get_db()

    try:
        latest = auxiliary_battery_service.get_latest_voltage_reading(db)

        if not latest:
            return jsonify({"error": "No voltage readings available"}), 404

        return jsonify(
            {
                "reading": latest.to_dict(),
                "health_status": latest.health_status,
                "health_percentage": latest.health_percentage,
            }
        )
    except Exception as e:
        logger.error(f"Error fetching latest reading: {str(e)}", exc_info=True)
        return jsonify({"error": "Failed to fetch latest reading", "details": str(e)}), 500


@auxiliary_battery_bp.route("/battery/auxiliary/statistics", methods=["GET"])
def get_voltage_stats():
    """
    Get voltage statistics for a specified period.

    Query params:
        - days: Number of days to analyze (default: 30)

    Returns:
        JSON with voltage statistics:
        - all_voltages: Stats for all readings
        - rest_voltages: Stats for at-rest readings only
        - total_readings: Count of all readings
        - rest_readings: Count of at-rest readings
    """
    db = get_db()

    try:
        days = int(request.args.get("days", 30))
        days = min(days, 365)  # Cap at 1 year

        stats = auxiliary_battery_service.get_voltage_statistics(db, days=days)
        return jsonify(stats)
    except ValueError as e:
        return jsonify({"error": "Invalid query parameter", "details": str(e)}), 400
    except Exception as e:
        logger.error(f"Error calculating statistics: {str(e)}", exc_info=True)
        return jsonify({"error": "Failed to calculate statistics", "details": str(e)}), 500
