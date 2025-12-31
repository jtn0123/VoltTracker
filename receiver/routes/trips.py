"""
Trips routes for VoltTracker.

Handles trip CRUD operations, efficiency statistics, and analysis.
"""

import logging
import statistics
from datetime import timedelta
from typing import Union, Tuple
from flask import Blueprint, request, jsonify, Response
from sqlalchemy import desc

from config import Config
from database import get_db
from models import Trip, TelemetryRaw, FuelEvent, SocTransition
from utils import analyze_soc_floor, utc_now, normalize_datetime

logger = logging.getLogger(__name__)

trips_bp = Blueprint('trips', __name__)


@trips_bp.route('/trips', methods=['GET'])
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


@trips_bp.route('/trips/<int:trip_id>', methods=['GET'])
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


@trips_bp.route('/trips/<int:trip_id>', methods=['DELETE'])
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


@trips_bp.route('/trips/<int:trip_id>', methods=['PATCH'])
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


@trips_bp.route('/efficiency/summary', methods=['GET'])
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


@trips_bp.route('/soc/analysis', methods=['GET'])
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

        analysis['trend'] = {
            'early_avg': round(statistics.mean(first_10), 1),
            'recent_avg': round(statistics.mean(last_10), 1),
            'direction': 'increasing' if statistics.mean(last_10) > statistics.mean(first_10) else 'decreasing'
        }
    else:
        analysis['trend'] = None

    return jsonify(analysis)


@trips_bp.route('/mpg/trend', methods=['GET'])
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
    start_date = utc_now() - timedelta(days=days)

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
