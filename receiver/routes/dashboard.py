"""
Dashboard routes for VoltTracker.

Handles the main dashboard page and status endpoints.
"""

from flask import Blueprint, render_template, jsonify, Response
from sqlalchemy import desc

from models import TelemetryRaw, Trip

dashboard_bp = Blueprint('dashboard', __name__)


def get_db():
    """Import get_db from app to avoid circular imports."""
    from app import get_db as app_get_db
    return app_get_db()


def get_auth():
    """Import auth from app to avoid circular imports."""
    from app import auth
    return auth


@dashboard_bp.route('/')
def dashboard():
    """Serve the dashboard HTML (requires authentication if configured)."""
    auth = get_auth()

    @auth.login_required
    def protected_dashboard():
        return render_template('index.html')

    return protected_dashboard()


@dashboard_bp.route('/api/status', methods=['GET'])
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
