"""
Volt Efficiency Tracker - Flask Application

Receives telemetry from Torque Pro and provides API for dashboard.
"""

import logging
import io
import csv
from datetime import datetime, timedelta, timezone
from typing import Union, Tuple
from flask import Flask, request, jsonify, render_template, Response
from flask_caching import Cache
from flask_httpauth import HTTPBasicAuth
from flask_limiter import Limiter
from flask_limiter.util import get_remote_address
from flask_socketio import SocketIO
from werkzeug.security import check_password_hash
from sqlalchemy import func, desc
from sqlalchemy.orm import scoped_session, sessionmaker
from apscheduler.schedulers.background import BackgroundScheduler
import atexit

from config import Config
from models import (
    TelemetryRaw, Trip, FuelEvent, SocTransition, ChargingSession,
    BatteryCellReading, BatteryHealthReading, get_engine
)
from utils import (
    TorqueParser,
    calculate_gas_mpg,
    detect_gas_mode_entry,
    detect_refuel_event,
    calculate_electric_miles,
    calculate_average_temp,
    analyze_soc_floor,
    calculate_electric_kwh,
    calculate_kwh_per_mile,
    detect_charging_session,
    utc_now,
    normalize_datetime,
)
from utils.weather import get_weather_for_location, get_weather_impact_factor


# Configure logging with rotation
def setup_logging():
    """
    Configure logging with rotating file handler and console output.

    Creates logs in ./logs directory with rotation:
    - Max 10MB per file
    - Keep 5 backup files
    - Console output for Docker compatibility
    """
    import os
    from logging.handlers import RotatingFileHandler

    log_level = getattr(logging, Config.LOG_LEVEL)
    log_format = '%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    formatter = logging.Formatter(log_format)

    # Get root logger
    root_logger = logging.getLogger()
    root_logger.setLevel(log_level)

    # Clear existing handlers (avoid duplicates)
    root_logger.handlers.clear()

    # Console handler (always add for Docker/terminal visibility)
    console_handler = logging.StreamHandler()
    console_handler.setLevel(log_level)
    console_handler.setFormatter(formatter)
    root_logger.addHandler(console_handler)

    # File handler with rotation (optional, skip in testing)
    if not os.environ.get('FLASK_TESTING'):
        log_dir = os.path.join(os.path.dirname(__file__), 'logs')
        os.makedirs(log_dir, exist_ok=True)
        log_file = os.path.join(log_dir, 'volttracker.log')

        file_handler = RotatingFileHandler(
            log_file,
            maxBytes=10 * 1024 * 1024,  # 10 MB
            backupCount=5,
            encoding='utf-8'
        )
        file_handler.setLevel(log_level)
        file_handler.setFormatter(formatter)
        root_logger.addHandler(file_handler)


setup_logging()
logger = logging.getLogger(__name__)

# Initialize Flask app
app = Flask(__name__)
app.config.from_object(Config)

# Initialize cache (disabled in testing mode)
cache = Cache()


def init_cache(app):
    """Initialize cache based on environment."""
    import os
    if app.config.get('TESTING') or os.environ.get('FLASK_TESTING'):
        cache.init_app(app, config={'CACHE_TYPE': 'NullCache'})
    else:
        cache.init_app(app, config={
            'CACHE_TYPE': 'SimpleCache',
            'CACHE_DEFAULT_TIMEOUT': Config.CACHE_TIMEOUT_SECONDS
        })


init_cache(app)

# Initialize SocketIO for real-time updates
socketio = SocketIO(
    app,
    cors_allowed_origins=Config.CORS_ALLOWED_ORIGINS,
    async_mode='gevent',
    logger=False,
    engineio_logger=False
)


def emit_telemetry_update(data: dict):
    """Emit real-time telemetry update to all connected clients."""
    socketio.emit('telemetry', {
        'speed': data.get('speed_mph'),
        'rpm': data.get('engine_rpm'),
        'soc': data.get('state_of_charge'),
        'fuel_percent': data.get('fuel_level_percent'),
        'hv_power': data.get('hv_battery_power_kw'),
        'latitude': data.get('latitude'),
        'longitude': data.get('longitude'),
        'odometer': data.get('odometer_miles'),
        'timestamp': datetime.utcnow().isoformat()
    })


# ============================================================================
# Security: Authentication & Rate Limiting
# ============================================================================

# Initialize HTTP Basic Auth for dashboard
auth = HTTPBasicAuth()


@auth.verify_password
def verify_password(username, password):
    """Verify dashboard credentials."""
    # Skip auth if no password is configured (development mode)
    if not Config.DASHBOARD_PASSWORD:
        return username or 'dev'

    if username == Config.DASHBOARD_USER:
        # Compare with hashed password if it looks hashed, otherwise direct compare
        stored_password = Config.DASHBOARD_PASSWORD
        if stored_password.startswith('pbkdf2:') or stored_password.startswith('scrypt:'):
            return username if check_password_hash(stored_password, password) else None
        else:
            return username if password == stored_password else None
    return None


# Initialize rate limiter
limiter = Limiter(
    key_func=get_remote_address,
    app=app,
    default_limits=["200 per day", "50 per hour"] if Config.RATE_LIMIT_ENABLED else [],
    storage_uri="memory://",
    enabled=Config.RATE_LIMIT_ENABLED
)


@app.after_request
def add_security_headers(response):
    """Add security headers to all responses."""
    response.headers['X-Content-Type-Options'] = 'nosniff'
    response.headers['X-Frame-Options'] = 'SAMEORIGIN'
    response.headers['X-XSS-Protection'] = '1; mode=block'
    response.headers['Referrer-Policy'] = 'strict-origin-when-cross-origin'
    response.headers['Permissions-Policy'] = 'geolocation=(), microphone=(), camera=()'
    # Add HSTS in production (when not in debug mode)
    if not app.debug:
        response.headers['Strict-Transport-Security'] = 'max-age=31536000; includeSubDomains'
    return response


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
        cutoff_time = utc_now() - timedelta(seconds=Config.TRIP_TIMEOUT_SECONDS)

        # Find open trips with no recent telemetry
        open_trips = db.query(Trip).filter(
            Trip.is_closed.is_(False)
        ).all()

        for trip in open_trips:
            # Get latest telemetry for this trip
            latest = db.query(TelemetryRaw).filter(
                TelemetryRaw.session_id == trip.session_id
            ).order_by(desc(TelemetryRaw.timestamp)).first()

            if latest:
                # normalize_datetime handles both naive and timezone-aware datetimes
                latest_ts = normalize_datetime(latest.timestamp)

                if latest_ts < cutoff_time:
                    logger.info(f"Closing stale trip {trip.id} (session: {trip.session_id})")
                    finalize_trip(db, trip)

        db.commit()
    except Exception as e:
        logger.error(f"Error closing stale trips: {e}")
        db.rollback()
    finally:
        Session.remove()


