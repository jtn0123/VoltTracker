"""
Export routes for VoltTracker.

Handles data export and import operations.
"""

import csv
import io
import logging
import os
import json
import time
from datetime import datetime, timedelta
from pathlib import Path

from database import get_db
from exceptions import CSVImportError
from flask import Blueprint, Response, jsonify, request
from models import ChargingSession, CsvImport, FuelEvent, SocTransition, TelemetryRaw, Trip
from services.trip_service import finalize_trip
from sqlalchemy import and_, desc, func, or_
from sqlalchemy.exc import IntegrityError
from utils import utc_now
from utils.import_utils import generate_import_code, get_file_hash, format_reportable, get_failure_suggestion

# Import limiter for rate limiting sensitive endpoints
from extensions import limiter

# Backup directory for imported CSV files
CSV_BACKUP_DIR = Path(os.environ.get("CSV_BACKUP_DIR", "/app/backups/csv-imports"))

logger = logging.getLogger(__name__)

export_bp = Blueprint("export", __name__)


@export_bp.route("/export/trips", methods=["GET"])
def export_trips():
    """
    Export trips as CSV or JSON with streaming support for large datasets.

    Query params:
        format: 'csv' (default) or 'json'
        start_date: Filter trips after this date
        end_date: Filter trips before this date
        gas_only: If true, only export trips with gas usage
        stream: If 'true', use streaming mode (default for CSV, recommended for >1000 trips)

    Streaming mode processes trips in batches to avoid memory exhaustion.
    """
    db = get_db()
    export_format = request.args.get("format", "csv").lower()
    use_streaming = request.args.get("stream", "true" if export_format == "csv" else "false").lower() == "true"

    query = db.query(Trip).filter(Trip.is_closed.is_(True))

    # Apply filters
    start_date = request.args.get("start_date")
    if start_date:
        query = query.filter(Trip.start_time >= start_date)

    end_date = request.args.get("end_date")
    if end_date:
        query = query.filter(Trip.start_time <= end_date)

    gas_only = request.args.get("gas_only", "").lower() == "true"
    if gas_only:
        query = query.filter(Trip.gas_mode_entered.is_(True))

    query = query.order_by(desc(Trip.start_time))

    # JSON export (non-streaming, loads all into memory)
    if export_format == "json":
        if use_streaming:
            # Streaming JSON (newline-delimited JSON)
            def generate_json():
                for trip in query.yield_per(100):
                    yield json.dumps(trip.to_dict()) + "\n"

            return Response(
                generate_json(),
                mimetype="application/x-ndjson",
                headers={"Content-Disposition": "attachment; filename=trips.ndjson"}
            )
        else:
            trips = query.all()
            return jsonify([t.to_dict() for t in trips])

    # CSV export with streaming
    if use_streaming:
        def generate_csv():
            """Generator function that yields CSV data in chunks."""
            # Yield header row
            header_buffer = io.StringIO()
            header_writer = csv.writer(header_buffer)
            header_writer.writerow([
                "id", "session_id", "start_time", "end_time",
                "distance_miles", "electric_miles", "gas_miles",
                "start_soc", "soc_at_gas_transition", "gas_mpg",
                "fuel_used_gallons", "ambient_temp_avg_f",
            ])
            yield header_buffer.getvalue()

            # Yield data rows in batches
            for trip in query.yield_per(100):  # Process 100 trips at a time
                row_buffer = io.StringIO()
                row_writer = csv.writer(row_buffer)
                row_writer.writerow([
                    trip.id,
                    str(trip.session_id),
                    trip.start_time.isoformat() if trip.start_time else "",
                    trip.end_time.isoformat() if trip.end_time else "",
                    trip.distance_miles or "",
                    trip.electric_miles or "",
                    trip.gas_miles or "",
                    trip.start_soc or "",
                    trip.soc_at_gas_transition or "",
                    trip.gas_mpg or "",
                    trip.fuel_used_gallons or "",
                    trip.ambient_temp_avg_f or "",
                ])
                yield row_buffer.getvalue()

        return Response(
            generate_csv(),
            mimetype="text/csv",
            headers={"Content-Disposition": "attachment; filename=trips.csv"}
        )

    # Non-streaming CSV (legacy, for small datasets)
    trips = query.all()
    output = io.StringIO()
    writer = csv.writer(output)

    # Header row
    writer.writerow([
        "id", "session_id", "start_time", "end_time",
        "distance_miles", "electric_miles", "gas_miles",
        "start_soc", "soc_at_gas_transition", "gas_mpg",
        "fuel_used_gallons", "ambient_temp_avg_f",
    ])

    # Data rows
    for t in trips:
        writer.writerow([
            t.id, str(t.session_id),
            t.start_time.isoformat() if t.start_time else "",
            t.end_time.isoformat() if t.end_time else "",
            t.distance_miles or "", t.electric_miles or "", t.gas_miles or "",
            t.start_soc or "", t.soc_at_gas_transition or "",
            t.gas_mpg or "", t.fuel_used_gallons or "",
            t.ambient_temp_avg_f or "",
        ])

    output.seek(0)
    return Response(
        output.getvalue(),
        mimetype="text/csv",
        headers={"Content-Disposition": "attachment; filename=trips.csv"}
    )


