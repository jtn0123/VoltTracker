"""
Dashboard routes for VoltTracker.

Handles the main dashboard page and status endpoints.
"""

import os

from database import get_db
from extensions import limiter
from config import Config
from flask import Blueprint, current_app, jsonify, render_template, send_from_directory
from models import TelemetryRaw, Trip
from sqlalchemy import desc
from version import APP_VERSION

dashboard_bp = Blueprint("dashboard", __name__)


@dashboard_bp.route("/")
def dashboard():
    """Serve the dashboard HTML."""
    # Auth is handled by the @auth.login_required decorator in app.py
    # For blueprint, we need to check if auth is required
    # S9: The WebSocket token is intentionally passed into the HTML template.
    # This is safe because the dashboard page itself is behind HTTP Basic Auth
    # (require_auth_globally), so only authenticated users can see the token.
    # The token allows the frontend JS to authenticate its Socket.IO connection.
    ws_token = Config.WEBSOCKET_TOKEN or Config.DASHBOARD_PASSWORD or ""
    # APP_VERSION is imported from the dedicated ``version`` module (not ``app``)
    # so that serving this route does not trigger a re-import of ``app.py`` when
    # the server is started via ``python receiver/app.py`` (where ``app`` is
    # ``__main__``). See JTN-482.
    return render_template("index.html", ws_token=ws_token, app_version=APP_VERSION)


@dashboard_bp.route("/map")
def map_view():
    """Serve the GPS track map view HTML."""
    return render_template("map.html")


@dashboard_bp.route("/v2", strict_slashes=False)
def dashboard_v2():
    """Serve the Volt Cluster redesign (Direction D) preview.

    This is the modernized "Volt Cluster" UI handed off from Claude Design.
    It is a self-contained static front-end (React + Babel standalone) living
    under ``static/v2/`` and currently runs on mock data. It is exposed at a
    dedicated route so it can be reviewed alongside the existing dashboard
    without disrupting it.
    """
    v2_dir = os.path.join(current_app.static_folder, "v2")
    return send_from_directory(v2_dir, "index.html")


@dashboard_bp.route("/api/status", methods=["GET"])
@limiter.limit("60 per minute")  # Rate limit status endpoint to prevent abuse
def get_status():
    """Get system status and last sync time."""
    db = get_db()

    last_telemetry = db.query(TelemetryRaw).order_by(desc(TelemetryRaw.timestamp)).first()

    active_trip = db.query(Trip).filter(Trip.is_closed.is_(False)).first()

    return jsonify(
        {
            "status": "online",
            "last_sync": last_telemetry.timestamp.isoformat() if last_telemetry else None,
            "active_trip": active_trip.to_dict() if active_trip else None,
            "database": "connected",
        }
    )