# ============================================================================
# Trip Finalization Helpers
# ============================================================================

def _calculate_trip_basics(trip: Trip, telemetry: list) -> None:
    """
    Calculate basic trip metrics: end time, odometer, distance, temperature.

    Args:
        trip: Trip to update
        telemetry: List of TelemetryRaw records (ordered by timestamp)
    """
    trip.end_time = telemetry[-1].timestamp
    trip.end_odometer = telemetry[-1].odometer_miles

    if trip.start_odometer and trip.end_odometer:
        trip.distance_miles = trip.end_odometer - trip.start_odometer

    # Calculate average temperature from telemetry points
    points = [t.to_dict() for t in telemetry]
    trip.ambient_temp_avg_f = calculate_average_temp(points)


def _process_gas_mode(db, trip: Trip, telemetry: list, points: list) -> None:
    """
    Process gas mode entry: detect transition, calculate miles/MPG, record SOC.

    Args:
        db: Database session
        trip: Trip to update
        telemetry: List of TelemetryRaw records
        points: List of telemetry dicts (from to_dict())
    """
    gas_entry = detect_gas_mode_entry(points)

    if gas_entry:
        trip.gas_mode_entered = True

        # Parse and normalize entry timestamp
        entry_time = gas_entry.get('timestamp')
        if isinstance(entry_time, str):
            entry_time = datetime.fromisoformat(entry_time.replace('Z', '+00:00'))
        trip.gas_mode_entry_time = normalize_datetime(entry_time)

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

        # Calculate gas MPG (only if we drove at least 1 mile on gas)
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

        # Record SOC transition for battery health tracking
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
        # Entire trip was electric - all distance is electric miles
        if trip.start_odometer and trip.end_odometer:
            trip.electric_miles = trip.distance_miles


def _calculate_electric_efficiency(trip: Trip, points: list) -> None:
    """
    Calculate electric efficiency metrics: kWh used, kWh per mile.

    Args:
        trip: Trip to update
        points: List of telemetry dicts
    """
    if trip.electric_miles and trip.electric_miles > 0.5:
        trip.electric_kwh_used = calculate_electric_kwh(points)
        if trip.electric_kwh_used:
            trip.kwh_per_mile = calculate_kwh_per_mile(
                trip.electric_kwh_used, trip.electric_miles
            )


def _fetch_trip_weather(trip: Trip, points: list) -> None:
    """
    Fetch weather data for the trip location and time.

    Args:
        trip: Trip to update
        points: List of telemetry dicts (must have GPS data)
    """
    try:
        # Find first point with GPS coordinates
        gps_point = next(
            (p for p in points if p.get('latitude') and p.get('longitude')),
            None
        )
        if gps_point and trip.start_time:
            weather = get_weather_for_location(
                gps_point['latitude'],
                gps_point['longitude'],
                trip.start_time
            )
            if weather:
                trip.weather_temp_f = weather.get('temperature_f')
                trip.weather_precipitation_in = weather.get('precipitation_in')
                trip.weather_wind_mph = weather.get('wind_speed_mph')
                trip.weather_conditions = weather.get('conditions')
                trip.weather_impact_factor = get_weather_impact_factor(weather)
                logger.debug(
                    f"Weather for trip {trip.id}: "
                    f"{weather.get('conditions')}, {weather.get('temperature_f')}°F"
                )
    except Exception as e:
        logger.warning(f"Failed to fetch weather for trip {trip.id}: {e}")