@export_bp.route("/export/fuel", methods=["GET"])
def export_fuel():
    """
    Export fuel events as CSV or JSON.

    Query params:
        format: 'csv' (default) or 'json'
    """
    db = get_db()
    export_format = request.args.get("format", "csv").lower()

    events = db.query(FuelEvent).order_by(desc(FuelEvent.timestamp)).all()

    if export_format == "json":
        return jsonify([e.to_dict() for e in events])

    # CSV export
    output = io.StringIO()
    writer = csv.writer(output)

    writer.writerow(
        [
            "id",
            "timestamp",
            "odometer_miles",
            "gallons_added",
            "fuel_level_before",
            "fuel_level_after",
            "price_per_gallon",
            "total_cost",
            "notes",
        ]
    )

    for e in events:
        writer.writerow(
            [
                e.id,
                e.timestamp.isoformat() if e.timestamp else "",
                e.odometer_miles or "",
                e.gallons_added or "",
                e.fuel_level_before or "",
                e.fuel_level_after or "",
                e.price_per_gallon or "",
                e.total_cost or "",
                e.notes or "",
            ]
        )

    output.seek(0)
    return Response(
        output.getvalue(), mimetype="text/csv", headers={"Content-Disposition": "attachment; filename=fuel_events.csv"}
    )


@export_bp.route("/export/all", methods=["GET"])
@limiter.limit("10 per hour")  # Limit bulk exports to prevent abuse
def export_all():
    """
    Export data as JSON for backup with optional date range filtering.

    Query parameters:
        start_date: Start date (ISO format, optional)
        end_date: End date (ISO format, optional)
        limit: Max records per table (default 10000, optional)

    Returns trips, fuel events, SOC transitions, charging sessions, and summary stats.
    """
    from datetime import datetime

    db = get_db()

    # Parse date range filters
    start_date = request.args.get("start_date")
    end_date = request.args.get("end_date")
    limit = min(int(request.args.get("limit", 10000)), 50000)  # Max 50k records per table

    # Build queries with optional filters
    trip_query = db.query(Trip).order_by(desc(Trip.start_time))
    fuel_query = db.query(FuelEvent).order_by(desc(FuelEvent.timestamp))
    soc_query = db.query(SocTransition).order_by(SocTransition.timestamp)
    charging_query = db.query(ChargingSession).order_by(desc(ChargingSession.start_time))

    # Apply date filters if provided
    if start_date:
        try:
            start_dt = datetime.fromisoformat(start_date.replace("Z", "+00:00"))
            trip_query = trip_query.filter(Trip.start_time >= start_dt)
            fuel_query = fuel_query.filter(FuelEvent.timestamp >= start_dt)
            soc_query = soc_query.filter(SocTransition.timestamp >= start_dt)
            charging_query = charging_query.filter(ChargingSession.start_time >= start_dt)
        except ValueError:
            return jsonify({"error": "Invalid start_date format. Use ISO 8601 format."}), 400

    if end_date:
        try:
            end_dt = datetime.fromisoformat(end_date.replace("Z", "+00:00"))
            trip_query = trip_query.filter(Trip.start_time <= end_dt)
            fuel_query = fuel_query.filter(FuelEvent.timestamp <= end_dt)
            soc_query = soc_query.filter(SocTransition.timestamp <= end_dt)
            charging_query = charging_query.filter(ChargingSession.start_time <= end_dt)
        except ValueError:
            return jsonify({"error": "Invalid end_date format. Use ISO 8601 format."}), 400

    # Apply limit and fetch
    trips = trip_query.limit(limit).all()
    fuel_events = fuel_query.limit(limit).all()
    soc_transitions = soc_query.limit(limit).all()
    charging_sessions = charging_query.limit(limit).all()

    return jsonify(
        {
            "exported_at": utc_now().isoformat(),
            "filters": {"start_date": start_date, "end_date": end_date, "limit": limit},
            "trips": [t.to_dict() for t in trips],
            "fuel_events": [e.to_dict() for e in fuel_events],
            "soc_transitions": [s.to_dict() for s in soc_transitions],
            "charging_sessions": [c.to_dict() for c in charging_sessions],
            "summary": {
                "total_trips": len(trips),
                "total_fuel_events": len(fuel_events),
                "total_soc_transitions": len(soc_transitions),
                "total_charging_sessions": len(charging_sessions),
            },
        }
    )


