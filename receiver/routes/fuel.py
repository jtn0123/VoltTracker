"""
Fuel routes for VoltTracker.

Handles fuel event CRUD operations and fuel history.
"""

import logging
from datetime import datetime
from flask import Blueprint, request, jsonify
from sqlalchemy import desc

from database import get_db
from models import FuelEvent
from utils import utc_now

logger = logging.getLogger(__name__)

fuel_bp = Blueprint('fuel', __name__)


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


@fuel_bp.route('/fuel/history', methods=['GET'])
def get_fuel_history():
    """Get fuel event history for tank-by-tank analysis."""
    db = get_db()

    events = db.query(FuelEvent).order_by(
        desc(FuelEvent.timestamp)
    ).limit(50).all()

    return jsonify([e.to_dict() for e in events])


@fuel_bp.route('/fuel/add', methods=['POST'])
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
        timestamp = utc_now()

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


@fuel_bp.route('/fuel/<int:fuel_id>', methods=['DELETE'])
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


@fuel_bp.route('/fuel/<int:fuel_id>', methods=['PATCH'])
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
