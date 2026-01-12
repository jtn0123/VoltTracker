"""
Routes module for VoltTracker Flask blueprints.

This module contains Flask blueprints that handle different areas of the API.
"""

from routes.analytics import analytics_bp
from routes.battery import battery_bp
from routes.bulk_operations import bulk_bp
from routes.charging import charging_bp
from routes.combined_analytics import combined_analytics_bp
from routes.dashboard import dashboard_bp
from routes.elevation_analytics import elevation_analytics_bp
from routes.export import export_bp
from routes.fuel import fuel_bp
from routes.map import map_bp
from routes.statistics import statistics_bp
from routes.telemetry import telemetry_bp
from routes.trips import trips_bp
from routes.weather_analytics import weather_analytics_bp

__all__ = [
    "dashboard_bp",
    "trips_bp",
    "fuel_bp",
    "charging_bp",
    "battery_bp",
    "telemetry_bp",
    "export_bp",
    "bulk_bp",
    "map_bp",
    "analytics_bp",
    "statistics_bp",
    "weather_analytics_bp",
    "elevation_analytics_bp",
    "combined_analytics_bp",
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
    app.register_blueprint(bulk_bp, url_prefix="/api")
    app.register_blueprint(map_bp)  # Map endpoints include /api prefix in route definitions
    app.register_blueprint(statistics_bp, url_prefix="/api")
    app.register_blueprint(analytics_bp)
    app.register_blueprint(weather_analytics_bp)
    app.register_blueprint(elevation_analytics_bp)
    app.register_blueprint(combined_analytics_bp)