@export_bp.route("/export/torque-pids", methods=["GET"])
def export_torque_pids():
    """
    Download the Volt PID configuration file for Torque Pro.

    This CSV can be imported into Torque Pro to enable Volt-specific PIDs.
    """
    # Try mounted volume first (Docker), then relative path (development)
    pid_file_path = "/app/torque-config/volt_pids_complete.csv"

    if not os.path.exists(pid_file_path):
        pid_file_path = os.path.join(
            os.path.dirname(os.path.dirname(__file__)), "torque-config", "volt_pids_complete.csv"
        )

    if not os.path.exists(pid_file_path):
        # Return embedded version if file not found
        csv_content = """Name,ShortName,ModeAndPID,Equation,Min Value,Max Value,Units,Header
Fuel Level Percent,FuelPct,22002F,(A*100)/255,0,100,%,7E4
State of Charge,SOC,22005B,A/2.55,0,100,%,7E4
Battery Capacity kWh,BattCap,2241A3,(A*256+B)/28,0,25,kWh,7E4
HV Battery Voltage,HVBattV,220009,(A*256+B)/100,0,500,V,7E4
HV Battery Current,HVBattA,22000A,((A*256+B)-32768)/100,-300,300,A,7E4
HV Battery Power,HVBattKW,22000B,((A*256+B)-32768)/100,-150,150,kW,7E4
Charger Status,ChgStat,220057,A,0,10,,7E4
Charger Power kW,ChgPwrKW,22006E,(A*256+B)/1000,0,10,kW,7E4
Motor A Speed,MotARPM,220051,(A*256+B)/4,0,10000,RPM,7E4
Motor B Speed,MotBRPM,220052,(A*256+B)/4,0,10000,RPM,7E4
Generator Speed,GenRPM,220053,(A*256+B)/4,0,10000,RPM,7E4
Engine Running,EngRun,221930,A,0,1,,7E0
Ambient Air Temp,AmbTemp,22004F,(A-40),-40,100,C,7E4
"""
        return Response(
            csv_content, mimetype="text/csv", headers={"Content-Disposition": "attachment; filename=volt_pids.csv"}
        )

    with open(pid_file_path, "r") as f:
        csv_content = f.read()

    return Response(
        csv_content, mimetype="text/csv", headers={"Content-Disposition": "attachment; filename=volt_pids_complete.csv"}
    )


