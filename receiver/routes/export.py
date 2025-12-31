"""
Export routes for VoltTracker.

Handles data export and import operations.
"""

import io
import csv
import os
import logging
from flask import Blueprint, request, jsonify, Response
from sqlalchemy import desc

from database import get_db
from exceptions import CSVImportError
from models import Trip, FuelEvent, SocTransition, ChargingSession, TelemetryRaw
from utils import utc_now

logger = logging.getLogger(__name__)

export_bp = Blueprint('export', __name__)


@export_bp.route('/export/trips', methods=['GET'])
def export_trips() -> Response:
    """
    Export trips as CSV or JSON.

    Query params:
        format: 'csv' (default) or 'json'
        start_date: Filter trips after this date
        end_date: Filter trips before this date
        gas_only: If true, only export trips with gas usage
    """
    db = get_db()
    export_format = request.args.get('format', 'csv').lower()

    query = db.query(Trip).filter(Trip.is_closed.is_(True))

    # Apply filters
    start_date = request.args.get('start_date')
    if start_date:
        query = query.filter(Trip.start_time >= start_date)

    end_date = request.args.get('end_date')
    if end_date:
        query = query.filter(Trip.start_time <= end_date)

    gas_only = request.args.get('gas_only', '').lower() == 'true'
    if gas_only:
        query = query.filter(Trip.gas_mode_entered.is_(True))

    trips = query.order_by(desc(Trip.start_time)).all()

    if export_format == 'json':
        return jsonify([t.to_dict() for t in trips])

    # CSV export
    output = io.StringIO()
    writer = csv.writer(output)

    # Header row
    writer.writerow([
        'id', 'session_id', 'start_time', 'end_time',
        'distance_miles', 'electric_miles', 'gas_miles',
        'start_soc', 'soc_at_gas_transition', 'gas_mpg',
        'fuel_used_gallons', 'ambient_temp_avg_f'
    ])

    # Data rows
    for t in trips:
        writer.writerow([
            t.id, str(t.session_id),
            t.start_time.isoformat() if t.start_time else '',
            t.end_time.isoformat() if t.end_time else '',
            t.distance_miles or '', t.electric_miles or '', t.gas_miles or '',
            t.start_soc or '', t.soc_at_gas_transition or '', t.gas_mpg or '',
            t.fuel_used_gallons or '', t.ambient_temp_avg_f or ''
        ])

    output.seek(0)
    return Response(
        output.getvalue(),
        mimetype='text/csv',
        headers={'Content-Disposition': 'attachment; filename=trips.csv'}
    )


@export_bp.route('/export/fuel', methods=['GET'])
def export_fuel():
    """
    Export fuel events as CSV or JSON.

    Query params:
        format: 'csv' (default) or 'json'
    """
    db = get_db()
    export_format = request.args.get('format', 'csv').lower()

    events = db.query(FuelEvent).order_by(desc(FuelEvent.timestamp)).all()

    if export_format == 'json':
        return jsonify([e.to_dict() for e in events])

    # CSV export
    output = io.StringIO()
    writer = csv.writer(output)

    writer.writerow([
        'id', 'timestamp', 'odometer_miles', 'gallons_added',
        'fuel_level_before', 'fuel_level_after',
        'price_per_gallon', 'total_cost', 'notes'
    ])

    for e in events:
        writer.writerow([
            e.id, e.timestamp.isoformat() if e.timestamp else '',
            e.odometer_miles or '', e.gallons_added or '',
            e.fuel_level_before or '', e.fuel_level_after or '',
            e.price_per_gallon or '', e.total_cost or '', e.notes or ''
        ])

    output.seek(0)
    return Response(
        output.getvalue(),
        mimetype='text/csv',
        headers={'Content-Disposition': 'attachment; filename=fuel_events.csv'}
    )


@export_bp.route('/export/all', methods=['GET'])
def export_all():
    """
    Export all data as JSON for backup.

    Returns trips, fuel events, SOC transitions, charging sessions, and summary stats.
    """
    db = get_db()

    trips = db.query(Trip).order_by(desc(Trip.start_time)).all()
    fuel_events = db.query(FuelEvent).order_by(desc(FuelEvent.timestamp)).all()
    soc_transitions = db.query(SocTransition).order_by(SocTransition.timestamp).all()
    charging_sessions = db.query(ChargingSession).order_by(desc(ChargingSession.start_time)).all()

    return jsonify({
        'exported_at': utc_now().isoformat(),
        'trips': [t.to_dict() for t in trips],
        'fuel_events': [e.to_dict() for e in fuel_events],
        'soc_transitions': [s.to_dict() for s in soc_transitions],
        'charging_sessions': [c.to_dict() for c in charging_sessions],
        'summary': {
            'total_trips': len(trips),
            'total_fuel_events': len(fuel_events),
            'total_soc_transitions': len(soc_transitions),
            'total_charging_sessions': len(charging_sessions)
        }
    })


