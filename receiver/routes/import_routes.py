"""
Import routes for VoltTracker (C3: split from export.py).

Handles CSV import and import history endpoints.
"""

import json
import logging
import os
import time
from datetime import datetime, timezone
from pathlib import Path

from database import get_db
from exceptions import CSVImportError
from flask import Blueprint, jsonify, request
from models import CsvImport
from sqlalchemy import desc
from extensions import limiter
from services.import_service import (
    build_import_response,
    check_duplicate_file,
    create_trip_from_import,
    get_existing_timestamps,
    insert_telemetry_records,
    record_import,
)
from utils.import_utils import generate_import_code, get_file_hash, get_failure_suggestion

# Backup directory for imported CSV files
CSV_BACKUP_DIR = Path(os.environ.get("CSV_BACKUP_DIR", "/app/backups/csv-imports"))

logger = logging.getLogger(__name__)

import_bp = Blueprint("import", __name__)


def _fail_import(db, import_code, import_event, reason, message, suggestion=None,
                 filename=None, file_hash=None, file_size=None, http_status=400, stats=None):
    """Helper to handle import failures with consistent logging and response."""
    import_event["failure_reason"] = reason
    import_event["duration_ms"] = int((time.time() - import_event["_start_time"]) * 1000)
    logger.info(f"csv_import_complete: {json.dumps({k: v for k, v in import_event.items() if k != '_start_time'})}")
    record_import(db, import_code, "failed", reason, suggestion or message,
                  stats, filename=filename, file_hash=file_hash, file_size=file_size,
                  import_event=import_event)
    # Surface the structured first_error (parser/commit/uncaught-exception details)
    # to the response so callers without log access — e.g. mobile clients posting
    # via /import/csv — can see *why* the import failed, not just *that* it did.
    resp, code = build_import_response("failed", message, import_code, reason,
                                       suggestion, stats, http_status=http_status,
                                       first_error=import_event.get("first_error"))
    return jsonify(resp), code


def _validate_upload(request_files):
    """Validate the uploaded file. Returns (file, error_reason, error_message) tuple."""
    if "file" not in request_files:
        return None, "no_file_provided", "No file provided"

    file = request_files["file"]
    if file.filename == "":
        return file, "no_file_selected", "No file selected"

    if not file.filename.lower().endswith(".csv"):
        return file, "not_csv_file", "File must be a CSV"

    return file, None, None


def _check_file_size(file_bytes, _filename):
    """Check file size against limit. Returns (error_reason, error_message) or (None, None)."""
    from config import Config as AppConfig
    file_size = len(file_bytes)
    if file_size <= AppConfig.MAX_CSV_FILE_SIZE:
        return None, None
    max_size_mb = AppConfig.MAX_CSV_FILE_SIZE / (1024 * 1024)
    actual_size_mb = file_size / (1024 * 1024)
    return "file_too_large", f"CSV file must be less than {max_size_mb:.1f} MB. Your file is {actual_size_mb:.1f} MB."


def _backup_csv(csv_content, import_code, filename):
    """Backup the CSV file. Errors are logged but non-fatal."""
    try:
        CSV_BACKUP_DIR.mkdir(parents=True, exist_ok=True)
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
        safe_filename = "".join(c for c in filename if c.isalnum() or c in "._-")
        backup_path = CSV_BACKUP_DIR / f"{timestamp}_{import_code}_{safe_filename}"
        backup_path.write_text(csv_content, encoding="utf-8")
    except Exception as e:
        logger.warning(f"Failed to backup CSV: {e}")


def _handle_no_records(db, import_code, import_event, stats, filename, file_hash, file_size):
    """Handle the case where no records were parsed from the CSV."""
    if stats.get("duplicates_removed", 0) > 0:
        reason = "all_duplicates"
        suggestion = (
            "All records in this file already exist in the"
            " database. This file may have been imported previously."
        )
        message = "All records already imported"
    else:
        reason = stats.get("failure_reason", "no_valid_rows")
        suggestion = get_failure_suggestion(reason, stats.get("columns_detected"))
        message = "No valid records found in CSV"

    return _fail_import(db, import_code, import_event, reason, message,
                        suggestion, filename, file_hash, file_size, stats=stats)


def _handle_existing_trip(db, import_code, import_event, stats, _records, inserted_count,
                          existing_trip, session_id_str, filename, file_hash, file_size):
    """Handle import when a trip already exists for this session."""
    trip_id = existing_trip.id
    import_event.update({
        "inserted_count": inserted_count, "success": True,
        "trip_id": trip_id, "session_id": session_id_str,
    })
    import_event["duration_ms"] = int((time.time() - import_event["_start_time"]) * 1000)
    logger.info(f"csv_import_complete: {json.dumps({k: v for k, v in import_event.items() if k != '_start_time'})}")
    status = "partial" if stats.get("duplicates_removed", 0) > 0 else "success"
    record_import(db, import_code, status, None, None, stats, trip_id, session_id_str,
                  file_hash, filename, file_size, import_event)
    resp, code = build_import_response(
        status, f"Imported {inserted_count} records (trip already exists)",
        import_code, stats=stats, trip_id=trip_id)
    return jsonify(resp), code


