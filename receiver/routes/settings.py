"""
Settings routes for VoltTracker.

Provides a settings page for configuring application preferences
such as electricity cost rate, gas price, battery capacity, and distance units.
"""

import logging

from database import get_db
from extensions import limiter, RateLimits
from flask import Blueprint, jsonify, render_template, request
from models import Settings

logger = logging.getLogger(__name__)

settings_bp = Blueprint("settings", __name__)

# Default settings with their types and descriptions
SETTING_DEFAULTS = {
    "electricity_cost_rate": {
        "default": "0.12",
        "label": "Electricity Cost ($/kWh)",
        "type": "number",
        "step": "0.01",
        "min": "0",
        "description": "Cost per kWh for charging at home",
    },
    "gas_price": {
        "default": "3.50",
        "label": "Gas Price ($/gallon)",
        "type": "number",
        "step": "0.01",
        "min": "0",
        "description": "Current gas price per gallon",
    },
    "battery_capacity": {
        "default": "18.4",
        "label": "Battery Capacity (kWh)",
        "type": "number",
        "step": "0.1",
        "min": "0",
        "description": "Total battery capacity in kWh",
    },
    "distance_units": {
        "default": "miles",
        "label": "Distance Units",
        "type": "select",
        "options": ["miles", "km"],
        "description": "Preferred distance unit",
    },
}


@settings_bp.route("/settings", methods=["GET"])
def settings_page():
    """Serve the settings page."""
    db = get_db()
    current = {}
    for key, meta in SETTING_DEFAULTS.items():
        row = db.query(Settings).filter_by(key=key).first()
        current[key] = {
            **meta,
            "value": row.value if row else meta["default"],
        }
    return render_template("settings.html", settings=current)


@settings_bp.route("/settings", methods=["POST"])
@limiter.limit(RateLimits.WRITE_MODERATE)
def save_settings():
    """Save settings from form submission or JSON body.

    S2: CSRF protection — only accept JSON requests (Content-Type: application/json).
    Form submissions are rejected to prevent cross-site request forgery.
    """
    # S2: Require JSON content type as CSRF mitigation
    if not request.is_json:
        return jsonify({"error": "Content-Type must be application/json"}), 415

    db = get_db()
    data = request.get_json()

    saved = {}
    for key, meta in SETTING_DEFAULTS.items():
        if key in data:
            value = str(data[key]).strip()
            row = db.query(Settings).filter_by(key=key).first()
            if row:
                row.value = value
            else:
                db.add(Settings(key=key, value=value))
            saved[key] = value

    db.commit()
    logger.info("Settings updated: %s", list(saved.keys()))

    if request.is_json:
        return jsonify({"status": "ok", "saved": saved})

    # For form POST, redirect back to settings page
    from flask import redirect, url_for, flash

    flash("Settings saved successfully.", "success")
    return redirect(url_for("settings.settings_page"))


@settings_bp.route("/api/settings", methods=["GET"])
@limiter.limit(RateLimits.READ_HEAVY)
def get_settings_api():
    """API endpoint to retrieve all settings as JSON."""
    db = get_db()
    result = {}
    for key, meta in SETTING_DEFAULTS.items():
        row = db.query(Settings).filter_by(key=key).first()
        result[key] = row.value if row else meta["default"]
    return jsonify(result)