@export_bp.route('/export/torque-pids', methods=['GET'])
def export_torque_pids():
    """
    Download the Volt PID configuration file for Torque Pro.

    This CSV can be imported into Torque Pro to enable Volt-specific PIDs.
    """
    # Try mounted volume first (Docker), then relative path (development)
    pid_file_path = '/app/torque-config/volt_pids_complete.csv'

    if not os.path.exists(pid_file_path):
        pid_file_path = os.path.join(
            os.path.dirname(os.path.dirname(__file__)),
            'torque-config',
            'volt_pids_complete.csv'
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
            csv_content,
            mimetype='text/csv',
            headers={'Content-Disposition': 'attachment; filename=volt_pids.csv'}
        )

    with open(pid_file_path, 'r') as f:
        csv_content = f.read()

    return Response(
        csv_content,
        mimetype='text/csv',
        headers={'Content-Disposition': 'attachment; filename=volt_pids_complete.csv'}
    )


@export_bp.route('/import/csv', methods=['POST'])
def import_csv():
    """
    Import telemetry data from a Torque Pro CSV log file.

    Accepts multipart form data with a CSV file.

    Returns:
        JSON with import statistics (rows imported, skipped, errors)
    """
    from utils.csv_importer import TorqueCSVImporter

    if 'file' not in request.files:
        return jsonify({'error': 'No file provided'}), 400

    file = request.files['file']
    if file.filename == '':
        return jsonify({'error': 'No file selected'}), 400

    if not file.filename.lower().endswith('.csv'):
        return jsonify({'error': 'File must be a CSV'}), 400

    try:
        # Read and parse CSV
        csv_content = file.read().decode('utf-8')
        records, stats = TorqueCSVImporter.parse_csv(csv_content)

        if not records:
            return jsonify({
                'message': 'No valid records found in CSV',
                'stats': stats
            }), 400

        # Insert records into database
        db = get_db()
        inserted_count = 0

        for record in records:
            telemetry = TelemetryRaw(
                session_id=record['session_id'],
                timestamp=record['timestamp'],
                latitude=record.get('latitude'),
                longitude=record.get('longitude'),
                speed_mph=record.get('speed_mph'),
                engine_rpm=record.get('engine_rpm'),
                throttle_position=record.get('throttle_position'),
                coolant_temp_f=record.get('coolant_temp_f'),
                intake_air_temp_f=record.get('intake_air_temp_f'),
                fuel_level_percent=record.get('fuel_level_percent'),
                fuel_remaining_gallons=record.get('fuel_remaining_gallons'),
                state_of_charge=record.get('state_of_charge'),
                battery_voltage=record.get('battery_voltage'),
                ambient_temp_f=record.get('ambient_temp_f'),
                odometer_miles=record.get('odometer_miles'),
                hv_battery_power_kw=record.get('hv_battery_power_kw'),
                raw_data=record.get('raw_data', {})
            )
            db.add(telemetry)
            inserted_count += 1

        db.commit()

        # Create a trip for the imported data
        if records:
            session_id = records[0]['session_id']
            first_record = records[0]
            last_record = records[-1]

            trip = Trip(
                session_id=session_id,
                start_time=first_record['timestamp'],
                end_time=last_record['timestamp'],
                start_odometer=first_record.get('odometer_miles'),
                end_odometer=last_record.get('odometer_miles'),
                start_soc=first_record.get('state_of_charge'),
                fuel_level_at_end=last_record.get('fuel_level_percent'),
            )

            # Calculate distance if odometer available
            if trip.start_odometer and trip.end_odometer:
                trip.distance_miles = trip.end_odometer - trip.start_odometer

            db.add(trip)
            db.commit()

            stats['trip_id'] = trip.id

        logger.info(f"Imported {inserted_count} telemetry records from CSV")

        return jsonify({
            'message': f'Successfully imported {inserted_count} records',
            'stats': stats
        })

    except UnicodeDecodeError:
        return jsonify({'error': 'File encoding error. Please use UTF-8 encoded CSV'}), 400
    except CSVImportError as e:
        logger.error(str(e))
        return jsonify({'error': f'Import failed: {e.message}'}), 400
    except Exception as e:
        error = CSVImportError(f"CSV import error: {e}")
        logger.error(str(error))
        return jsonify({'error': f'Import failed: {str(e)}'}), 500


