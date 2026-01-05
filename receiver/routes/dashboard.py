"""
Dashboard routes for VoltTracker.

Handles the main dashboard page and status endpoints.
"""

from flask import Blueprint, render_template, jsonify
from sqlalchemy import desc

from models import TelemetryRaw, Trip
from database import get_db

dashboard_bp = Blueprint('dashboard', __name__)


@dashboard_bp.route('/')
def dashboard():
    """Serve the dashboard HTML."""
    # Auth is handled by the @auth.login_required decorator in app.py
    # For blueprint, we need to check if auth is required
    return render_template('index.html')


@dashboard_bp.route('/api/status', methods=['GET'])
def get_status():
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
