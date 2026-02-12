"""
Export routes for VoltTracker (C3: split from export.py).

Handles data export operations (CSV, JSON, backup).
"""

import csv
import io
import json
import logging
import os

from database import get_db
from flask import Blueprint, Response, jsonify, request
from models import ChargingSession, FuelEvent, SocTransition, Trip
from sqlalchemy import desc
from utils import utc_now

from extensions import limiter

logger = logging.getLogger(__name__)

_UTC_SUFFIX = "+00:00"
_CSV_MIMETYPE = "text/csv"

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
        stream: If 'true', use streaming mode (default for CSV)
    """
    db = get_db()
    export_format = request.args.get("format", "csv").lower()
    use_streaming = request.args.get("stream", "true" if export_format == "csv" else "false").lower() == "true"

    query = db.query(Trip).filter(Trip.is_closed.is_(True), Trip.deleted_at.is_(None))

    # Apply filters
    start_date = request.args.get("start_date")
    if start_date:
        try:
            from datetime import datetime
            start_dt = datetime.fromisoformat(start_date.replace("Z", _UTC_SUFFIX))
            query = query.filter(Trip.start_time >= start_dt)
        except ValueError:
            return jsonify({"error": "Invalid start_date format. Use ISO 8601 format."}), 400

    end_date = request.args.get("end_date")
    if end_date:
        try:
            from datetime import datetime
            end_dt = datetime.fromisoformat(end_date.replace("Z", _UTC_SUFFIX))
            query = query.filter(Trip.start_time <= end_dt)
        except ValueError:
            return jsonify({"error": "Invalid end_date format. Use ISO 8601 format."}), 400

    gas_only = request.args.get("gas_only", "").lower() == "true"
    if gas_only:
        query = query.filter(Trip.gas_mode_entered.is_(True))

    query = query.order_by(desc(Trip.start_time))

    # JSON export
    if export_format == "json":
        if use_streaming:
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
            header_buffer = io.StringIO()
            header_writer = csv.writer(header_buffer)
            header_writer.writerow([
                "id", "session_id", "start_time", "end_time",
                "distance_miles", "electric_miles", "gas_miles",
                "start_soc", "soc_at_gas_transition", "gas_mpg",
                "fuel_used_gallons", "ambient_temp_avg_f",
            ])
            yield header_buffer.getvalue()

            for trip in query.yield_per(100):
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
            mimetype=_CSV_MIMETYPE,
            headers={"Content-Disposition": "attachment; filename=trips.csv"}
        )

    # Non-streaming CSV (legacy)
    trips = query.all()
    output = io.StringIO()
    writer = csv.writer(output)

    writer.writerow([
        "id", "session_id", "start_time", "end_time",
        "distance_miles", "electric_miles", "gas_miles",
        "start_soc", "soc_at_gas_transition", "gas_mpg",
        "fuel_used_gallons", "ambient_temp_avg_f",
    ])

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
        mimetype=_CSV_MIMETYPE,
        headers={"Content-Disposition": "attachment; filename=trips.csv"}
    )


@export_bp.route("/export/fuel", methods=["GET"])
def export_fuel():
    """Export fuel events as CSV or JSON."""
    db = get_db()
    export_format = request.args.get("format", "csv").lower()

    events = db.query(FuelEvent).order_by(desc(FuelEvent.timestamp)).all()

    if export_format == "json":
        return jsonify([e.to_dict() for e in events])

    output = io.StringIO()
    writer = csv.writer(output)

    writer.writerow([
        "id", "timestamp", "odometer_miles", "gallons_added",
        "fuel_level_before", "fuel_level_after",
        "price_per_gallon", "total_cost", "notes",
    ])

    for e in events:
        writer.writerow([
            e.id,
            e.timestamp.isoformat() if e.timestamp else "",
            e.odometer_miles or "", e.gallons_added or "",
            e.fuel_level_before or "", e.fuel_level_after or "",
            e.price_per_gallon or "", e.total_cost or "",
            e.notes or "",
        ])

    output.seek(0)
    return Response(
        output.getvalue(), mimetype=_CSV_MIMETYPE,
        headers={"Content-Disposition": "attachment; filename=fuel_events.csv"}
    )


@export_bp.route("/export/all", methods=["GET"])
@limiter.limit("10 per hour")
def export_all():
    """
    Export data as streaming JSON for backup (P4: streaming via generator).

    Query parameters:
        start_date: Start date (ISO format, optional)
        end_date: End date (ISO format, optional)
        limit: Max records per table (default 10000, optional)
    """
    from datetime import datetime

    db = get_db()

    start_date = request.args.get("start_date")
    end_date = request.args.get("end_date")
    try:
        limit = min(int(request.args.get("limit", 10000)), 50000)
    except (ValueError, TypeError):
        limit = 10000

    # Build queries with optional filters
    trip_query = db.query(Trip).filter(Trip.deleted_at.is_(None)).order_by(desc(Trip.start_time))
    fuel_query = db.query(FuelEvent).order_by(desc(FuelEvent.timestamp))
    soc_query = db.query(SocTransition).order_by(SocTransition.timestamp)
    charging_query = db.query(ChargingSession).order_by(desc(ChargingSession.start_time))

    if start_date:
        try:
            start_dt = datetime.fromisoformat(start_date.replace("Z", _UTC_SUFFIX))
            trip_query = trip_query.filter(Trip.start_time >= start_dt)
            fuel_query = fuel_query.filter(FuelEvent.timestamp >= start_dt)
            soc_query = soc_query.filter(SocTransition.timestamp >= start_dt)
            charging_query = charging_query.filter(ChargingSession.start_time >= start_dt)
        except ValueError:
            return jsonify({"error": "Invalid start_date format. Use ISO 8601 format."}), 400

    if end_date:
        try:
            end_dt = datetime.fromisoformat(end_date.replace("Z", _UTC_SUFFIX))
            trip_query = trip_query.filter(Trip.start_time <= end_dt)
            fuel_query = fuel_query.filter(FuelEvent.timestamp <= end_dt)
            soc_query = soc_query.filter(SocTransition.timestamp <= end_dt)
            charging_query = charging_query.filter(ChargingSession.start_time <= end_dt)
        except ValueError:
            return jsonify({"error": "Invalid end_date format. Use ISO 8601 format."}), 400

    def generate_export():
        """Stream JSON output using a generator (P4) to avoid building full list in memory."""
        yield '{"exported_at":"' + utc_now().isoformat() + '",'
        yield '"filters":' + json.dumps({"start_date": start_date, "end_date": end_date, "limit": limit}) + ','

        # Stream trips
        yield '"trips":['
        trip_count = 0
        for i, trip in enumerate(trip_query.limit(limit).yield_per(100)):
            if i > 0:
                yield ','
            yield json.dumps(trip.to_dict(), default=str)
            trip_count += 1
        yield '],'

        # Stream fuel events
        yield '"fuel_events":['
        fuel_count = 0
        for i, evt in enumerate(fuel_query.limit(limit).yield_per(100)):
            if i > 0:
                yield ','
            yield json.dumps(evt.to_dict(), default=str)
            fuel_count += 1
        yield '],'

        # Stream SOC transitions
        yield '"soc_transitions":['
        soc_count = 0
        for i, soc in enumerate(soc_query.limit(limit).yield_per(100)):
            if i > 0:
                yield ','
            yield json.dumps(soc.to_dict(), default=str)
            soc_count += 1
        yield '],'

        # Stream charging sessions
        yield '"charging_sessions":['
        charge_count = 0
        for i, cs in enumerate(charging_query.limit(limit).yield_per(100)):
            if i > 0:
                yield ','
            yield json.dumps(cs.to_dict(), default=str)
            charge_count += 1
        yield '],'

        # Summary
        yield '"summary":' + json.dumps({
            "total_trips": trip_count,
            "total_fuel_events": fuel_count,
            "total_soc_transitions": soc_count,
            "total_charging_sessions": charge_count,
        }) + '}'

    return Response(
        generate_export(),
        mimetype="application/json",
        headers={"Content-Disposition": "attachment; filename=volttracker_backup.json"}
    )


@export_bp.route("/export/torque-pids", methods=["GET"])
def export_torque_pids():
    """Download the Volt PID configuration file for Torque Pro."""
    pid_file_path = "/app/torque-config/volt_pids_complete.csv"

    if not os.path.exists(pid_file_path):
        pid_file_path = os.path.join(
            os.path.dirname(os.path.dirname(__file__)), "torque-config", "volt_pids_complete.csv"
        )

    if not os.path.exists(pid_file_path):
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
            csv_content, mimetype=_CSV_MIMETYPE,
            headers={"Content-Disposition": "attachment; filename=volt_pids.csv"}
        )

    with open(pid_file_path, "r") as f:
        csv_content = f.read()

    return Response(
        csv_content, mimetype=_CSV_MIMETYPE,
        headers={"Content-Disposition": "attachment; filename=volt_pids_complete.csv"}
    )


@export_bp.route("/docs", methods=["GET"])
def api_docs():
    """Return API documentation as JSON."""
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
            },
            {
                "path": "/telemetry",
                "methods": ["POST"],
                "description": "Receive telemetry from Torque Pro",
            },
            {
                "path": "/trips",
                "methods": ["GET"],
                "description": "List trips with pagination",
            },
            {
                "path": "/trips/<trip_id>",
                "methods": ["GET", "DELETE", "PATCH"],
                "description": "Get, delete, or update a specific trip",
            },
            {"path": "/trips/summary", "methods": ["GET"], "description": "Get lifetime MPG and trip statistics"},
            {"path": "/fuel/events", "methods": ["GET"], "description": "List fuel events with pagination"},
            {"path": "/fuel/add", "methods": ["POST"], "description": "Add a manual fuel event"},
            {"path": "/soc/analysis", "methods": ["GET"], "description": "Get SOC floor analysis"},
            {"path": "/charging/history", "methods": ["GET"], "description": "List charging sessions"},
            {"path": "/charging/add", "methods": ["POST"], "description": "Add a charging session"},
            {"path": "/charging/summary", "methods": ["GET"], "description": "Get charging statistics"},
            {"path": "/battery/cells", "methods": ["GET"], "description": "Get battery cell voltage readings"},
            {"path": "/battery/cells/latest", "methods": ["GET"], "description": "Get most recent cell reading"},
            {"path": "/battery/cells/analysis", "methods": ["GET"], "description": "Get battery health analysis"},
            {"path": "/battery/cells/add", "methods": ["POST"], "description": "Add a cell voltage reading"},
            {"path": "/export/trips", "methods": ["GET"], "description": "Export trips as CSV or JSON"},
            {"path": "/export/fuel", "methods": ["GET"], "description": "Export fuel events as CSV"},
            {"path": "/export/all", "methods": ["GET"], "description": "Export all data as JSON backup"},
            {"path": "/import/csv", "methods": ["POST"], "description": "Import Torque CSV log file"},
        ],
    }
    return jsonify(docs)