def finalize_trip(db, trip: Trip):
    """
    Finalize a trip by calculating statistics.

    Orchestrates the trip finalization process by calling specialized helpers:
    - _calculate_trip_basics: End time, distance, temperature
    - _process_gas_mode: Gas/electric split, MPG, SOC transition
    - _calculate_electric_efficiency: kWh used, efficiency
    - _fetch_trip_weather: Weather conditions during trip

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

    # Convert to dicts for calculation functions
    points = [t.to_dict() for t in telemetry]

    # Calculate basic trip metrics
    _calculate_trip_basics(trip, telemetry)

    # Process gas mode entry and related calculations
    _process_gas_mode(db, trip, telemetry, points)

    # Calculate electric efficiency
    _calculate_electric_efficiency(trip, points)

    # Fetch weather data
    _fetch_trip_weather(trip, points)

    # Mark trip as complete
    trip.is_closed = True
    logger.info(
        f"Trip {trip.id} finalized: {(trip.distance_miles or 0):.1f} mi "
        f"(electric: {(trip.electric_miles or 0):.1f}, gas: {(trip.gas_miles or 0):.1f}, "
        f"MPG: {trip.gas_mpg or 'N/A'}, kWh/mi: {trip.kwh_per_mile or 'N/A'})"
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


def check_charging_sessions():
    """Detect and track charging sessions from telemetry data."""
    db = get_db()
    try:
        # Get recent telemetry with charger data
        recent = db.query(TelemetryRaw).filter(
            TelemetryRaw.charger_connected.is_(True)
        ).order_by(desc(TelemetryRaw.timestamp)).limit(50).all()

        if not recent:
            # No active charging - check if we need to close any active sessions
            active_session = db.query(ChargingSession).filter(
                ChargingSession.is_complete.is_(False)
            ).first()

            if active_session:
                # Charger disconnected - finalize session
                active_session.end_time = datetime.utcnow()
                active_session.is_complete = True

                # Calculate kWh added from SOC change
                if active_session.start_soc and active_session.end_soc:
                    soc_gained = active_session.end_soc - active_session.start_soc
                    if soc_gained > 0:
                        active_session.kwh_added = (soc_gained / 100) * Config.BATTERY_CAPACITY_KWH

                db.commit()
                logger.info(
                    f"Charging session completed (no charger data): "
                    f"{active_session.kwh_added or 0:.2f} kWh added"
                )
            return

        # Convert to dicts for the detection function
        points = [t.to_dict() for t in recent]
        session_info = detect_charging_session(points)

        if session_info and session_info.get('is_charging'):
            # Check for existing active charging session
            active_session = db.query(ChargingSession).filter(
                ChargingSession.is_complete.is_(False)
            ).order_by(desc(ChargingSession.start_time)).first()

            if not active_session:
                # Create new charging session
                first_point = recent[-1]  # Oldest in the set
                active_session = ChargingSession(
                    start_time=first_point.timestamp,
                    start_soc=session_info.get('start_soc'),
                    latitude=first_point.latitude,
                    longitude=first_point.longitude,
                    charge_type=session_info.get('charge_type', 'L1')
                )
                db.add(active_session)
                logger.info(f"Charging session started: {session_info.get('charge_type')}")

            # Update with latest data
            active_session.end_soc = session_info.get('current_soc')
            active_session.peak_power_kw = session_info.get('peak_power_kw')
            active_session.avg_power_kw = session_info.get('avg_power_kw')

            db.commit()

        else:
            # Check if we need to close an active session
            active_session = db.query(ChargingSession).filter(
                ChargingSession.is_complete.is_(False)
            ).first()

            if active_session:
                # Charger disconnected - finalize session
                active_session.end_time = db.query(func.max(TelemetryRaw.timestamp)).scalar()
                active_session.is_complete = True

                # Calculate kWh added from SOC change
                if active_session.start_soc and active_session.end_soc:
                    soc_gained = active_session.end_soc - active_session.start_soc
                    if soc_gained > 0:
                        active_session.kwh_added = (soc_gained / 100) * Config.BATTERY_CAPACITY_KWH

                db.commit()
                logger.info(
                    f"Charging session completed: {active_session.kwh_added or 0:.2f} kWh added, "
                    f"SOC {active_session.start_soc:.0f}% -> {active_session.end_soc:.0f}%"
                )

    except Exception as e:
        logger.error(f"Error checking charging sessions: {e}")
        db.rollback()
    finally:
        Session.remove()


# Initialize scheduler
scheduler = BackgroundScheduler()
scheduler.add_job(close_stale_trips, 'interval', minutes=1)
scheduler.add_job(check_refuel_events, 'interval', minutes=5)
scheduler.add_job(check_charging_sessions, 'interval', minutes=2)
scheduler.start()
atexit.register(lambda: scheduler.shutdown())


# ============================================================================
# Torque Pro Endpoint
# ============================================================================

@app.route('/torque/upload', methods=['GET', 'POST'])
@app.route('/torque/upload/<token>', methods=['GET', 'POST'])
@limiter.exempt
def torque_upload(token=None):
    """
    Receive data from Torque Pro app.

    Torque sends data as either GET query params or POST form data.
    Must respond with "OK!" exactly.

    URL formats:
        - /torque/upload (no auth, works if TORQUE_API_TOKEN not set)
        - /torque/upload/<token> (token must match TORQUE_API_TOKEN)
    """
    # Validate API token if configured
    if Config.TORQUE_API_TOKEN:
        if token != Config.TORQUE_API_TOKEN:
            logger.warning(f"Invalid Torque API token attempt from {request.remote_addr}")
            return "Unauthorized", 401

    try:
        # Handle both GET (query params) and POST (form data)
        if request.method == 'GET':
            form_data = request.args
        else:
            form_data = request.form
        data = TorqueParser.parse(form_data)
        db = get_db()

        # Create or get trip (handle race condition)
        trip = db.query(Trip).filter(
            Trip.session_id == data['session_id']
        ).first()

        if not trip:
            try:
                trip = Trip(
                    session_id=data['session_id'],
                    start_time=data['timestamp'],
                    start_odometer=data['odometer_miles'],
                    start_soc=data['state_of_charge']
                )
                db.add(trip)
                db.flush()
                logger.info(f"New trip started: {trip.session_id}")
            except Exception as e:
                # Race condition - trip was created by another request
                logger.debug(f"Trip race condition handled for session {data['session_id']}: {e}")
                db.rollback()
                trip = db.query(Trip).filter(
                    Trip.session_id == data['session_id']
                ).first()

        # Update trip start values if they were null initially
        if trip:
            if trip.start_soc is None and data['state_of_charge'] is not None:
                trip.start_soc = data['state_of_charge']
            if trip.start_odometer is None and data['odometer_miles'] is not None:
                trip.start_odometer = data['odometer_miles']

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
            hv_battery_power_kw=data['hv_battery_power_kw'],
            hv_battery_current_a=data['hv_battery_current_a'],
            hv_battery_voltage_v=data['hv_battery_voltage_v'],
            charger_ac_power_kw=data['charger_ac_power_kw'],
            charger_connected=data['charger_connected'],
            raw_data=data['raw_data']
        )
        db.add(telemetry)
        db.commit()

        # Emit real-time update to WebSocket clients
        emit_telemetry_update(data)

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
        page: Page number (default 1)
        per_page: Items per page (default 50, max 100)
    """
    db = get_db()

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

    # Pagination
    try:
        page = max(1, int(request.args.get('page', 1)))
    except (ValueError, TypeError):
        page = 1

    try:
        per_page = min(Config.API_MAX_PER_PAGE, max(1, int(request.args.get('per_page', Config.API_DEFAULT_PER_PAGE))))
    except (ValueError, TypeError):
        per_page = Config.API_DEFAULT_PER_PAGE

    # Get total count for pagination info
    total_count = query.count()

    # Apply pagination
    offset = (page - 1) * per_page
    trips = query.order_by(desc(Trip.start_time)).offset(offset).limit(per_page).all()

    # Return consistent paginated response with metadata
    return jsonify({
        'trips': [t.to_dict() for t in trips],
        'pagination': {
            'page': page,
            'per_page': per_page,
            'total': total_count,
            'pages': (total_count + per_page - 1) // per_page if per_page > 0 else 0
        }
    })


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
@cache.cached(timeout=30)
def get_efficiency_summary():
    """
    Get efficiency statistics.

    Calculates from available trip data, falling back to computing from
    raw values when pre-calculated fields are missing.

    Returns:
        - Lifetime gas MPG average
        - Last 30 days gas MPG average
        - Current tank MPG (since last fill)
        - Total miles tracked
        - Electric stats (miles, kWh, efficiency)
    """
    db = get_db()

    # Get all closed trips for comprehensive stats
    closed_trips = db.query(Trip).filter(Trip.is_closed.is_(True)).all()

    # Calculate totals from whatever data exists
    total_miles = 0.0
    total_electric_miles = 0.0
    total_gas_miles = 0.0
    total_fuel_used = 0.0
    total_kwh_used = 0.0

    for trip in closed_trips:
        if trip.distance_miles:
            total_miles += float(trip.distance_miles)
        if trip.electric_miles:
            total_electric_miles += float(trip.electric_miles)
        if trip.gas_miles:
            total_gas_miles += float(trip.gas_miles)
        if trip.fuel_used_gallons:
            total_fuel_used += float(trip.fuel_used_gallons)
        if trip.electric_kwh_used:
            total_kwh_used += float(trip.electric_kwh_used)

    # Calculate lifetime gas MPG
    lifetime_mpg = None
    if total_gas_miles > 0 and total_fuel_used > 0:
        lifetime_mpg = round(total_gas_miles / total_fuel_used, 1)

    # Calculate average kWh/mile for electric driving
    avg_kwh_per_mile = None
    if total_electric_miles > 0 and total_kwh_used > 0:
        avg_kwh_per_mile = round(total_kwh_used / total_electric_miles, 3)

    # Calculate EV ratio
    ev_ratio = None
    if total_miles > 0:
        ev_ratio = round(total_electric_miles / total_miles * 100, 1)

    # Last 30 days stats
    thirty_days_ago = utc_now() - timedelta(days=30)
    recent_trips = []
    for t in closed_trips:
        if not t.start_time:
            continue
        trip_time = normalize_datetime(t.start_time)
        if trip_time >= thirty_days_ago:
            recent_trips.append(t)

    recent_gas_miles = sum(float(t.gas_miles) for t in recent_trips if t.gas_miles) or 0
    recent_fuel = sum(float(t.fuel_used_gallons) for t in recent_trips if t.fuel_used_gallons) or 0

    recent_mpg = None
    if recent_gas_miles > 0 and recent_fuel > 0:
        recent_mpg = round(recent_gas_miles / recent_fuel, 1)

    # Current tank (since last refuel)
    last_refuel = db.query(FuelEvent).order_by(desc(FuelEvent.timestamp)).first()

    current_tank_mpg = None
    current_tank_miles = None
    if last_refuel:
        refuel_time = normalize_datetime(last_refuel.timestamp)
        tank_trips = []
        for t in closed_trips:
            if not t.start_time:
                continue
            trip_time = normalize_datetime(t.start_time)
            if trip_time >= refuel_time:
                tank_trips.append(t)

        tank_gas_miles = sum(float(t.gas_miles) for t in tank_trips if t.gas_miles) or 0
        tank_fuel = sum(float(t.fuel_used_gallons) for t in tank_trips if t.fuel_used_gallons) or 0

        if tank_gas_miles > 0 and tank_fuel > 0:
            current_tank_mpg = round(tank_gas_miles / tank_fuel, 1)
            current_tank_miles = round(tank_gas_miles, 1)

    return jsonify({
        'lifetime_gas_mpg': lifetime_mpg,
        'lifetime_gas_miles': round(total_gas_miles, 1),
        'recent_30d_mpg': recent_mpg,
        'current_tank_mpg': current_tank_mpg,
        'current_tank_miles': current_tank_miles,
        'total_miles_tracked': round(total_miles, 1),
        'total_electric_miles': round(total_electric_miles, 1),
        'total_kwh_used': round(total_kwh_used, 2),
        'avg_kwh_per_mile': avg_kwh_per_mile,
        'ev_ratio': ev_ratio,
    })