@export_bp.route('/docs', methods=['GET'])
def api_docs():
    """
    Return API documentation as JSON.

    Provides a comprehensive list of all endpoints with their methods,
    parameters, and descriptions.
    """
    docs = {
        'title': 'VoltTracker API',
        'version': '1.0.0',
        'description': 'API for tracking Chevy Volt efficiency, trips, and battery health',
        'base_url': '/api',
        'endpoints': [
            {
                'path': '/status',
                'methods': ['GET'],
                'description': 'Get system status and last sync time',
                'response': {'status': 'string', 'last_sync': 'datetime', 'uptime_seconds': 'number'}
            },
            {
                'path': '/telemetry',
                'methods': ['POST'],
                'description': 'Receive telemetry from Torque Pro',
                'parameters': 'Form-encoded Torque data',
                'response': {'status': 'string', 'session_id': 'uuid'}
            },
            {
                'path': '/trips',
                'methods': ['GET'],
                'description': 'List trips with pagination',
                'query_params': {
                    'page': 'Page number (default: 1)',
                    'per_page': 'Items per page (default: 50, max: 100)',
                    'start_date': 'Filter start date (YYYY-MM-DD)',
                    'end_date': 'Filter end date (YYYY-MM-DD)'
                }
            },
            {
                'path': '/trips/<trip_id>',
                'methods': ['GET', 'DELETE', 'PATCH'],
                'description': 'Get, delete, or update a specific trip',
                'patch_fields': ['gas_mpg', 'gas_miles', 'electric_miles', 'fuel_used_gallons', 'notes']
            },
            {
                'path': '/trips/summary',
                'methods': ['GET'],
                'description': 'Get lifetime MPG and trip statistics'
            },
            {
                'path': '/fuel/events',
                'methods': ['GET'],
                'description': 'List fuel events with pagination'
            },
            {
                'path': '/fuel/add',
                'methods': ['POST'],
                'description': 'Add a manual fuel event',
                'body': {
                    'gallons_added': 'number (required)',
                    'price_per_gallon': 'number',
                    'odometer_miles': 'number',
                    'timestamp': 'ISO datetime'
                }
            },
            {
                'path': '/soc/analysis',
                'methods': ['GET'],
                'description': 'Get SOC floor analysis with temperature correlation'
            },
            {
                'path': '/charging/history',
                'methods': ['GET'],
                'description': 'List charging sessions'
            },
            {
                'path': '/charging/add',
                'methods': ['POST'],
                'description': 'Add a charging session',
                'body': {
                    'start_time': 'ISO datetime (required)',
                    'end_time': 'ISO datetime',
                    'kwh_added': 'number',
                    'charge_type': 'L1|L2|DCFC',
                    'cost': 'number',
                    'location_name': 'string'
                }
            },
            {
                'path': '/charging/summary',
                'methods': ['GET'],
                'description': 'Get charging statistics and EV ratio'
            },
            {
                'path': '/battery/cells',
                'methods': ['GET'],
                'description': 'Get battery cell voltage readings',
                'query_params': {
                    'limit': 'Max readings (default: 10, max: 100)',
                    'days': 'Filter to last N days'
                }
            },
            {
                'path': '/battery/cells/latest',
                'methods': ['GET'],
                'description': 'Get the most recent cell voltage reading'
            },
            {
                'path': '/battery/cells/analysis',
                'methods': ['GET'],
                'description': 'Get battery health analysis with weak cell detection',
                'query_params': {'days': 'Analysis period (default: 30)'}
            },
            {
                'path': '/battery/cells/add',
                'methods': ['POST'],
                'description': 'Add a cell voltage reading',
                'body': {
                    'cell_voltages': 'array of 96 floats (required)',
                    'timestamp': 'ISO datetime',
                    'state_of_charge': 'number',
                    'ambient_temp_f': 'number'
                }
            },
            {
                'path': '/export/trips',
                'methods': ['GET'],
                'description': 'Export trips as CSV or JSON',
                'query_params': {'format': 'csv|json (default: csv)'}
            },
            {
                'path': '/export/fuel',
                'methods': ['GET'],
                'description': 'Export fuel events as CSV'
            },
            {
                'path': '/export/all',
                'methods': ['GET'],
                'description': 'Export all data as JSON backup'
            },
            {
                'path': '/import/csv',
                'methods': ['POST'],
                'description': 'Import Torque CSV log file',
                'content_type': 'multipart/form-data',
                'body': {'file': 'CSV file'}
            }
        ]
    }
    return jsonify(docs)