@export_bp.route("/import/csv", methods=["POST"])
@limiter.limit("5 per hour")  # Limit CSV imports (resource-intensive operation)
def import_csv():
    """
    Import telemetry data from a Torque Pro CSV log file.

    Accepts multipart form data with a CSV file.

    Features:
    - Generates unique import code (IMP-YYYYMMDD-XXXXXX) for tracking
    - File hash duplicate detection (rejects exact duplicate files)
    - Timestamp duplicate detection (skips records already in DB)
    - Records all imports in csv_imports table for audit trail

    Returns:
        JSON with import code, status, statistics, and reportable string
    """
    from utils.csv_importer import TorqueCSVImporter

    db = get_db()

    # Generate unique import code for this attempt
    import_code = generate_import_code()

    # Start timing for wide event
    start_time = time.time()

    # Build import event context (loggingsucks.com wide event pattern)
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
        """Emit the wide event at end of import."""
        import_event["duration_ms"] = int((time.time() - start_time) * 1000)
        # Log as structured JSON for easy parsing
        logger.info(f"csv_import_complete: {json.dumps(import_event)}")

    def _record_import(status: str, failure_reason: str = None, suggestion: str = None,
                       stats: dict = None, trip_id: int = None, session_id: str = None,
                       file_hash: str = None, filename: str = None, file_size: int = 0):
        """Record import attempt in csv_imports table."""
        try:
            csv_import = CsvImport(
                import_code=import_code,
                filename=filename or import_event.get("filename") or "unknown",
                file_hash=file_hash or import_event.get("file_hash") or "unknown",
                file_size_bytes=file_size or import_event.get("file_size_bytes") or 0,
                status=status,
                failure_reason=failure_reason,
                failure_details={"first_error": import_event.get("first_error")} if import_event.get("first_error") else None,
                suggestion=suggestion,
                total_rows=stats.get("total_rows", 0) if stats else import_event.get("total_rows", 0),
                parsed_rows=stats.get("parsed_rows", 0) if stats else import_event.get("parsed_rows", 0),
                skipped_rows=stats.get("skipped_rows", 0) if stats else import_event.get("skipped_rows", 0),
                duplicate_rows=stats.get("duplicates_removed", 0) if stats else import_event.get("duplicate_rows", 0),
                columns_detected=stats.get("columns_detected") if stats else import_event.get("columns_detected"),
                columns_mapped=stats.get("columns_mapped") if stats else import_event.get("columns_mapped"),
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

    def _build_response(status: str, message: str, failure_reason: str = None, suggestion: str = None,
                        stats: dict = None, trip_id: int = None, http_status: int = 200):
        """Build standardized import response."""
        response = {
            "status": status,
            "import_code": import_code,
            "message": message,
        }
        if failure_reason:
            response["failure_reason"] = failure_reason
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
                    "start": stats["timestamp_range_start"].isoformat() if hasattr(stats["timestamp_range_start"], 'isoformat') else str(stats["timestamp_range_start"]),
                    "end": stats["timestamp_range_end"].isoformat() if hasattr(stats["timestamp_range_end"], 'isoformat') else str(stats["timestamp_range_end"]),
                }
        if trip_id:
            response["trip_id"] = trip_id

        # Generate reportable string for easy copy-paste
        response["reportable"] = format_reportable(
            import_code=import_code,
            status=status,
            failure_reason=failure_reason,
            parsed_rows=stats.get("parsed_rows", 0) if stats else 0,
            total_rows=stats.get("total_rows", 0) if stats else 0,
            trip_id=trip_id,
            columns_detected=stats.get("columns_detected") if stats else None,
        )

        return jsonify(response), http_status

    if "file" not in request.files:
        import_event["failure_reason"] = "no_file_provided"
        _log_import_event()
        _record_import("failed", "no_file_provided", "No file was provided in the request")
        return _build_response("failed", "No file provided", "no_file_provided",
                               "Please select a CSV file to upload", http_status=400)

    file = request.files["file"]
    import_event["filename"] = file.filename

    if file.filename == "":
        import_event["failure_reason"] = "no_file_selected"
        _log_import_event()
        _record_import("failed", "no_file_selected", "No file was selected")
        return _build_response("failed", "No file selected", "no_file_selected",
                               "Please select a CSV file to upload", http_status=400)

    if not file.filename.lower().endswith(".csv"):
        import_event["failure_reason"] = "not_csv_file"
        _log_import_event()
        _record_import("failed", "not_csv_file", "Only CSV files are supported", filename=file.filename)
        return _build_response("failed", "File must be a CSV", "not_csv_file",
                               "Only CSV files are supported. Please upload a .csv file", http_status=400)

    try:
        # Read file content as bytes first for hashing
        file_bytes = file.read()
        file_size = len(file_bytes)

        # Validate file size
        from config import Config as AppConfig
        if file_size > AppConfig.MAX_CSV_FILE_SIZE:
            max_size_mb = AppConfig.MAX_CSV_FILE_SIZE / (1024 * 1024)
            actual_size_mb = file_size / (1024 * 1024)
            import_event["failure_reason"] = "file_too_large"
            _log_import_event()
            _record_import("failed", "file_too_large",
                          f"File size ({actual_size_mb:.1f} MB) exceeds maximum ({max_size_mb:.1f} MB)",
                          filename=file.filename, file_size=file_size)
            return _build_response("failed", "File too large", "file_too_large",
                                  f"CSV file must be less than {max_size_mb:.1f} MB. Your file is {actual_size_mb:.1f} MB.",
                                  http_status=413)

        file_hash = get_file_hash(file_bytes)
        import_event["file_hash"] = file_hash
        import_event["file_size_bytes"] = file_size

        # Check for exact duplicate file (same hash already imported)
        # Include "all_duplicates" failures since that means records already exist
        existing_import = db.query(CsvImport).filter(
            CsvImport.file_hash == file_hash,
            or_(
                CsvImport.status.in_(["success", "partial"]),
                and_(CsvImport.status == "failed", CsvImport.failure_reason == "all_duplicates")
            )
        ).first()

        if existing_import:
            import_event["failure_reason"] = "duplicate_file"
            _log_import_event()
            _record_import("duplicate", "duplicate_file",
                           f"This exact file was already imported as {existing_import.import_code}",
                           filename=file.filename, file_hash=file_hash, file_size=len(file_bytes))
            return jsonify({
                "status": "duplicate",
                "import_code": import_code,
                "message": "This exact file was already imported",
                "original_import_code": existing_import.import_code,
                "original_import_date": existing_import.created_at.isoformat() if existing_import.created_at else None,
                "original_trip_id": existing_import.trip_id,
                "reportable": f"{import_code} | DUPLICATE | Same as {existing_import.import_code}",
            }), 409

        # Decode file content
        csv_content = file_bytes.decode("utf-8")

        # Backup original CSV file
        try:
            CSV_BACKUP_DIR.mkdir(parents=True, exist_ok=True)
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            safe_filename = "".join(c for c in file.filename if c.isalnum() or c in "._-")
            backup_path = CSV_BACKUP_DIR / f"{timestamp}_{import_code}_{safe_filename}"
            backup_path.write_text(csv_content, encoding="utf-8")
            logger.debug(f"Backed up CSV to {backup_path}")
        except Exception as e:
            logger.warning(f"Failed to backup CSV: {e}")  # Don't fail import if backup fails

        # Get existing timestamps from last 60 days for duplicate detection
        cutoff = datetime.utcnow() - timedelta(days=60)
        existing_timestamps = set(
            t[0] for t in db.query(TelemetryRaw.timestamp)
            .filter(TelemetryRaw.timestamp >= cutoff)
            .all()
        )
        logger.debug(f"Loaded {len(existing_timestamps)} existing timestamps for duplicate detection")

        # Parse CSV with duplicate detection enabled
        records, stats = TorqueCSVImporter.parse_csv(csv_content, existing_timestamps=existing_timestamps)

        # Update import event with stats
        import_event["total_rows"] = stats.get("total_rows", 0)
        import_event["parsed_rows"] = stats.get("parsed_rows", 0)
        import_event["skipped_rows"] = stats.get("skipped_rows", 0)
        import_event["duplicate_rows"] = stats.get("duplicates_removed", 0)
        import_event["columns_detected"] = stats.get("columns_detected", [])
        import_event["columns_mapped"] = stats.get("columns_mapped", [])
        import_event["timestamp_column_found"] = stats.get("timestamp_column_found", False)
        import_event["errors_count"] = stats.get("total_errors", 0)
        import_event["failure_reason"] = stats.get("failure_reason")

        # Capture first error for quick debugging
        errors = stats.get("errors", [])
        if errors and isinstance(errors[0], dict):
            import_event["first_error"] = errors[0]

        if not records:
            # Check if all records were duplicates (detected before other failure reasons)
            if stats.get("duplicates_removed", 0) > 0:
                failure_reason = "all_duplicates"
                import_event["failure_reason"] = failure_reason
                _log_import_event()
                suggestion = "All records in this file already exist in the database. This file may have been imported previously."
                _record_import("failed", failure_reason, suggestion, stats,
                               filename=file.filename, file_hash=file_hash, file_size=len(file_bytes))
                return _build_response("failed", "All records already imported", failure_reason,
                                       suggestion, stats, http_status=400)
            else:
                failure_reason = stats.get("failure_reason", "no_valid_rows")
                import_event["failure_reason"] = failure_reason
                _log_import_event()
                suggestion = get_failure_suggestion(failure_reason, stats.get("columns_detected"))
                _record_import("failed", failure_reason, suggestion, stats,
                               filename=file.filename, file_hash=file_hash, file_size=len(file_bytes))
                return _build_response("failed", "No valid records found in CSV", failure_reason,
                                       suggestion, stats, http_status=400)

        # Insert records into database using batch operations for performance
        inserted_count = 0

        # Prepare batch insert data (much faster than individual db.add() calls)
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

        # Bulk insert all records at once (10-50x faster than individual inserts)
        if telemetry_batch:
            db.bulk_insert_mappings(TelemetryRaw, telemetry_batch)
            inserted_count = len(telemetry_batch)

        db.commit()

        # Get timestamp range for the import
        if records:
            timestamps = [r["timestamp"] for r in records if r.get("timestamp")]
            if timestamps:
                stats["timestamp_range_start"] = min(timestamps)
                stats["timestamp_range_end"] = max(timestamps)

        # Create a trip for the imported data
        trip_id = None
        session_id_str = None
        if records:
            session_id = records[0]["session_id"]
            session_id_str = str(session_id)
            first_record = records[0]
            last_record = records[-1]

            # Check for duplicate trip (same session_id already exists)
            existing_trip = db.query(Trip).filter(Trip.session_id == session_id).first()
            if existing_trip:
                logger.info(f"Skipping duplicate trip for session {session_id} (existing trip ID: {existing_trip.id})")
                stats["skipped_duplicate"] = True
                stats["existing_trip_id"] = existing_trip.id
                trip_id = existing_trip.id

                import_event["inserted_count"] = inserted_count
                import_event["success"] = True
                import_event["trip_id"] = trip_id
                import_event["session_id"] = session_id_str
                _log_import_event()

                status = "partial" if stats.get("duplicates_removed", 0) > 0 else "success"
                _record_import(status, None, None, stats, trip_id, session_id_str,
                               file_hash, file.filename, len(file_bytes))
                return _build_response(status,
                                       f"Imported {inserted_count} records (trip already exists)",
                                       stats=stats, trip_id=trip_id)

            # Query MIN/MAX odometer from all telemetry (more reliable than first/last record)
            odometer_range = (
                db.query(func.min(TelemetryRaw.odometer_miles), func.max(TelemetryRaw.odometer_miles))
                .filter(TelemetryRaw.session_id == session_id, TelemetryRaw.odometer_miles.isnot(None))
                .first()
            )

            # Query MIN/MAX SOC from all telemetry
            soc_range = (
                db.query(func.min(TelemetryRaw.state_of_charge), func.max(TelemetryRaw.state_of_charge))
                .filter(TelemetryRaw.session_id == session_id, TelemetryRaw.state_of_charge.isnot(None))
                .first()
            )

            # Query time range from telemetry
            time_range = (
                db.query(func.min(TelemetryRaw.timestamp), func.max(TelemetryRaw.timestamp))
                .filter(TelemetryRaw.session_id == session_id)
                .first()
            )

            trip = Trip(
                session_id=session_id,
                start_time=time_range[0] if time_range else first_record["timestamp"],
                end_time=time_range[1] if time_range else last_record["timestamp"],
                start_odometer=odometer_range[0] if odometer_range else None,  # MIN (trip start)
                end_odometer=odometer_range[1] if odometer_range else None,  # MAX (trip end)
                start_soc=soc_range[1] if soc_range else None,  # MAX SOC (start of trip)
                fuel_level_at_end=last_record.get("fuel_level_percent"),
                is_imported=True,  # Mark as imported for soft-delete protection
                is_closed=False,  # Will be closed by finalize_trip
            )

            # Calculate distance if odometer available (use abs to handle any ordering issues)
            if trip.start_odometer is not None and trip.end_odometer is not None:
                trip.distance_miles = abs(trip.end_odometer - trip.start_odometer)

            db.add(trip)
            db.commit()

            # Finalize trip to calculate electric_miles, gas_mpg, etc.
            try:
                finalize_trip(db, trip)
                logger.info(f"Finalized imported trip {trip.id}")
            except Exception as e:
                logger.warning(f"Failed to finalize imported trip {trip.id}: {e}")
                # Still mark as closed even if finalization has issues
                trip.is_closed = True
                db.commit()

            trip_id = trip.id
            stats["trip_id"] = trip_id
            import_event["trip_id"] = trip_id

        import_event["inserted_count"] = inserted_count
        import_event["success"] = True
        import_event["session_id"] = session_id_str
        _log_import_event()

        # Determine final status
        status = "success"
        if stats.get("duplicates_removed", 0) > 0:
            status = "partial"

        _record_import(status, None, None, stats, trip_id, session_id_str,
                       file_hash, file.filename, len(file_bytes))
        return _build_response(status, f"Successfully imported {inserted_count} records",
                               stats=stats, trip_id=trip_id)

    except UnicodeDecodeError as e:
        import_event["failure_reason"] = "encoding_error"
        import_event["first_error"] = {"error_type": "UnicodeDecodeError", "reason": str(e)}
        _log_import_event()
        suggestion = get_failure_suggestion("encoding_error")
        _record_import("failed", "encoding_error", suggestion, filename=file.filename)
        return _build_response("failed", "File encoding error. Please use UTF-8 encoded CSV",
                               "encoding_error", suggestion, http_status=400)
    except CSVImportError as e:
        import_event["failure_reason"] = "csv_import_error"
        import_event["first_error"] = {"error_type": "CSVImportError", "reason": str(e)}
        _log_import_event()
        _record_import("failed", "csv_import_error", str(e), filename=file.filename)
        return _build_response("failed", f"Import failed: {e.message}", "csv_import_error",
                               http_status=400)
    except Exception as e:
        import_event["failure_reason"] = "database_error"
        import_event["first_error"] = {"error_type": type(e).__name__, "reason": str(e)}
        _log_import_event()
        logger.exception(f"CSV import failed with unexpected error: {e}")
        suggestion = get_failure_suggestion("database_error")
        _record_import("failed", "database_error", suggestion, filename=file.filename if file else None)
        return _build_response("failed", f"Import failed: {str(e)}", "database_error",
                               suggestion, http_status=500)