@app.route('/api/soc/analysis', methods=['GET'])
@cache.cached(timeout=60)
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


def validate_fuel_event_data(data):
    """
    Validate fuel event data.

    Returns (is_valid, errors) tuple.
    """
    errors = []

    # Check numeric fields are valid if provided
    numeric_fields = {
        'odometer_miles': (0, 1000000),
        'gallons_added': (0, 20),  # Tank is ~9.3 gal
        'fuel_level_before': (0, 100),
        'fuel_level_after': (0, 100),
        'price_per_gallon': (0, 20),
        'total_cost': (0, 500)
    }

    for field, (min_val, max_val) in numeric_fields.items():
        value = data.get(field)
        if value is not None:
            try:
                num_val = float(value)
                if num_val < min_val or num_val > max_val:
                    errors.append(f'{field} must be between {min_val} and {max_val}')
            except (ValueError, TypeError):
                errors.append(f'{field} must be a valid number')

    return len(errors) == 0, errors


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

    # Validate input data
    is_valid, errors = validate_fuel_event_data(data)
    if not is_valid:
        return jsonify({'error': 'Validation failed', 'details': errors}), 400

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
        Trip.gas_mode_entered.is_(True),
        Trip.gas_mpg.isnot(None)
    ).order_by(Trip.start_time).all()

    return jsonify([{
        'date': t.start_time.isoformat(),
        'mpg': t.gas_mpg,
        'gas_miles': t.gas_miles,
        'ambient_temp': t.ambient_temp_avg_f
    } for t in trips])


@app.route('/api/status', methods=['GET'])
def get_status() -> Response:
    """Get system status and last sync time."""
    db = get_db()

    last_telemetry = db.query(TelemetryRaw).order_by(
        desc(TelemetryRaw.timestamp)
    ).first()

    active_trip = db.query(Trip).filter(
        Trip.is_closed.is_(False)
    ).first()

    return jsonify({
        'status': 'online',
        'last_sync': last_telemetry.timestamp.isoformat() if last_telemetry else None,
        'active_trip': active_trip.to_dict() if active_trip else None,
        'database': 'connected'
    })


