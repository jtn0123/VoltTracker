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


_TRIP_CSV_HEADERS = [
    "id", "session_id", "start_time", "end_time",
    "distance_miles", "electric_miles", "gas_miles",
    "start_soc", "soc_at_gas_transition", "gas_mpg",
    "fuel_used_gallons", "ambient_temp_avg_f",
]


def _trip_csv_row(trip):
    """Convert a trip to a CSV row list."""
    return [
        trip.id, str(trip.session_id),
        trip.start_time.isoformat() if trip.start_time else "",
        trip.end_time.isoformat() if trip.end_time else "",
        trip.distance_miles or "", trip.electric_miles or "", trip.gas_miles or "",
        trip.start_soc or "", trip.soc_at_gas_transition or "",
        trip.gas_mpg or "", trip.fuel_used_gallons or "",
        trip.ambient_temp_avg_f or "",
    ]


def _parse_date_filter(param_name):
    """Parse a date query param, returning (datetime, None) or (None, error_response).

    A date-only ``end_date`` (no time component) is expanded to the end of that
    day so the requested day is included in ``start_time <= end_date`` filters;
    otherwise it would resolve to midnight and exclude the entire end day.
    """
    from datetime import datetime as dt
    value = request.args.get(param_name)
    if not value:
        return None, None
    try:
        parsed = dt.fromisoformat(value.replace("Z", _UTC_SUFFIX))
    except ValueError:
        return None, (jsonify({"error": f"Invalid {param_name} format. Use ISO 8601 format."}), 400)
    # Date-only end_date -> include the whole day.
    if param_name == "end_date" and len(value) <= 10:
        parsed = parsed.replace(hour=23, minute=59, second=59, microsecond=999999)
    return parsed, None


def _build_trip_export_query(db):
    """Build filtered trip query from request args. Returns (query, error_response)."""
    query = db.query(Trip).filter(Trip.is_closed.is_(True), Trip.deleted_at.is_(None))

    start_dt, err = _parse_date_filter("start_date")
    if err:
        return None, err
    if start_dt:
        query = query.filter(Trip.start_time >= start_dt)

    end_dt, err = _parse_date_filter("end_date")
    if err:
        return None, err
    if end_dt:
        query = query.filter(Trip.start_time <= end_dt)

    if request.args.get("gas_only", "").lower() == "true":
        query = query.filter(Trip.gas_mode_entered.is_(True))

    return query.order_by(desc(Trip.start_time)), None


def _export_trips_json(query, streaming):
    """Export trips as JSON (streaming NDJSON or regular)."""
    if streaming:
        def generate():
            for trip in query.yield_per(100):
                yield json.dumps(trip.to_dict()) + "\n"
        return Response(generate(), mimetype="application/x-ndjson",
                        headers={"Content-Disposition": "attachment; filename=trips.ndjson"})
    return jsonify([t.to_dict() for t in query.all()])


def _export_trips_csv(query, streaming):
    """Export trips as CSV (streaming or buffered)."""
    if streaming:
        def generate():
            buf = io.StringIO()
            csv.writer(buf).writerow(_TRIP_CSV_HEADERS)
            yield buf.getvalue()
            for trip in query.yield_per(100):
                buf = io.StringIO()
                csv.writer(buf).writerow(_trip_csv_row(trip))
                yield buf.getvalue()
        return Response(generate(), mimetype=_CSV_MIMETYPE,
                        headers={"Content-Disposition": "attachment; filename=trips.csv"})

    output = io.StringIO()
    writer = csv.writer(output)
    writer.writerow(_TRIP_CSV_HEADERS)
    for t in query.all():
        writer.writerow(_trip_csv_row(t))
    output.seek(0)
    return Response(output.getvalue(), mimetype=_CSV_MIMETYPE,
                    headers={"Content-Disposition": "attachment; filename=trips.csv"})


@export_bp.route("/export/trips", methods=["GET"])
def export_trips():
    """Export trips as CSV or JSON with streaming support for large datasets."""
    db = get_db()
    export_format = request.args.get("format", "csv").lower()
    use_streaming = request.args.get(
        "stream", "true" if export_format == "csv" else "false"
    ).lower() == "true"

    query, err = _build_trip_export_query(db)
    if err:
        return err

    if export_format == "json":
        return _export_trips_json(query, use_streaming)
    return _export_trips_csv(query, use_streaming)


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


def _apply_date_filters(queries_and_columns, start_dt, end_dt):
    """Apply date range filters to a list of (query, timestamp_column) tuples."""
    result = []
    for query, col in queries_and_columns:
        if start_dt:
            query = query.filter(col >= start_dt)
        if end_dt:
            query = query.filter(col <= end_dt)
        result.append(query)
    return result


def _stream_json_array(query, limit_val):
    """Yield JSON items from a query as a streaming array. Returns (generator, count_ref)."""
    count = [0]

    def gen():
        for i, item in enumerate(query.limit(limit_val).yield_per(100)):
            if i > 0:
                yield ','
            yield json.dumps(item.to_dict(), default=str)
            count[0] += 1
    return gen, count


@export_bp.route("/export/all", methods=["GET"])
@limiter.limit("10 per hour")
def export_all():
    """Export data as streaming JSON for backup."""
    db = get_db()

    start_date = request.args.get("start_date")
    end_date = request.args.get("end_date")
    try:
        # Clamp to [1, 50000]; a negative LIMIT raises an error in PostgreSQL.
        limit_val = max(1, min(int(request.args.get("limit", 10000)), 50000))
    except (ValueError, TypeError):
        limit_val = 10000

    start_dt, err = _parse_date_filter("start_date")
    if err:
        return err
    end_dt, err = _parse_date_filter("end_date")
    if err:
        return err

    trip_q = db.query(Trip).filter(Trip.deleted_at.is_(None)).order_by(desc(Trip.start_time))
    fuel_q = db.query(FuelEvent).order_by(desc(FuelEvent.timestamp))
    soc_q = db.query(SocTransition).order_by(SocTransition.timestamp)
    charge_q = db.query(ChargingSession).order_by(desc(ChargingSession.start_time))

    trip_q, fuel_q, soc_q, charge_q = _apply_date_filters([
        (trip_q, Trip.start_time), (fuel_q, FuelEvent.timestamp),
        (soc_q, SocTransition.timestamp), (charge_q, ChargingSession.start_time),
    ], start_dt, end_dt)

    def generate_export():
        yield '{"exported_at":"' + utc_now().isoformat() + '",'
        yield '"filters":' + json.dumps({"start_date": start_date, "end_date": end_date, "limit": limit_val}) + ','

        tables = [
            ("trips", trip_q), ("fuel_events", fuel_q),
            ("soc_transitions", soc_q), ("charging_sessions", charge_q),
        ]
        counts = {}
        for name, query in tables:
            yield f'"{name}":['
            gen, count_ref = _stream_json_array(query, limit_val)
            yield from gen()
            counts[name] = count_ref[0]
            yield '],'

        yield '"summary":' + json.dumps({
            f"total_{k}": v for k, v in counts.items()
        }) + '}'

    return Response(
        generate_export(), mimetype="application/json",
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
