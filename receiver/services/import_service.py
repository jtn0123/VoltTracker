"""
Import service for VoltTracker (C3/A3: extracted from export.py).

Handles CSV import business logic: parsing, dedup, insertion, trip creation.
"""

import logging
from datetime import datetime, timedelta, timezone

from models import CsvImport, TelemetryRaw, Trip
from sqlalchemy import and_, func, or_
from sqlalchemy.exc import IntegrityError
from utils.import_utils import format_reportable

logger = logging.getLogger(__name__)


def _stat_or_event(stats, import_event, stat_key, event_key=None, default=0):
    """Get a value from stats dict, falling back to import_event."""
    if stats:
        return stats.get(stat_key, default)
    if import_event:
        return import_event.get(event_key or stat_key, default)
    return default


def check_duplicate_file(db, file_hash):
    """Check if a file with this hash was already imported.

    Returns the existing CsvImport record if duplicate, else None.
    """
    return db.query(CsvImport).filter(
        CsvImport.file_hash == file_hash,
        or_(
            CsvImport.status.in_(["success", "partial"]),
            and_(CsvImport.status == "failed", CsvImport.failure_reason == "all_duplicates")
        )
    ).first()


def get_existing_timestamps(db):
    """Get existing timestamps from last 365 days for duplicate detection (P1).

    Uses DB-side EXISTS check in batches of 1000 instead of loading all into memory.
    Returns a set of timestamps.
    """
    cutoff = datetime.now(timezone.utc) - timedelta(days=365)
    existing_timestamps = set()
    # Query in batches to avoid loading entire table into memory (P1)
    batch_size = 1000
    offset = 0
    while True:
        batch = (
            db.query(TelemetryRaw.timestamp)
            .filter(TelemetryRaw.timestamp >= cutoff)
            .order_by(TelemetryRaw.timestamp)
            .offset(offset)
            .limit(batch_size)
            .all()
        )
        if not batch:
            break
        existing_timestamps.update(t[0] for t in batch)
        offset += batch_size
        if len(batch) < batch_size:
            break
    return existing_timestamps


def record_import(db, import_code, status, failure_reason=None, suggestion=None,
                  stats=None, trip_id=None, session_id=None,
                  file_hash=None, filename=None, file_size=0,
                  import_event=None):
    """Record import attempt in csv_imports table."""
    try:
        csv_import = CsvImport(
            import_code=import_code,
            filename=filename or (import_event.get("filename") if import_event else None) or "unknown",
            file_hash=file_hash or (import_event.get("file_hash") if import_event else None) or "unknown",
            file_size_bytes=file_size or (import_event.get("file_size_bytes") if import_event else None) or 0,
            status=status,
            failure_reason=failure_reason,
            failure_details=(
                {"first_error": import_event.get("first_error")}
                if import_event and import_event.get("first_error") else None
            ),
            suggestion=suggestion,
            total_rows=_stat_or_event(stats, import_event, "total_rows"),
            parsed_rows=_stat_or_event(stats, import_event, "parsed_rows"),
            skipped_rows=_stat_or_event(stats, import_event, "skipped_rows"),
            duplicate_rows=_stat_or_event(stats, import_event, "duplicates_removed", "duplicate_rows"),
            columns_detected=_stat_or_event(stats, import_event, "columns_detected", default=None),
            columns_mapped=_stat_or_event(stats, import_event, "columns_mapped", default=None),
            timestamp_range_start=stats.get("timestamp_range_start") if stats else None,
            timestamp_range_end=stats.get("timestamp_range_end") if stats else None,
            trip_id=trip_id,
            session_id=session_id,
        )
        db.add(csv_import)
        db.commit()
    except IntegrityError:
        db.rollback()
        logger.warning(f"Failed to record import {import_code} (possible duplicate hash)")
    except Exception as e:
        db.rollback()
        logger.warning(f"Failed to record import {import_code}: {e}")