def _calculate_trip_stats(
    first: TelemetryRaw | None,
    latest: TelemetryRaw | None,
    trip: Trip | None
) -> dict:
    """
    Calculate real-time trip efficiency statistics.

    Returns stats for display in the live trip card:
    - miles_driven: Distance traveled this trip
    - kwh_used: Electric energy consumed
    - kwh_per_mile: Electric efficiency
    - in_gas_mode: Whether engine is running on gas
    - gas_miles: Miles driven in gas mode
    - gas_mpg: Fuel efficiency if in gas mode
    """
    stats = {
        'miles_driven': None,
        'kwh_used': None,
        'kwh_per_mile': None,
        'in_gas_mode': False,
        'electric_miles': None,
        'gas_miles': None,
        'gas_mpg': None,
        'fuel_used_gallons': None
    }

    if not first or not latest:
        return stats

    # Calculate miles driven from odometer
    if first.odometer_miles and latest.odometer_miles:
        stats['miles_driven'] = float(latest.odometer_miles - first.odometer_miles)

    # Get start SOC (prefer trip's stored value, fall back to first telemetry)
    start_soc = None
    if trip and trip.start_soc:
        start_soc = float(trip.start_soc)
    elif first.state_of_charge:
        start_soc = float(first.state_of_charge)

    current_soc = float(latest.state_of_charge) if latest.state_of_charge else None

    # Calculate kWh used from SOC change
    if start_soc is not None and current_soc is not None:
        soc_change = start_soc - current_soc
        if soc_change >= 0:  # Only count discharge, not regen gains
            stats['kwh_used'] = soc_change / 100.0 * Config.BATTERY_CAPACITY_KWH

    # Detect gas mode: engine running AND low SOC
    current_rpm = float(latest.engine_rpm) if latest.engine_rpm else 0
    in_gas_mode = (
        current_rpm > Config.RPM_THRESHOLD
        and current_soc is not None
        and current_soc < Config.SOC_GAS_THRESHOLD
    )
    stats['in_gas_mode'] = in_gas_mode

    # Calculate kWh/mile for electric portion
    if stats['kwh_used'] and stats['miles_driven'] and stats['miles_driven'] > 0:
        # For simplicity, use total miles for now
        # A more accurate version would track when gas mode started
        stats['kwh_per_mile'] = stats['kwh_used'] / stats['miles_driven']

    # Calculate gas usage if fuel data available
    start_fuel = None
    if first.fuel_level_percent:
        start_fuel = float(first.fuel_level_percent)

    current_fuel = float(latest.fuel_level_percent) if latest.fuel_level_percent else None

    if start_fuel is not None and current_fuel is not None:
        fuel_percent_used = start_fuel - current_fuel
        if fuel_percent_used > 0.5:  # Only count if meaningful fuel was used
            fuel_gallons_used = fuel_percent_used / 100.0 * Config.TANK_CAPACITY_GALLONS
            stats['fuel_used_gallons'] = fuel_gallons_used

            # Estimate gas miles (rough: if fuel used, assume some portion was gas driving)
            if stats['miles_driven'] and stats['miles_driven'] > 0:
                # Calculate gas MPG from fuel consumption
                stats['gas_mpg'] = stats['miles_driven'] / fuel_gallons_used

    return stats


@app.route('/api/telemetry/latest', methods=['GET'])
def get_latest_telemetry() -> Response:
    """Get latest telemetry for real-time dashboard display."""
    db = get_db()

    # Find the most recent telemetry point to identify the active trip
    latest_telemetry = db.query(TelemetryRaw).order_by(
        desc(TelemetryRaw.timestamp)
    ).first()

    if not latest_telemetry:
        return jsonify({'active': False})

    # Check if the latest data is recent (within timeout period)
    cutoff_time = utc_now() - timedelta(seconds=Config.TRIP_TIMEOUT_SECONDS)
    latest_ts = normalize_datetime(latest_telemetry.timestamp)
    if latest_ts < cutoff_time:
        return jsonify({'active': False})

    # Get the trip for this session
    active_trip = db.query(Trip).filter(
        Trip.session_id == latest_telemetry.session_id
    ).first()

    # Get last 10 telemetry points for this session
    recent = db.query(TelemetryRaw).filter(
        TelemetryRaw.session_id == latest_telemetry.session_id
    ).order_by(desc(TelemetryRaw.timestamp)).limit(10).all()

    latest = recent[0] if recent else latest_telemetry

    # Get the first telemetry point for this session to calculate trip stats
    first_telemetry = db.query(TelemetryRaw).filter(
        TelemetryRaw.session_id == latest_telemetry.session_id
    ).order_by(TelemetryRaw.timestamp).first()

    # Calculate trip efficiency stats
    trip_stats = _calculate_trip_stats(first_telemetry, latest, active_trip)

    return jsonify({
        'active': True,
        'session_id': str(latest_telemetry.session_id),
        'start_time': active_trip.start_time.isoformat() if active_trip else None,
        'start_soc': float(active_trip.start_soc) if active_trip and active_trip.start_soc else None,
        'data': {
            'timestamp': latest.timestamp.isoformat(),
            'soc': float(latest.state_of_charge) if latest.state_of_charge else None,
            'fuel_percent': float(latest.fuel_level_percent) if latest.fuel_level_percent else None,
            'speed_mph': float(latest.speed_mph) if latest.speed_mph else None,
            'engine_rpm': float(latest.engine_rpm) if latest.engine_rpm else None,
            'latitude': float(latest.latitude) if latest.latitude else None,
            'longitude': float(latest.longitude) if latest.longitude else None,
            'odometer': float(latest.odometer_miles) if latest.odometer_miles else None,
            # Power flow data
            'hv_battery_power_kw': float(latest.hv_battery_power_kw) if latest.hv_battery_power_kw else None,
            'hv_battery_voltage_v': float(latest.hv_battery_voltage_v) if latest.hv_battery_voltage_v else None,
            'hv_battery_current_a': float(latest.hv_battery_current_a) if latest.hv_battery_current_a else None,
            # Motor/Generator
            'motor_a_rpm': float(latest.motor_a_rpm) if latest.motor_a_rpm else None,
            'motor_b_rpm': float(latest.motor_b_rpm) if latest.motor_b_rpm else None,
            'generator_rpm': float(latest.generator_rpm) if latest.generator_rpm else None,
            'motor_temp_max_f': float(latest.motor_temp_max_f) if latest.motor_temp_max_f else None,
            # Engine
            'engine_running': latest.engine_running if latest.engine_running is not None else (latest.engine_rpm and latest.engine_rpm > 500),
            'engine_oil_temp_f': float(latest.engine_oil_temp_f) if latest.engine_oil_temp_f else None,
            # Battery health
            'battery_capacity_kwh': float(latest.battery_capacity_kwh) if latest.battery_capacity_kwh else None,
            'battery_temp_f': float(latest.battery_temp_f) if latest.battery_temp_f else None,
            # Charging
            'charger_power_kw': float(latest.charger_power_kw) if latest.charger_power_kw else None,
            'charger_connected': latest.charger_connected,
        },
        'trip_stats': trip_stats,
        'point_count': len(recent)
    })


# ============================================================================
# Export Endpoints
# ============================================================================