@export_bp.route("/imports", methods=["GET"])
def get_import_history():
    """
    Get CSV import history.

    Query params:
        limit: Number of records to return (default: 20, max: 100)
        status: Filter by status (success, partial, failed, duplicate)

    Returns:
        JSON array of import records
    """
    db = get_db()

    limit = min(int(request.args.get("limit", 20)), 100)
    status_filter = request.args.get("status")

    query = db.query(CsvImport).order_by(desc(CsvImport.created_at))

    if status_filter:
        query = query.filter(CsvImport.status == status_filter)

    imports = query.limit(limit).all()

    return jsonify([imp.to_dict() for imp in imports])


@export_bp.route("/imports/<import_code>", methods=["GET"])
def get_import_details(import_code):
    """
    Get details for a specific import by import code.

    Returns:
        JSON with import details or 404 if not found
    """
    db = get_db()

    csv_import = db.query(CsvImport).filter(CsvImport.import_code == import_code).first()

    if not csv_import:
        return jsonify({"error": "Import not found"}), 404

    return jsonify(csv_import.to_dict())


@export_bp.route("/docs", methods=["GET"])
def api_docs():
    """
    Return API documentation as JSON.

    Provides a comprehensive list of all endpoints with their methods,
    parameters, and descriptions.
    """
    docs = {
        "title": "VoltTracker API",
        "version": "1.0.0",
        "description": "API for tracking Chevy Volt efficiency, trips, and battery health",
        "base_url": "/api",
        "endpoints": [
            {
                "path": "/status",
                "methods": ["GET"],
                "description": "Get system status and last sync time",
                "response": {"status": "string", "last_sync": "datetime", "uptime_seconds": "number"},
            },
            {
                "path": "/telemetry",
                "methods": ["POST"],
                "description": "Receive telemetry from Torque Pro",
                "parameters": "Form-encoded Torque data",
                "response": {"status": "string", "session_id": "uuid"},
            },
            {
                "path": "/trips",
                "methods": ["GET"],
                "description": "List trips with pagination",
                "query_params": {
                    "page": "Page number (default: 1)",
                    "per_page": "Items per page (default: 50, max: 100)",
                    "start_date": "Filter start date (YYYY-MM-DD)",
                    "end_date": "Filter end date (YYYY-MM-DD)",
                },
            },
            {
                "path": "/trips/<trip_id>",
                "methods": ["GET", "DELETE", "PATCH"],
                "description": "Get, delete, or update a specific trip",
                "patch_fields": ["gas_mpg", "gas_miles", "electric_miles", "fuel_used_gallons"],
            },
            {"path": "/trips/summary", "methods": ["GET"], "description": "Get lifetime MPG and trip statistics"},
            {"path": "/fuel/events", "methods": ["GET"], "description": "List fuel events with pagination"},
            {
                "path": "/fuel/add",
                "methods": ["POST"],
                "description": "Add a manual fuel event",
                "body": {
                    "gallons_added": "number (required)",
                    "price_per_gallon": "number",
                    "odometer_miles": "number",
                    "timestamp": "ISO datetime",
                },
            },
            {
                "path": "/soc/analysis",
                "methods": ["GET"],
                "description": "Get SOC floor analysis with temperature correlation",
            },
            {"path": "/charging/history", "methods": ["GET"], "description": "List charging sessions"},
            {
                "path": "/charging/add",
                "methods": ["POST"],
                "description": "Add a charging session",
                "body": {
                    "start_time": "ISO datetime (required)",
                    "end_time": "ISO datetime",
                    "kwh_added": "number",
                    "charge_type": "L1|L2|DCFC",
                    "cost": "number",
                    "location_name": "string",
                },
            },
            {"path": "/charging/summary", "methods": ["GET"], "description": "Get charging statistics and EV ratio"},
            {
                "path": "/battery/cells",
                "methods": ["GET"],
                "description": "Get battery cell voltage readings",
                "query_params": {"limit": "Max readings (default: 10, max: 100)", "days": "Filter to last N days"},
            },
            {
                "path": "/battery/cells/latest",
                "methods": ["GET"],
                "description": "Get the most recent cell voltage reading",
            },
            {
                "path": "/battery/cells/analysis",
                "methods": ["GET"],
                "description": "Get battery health analysis with weak cell detection",
                "query_params": {"days": "Analysis period (default: 30)"},
            },
            {
                "path": "/battery/cells/add",
                "methods": ["POST"],
                "description": "Add a cell voltage reading",
                "body": {
                    "cell_voltages": "array of 96 floats (required)",
                    "timestamp": "ISO datetime",
                    "state_of_charge": "number",
                    "ambient_temp_f": "number",
                },
            },
            {
                "path": "/export/trips",
                "methods": ["GET"],
                "description": "Export trips as CSV or JSON",
                "query_params": {"format": "csv|json (default: csv)"},
            },
            {"path": "/export/fuel", "methods": ["GET"], "description": "Export fuel events as CSV"},
            {"path": "/export/all", "methods": ["GET"], "description": "Export all data as JSON backup"},
            {
                "path": "/import/csv",
                "methods": ["POST"],
                "description": "Import Torque CSV log file",
                "content_type": "multipart/form-data",
                "body": {"file": "CSV file"},
            },
        ],
    }
    return jsonify(docs)
