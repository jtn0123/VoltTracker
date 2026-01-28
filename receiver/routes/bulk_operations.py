"""
Bulk operations routes for VoltTracker.

Provides endpoints for:
- Bulk delete trips
- Bulk update trips
- Bulk export selections
"""

import logging
from flask import Blueprint, jsonify, request
from database import get_db
from models import Trip, TelemetryRaw, FuelEvent
from sqlalchemy import and_
from datetime import datetime, timezone

logger = logging.getLogger(__name__)

bulk_bp = Blueprint("bulk", __name__)


@bulk_bp.route("/bulk/trips/delete", methods=["POST"])
def bulk_delete_trips():
    """
    Bulk delete trips (soft delete).

    Request body (JSON):
        trip_ids: List of trip IDs to delete
        permanent: If true, permanently delete (default: false, soft delete)

    Returns:
        JSON with deletion results
    """
    db = get_db()
    data = request.get_json()

    if not data or "trip_ids" not in data:
        return jsonify({"error": "trip_ids required"}), 400

    trip_ids = data.get("trip_ids", [])
    permanent = data.get("permanent", False)

    if not isinstance(trip_ids, list) or len(trip_ids) == 0:
        return jsonify({"error": "trip_ids must be a non-empty list"}), 400

    if len(trip_ids) > 1000:
        return jsonify({"error": "Maximum 1000 trips per batch"}), 400

    try:
        # Fetch trips to delete
        trips = db.query(Trip).filter(
            and_(
                Trip.id.in_(trip_ids),
                Trip.deleted_at.is_(None)  # Only delete non-deleted trips
            )
        ).all()

        if not trips:
            return jsonify({
                "deleted_count": 0,
                "message": "No trips found to delete"
            }), 200

        deleted_count = len(trips)

        if permanent:
            # Permanent delete - remove from database
            # First delete associated data
            session_ids = [t.session_id for t in trips]

            # Delete telemetry
            telemetry_deleted = db.query(TelemetryRaw).filter(
                TelemetryRaw.session_id.in_(session_ids)
            ).delete(synchronize_session=False)

            # Delete trips
            db.query(Trip).filter(Trip.id.in_(trip_ids)).delete(synchronize_session=False)

            db.commit()

            return jsonify({
                "deleted_count": deleted_count,
                "telemetry_deleted": telemetry_deleted,
                "permanent": True,
                "message": f"Permanently deleted {deleted_count} trips"
            }), 200

        else:
            # Soft delete - mark as deleted
            for trip in trips:
                trip.deleted_at = datetime.now(timezone.utc)

            db.commit()

            return jsonify({
                "deleted_count": deleted_count,
                "permanent": False,
                "message": f"Soft deleted {deleted_count} trips (can be restored)"
            }), 200

    except Exception as e:
        db.rollback()
        logger.error(f"Bulk delete failed: {e}", exc_info=True)
        return jsonify({"error": f"Bulk delete failed: {str(e)}"}), 500


@bulk_bp.route("/bulk/trips/restore", methods=["POST"])
def bulk_restore_trips():
    """
    Restore soft-deleted trips.

    Request body (JSON):
        trip_ids: List of trip IDs to restore

    Returns:
        JSON with restore results
    """
    db = get_db()
    data = request.get_json()

    if not data or "trip_ids" not in data:
        return jsonify({"error": "trip_ids required"}), 400

    trip_ids = data.get("trip_ids", [])

    if not isinstance(trip_ids, list) or len(trip_ids) == 0:
        return jsonify({"error": "trip_ids must be a non-empty list"}), 400

    try:
        # Fetch deleted trips
        trips = db.query(Trip).filter(
            and_(
                Trip.id.in_(trip_ids),
                Trip.deleted_at.isnot(None)  # Only restore deleted trips
            )
        ).all()

        if not trips:
            return jsonify({
                "restored_count": 0,
                "message": "No deleted trips found to restore"
            }), 200

        # Restore trips
        for trip in trips:
            trip.deleted_at = None

        db.commit()

        return jsonify({
            "restored_count": len(trips),
            "message": f"Restored {len(trips)} trips"
        }), 200

    except Exception as e:
        db.rollback()
        logger.error(f"Bulk restore failed: {e}", exc_info=True)
        return jsonify({"error": f"Bulk restore failed: {str(e)}"}), 500