@app.route('/api/export/trips', methods=['GET'])
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

    Returns trips, fuel events, SOC transitions, charging sessions, and summary stats.
    """
    db = get_db()

    trips = db.query(Trip).order_by(desc(Trip.start_time)).all()
    fuel_events = db.query(FuelEvent).order_by(desc(FuelEvent.timestamp)).all()
    soc_transitions = db.query(SocTransition).order_by(SocTransition.timestamp).all()
    charging_sessions = db.query(ChargingSession).order_by(desc(ChargingSession.start_time)).all()

    return jsonify({
        'exported_at': datetime.now(timezone.utc).isoformat(),
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


@app.route('/api/export/torque-pids', methods=['GET'])
def export_torque_pids():
    """
    Download the Volt PID configuration file for Torque Pro.

    This CSV can be imported into Torque Pro to enable Volt-specific PIDs.
    """
    import os
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


# ============================================================================
# Import Endpoints
# ============================================================================

@app.route('/api/import/csv', methods=['POST'])
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
    except Exception as e:
        logger.error(f"CSV import error: {e}")
        return jsonify({'error': f'Import failed: {str(e)}'}), 500


# ============================================================================
# Trip Management Endpoints
# ============================================================================

@app.route('/api/trips/<int:trip_id>', methods=['DELETE'])
def delete_trip(trip_id: int) -> Union[Response, Tuple[Response, int]]:
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
# Charging Session Endpoints
# ============================================================================

@app.route('/api/charging/history', methods=['GET'])
def get_charging_history():
    """Get charging session history."""
    db = get_db()

    sessions = db.query(ChargingSession).order_by(
        desc(ChargingSession.start_time)
    ).limit(50).all()

    return jsonify([s.to_dict() for s in sessions])


@app.route('/api/charging/add', methods=['POST'])
def add_charging_session():
    """
    Manually add a charging session.

    Request body:
        start_time: ISO datetime (required)
        end_time: ISO datetime
        start_soc: Starting SOC percentage
        end_soc: Ending SOC percentage
        kwh_added: kWh added during session
        charge_type: 'L1', 'L2', or 'DCFC'
        location_name: Location description
        cost: Total cost
        notes: Optional notes
    """
    db = get_db()

    data = request.get_json()
    if not data:
        return jsonify({'error': 'No data provided'}), 400

    try:
        start_time = datetime.fromisoformat(data.get('start_time', ''))
    except (ValueError, TypeError):
        return jsonify({'error': 'Invalid or missing start_time'}), 400

    end_time = None
    if data.get('end_time'):
        try:
            end_time = datetime.fromisoformat(data['end_time'])
        except (ValueError, TypeError):
            pass

    session = ChargingSession(
        start_time=start_time,
        end_time=end_time,
        start_soc=data.get('start_soc'),
        end_soc=data.get('end_soc'),
        kwh_added=data.get('kwh_added'),
        peak_power_kw=data.get('peak_power_kw'),
        avg_power_kw=data.get('avg_power_kw'),
        latitude=data.get('latitude'),
        longitude=data.get('longitude'),
        location_name=data.get('location_name'),
        charge_type=data.get('charge_type'),
        cost=data.get('cost'),
        cost_per_kwh=data.get('cost_per_kwh'),
        notes=data.get('notes'),
        is_complete=end_time is not None,
    )
    db.add(session)
    db.commit()

    return jsonify(session.to_dict()), 201


@app.route('/api/charging/<int:session_id>', methods=['GET'])
def get_charging_session(session_id):
    """Get details of a specific charging session."""
    db = get_db()

    session = db.query(ChargingSession).filter(ChargingSession.id == session_id).first()
    if not session:
        return jsonify({'error': 'Charging session not found'}), 404

    return jsonify(session.to_dict())


@app.route('/api/charging/<int:session_id>', methods=['DELETE'])
def delete_charging_session(session_id):
    """Delete a charging session."""
    db = get_db()

    session = db.query(ChargingSession).filter(ChargingSession.id == session_id).first()
    if not session:
        return jsonify({'error': 'Charging session not found'}), 404

    db.delete(session)
    db.commit()

    logger.info(f"Deleted charging session {session_id}")
    return jsonify({'message': f'Charging session {session_id} deleted successfully'})


@app.route('/api/charging/<int:session_id>', methods=['PATCH'])
def update_charging_session(session_id):
    """Update a charging session."""
    db = get_db()

    session = db.query(ChargingSession).filter(ChargingSession.id == session_id).first()
    if not session:
        return jsonify({'error': 'Charging session not found'}), 404

    data = request.get_json()
    if not data:
        return jsonify({'error': 'No data provided'}), 400

    allowed_fields = [
        'end_time', 'end_soc', 'kwh_added', 'peak_power_kw', 'avg_power_kw',
        'location_name', 'charge_type', 'cost', 'cost_per_kwh', 'notes', 'is_complete'
    ]

    for field in allowed_fields:
        if field in data:
            if field == 'end_time' and data[field]:
                try:
                    setattr(session, field, datetime.fromisoformat(data[field]))
                except (ValueError, TypeError):
                    pass
            else:
                setattr(session, field, data[field])

    db.commit()

    logger.info(f"Updated charging session {session_id}: {data}")
    return jsonify(session.to_dict())


@app.route('/api/charging/summary', methods=['GET'])
def get_charging_summary():
    """Get charging statistics summary with cost analysis."""
    db = get_db()

    sessions = db.query(ChargingSession).filter(
        ChargingSession.is_complete.is_(True)
    ).all()

    # Get trip data for electric miles and EV ratio
    trips = db.query(Trip).filter(Trip.is_closed.is_(True)).all()
    total_miles = sum(t.distance_miles or 0 for t in trips)
    total_electric_miles = sum(t.electric_miles or 0 for t in trips)
    total_gas_miles = sum(t.gas_miles or 0 for t in trips)
    total_fuel_used = sum(t.fuel_used_gallons or 0 for t in trips if t.fuel_used_gallons)

    # Calculate EV ratio
    ev_ratio = None
    if total_miles > 0:
        ev_ratio = round((total_electric_miles / total_miles) * 100, 1)

    # Get configured rates
    electricity_rate = Config.ELECTRICITY_COST_PER_KWH
    gas_rate = Config.GAS_COST_PER_GALLON

    if not sessions:
        return jsonify({
            'total_sessions': 0,
            'total_kwh': 0,
            'total_cost': None,
            'estimated_cost': None,
            'avg_kwh_per_session': None,
            'by_charge_type': {},
            'total_electric_miles': round(total_electric_miles, 1) if total_electric_miles else None,
            'ev_ratio': ev_ratio,
            'l1_sessions': 0,
            'l2_sessions': 0,
            'cost_per_mile_electric': None,
            'cost_per_mile_gas': None,
            'electricity_rate': electricity_rate,
            'gas_rate': gas_rate,
        })

    total_kwh = sum(s.kwh_added or 0 for s in sessions)
    # Sum explicit costs
    explicit_cost = sum(s.cost or 0 for s in sessions if s.cost)
    # Estimate cost for sessions without explicit cost
    estimated_cost = total_kwh * electricity_rate
    # Use explicit if available, otherwise estimated
    total_cost = explicit_cost if explicit_cost > 0 else estimated_cost

    # Calculate cost per mile (electric)
    cost_per_mile_electric = None
    if total_electric_miles > 0 and total_kwh > 0:
        cost_per_mile_electric = round((total_kwh * electricity_rate) / total_electric_miles, 3)

    # Calculate cost per mile (gas)
    cost_per_mile_gas = None
    if total_gas_miles > 0 and total_fuel_used > 0:
        cost_per_mile_gas = round((total_fuel_used * gas_rate) / total_gas_miles, 3)

    # Group by charge type and count L1/L2
    by_type = {}
    l1_count = 0
    l2_count = 0
    for s in sessions:
        ctype = s.charge_type or 'Unknown'
        if ctype not in by_type:
            by_type[ctype] = {'count': 0, 'kwh': 0}
        by_type[ctype]['count'] += 1
        by_type[ctype]['kwh'] += s.kwh_added or 0

        if ctype == 'L1':
            l1_count += 1
        elif ctype == 'L2':
            l2_count += 1

    # Calculate monthly stats (last 30 days)
    # Use naive datetime for comparison since database stores naive datetimes
    month_ago = datetime.utcnow() - timedelta(days=30)
    monthly_sessions = [s for s in sessions if s.start_time and s.start_time.replace(tzinfo=None) >= month_ago]
    monthly_kwh = sum(s.kwh_added or 0 for s in monthly_sessions)
    monthly_cost = monthly_kwh * electricity_rate

    return jsonify({
        'total_sessions': len(sessions),
        'total_kwh': round(total_kwh, 2),
        'total_cost': round(total_cost, 2) if total_cost else None,
        'estimated_cost': round(estimated_cost, 2),
        'has_explicit_costs': explicit_cost > 0,
        'avg_kwh_per_session': round(total_kwh / len(sessions), 2) if sessions else None,
        'by_charge_type': by_type,
        'total_electric_miles': round(total_electric_miles, 1) if total_electric_miles else None,
        'total_gas_miles': round(total_gas_miles, 1) if total_gas_miles else None,
        'ev_ratio': ev_ratio,
        'l1_sessions': l1_count,
        'l2_sessions': l2_count,
        'cost_per_mile_electric': cost_per_mile_electric,
        'cost_per_mile_gas': cost_per_mile_gas,
        'electricity_rate': electricity_rate,
        'gas_rate': gas_rate,
        'monthly_kwh': round(monthly_kwh, 2),
        'monthly_cost': round(monthly_cost, 2),
        'monthly_sessions': len(monthly_sessions),
    })


# ============================================================================
# Battery Health Endpoints
# ============================================================================

@app.route('/api/battery/health', methods=['GET'])
def get_battery_health():
    """
    Get battery health and degradation analysis.

    Returns current capacity, original capacity, percentage remaining,
    and yearly degradation trend based on available data.
    """
    db = get_db()

    # Original Gen 2 Volt battery capacity
    original_capacity = Config.BATTERY_ORIGINAL_CAPACITY_KWH

    # Get battery health readings if any exist
    readings = db.query(BatteryHealthReading).order_by(
        desc(BatteryHealthReading.timestamp)
    ).limit(100).all()

    # Also check telemetry for battery_capacity_kwh data
    telemetry_capacity = db.query(
        func.avg(TelemetryRaw.battery_capacity_kwh).label('avg_capacity'),
        func.max(TelemetryRaw.battery_capacity_kwh).label('max_capacity'),
        func.min(TelemetryRaw.battery_capacity_kwh).label('min_capacity'),
        func.count(TelemetryRaw.battery_capacity_kwh).label('count')
    ).filter(
        TelemetryRaw.battery_capacity_kwh.isnot(None),
        TelemetryRaw.battery_capacity_kwh > 0
    ).first()

    current_capacity = None
    capacity_readings_count = 0
    health_percent = None

    # Prefer dedicated health readings, fall back to telemetry
    if readings:
        # Use most recent normalized reading
        latest_reading = readings[0]
        current_capacity = latest_reading.normalized_capacity_kwh or latest_reading.capacity_kwh
        capacity_readings_count = len(readings)
    elif telemetry_capacity and telemetry_capacity.count and telemetry_capacity.count > 0:
        # Use average from telemetry
        current_capacity = float(telemetry_capacity.avg_capacity)
        capacity_readings_count = telemetry_capacity.count

    if current_capacity:
        health_percent = round((current_capacity / original_capacity) * 100, 1)

    # Calculate trend if we have enough historical data
    yearly_trend = None
    if readings and len(readings) >= 10:
        # Get readings from ~1 year ago and compare
        one_year_ago = datetime.now(timezone.utc) - timedelta(days=365)
        old_readings = [r for r in readings if r.timestamp and r.timestamp < one_year_ago]
        recent_readings = readings[:10]  # Most recent 10

        if old_readings and recent_readings:
            old_avg = sum(
                r.normalized_capacity_kwh or r.capacity_kwh or 0 for r in old_readings
            ) / len(old_readings)
            recent_avg = sum(
                r.normalized_capacity_kwh or r.capacity_kwh or 0 for r in recent_readings
            ) / len(recent_readings)

            if old_avg > 0:
                yearly_change = ((recent_avg - old_avg) / old_avg) * 100
                yearly_trend = round(yearly_change, 2)

    # Determine health status
    health_status = 'unknown'
    if health_percent:
        if health_percent >= 90:
            health_status = 'excellent'
        elif health_percent >= 80:
            health_status = 'good'
        elif health_percent >= 70:
            health_status = 'fair'
        else:
            health_status = 'degraded'

    return jsonify({
        'current_capacity_kwh': round(current_capacity, 2) if current_capacity else None,
        'original_capacity_kwh': original_capacity,
        'health_percent': health_percent,
        'health_status': health_status,
        'yearly_trend_percent': yearly_trend,
        'readings_count': capacity_readings_count,
        'has_data': capacity_readings_count > 0,
        'degradation_warning_threshold': Config.BATTERY_DEGRADATION_WARNING_PERCENT
    })


# ============================================================================
# Battery Cell Voltage Endpoints
# ============================================================================

@app.route('/api/battery/cells', methods=['GET'])
def get_battery_cell_readings():
    """
    Get battery cell voltage readings.

    Query params:
        - limit: Max readings to return (default 10)
        - days: Filter to last N days
    """
    db = get_db()

    limit = request.args.get('limit', 10, type=int)
    days = request.args.get('days', type=int)

    query = db.query(BatteryCellReading).order_by(desc(BatteryCellReading.timestamp))

    if days:
        cutoff = datetime.now(timezone.utc) - timedelta(days=days)
        query = query.filter(BatteryCellReading.timestamp >= cutoff)

    readings = query.limit(min(limit, 100)).all()

    return jsonify({
        'readings': [r.to_dict() for r in readings],
        'count': len(readings)
    })


@app.route('/api/battery/cells/latest', methods=['GET'])
def get_latest_cell_reading():
    """Get the most recent cell voltage reading."""
    db = get_db()

    reading = db.query(BatteryCellReading).order_by(
        desc(BatteryCellReading.timestamp)
    ).first()

    if not reading:
        return jsonify({'reading': None, 'message': 'No cell readings available'})

    return jsonify({'reading': reading.to_dict()})


@app.route('/api/battery/cells/analysis', methods=['GET'])
def get_cell_analysis():
    """
    Get battery cell health analysis.

    Analyzes voltage delta trends, weak cells, and module balance.
    """
    db = get_db()

    days = request.args.get('days', 30, type=int)
    cutoff = datetime.now(timezone.utc) - timedelta(days=days)

    readings = db.query(BatteryCellReading).filter(
        BatteryCellReading.timestamp >= cutoff
    ).order_by(BatteryCellReading.timestamp).all()

    if not readings:
        return jsonify({
            'message': 'No cell readings in the specified period',
            'analysis': None
        })

    # Calculate statistics
    import statistics

    deltas = [r.voltage_delta for r in readings if r.voltage_delta]
    avg_voltages = [r.avg_voltage for r in readings if r.avg_voltage]

    # Find cells that are consistently low or high
    weak_cells = []
    if readings and readings[-1].cell_voltages:
        latest = readings[-1]
        voltages = latest.cell_voltages
        if voltages and latest.avg_voltage:
            threshold = latest.avg_voltage * 0.02  # 2% below average
            for i, v in enumerate(voltages):
                if v and v < (latest.avg_voltage - threshold):
                    weak_cells.append({
                        'cell_index': i + 1,
                        'voltage': v,
                        'deviation': round(v - latest.avg_voltage, 4)
                    })

    analysis = {
        'period_days': days,
        'reading_count': len(readings),
        'avg_voltage_delta': round(statistics.mean(deltas), 4) if deltas else None,
        'max_voltage_delta': round(max(deltas), 4) if deltas else None,
        'min_voltage_delta': round(min(deltas), 4) if deltas else None,
        'avg_cell_voltage': round(statistics.mean(avg_voltages), 4) if avg_voltages else None,
        'weak_cells': weak_cells[:5],  # Top 5 weakest cells
        'health_status': 'good' if deltas and max(deltas) < 0.05 else 'monitor',
    }

    # Module balance analysis
    if readings:
        latest = readings[-1]
        if all([latest.module1_avg, latest.module2_avg, latest.module3_avg]):
            module_avgs = [latest.module1_avg, latest.module2_avg, latest.module3_avg]
            module_delta = max(module_avgs) - min(module_avgs)
            analysis['module_balance'] = {
                'module1_avg': latest.module1_avg,
                'module2_avg': latest.module2_avg,
                'module3_avg': latest.module3_avg,
                'module_delta': round(module_delta, 4),
                'balanced': module_delta < 0.02
            }

    return jsonify({'analysis': analysis})


@app.route('/api/battery/cells/add', methods=['POST'])
def add_cell_reading():
    """
    Add a battery cell voltage reading.

    JSON body:
        - cell_voltages: Array of 96 cell voltages
        - timestamp: ISO timestamp (optional, defaults to now)
        - ambient_temp_f: Ambient temperature (optional)
        - state_of_charge: Current SOC (optional)
        - is_charging: Whether charging (optional)
    """
    db = get_db()
    data = request.get_json()

    if not data or 'cell_voltages' not in data:
        return jsonify({'error': 'cell_voltages array is required'}), 400

    cell_voltages = data['cell_voltages']
    if not isinstance(cell_voltages, list) or len(cell_voltages) == 0:
        return jsonify({'error': 'cell_voltages must be a non-empty array'}), 400

    timestamp_str = data.get('timestamp')
    if timestamp_str:
        try:
            timestamp = datetime.fromisoformat(timestamp_str.replace('Z', '+00:00'))
        except ValueError:
            return jsonify({'error': 'Invalid timestamp format'}), 400
    else:
        timestamp = datetime.now(timezone.utc)

    reading = BatteryCellReading.from_cell_voltages(
        timestamp=timestamp,
        cell_voltages=cell_voltages,
        ambient_temp_f=data.get('ambient_temp_f'),
        state_of_charge=data.get('state_of_charge'),
        is_charging=data.get('is_charging', False)
    )

    if not reading:
        return jsonify({'error': 'Could not create reading from provided data'}), 400

    db.add(reading)
    db.commit()

    logger.info(f"Added cell reading: delta={reading.voltage_delta}V")

    return jsonify({
        'message': 'Cell reading added',
        'reading': reading.to_dict()
    }), 201


# ============================================================================
# API Documentation
# ============================================================================

@app.route('/api/docs', methods=['GET'])
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


# ============================================================================
# Dashboard
# ============================================================================

@app.route('/')
@auth.login_required
def dashboard():
    """Serve the dashboard HTML (requires authentication if configured)."""
    return render_template('index.html')


# ============================================================================
# Main
# ============================================================================

if __name__ == '__main__':
    socketio.run(app, host=Config.FLASK_HOST, port=Config.FLASK_PORT, debug=Config.DEBUG)
