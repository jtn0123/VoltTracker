"""
Volt Efficiency Tracker - Flask Application

Receives telemetry from Torque Pro and provides API for dashboard.
"""

import logging
import io
import csv
from datetime import datetime, timedelta, timezone
from flask import Flask, request, jsonify, render_template, Response
from sqlalchemy import func, desc
from sqlalchemy.orm import scoped_session, sessionmaker
from apscheduler.schedulers.background import BackgroundScheduler
import atexit

from config import Config
from models import (
    Base, TelemetryRaw, Trip, FuelEvent, SocTransition,
    get_engine
)
from utils import (
    TorqueParser,
    calculate_gas_mpg,
    smooth_fuel_level,
    detect_gas_mode_entry,
    detect_refuel_event,
    calculate_electric_miles,
    calculate_average_temp,
    analyze_soc_floor,
)

# Configure logging
logging.basicConfig(
    level=getattr(logging, Config.LOG_LEVEL),
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Initialize Flask app
app = Flask(__name__)
app.config.from_object(Config)

# Database setup
engine = get_engine(Config.DATABASE_URL)
Session = scoped_session(sessionmaker(bind=engine))


def get_db():
    """Get database session."""
    return Session()


@app.teardown_appcontext
def shutdown_session(exception=None):
    """Remove database session at end of request."""
    Session.remove()


# ============================================================================
# Background Tasks
# ============================================================================

def close_stale_trips():
    """
    Close trips that have no new data for TRIP_TIMEOUT_SECONDS.
    Calculate trip statistics and detect gas mode transitions.
    """
    db = get_db()
    try:
        cutoff_time = datetime.now(timezone.utc) - timedelta(seconds=Config.TRIP_TIMEOUT_SECONDS)

        # Find open trips with no recent telemetry
        open_trips = db.query(Trip).filter(
            Trip.is_closed == False
        ).all()

        for trip in open_trips:
            # Get latest telemetry for this trip
            latest = db.query(TelemetryRaw).filter(
                TelemetryRaw.session_id == trip.session_id
            ).order_by(desc(TelemetryRaw.timestamp)).first()

            if latest and latest.timestamp < cutoff_time:
                logger.info(f"Closing stale trip {trip.id} (session: {trip.session_id})")
                finalize_trip(db, trip)

        db.commit()
    except Exception as e:
        logger.error(f"Error closing stale trips: {e}")
        db.rollback()
    finally:
        Session.remove()


def finalize_trip(db, trip: Trip):
    """
    Finalize a trip by calculating statistics.

    Args:
        db: Database session
        trip: Trip to finalize
    """
    # Get all telemetry for this trip
    telemetry = db.query(TelemetryRaw).filter(
        TelemetryRaw.session_id == trip.session_id
    ).order_by(TelemetryRaw.timestamp).all()

    if not telemetry:
        trip.is_closed = True
        return

    # Convert to dicts for processing
    points = [t.to_dict() for t in telemetry]

    # Set end time and odometer
    trip.end_time = telemetry[-1].timestamp
    trip.end_odometer = telemetry[-1].odometer_miles

    if trip.start_odometer and trip.end_odometer:
        trip.distance_miles = trip.end_odometer - trip.start_odometer

    # Calculate average temperature
    trip.ambient_temp_avg_f = calculate_average_temp(points)

    # Detect gas mode entry
    gas_entry = detect_gas_mode_entry(points)

    if gas_entry:
        trip.gas_mode_entered = True
        trip.gas_mode_entry_time = gas_entry.get('timestamp')
        trip.soc_at_gas_transition = gas_entry.get('state_of_charge')
        trip.fuel_level_at_gas_entry = gas_entry.get('fuel_level_percent')
        trip.fuel_level_at_end = telemetry[-1].fuel_level_percent

        # Calculate electric and gas miles
        if gas_entry.get('odometer_miles') and trip.start_odometer:
            trip.electric_miles, trip.gas_miles = calculate_electric_miles(
                gas_entry.get('odometer_miles'),
                trip.start_odometer,
                trip.end_odometer or gas_entry.get('odometer_miles')
            )

        # Calculate gas MPG
        if trip.gas_miles and trip.gas_miles >= 1.0:
            trip.gas_mpg = calculate_gas_mpg(
                gas_entry.get('odometer_miles'),
                trip.end_odometer,
                trip.fuel_level_at_gas_entry,
                trip.fuel_level_at_end
            )

            if trip.gas_mpg and trip.fuel_level_at_gas_entry and trip.fuel_level_at_end:
                trip.fuel_used_gallons = (
                    (trip.fuel_level_at_gas_entry - trip.fuel_level_at_end) / 100
                    * Config.TANK_CAPACITY_GALLONS
                )

        # Record SOC transition
        if trip.soc_at_gas_transition:
            soc_transition = SocTransition(
                trip_id=trip.id,
                timestamp=trip.gas_mode_entry_time,
                soc_at_transition=trip.soc_at_gas_transition,
                ambient_temp_f=gas_entry.get('ambient_temp_f'),
                odometer_miles=gas_entry.get('odometer_miles')
            )
            db.add(soc_transition)
    else:
        # Entire trip was electric
        if trip.start_odometer and trip.end_odometer:
            trip.electric_miles = trip.distance_miles

    trip.is_closed = True
    logger.info(
        f"Trip {trip.id} finalized: {trip.distance_miles:.1f} mi "
        f"(electric: {trip.electric_miles or 0:.1f}, gas: {trip.gas_miles or 0:.1f}, "
        f"MPG: {trip.gas_mpg or 'N/A'})"
    )


def check_refuel_events():
    """Check for refueling events based on fuel level jumps."""
    db = get_db()
    try:
        # Get recent telemetry ordered by timestamp
        recent = db.query(TelemetryRaw).filter(
            TelemetryRaw.fuel_level_percent.isnot(None)
        ).order_by(desc(TelemetryRaw.timestamp)).limit(100).all()

        if len(recent) < 2:
            return

        # Check for fuel level jumps
        for i in range(len(recent) - 1):
            current = recent[i]
            previous = recent[i + 1]

            if detect_refuel_event(
                current.fuel_level_percent,
                previous.fuel_level_percent
            ):
                # Check if we already logged this refuel
                existing = db.query(FuelEvent).filter(
                    FuelEvent.timestamp >= previous.timestamp,
                    FuelEvent.timestamp <= current.timestamp
                ).first()

                if not existing:
                    fuel_event = FuelEvent(
                        timestamp=current.timestamp,
                        odometer_miles=current.odometer_miles,
                        fuel_level_before=previous.fuel_level_percent,
                        fuel_level_after=current.fuel_level_percent,
                        gallons_added=(
                            (current.fuel_level_percent - previous.fuel_level_percent)
                            / 100 * Config.TANK_CAPACITY_GALLONS
                        )
                    )
                    db.add(fuel_event)
                    logger.info(
                        f"Refuel detected: {fuel_event.gallons_added:.2f} gal "
                        f"at {fuel_event.odometer_miles:.1f} mi"
                    )

        db.commit()
    except Exception as e:
        logger.error(f"Error checking refuel events: {e}")
        db.rollback()
    finally:
        Session.remove()


# Initialize scheduler
scheduler = BackgroundScheduler()
scheduler.add_job(close_stale_trips, 'interval', minutes=1)
scheduler.add_job(check_refuel_events, 'interval', minutes=5)
scheduler.start()
atexit.register(lambda: scheduler.shutdown())


# ============================================================================
# Torque Pro Endpoint
# ============================================================================

@app.route('/torque/upload', methods=['POST'])
def torque_upload():
    """
    Receive data from Torque Pro app.

    Torque sends form-encoded POST with dynamic field names.
    Must respond with "OK!" exactly.
    """
    try:
        data = TorqueParser.parse(request.form)
        db = get_db()

        # Create or get trip
        trip = db.query(Trip).filter(
            Trip.session_id == data['session_id']
        ).first()

        if not trip:
            trip = Trip(
                session_id=data['session_id'],
                start_time=data['timestamp'],
                start_odometer=data['odometer_miles'],
                start_soc=data['state_of_charge']
            )
            db.add(trip)
            db.flush()
            logger.info(f"New trip started: {trip.session_id}")

        # Store telemetry
        telemetry = TelemetryRaw(
            session_id=data['session_id'],
            timestamp=data['timestamp'],
            latitude=data['latitude'],
            longitude=data['longitude'],
            speed_mph=data['speed_mph'],
            engine_rpm=data['engine_rpm'],
            throttle_position=data['throttle_position'],
            coolant_temp_f=data['coolant_temp_f'],
            intake_air_temp_f=data['intake_air_temp_f'],
            fuel_level_percent=data['fuel_level_percent'],
            fuel_remaining_gallons=data['fuel_remaining_gallons'],
            state_of_charge=data['state_of_charge'],
            battery_voltage=data['battery_voltage'],
            ambient_temp_f=data['ambient_temp_f'],
            odometer_miles=data['odometer_miles'],
            raw_data=data['raw_data']
        )
        db.add(telemetry)
        db.commit()

        return "OK!"

    except Exception as e:
        logger.error(f"Error processing Torque upload: {e}")
        return "OK!"  # Still return OK to avoid Torque retries


# ============================================================================
# API Endpoints
# ============================================================================

@app.route('/api/trips', methods=['GET'])
def get_trips():
    """
    Get trip list with summary statistics.

    Query params:
        start_date: Filter trips after this date (ISO format)
        end_date: Filter trips before this date (ISO format)
        gas_only: If true, only return trips with gas usage
    """
    db = get_db()

    query = db.query(Trip).filter(Trip.is_closed == True)

    # Apply filters
    start_date = request.args.get('start_date')
    if start_date:
        query = query.filter(Trip.start_time >= start_date)

    end_date = request.args.get('end_date')
    if end_date:
        query = query.filter(Trip.start_time <= end_date)

    gas_only = request.args.get('gas_only', '').lower() == 'true'
    if gas_only:
        query = query.filter(Trip.gas_mode_entered == True)

    trips = query.order_by(desc(Trip.start_time)).limit(100).all()

    return jsonify([t.to_dict() for t in trips])


@app.route('/api/trips/<int:trip_id>', methods=['GET'])
def get_trip_detail(trip_id):
    """Get detailed trip data including telemetry points."""
    db = get_db()

    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if not trip:
        return jsonify({'error': 'Trip not found'}), 404

    # Get telemetry for this trip
    telemetry = db.query(TelemetryRaw).filter(
        TelemetryRaw.session_id == trip.session_id
    ).order_by(TelemetryRaw.timestamp).all()

    return jsonify({
        'trip': trip.to_dict(),
        'telemetry': [t.to_dict() for t in telemetry]
    })


@app.route('/api/efficiency/summary', methods=['GET'])
def get_efficiency_summary():
    """
    Get efficiency statistics.

    Returns:
        - Lifetime gas MPG average
        - Last 30 days gas MPG average
        - Current tank MPG (since last fill)
        - Total miles tracked
    """
    db = get_db()

    # Lifetime gas MPG
    lifetime_stats = db.query(
        func.sum(Trip.gas_miles).label('total_gas_miles'),
        func.sum(Trip.fuel_used_gallons).label('total_fuel')
    ).filter(
        Trip.gas_mode_entered == True,
        Trip.gas_mpg.isnot(None)
    ).first()

    lifetime_mpg = None
    if lifetime_stats.total_gas_miles and lifetime_stats.total_fuel:
        lifetime_mpg = round(lifetime_stats.total_gas_miles / lifetime_stats.total_fuel, 1)

    # Last 30 days
    thirty_days_ago = datetime.now(timezone.utc) - timedelta(days=30)
    recent_stats = db.query(
        func.sum(Trip.gas_miles).label('total_gas_miles'),
        func.sum(Trip.fuel_used_gallons).label('total_fuel')
    ).filter(
        Trip.start_time >= thirty_days_ago,
        Trip.gas_mode_entered == True,
        Trip.gas_mpg.isnot(None)
    ).first()

    recent_mpg = None
    if recent_stats.total_gas_miles and recent_stats.total_fuel:
        recent_mpg = round(recent_stats.total_gas_miles / recent_stats.total_fuel, 1)

    # Current tank (since last refuel)
    last_refuel = db.query(FuelEvent).order_by(desc(FuelEvent.timestamp)).first()

    current_tank_mpg = None
    current_tank_miles = None
    if last_refuel:
        tank_trips = db.query(
            func.sum(Trip.gas_miles).label('miles'),
            func.sum(Trip.fuel_used_gallons).label('fuel')
        ).filter(
            Trip.start_time >= last_refuel.timestamp,
            Trip.gas_mode_entered == True,
            Trip.gas_mpg.isnot(None)
        ).first()

        if tank_trips.miles and tank_trips.fuel:
            current_tank_mpg = round(tank_trips.miles / tank_trips.fuel, 1)
            current_tank_miles = round(tank_trips.miles, 1)

    # Total miles tracked
    total_miles = db.query(func.sum(Trip.distance_miles)).filter(
        Trip.is_closed == True
    ).scalar() or 0

    return jsonify({
        'lifetime_gas_mpg': lifetime_mpg,
        'lifetime_gas_miles': round(lifetime_stats.total_gas_miles or 0, 1),
        'recent_30d_mpg': recent_mpg,
        'current_tank_mpg': current_tank_mpg,
        'current_tank_miles': current_tank_miles,
        'total_miles_tracked': round(total_miles, 1),
    })


@app.route('/api/soc/analysis', methods=['GET'])
def get_soc_analysis():
    """
    Get SOC floor analysis.

    Returns:
        - Average SOC at gas transition
        - SOC transition histogram
        - Trend over time
        - Temperature correlation
    """
    db = get_db()

    transitions = db.query(SocTransition).order_by(
        SocTransition.timestamp
    ).all()

    transition_dicts = [t.to_dict() for t in transitions]
    analysis = analyze_soc_floor(transition_dicts)

    # Add recent trends (last 10 vs first 10)
    if len(transition_dicts) >= 20:
        first_10 = [t['soc_at_transition'] for t in transition_dicts[:10]]
        last_10 = [t['soc_at_transition'] for t in transition_dicts[-10:]]

        import statistics
        analysis['trend'] = {
            'early_avg': round(statistics.mean(first_10), 1),
            'recent_avg': round(statistics.mean(last_10), 1),
            'direction': 'increasing' if statistics.mean(last_10) > statistics.mean(first_10) else 'decreasing'
        }
    else:
        analysis['trend'] = None

    return jsonify(analysis)


@app.route('/api/fuel/history', methods=['GET'])
def get_fuel_history():
    """Get fuel event history for tank-by-tank analysis."""
    db = get_db()

    events = db.query(FuelEvent).order_by(
        desc(FuelEvent.timestamp)
    ).limit(50).all()

    return jsonify([e.to_dict() for e in events])


@app.route('/api/fuel/add', methods=['POST'])
def add_fuel_event():
    """
    Manually add a fuel event.

    Request body:
        timestamp: ISO datetime
        odometer_miles: Current odometer
        gallons_added: Gallons added
        price_per_gallon: Optional price per gallon
        total_cost: Optional total cost
        notes: Optional notes
    """
    db = get_db()

    data = request.get_json()
    if not data:
        return jsonify({'error': 'No data provided'}), 400

    try:
        timestamp = datetime.fromisoformat(data.get('timestamp', ''))
    except (ValueError, TypeError):
        timestamp = datetime.now(timezone.utc)

    fuel_event = FuelEvent(
        timestamp=timestamp,
        odometer_miles=data.get('odometer_miles'),
        gallons_added=data.get('gallons_added'),
        fuel_level_before=data.get('fuel_level_before'),
        fuel_level_after=data.get('fuel_level_after'),
        price_per_gallon=data.get('price_per_gallon'),
        total_cost=data.get('total_cost'),
        notes=data.get('notes')
    )
    db.add(fuel_event)
    db.commit()

    return jsonify(fuel_event.to_dict()), 201


@app.route('/api/mpg/trend', methods=['GET'])
def get_mpg_trend():
    """
    Get MPG trend data for charting.

    Query params:
        days: Number of days to include (default 30)
    """
    db = get_db()

    try:
        days = int(request.args.get('days', 30))
    except (ValueError, TypeError):
        days = 30
    start_date = datetime.now(timezone.utc) - timedelta(days=days)

    trips = db.query(Trip).filter(
        Trip.start_time >= start_date,
        Trip.gas_mode_entered == True,
        Trip.gas_mpg.isnot(None)
    ).order_by(Trip.start_time).all()

    return jsonify([{
        'date': t.start_time.isoformat(),
        'mpg': t.gas_mpg,
        'gas_miles': t.gas_miles,
        'ambient_temp': t.ambient_temp_avg_f
    } for t in trips])


@app.route('/api/status', methods=['GET'])
def get_status():
    """Get system status and last sync time."""
    db = get_db()

    last_telemetry = db.query(TelemetryRaw).order_by(
        desc(TelemetryRaw.timestamp)
    ).first()

    active_trip = db.query(Trip).filter(
        Trip.is_closed == False
    ).first()

    return jsonify({
        'status': 'online',
        'last_sync': last_telemetry.timestamp.isoformat() if last_telemetry else None,
        'active_trip': active_trip.to_dict() if active_trip else None,
        'database': 'connected'
    })


# ============================================================================
# Export Endpoints
# ============================================================================

@app.route('/api/export/trips', methods=['GET'])
def export_trips():
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

    query = db.query(Trip).filter(Trip.is_closed == True)

    # Apply filters
    start_date = request.args.get('start_date')
    if start_date:
        query = query.filter(Trip.start_time >= start_date)

    end_date = request.args.get('end_date')
    if end_date:
        query = query.filter(Trip.start_time <= end_date)

    gas_only = request.args.get('gas_only', '').lower() == 'true'
    if gas_only:
        query = query.filter(Trip.gas_mode_entered == True)

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


@app.route('/api/export/fuel', methods=['GET'])
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


@app.route('/api/export/all', methods=['GET'])
def export_all():
    """
    Export all data as JSON for backup.

    Returns trips, fuel events, SOC transitions, and summary stats.
    """
    db = get_db()

    trips = db.query(Trip).order_by(desc(Trip.start_time)).all()
    fuel_events = db.query(FuelEvent).order_by(desc(FuelEvent.timestamp)).all()
    soc_transitions = db.query(SocTransition).order_by(SocTransition.timestamp).all()

    return jsonify({
        'exported_at': datetime.now(timezone.utc).isoformat(),
        'trips': [t.to_dict() for t in trips],
        'fuel_events': [e.to_dict() for e in fuel_events],
        'soc_transitions': [s.to_dict() for s in soc_transitions],
        'summary': {
            'total_trips': len(trips),
            'total_fuel_events': len(fuel_events),
            'total_soc_transitions': len(soc_transitions)
        }
    })


# ============================================================================
# Trip Management Endpoints
# ============================================================================

@app.route('/api/trips/<int:trip_id>', methods=['DELETE'])
def delete_trip(trip_id):
    """Delete a trip and its associated data."""
    db = get_db()

    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if not trip:
        return jsonify({'error': 'Trip not found'}), 404

    # Delete associated SOC transitions
    db.query(SocTransition).filter(SocTransition.trip_id == trip_id).delete()

    # Delete associated telemetry
    db.query(TelemetryRaw).filter(TelemetryRaw.session_id == trip.session_id).delete()

    # Delete the trip
    db.delete(trip)
    db.commit()

    logger.info(f"Deleted trip {trip_id}")
    return jsonify({'message': f'Trip {trip_id} deleted successfully'})


@app.route('/api/trips/<int:trip_id>', methods=['PATCH'])
def update_trip(trip_id):
    """
    Update trip fields (for manual corrections).

    Allowed fields:
        - gas_mpg: Override calculated MPG
        - gas_miles: Override gas miles
        - electric_miles: Override electric miles
        - fuel_used_gallons: Override fuel used
        - notes: Add notes to trip
    """
    db = get_db()

    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if not trip:
        return jsonify({'error': 'Trip not found'}), 404

    data = request.get_json()
    if not data:
        return jsonify({'error': 'No data provided'}), 400

    # Only allow specific fields to be updated
    allowed_fields = ['gas_mpg', 'gas_miles', 'electric_miles', 'fuel_used_gallons']

    for field in allowed_fields:
        if field in data:
            setattr(trip, field, data[field])

    db.commit()

    logger.info(f"Updated trip {trip_id}: {data}")
    return jsonify(trip.to_dict())


# ============================================================================
# Fuel Event Management Endpoints
# ============================================================================

@app.route('/api/fuel/<int:fuel_id>', methods=['DELETE'])
def delete_fuel_event(fuel_id):
    """Delete a fuel event."""
    db = get_db()

    event = db.query(FuelEvent).filter(FuelEvent.id == fuel_id).first()
    if not event:
        return jsonify({'error': 'Fuel event not found'}), 404

    db.delete(event)
    db.commit()

    logger.info(f"Deleted fuel event {fuel_id}")
    return jsonify({'message': f'Fuel event {fuel_id} deleted successfully'})


@app.route('/api/fuel/<int:fuel_id>', methods=['PATCH'])
def update_fuel_event(fuel_id):
    """
    Update a fuel event.

    Allowed fields:
        - odometer_miles
        - gallons_added
        - price_per_gallon
        - total_cost
        - notes
    """
    db = get_db()

    event = db.query(FuelEvent).filter(FuelEvent.id == fuel_id).first()
    if not event:
        return jsonify({'error': 'Fuel event not found'}), 404

    data = request.get_json()
    if not data:
        return jsonify({'error': 'No data provided'}), 400

    allowed_fields = ['odometer_miles', 'gallons_added', 'price_per_gallon', 'total_cost', 'notes']

    for field in allowed_fields:
        if field in data:
            setattr(event, field, data[field])

    db.commit()

    logger.info(f"Updated fuel event {fuel_id}: {data}")
    return jsonify(event.to_dict())


# ============================================================================
# Dashboard
# ============================================================================

@app.route('/')
def dashboard():
    """Serve the dashboard HTML."""
    return render_template('index.html')


# ============================================================================
# Main
# ============================================================================

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8080, debug=Config.DEBUG)