def build_import_response(status, message, import_code, failure_reason=None,
                          suggestion=None, stats=None, trip_id=None, http_status=200,
                          first_error=None):
    """Build standardized import response dict.

    When ``failure_reason`` is set, the response also includes ``failure_details``
    (from ``first_error``) and the first 10 parser errors (from ``stats['errors']``)
    so callers — especially mobile clients without log access — can debug imports
    from the API response alone.
    """
    response = {
        "status": status,
        "import_code": import_code,
        "message": message,
    }
    if failure_reason:
        response["failure_reason"] = failure_reason
        # Surface error details so callers can debug without log access.
        if first_error:
            response["failure_details"] = first_error
        if stats and stats.get("errors"):
            response["errors"] = stats["errors"][:10]
    if suggestion:
        response["suggestion"] = suggestion
    if stats:
        response["stats"] = {
            "total_rows": stats.get("total_rows", 0),
            "parsed_rows": stats.get("parsed_rows", 0),
            "skipped_rows": stats.get("skipped_rows", 0),
            "duplicate_rows": stats.get("duplicates_removed", 0),
            "columns_detected": stats.get("columns_detected", []),
            "columns_mapped": stats.get("columns_mapped", []),
        }
        if stats.get("timestamp_range_start") and stats.get("timestamp_range_end"):
            response["stats"]["timestamp_range"] = {
                "start": (
                    stats["timestamp_range_start"].isoformat()
                    if hasattr(stats["timestamp_range_start"], 'isoformat')
                    else str(stats["timestamp_range_start"])
                ),
                "end": (
                    stats["timestamp_range_end"].isoformat()
                    if hasattr(stats["timestamp_range_end"], 'isoformat')
                    else str(stats["timestamp_range_end"])
                ),
            }
    if trip_id:
        response["trip_id"] = trip_id

    response["reportable"] = format_reportable(
        import_code=import_code,
        status=status,
        failure_reason=failure_reason,
        parsed_rows=stats.get("parsed_rows", 0) if stats else 0,
        total_rows=stats.get("total_rows", 0) if stats else 0,
        trip_id=trip_id,
        columns_detected=stats.get("columns_detected") if stats else None,
    )

    return response, http_status


def insert_telemetry_records(db, records):
    """Bulk insert telemetry records. Returns inserted count."""
    telemetry_batch = []
    for record in records:
        telemetry_data = {
            "session_id": record["session_id"],
            "timestamp": record["timestamp"],
            "latitude": record.get("latitude"),
            "longitude": record.get("longitude"),
            "speed_mph": record.get("speed_mph"),
            "engine_rpm": record.get("engine_rpm"),
            "throttle_position": record.get("throttle_position"),
            "coolant_temp_f": record.get("coolant_temp_f"),
            "intake_air_temp_f": record.get("intake_air_temp_f"),
            "fuel_level_percent": record.get("fuel_level_percent"),
            "fuel_remaining_gallons": record.get("fuel_remaining_gallons"),
            "state_of_charge": record.get("state_of_charge"),
            "battery_voltage": record.get("battery_voltage"),
            "ambient_temp_f": record.get("ambient_temp_f"),
            "odometer_miles": record.get("odometer_miles"),
            "hv_battery_power_kw": record.get("hv_battery_power_kw"),
            "raw_data": record.get("raw_data", {}),
        }
        telemetry_batch.append(telemetry_data)

    if telemetry_batch:
        db.bulk_insert_mappings(TelemetryRaw, telemetry_batch)

    return len(telemetry_batch)


def create_trip_from_import(db, records, session_id):
    """Create a trip from imported records. Returns (trip_id, error_msg)."""
    from services.trip_service import finalize_trip

    existing_trip = db.query(Trip).filter(Trip.session_id == session_id).first()
    if existing_trip:
        return existing_trip.id, None

    first_record = records[0]
    last_record = records[-1]

    # Query ranges from telemetry
    odometer_range = (
        db.query(func.min(TelemetryRaw.odometer_miles), func.max(TelemetryRaw.odometer_miles))
        .filter(TelemetryRaw.session_id == session_id, TelemetryRaw.odometer_miles.isnot(None))
        .first()
    )

    soc_range = (
        db.query(func.min(TelemetryRaw.state_of_charge), func.max(TelemetryRaw.state_of_charge))
        .filter(TelemetryRaw.session_id == session_id, TelemetryRaw.state_of_charge.isnot(None))
        .first()
    )

    time_range = (
        db.query(func.min(TelemetryRaw.timestamp), func.max(TelemetryRaw.timestamp))
        .filter(TelemetryRaw.session_id == session_id)
        .first()
    )

    trip = Trip(
        session_id=session_id,
        start_time=time_range[0] if time_range else first_record["timestamp"],
        end_time=time_range[1] if time_range else last_record["timestamp"],
        start_odometer=odometer_range[0] if odometer_range else None,
        end_odometer=odometer_range[1] if odometer_range else None,
        start_soc=soc_range[1] if soc_range else None,
        fuel_level_at_end=last_record.get("fuel_level_percent"),
        is_imported=True,
        is_closed=False,
    )

    if trip.start_odometer is not None and trip.end_odometer is not None:
        trip.distance_miles = abs(trip.end_odometer - trip.start_odometer)

    db.add(trip)
    try:
        db.commit()
    except Exception as e:
        db.rollback()
        return None, str(e)

    try:
        finalize_trip(db, trip)
        logger.info(f"Finalized imported trip {trip.id}")
    except Exception as e:
        logger.warning(f"Failed to finalize imported trip {trip.id}: {e}")
        trip.is_closed = True
        try:
            db.commit()
        except Exception:
            db.rollback()

    return trip.id, None
