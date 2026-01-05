"""
Routes module for VoltTracker Flask blueprints.

This module contains Flask blueprints that handle different areas of the API.
"""

from routes.analytics import analytics_bp
from routes.battery import battery_bp
from routes.charging import charging_bp
from routes.dashboard import dashboard_bp
from routes.export import export_bp
from routes.fuel import fuel_bp
from routes.telemetry import telemetry_bp
from routes.trips import trips_bp

__all__ = [
    "dashboard_bp",
    "trips_bp",
    "fuel_bp",
    "charging_bp",
    "battery_bp",
    "telemetry_bp",
    "export_bp",
    "analytics_bp",
]


def register_blueprints(app):
    """Register all blueprints with the Flask app."""
    app.register_blueprint(dashboard_bp)
    app.register_blueprint(telemetry_bp)
    app.register_blueprint(trips_bp, url_prefix="/api")
    app.register_blueprint(fuel_bp, url_prefix="/api")
    app.register_blueprint(charging_bp, url_prefix="/api")
    app.register_blueprint(battery_bp, url_prefix="/api")
    app.register_blueprint(export_bp, url_prefix="/api")
    app.register_blueprint(analytics_bp)
