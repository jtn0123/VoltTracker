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


@import_bp.route("/import/csv", methods=["POST"])
@limiter.limit("5 per hour")
def import_csv():
    """
    Import telemetry data from a Torque Pro CSV log file.

    Accepts multipart form data with a CSV file.
    """
    from utils.csv_importer import TorqueCSVImporter

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
    }

    def _log_import_event():
        import_event["duration_ms"] = int((time.time() - start_time) * 1000)
        logger.info(f"csv_import_complete: {json.dumps(import_event)}")

    if "file" not in request.files:
        import_event["failure_reason"] = "no_file_provided"
        _log_import_event()
        record_import(db, import_code, "failed", "no_file_provided",
                      "No file was provided in the request", import_event=import_event)
        resp, code = build_import_response("failed", "No file provided", import_code,
                                           "no_file_provided", "Please select a CSV file to upload",
                                           http_status=400)
        return jsonify(resp), code

    file = request.files["file"]
    import_event["filename"] = file.filename

    if file.filename == "":
        import_event["failure_reason"] = "no_file_selected"
        _log_import_event()
        record_import(db, import_code, "failed", "no_file_selected",
                      "No file was selected", import_event=import_event)
        resp, code = build_import_response("failed", "No file selected", import_code,
                                           "no_file_selected", "Please select a CSV file to upload",
                                           http_status=400)
        return jsonify(resp), code

    if not file.filename.lower().endswith(".csv"):
        import_event["failure_reason"] = "not_csv_file"
        _log_import_event()
        record_import(db, import_code, "failed", "not_csv_file",
                      "Only CSV files are supported", filename=file.filename, import_event=import_event)
        resp, code = build_import_response("failed", "File must be a CSV", import_code,
                                           "not_csv_file", "Only CSV files are supported.",
                                           http_status=400)
        return jsonify(resp), code

    try:
        file_bytes = file.read()
        file_size = len(file_bytes)

        from config import Config as AppConfig
        if file_size > AppConfig.MAX_CSV_FILE_SIZE:
            max_size_mb = AppConfig.MAX_CSV_FILE_SIZE / (1024 * 1024)
            actual_size_mb = file_size / (1024 * 1024)
            import_event["failure_reason"] = "file_too_large"
            _log_import_event()
            record_import(db, import_code, "failed", "file_too_large",
                          f"File size ({actual_size_mb:.1f} MB) exceeds maximum ({max_size_mb:.1f} MB)",
                          filename=file.filename, file_size=file_size, import_event=import_event)
            resp, code = build_import_response(
                "failed", "File too large", import_code, "file_too_large",
                f"CSV file must be less than {max_size_mb:.1f} MB. Your file is {actual_size_mb:.1f} MB.",
                http_status=413)
            return jsonify(resp), code

        file_hash = get_file_hash(file_bytes)
        import_event["file_hash"] = file_hash
        import_event["file_size_bytes"] = file_size

        # Check for exact duplicate file
        existing_import = check_duplicate_file(db, file_hash)
        if existing_import:
            import_event["failure_reason"] = "duplicate_file"
            _log_import_event()
            record_import(db, import_code, "duplicate", "duplicate_file",
                          f"This exact file was already imported as {existing_import.import_code}",
                          filename=file.filename, file_hash=file_hash, file_size=len(file_bytes),
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

        # Backup original CSV file
        try:
            CSV_BACKUP_DIR.mkdir(parents=True, exist_ok=True)
            timestamp = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
            safe_filename = "".join(c for c in file.filename if c.isalnum() or c in "._-")
            backup_path = CSV_BACKUP_DIR / f"{timestamp}_{import_code}_{safe_filename}"
            backup_path.write_text(csv_content, encoding="utf-8")
        except Exception as e:
            logger.warning(f"Failed to backup CSV: {e}")

        # Get existing timestamps for dedup (P1: batched lookup)
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
            if stats.get("duplicates_removed", 0) > 0:
                failure_reason = "all_duplicates"
                import_event["failure_reason"] = failure_reason
                _log_import_event()
                suggestion = (
                    "All records in this file already exist in the"
                    " database. This file may have been imported previously."
                )
                record_import(db, import_code, "failed", failure_reason, suggestion, stats,
                              filename=file.filename, file_hash=file_hash, file_size=len(file_bytes),
                              import_event=import_event)
                resp, code = build_import_response("failed", "All records already imported", import_code,
                                                   failure_reason, suggestion, stats, http_status=400,
                                                   first_error=import_event.get("first_error"))
                return jsonify(resp), code
            else:
                failure_reason = stats.get("failure_reason", "no_valid_rows")
                import_event["failure_reason"] = failure_reason
                _log_import_event()
                suggestion = get_failure_suggestion(failure_reason, stats.get("columns_detected"))
                record_import(db, import_code, "failed", failure_reason, suggestion, stats,
                              filename=file.filename, file_hash=file_hash, file_size=len(file_bytes),
                              import_event=import_event)
                resp, code = build_import_response("failed", "No valid records found in CSV", import_code,
                                                   failure_reason, suggestion, stats, http_status=400,
                                                   first_error=import_event.get("first_error"))
                return jsonify(resp), code

        # Insert records
        inserted_count = insert_telemetry_records(db, records)

        try:
            db.commit()
        except Exception as commit_error:
            db.rollback()
            import_event["failure_reason"] = "database_commit_error"
            import_event["first_error"] = {"error_type": type(commit_error).__name__, "reason": str(commit_error)}
            _log_import_event()
            logger.error(f"CSV import commit failed: {commit_error}", exc_info=True)
            record_import(db, import_code, "failed", "database_commit_error",
                          "Database commit failed during bulk insert",
                          filename=file.filename, file_hash=file_hash, file_size=len(file_bytes),
                          import_event=import_event)
            resp, code = build_import_response("failed", f"Database commit failed: {str(commit_error)}",
                                               import_code, "database_commit_error", http_status=500,
                                               first_error=import_event.get("first_error"))
            return jsonify(resp), code

        # Get timestamp range
        if records:
            timestamps = [r["timestamp"] for r in records if r.get("timestamp")]
            if timestamps:
                stats["timestamp_range_start"] = min(timestamps)
                stats["timestamp_range_end"] = max(timestamps)

        # Create trip
        trip_id = None
        session_id_str = None
        if records:
            session_id = records[0]["session_id"]
            session_id_str = str(session_id)

            existing_trip = db.query(
                __import__('models', fromlist=['Trip']).Trip
            ).filter(
                __import__('models', fromlist=['Trip']).Trip.session_id == session_id
            ).first()

            if existing_trip:
                trip_id = existing_trip.id
                import_event.update({
                    "inserted_count": inserted_count, "success": True,
                    "trip_id": trip_id, "session_id": session_id_str,
                })
                _log_import_event()
                status = "partial" if stats.get("duplicates_removed", 0) > 0 else "success"
                record_import(db, import_code, status, None, None, stats, trip_id, session_id_str,
                              file_hash, file.filename, len(file_bytes), import_event)
                resp, code = build_import_response(
                    status, f"Imported {inserted_count} records (trip already exists)",
                    import_code, stats=stats, trip_id=trip_id)
                return jsonify(resp), code

            trip_id, trip_error = create_trip_from_import(db, records, session_id)
            if trip_error:
                import_event["failure_reason"] = "trip_commit_error"
                _log_import_event()
                record_import(db, import_code, "partial", "trip_creation_failed",
                              f"Telemetry imported but trip creation failed: {trip_error}",
                              stats, None, session_id_str, file_hash, file.filename, len(file_bytes),
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
        _log_import_event()

        status = "success"
        if stats.get("duplicates_removed", 0) > 0:
            status = "partial"

        record_import(db, import_code, status, None, None, stats, trip_id, session_id_str,
                      file_hash, file.filename, len(file_bytes), import_event)
        resp, code = build_import_response(status, f"Successfully imported {inserted_count} records",
                                           import_code, stats=stats, trip_id=trip_id)
        return jsonify(resp), code

    except UnicodeDecodeError as e:
        import_event["failure_reason"] = "encoding_error"
        import_event["first_error"] = {"error_type": "UnicodeDecodeError", "reason": str(e)}
        _log_import_event()
        suggestion = get_failure_suggestion("encoding_error")
        record_import(db, import_code, "failed", "encoding_error", suggestion,
                      filename=file.filename, import_event=import_event)
        resp, code = build_import_response("failed", "File encoding error. Please use UTF-8 encoded CSV",
                                           import_code, "encoding_error", suggestion, http_status=400,
                                           first_error=import_event.get("first_error"))
        return jsonify(resp), code
    except CSVImportError as e:
        import_event["failure_reason"] = "csv_import_error"
        import_event["first_error"] = {"error_type": "CSVImportError", "reason": str(e)}
        _log_import_event()
        record_import(db, import_code, "failed", "csv_import_error", str(e),
                      filename=file.filename, import_event=import_event)
        resp, code = build_import_response("failed", f"Import failed: {e.message}",
                                           import_code, "csv_import_error", http_status=400,
                                           first_error=import_event.get("first_error"))
        return jsonify(resp), code
    except Exception as e:
        import_event["failure_reason"] = "database_error"
        import_event["first_error"] = {"error_type": type(e).__name__, "reason": str(e)}
        _log_import_event()
        logger.exception(f"CSV import failed with unexpected error: {e}")
        suggestion = get_failure_suggestion("database_error")
        record_import(db, import_code, "failed", "database_error", suggestion,
                      filename=file.filename if file else None, import_event=import_event)
        resp, code = build_import_response("failed", f"Import failed: {str(e)}",
                                           import_code, "database_error", suggestion, http_status=500,
                                           first_error=import_event.get("first_error"))
        return jsonify(resp), code


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