def _process_csv_import(db, import_code, import_event, file):
    """Core CSV import processing logic. Returns a Flask response tuple."""
    file_bytes = file.read()
    file_size = len(file_bytes)

    size_error, size_msg = _check_file_size(file_bytes, file.filename)
    if size_error:
        return _fail_import(db, import_code, import_event, size_error, size_msg,
                            filename=file.filename, file_size=file_size, http_status=413)

    file_hash = get_file_hash(file_bytes)
    import_event["file_hash"] = file_hash
    import_event["file_size_bytes"] = file_size

    # Check for exact duplicate file
    existing_import = check_duplicate_file(db, file_hash)
    if existing_import:
        import_event["failure_reason"] = "duplicate_file"
        import_event["duration_ms"] = int((time.time() - import_event["_start_time"]) * 1000)
        logger.info(f"csv_import_complete: {json.dumps({k: v for k, v in import_event.items() if k != '_start_time'})}")
        record_import(db, import_code, "duplicate", "duplicate_file",
                      f"This exact file was already imported as {existing_import.import_code}",
                      filename=file.filename, file_hash=file_hash, file_size=file_size,
                      import_event=import_event)
        return jsonify({
            "status": "duplicate",
            "import_code": import_code,
            "message": "This exact file was already imported",
            "original_import_code": existing_import.import_code,
            "original_import_date": existing_import.created_at.isoformat() if existing_import.created_at else None,
            "original_trip_id": existing_import.trip_id,
            "reportable": f"{import_code} | DUPLICATE | Same as {existing_import.import_code}",
        }), 409

    csv_content = file_bytes.decode("utf-8")
    _backup_csv(csv_content, import_code, file.filename)

    # Parse CSV
    from utils.csv_importer import TorqueCSVImporter
    existing_timestamps = get_existing_timestamps(db)
    logger.debug(f"Loaded {len(existing_timestamps)} existing timestamps for duplicate detection")
    records, stats = TorqueCSVImporter.parse_csv(csv_content, existing_timestamps=existing_timestamps)

    # Update import event with stats
    import_event.update({
        "total_rows": stats.get("total_rows", 0),
        "parsed_rows": stats.get("parsed_rows", 0),
        "skipped_rows": stats.get("skipped_rows", 0),
        "duplicate_rows": stats.get("duplicates_removed", 0),
        "columns_detected": stats.get("columns_detected", []),
        "columns_mapped": stats.get("columns_mapped", []),
        "timestamp_column_found": stats.get("timestamp_column_found", False),
        "errors_count": stats.get("total_errors", 0),
        "failure_reason": stats.get("failure_reason"),
    })
    errors = stats.get("errors", [])
    if errors and isinstance(errors[0], dict):
        import_event["first_error"] = errors[0]

    if not records:
        return _handle_no_records(db, import_code, import_event, stats,
                                  file.filename, file_hash, file_size)

    # Insert records
    inserted_count = insert_telemetry_records(db, records)
    try:
        db.commit()
    except Exception as commit_error:
        db.rollback()
        import_event["first_error"] = {"error_type": type(commit_error).__name__, "reason": str(commit_error)}
        return _fail_import(db, import_code, import_event, "database_commit_error",
                            f"Database commit failed: {commit_error}",
                            filename=file.filename, file_hash=file_hash, file_size=file_size,
                            http_status=500)

    # Get timestamp range
    timestamps = [r["timestamp"] for r in records if r.get("timestamp")]
    if timestamps:
        stats["timestamp_range_start"] = min(timestamps)
        stats["timestamp_range_end"] = max(timestamps)

    # Create trip
    trip_id = None
    session_id_str = None
    session_id = records[0]["session_id"]
    session_id_str = str(session_id)

    from models import Trip
    existing_trip = db.query(Trip).filter(Trip.session_id == session_id).first()
    if existing_trip:
        return _handle_existing_trip(db, import_code, import_event, stats, records,
                                     inserted_count, existing_trip, session_id_str,
                                     file.filename, file_hash, file_size)

    trip_id, trip_error = create_trip_from_import(db, records, session_id)
    if trip_error:
        import_event["failure_reason"] = "trip_commit_error"
        import_event["duration_ms"] = int((time.time() - import_event["_start_time"]) * 1000)
        evt = {k: v for k, v in import_event.items() if k != '_start_time'}
        logger.info(f"csv_import_complete: {json.dumps(evt)}")
        record_import(db, import_code, "partial", "trip_creation_failed",
                      f"Telemetry imported but trip creation failed: {trip_error}",
                      stats, None, session_id_str, file_hash, file.filename, file_size,
                      import_event)
        resp, code = build_import_response(
            "partial", f"Telemetry imported ({inserted_count} records) but trip creation failed",
            import_code, "trip_creation_failed", stats=stats)
        return jsonify(resp), code

    stats["trip_id"] = trip_id
    import_event["trip_id"] = trip_id

    import_event.update({
        "inserted_count": inserted_count, "success": True,
        "session_id": session_id_str,
    })
    import_event["duration_ms"] = int((time.time() - import_event["_start_time"]) * 1000)
    logger.info(f"csv_import_complete: {json.dumps({k: v for k, v in import_event.items() if k != '_start_time'})}")

    status = "partial" if stats.get("duplicates_removed", 0) > 0 else "success"
    record_import(db, import_code, status, None, None, stats, trip_id, session_id_str,
                  file_hash, file.filename, file_size, import_event)
    resp, code = build_import_response(status, f"Successfully imported {inserted_count} records",
                                       import_code, stats=stats, trip_id=trip_id)
    return jsonify(resp), code