@bulk_bp.route("/bulk/trips/update", methods=["POST"])
def bulk_update_trips():
    """
    Bulk update trip properties.

    Request body (JSON):
        trip_ids: List of trip IDs to update
        updates: Dict of fields to update (e.g., {"is_favorite": true})

    Allowed update fields:
        - notes: Text notes
        - tags: Comma-separated tags

    Returns:
        JSON with update results
    """
    db = get_db()
    data = request.get_json()

    if not data or "trip_ids" not in data or "updates" not in data:
        return jsonify({"error": "trip_ids and updates required"}), 400

    trip_ids = data.get("trip_ids", [])
    updates = data.get("updates", {})

    if not isinstance(trip_ids, list) or len(trip_ids) == 0:
        return jsonify({"error": "trip_ids must be a non-empty list"}), 400

    if len(trip_ids) > 1000:
        return jsonify({"error": "Maximum 1000 trips per batch"}), 400

    # Validate allowed fields (prevent updating critical fields)
    # Note: Only fields that exist on the Trip model and are safe to bulk update
    allowed_fields = ["gas_mpg", "gas_miles", "electric_miles", "fuel_used_gallons"]
    invalid_fields = [f for f in updates.keys() if f not in allowed_fields]

    if invalid_fields:
        return jsonify({
            "error": f"Invalid fields: {invalid_fields}",
            "allowed_fields": allowed_fields
        }), 400

    try:
        # Fetch trips to update
        trips = db.query(Trip).filter(
            and_(
                Trip.id.in_(trip_ids),
                Trip.deleted_at.is_(None)
            )
        ).all()

        if not trips:
            return jsonify({
                "updated_count": 0,
                "message": "No trips found to update"
            }), 200

        # Apply updates
        for trip in trips:
            for field, value in updates.items():
                if hasattr(trip, field):
                    setattr(trip, field, value)

        db.commit()

        return jsonify({
            "updated_count": len(trips),
            "updates": updates,
            "message": f"Updated {len(trips)} trips"
        }), 200

    except Exception as e:
        db.rollback()
        logger.error(f"Bulk update failed: {e}", exc_info=True)
        return jsonify({"error": f"Bulk update failed: {str(e)}"}), 500


@bulk_bp.route("/bulk/trips/export", methods=["POST"])
def bulk_export_trips():
    """
    Export selected trips as CSV or JSON.

    Request body (JSON):
        trip_ids: List of trip IDs to export
        format: "csv" or "json" (default: csv)

    Returns:
        CSV or JSON file with selected trips
    """
    from flask import Response
    import csv
    import io

    db = get_db()
    data = request.get_json()

    if not data or "trip_ids" not in data:
        return jsonify({"error": "trip_ids required"}), 400

    trip_ids = data.get("trip_ids", [])
    export_format = data.get("format", "csv").lower()

    if not isinstance(trip_ids, list) or len(trip_ids) == 0:
        return jsonify({"error": "trip_ids must be a non-empty list"}), 400

    if len(trip_ids) > 10000:
        return jsonify({"error": "Maximum 10000 trips per export"}), 400

    try:
        # Fetch selected trips
        trips = db.query(Trip).filter(
            Trip.id.in_(trip_ids),
            Trip.is_closed.is_(True)
        ).order_by(Trip.start_time.desc()).all()

        if not trips:
            return jsonify({
                "error": "No trips found for export"
            }), 404

        if export_format == "json":
            return jsonify([t.to_dict() for t in trips]), 200

        # CSV export
        output = io.StringIO()
        writer = csv.writer(output)

        # Header
        writer.writerow([
            "id", "session_id", "start_time", "end_time",
            "distance_miles", "electric_miles", "gas_miles",
            "start_soc", "gas_mpg", "kwh_per_mile",
            "ambient_temp_avg_f"
        ])

        # Data
        for t in trips:
            writer.writerow([
                t.id,
                str(t.session_id),
                t.start_time.isoformat() if t.start_time else "",
                t.end_time.isoformat() if t.end_time else "",
                t.distance_miles or "",
                t.electric_miles or "",
                t.gas_miles or "",
                t.start_soc or "",
                t.gas_mpg or "",
                t.kwh_per_mile or "",
                t.ambient_temp_avg_f or ""
            ])

        output.seek(0)
        return Response(
            output.getvalue(),
            mimetype="text/csv",
            headers={"Content-Disposition": f"attachment; filename=trips_bulk_export.csv"}
        ), 200

    except Exception as e:
        logger.error(f"Bulk export failed: {e}", exc_info=True)
        return jsonify({"error": f"Bulk export failed: {str(e)}"}), 500


@bulk_bp.route("/bulk/trips/stats", methods=["POST"])
def bulk_trip_stats():
    """
    Get aggregate statistics for selected trips.

    Request body (JSON):
        trip_ids: List of trip IDs

    Returns:
        Aggregate statistics for selected trips
    """
    import statistics as stats_module

    db = get_db()
    data = request.get_json()

    if not data or "trip_ids" not in data:
        return jsonify({"error": "trip_ids required"}), 400

    trip_ids = data.get("trip_ids", [])

    if not isinstance(trip_ids, list) or len(trip_ids) == 0:
        return jsonify({"error": "trip_ids must be a non-empty list"}), 400

    try:
        # Fetch selected trips
        trips = db.query(Trip).filter(
            Trip.id.in_(trip_ids),
            Trip.is_closed.is_(True)
        ).all()

        if not trips:
            return jsonify({
                "error": "No trips found"
            }), 404

        # Calculate statistics
        total_distance = sum(t.distance_miles or 0 for t in trips)
        electric_miles = sum(t.electric_miles or 0 for t in trips)
        gas_miles = sum(t.gas_miles or 0 for t in trips)

        gas_trips = [t for t in trips if t.gas_mode_entered and t.gas_mpg]
        ev_trips = [t for t in trips if t.kwh_per_mile and t.kwh_per_mile > 0]

        result = {
            "trip_count": len(trips),
            "total_distance_miles": round(total_distance, 2),
            "electric_miles": round(electric_miles, 2),
            "gas_miles": round(gas_miles, 2),
            "ev_percent": round((electric_miles / total_distance * 100), 1) if total_distance > 0 else 0,
        }

        # MPG stats
        if gas_trips:
            mpg_values = [t.gas_mpg for t in gas_trips]
            result["gas_stats"] = {
                "trip_count": len(gas_trips),
                "avg_mpg": round(stats_module.mean(mpg_values), 2),
                "median_mpg": round(stats_module.median(mpg_values), 2),
                "min_mpg": round(min(mpg_values), 2),
                "max_mpg": round(max(mpg_values), 2)
            }

        # kWh/mile stats
        if ev_trips:
            kwh_values = [t.kwh_per_mile for t in ev_trips]
            result["ev_stats"] = {
                "trip_count": len(ev_trips),
                "avg_kwh_per_mile": round(stats_module.mean(kwh_values), 3),
                "median_kwh_per_mile": round(stats_module.median(kwh_values), 3),
                "min_kwh_per_mile": round(min(kwh_values), 3),
                "max_kwh_per_mile": round(max(kwh_values), 3)
            }

        return jsonify(result), 200

    except Exception as e:
        logger.error(f"Bulk stats failed: {e}", exc_info=True)
        return jsonify({"error": f"Bulk stats failed: {str(e)}"}), 500