@import_bp.route("/import/csv", methods=["POST"])
@limiter.limit("5 per hour")
def import_csv():
    """
    Import telemetry data from a Torque Pro CSV log file.

    Accepts multipart form data with a CSV file.
    """
    db = get_db()
    import_code = generate_import_code()
    start_time = time.time()

    import_event = {
        "event": "csv_import",
        "import_code": import_code,
        "filename": None,
        "file_size_bytes": 0,
        "file_hash": None,
        "success": False,
        "failure_reason": None,
        "total_rows": 0,
        "parsed_rows": 0,
        "skipped_rows": 0,
        "duplicate_rows": 0,
        "inserted_count": 0,
        "trip_id": None,
        "session_id": None,
        "columns_detected": [],
        "columns_mapped": [],
        "timestamp_column_found": False,
        "errors_count": 0,
        "first_error": None,
        "duration_ms": 0,
        "_start_time": start_time,
    }

    # Validate upload
    file, error_reason, error_message = _validate_upload(request.files)
    if error_reason:
        if file:
            import_event["filename"] = file.filename
        return _fail_import(db, import_code, import_event, error_reason, error_message,
                            "Please select a CSV file to upload",
                            filename=file.filename if file else None)

    import_event["filename"] = file.filename

    try:
        return _process_csv_import(db, import_code, import_event, file)
    except UnicodeDecodeError as e:
        import_event["first_error"] = {"error_type": "UnicodeDecodeError", "reason": str(e)}
        suggestion = get_failure_suggestion("encoding_error")
        return _fail_import(db, import_code, import_event, "encoding_error",
                            "File encoding error. Please use UTF-8 encoded CSV",
                            suggestion, filename=file.filename)
    except CSVImportError as e:
        import_event["first_error"] = {"error_type": "CSVImportError", "reason": str(e)}
        return _fail_import(db, import_code, import_event, "csv_import_error",
                            f"Import failed: {e.message}", filename=file.filename)
    except Exception as e:
        import_event["first_error"] = {"error_type": type(e).__name__, "reason": str(e)}
        logger.exception(f"CSV import failed with unexpected error: {e}")
        suggestion = get_failure_suggestion("database_error")
        return _fail_import(db, import_code, import_event, "database_error",
                            f"Import failed: {e}", suggestion,
                            filename=file.filename if file else None, http_status=500)


@import_bp.route("/imports", methods=["GET"])
def get_import_history():
    """Get CSV import history."""
    db = get_db()

    try:
        limit = min(int(request.args.get("limit", 20)), 100)
    except (ValueError, TypeError):
        limit = 20
    status_filter = request.args.get("status")

    query = db.query(CsvImport).order_by(desc(CsvImport.created_at))

    if status_filter:
        query = query.filter(CsvImport.status == status_filter)

    imports = query.limit(limit).all()

    return jsonify([imp.to_dict() for imp in imports])


@import_bp.route("/imports/latest", methods=["GET"])
def get_latest_import():
    """Get the most recent CSV import for quick status/error checking.

    Useful for checking import results without knowing the import code,
    especially when importing via phone where viewing logs is difficult.
    """
    db = get_db()

    latest = db.query(CsvImport).order_by(desc(CsvImport.created_at)).first()

    if not latest:
        return jsonify({"error": "No imports found"}), 404

    return jsonify(latest.to_dict())


@import_bp.route("/imports/<import_code>", methods=["GET"])
def get_import_details(import_code):
    """Get details for a specific import by import code."""
    db = get_db()

    csv_import = db.query(CsvImport).filter(CsvImport.import_code == import_code).first()

    if not csv_import:
        return jsonify({"error": "Import not found"}), 404

    return jsonify(csv_import.to_dict())
