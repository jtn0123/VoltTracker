"""
Battery routes for VoltTracker.

Handles battery health and cell voltage endpoints.
"""

import logging
import statistics
from datetime import datetime, timedelta

from config import Config
from database import get_db
from flask import Blueprint, jsonify, request
from models import BatteryCellReading, BatteryHealthReading, TelemetryRaw
from sqlalchemy import desc, func
from utils import utc_now

logger = logging.getLogger(__name__)

battery_bp = Blueprint("battery", __name__)


@battery_bp.route("/battery/health", methods=["GET"])
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
    readings = db.query(BatteryHealthReading).order_by(desc(BatteryHealthReading.timestamp)).limit(100).all()

    # Also check telemetry for battery_capacity_kwh data
    telemetry_capacity = (
        db.query(
            func.avg(TelemetryRaw.battery_capacity_kwh).label("avg_capacity"),
            func.max(TelemetryRaw.battery_capacity_kwh).label("max_capacity"),
            func.min(TelemetryRaw.battery_capacity_kwh).label("min_capacity"),
            func.count(TelemetryRaw.battery_capacity_kwh).label("count"),
        )
        .filter(TelemetryRaw.battery_capacity_kwh.isnot(None), TelemetryRaw.battery_capacity_kwh > 0)
        .first()
    )

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
        one_year_ago = utc_now() - timedelta(days=365)
        old_readings = [r for r in readings if r.timestamp and r.timestamp < one_year_ago]
        recent_readings = readings[:10]  # Most recent 10

        if old_readings and recent_readings:
            old_avg = sum(r.normalized_capacity_kwh or r.capacity_kwh or 0 for r in old_readings) / len(old_readings)
            recent_avg = sum(r.normalized_capacity_kwh or r.capacity_kwh or 0 for r in recent_readings) / len(
                recent_readings
            )

            if old_avg > 0:
                yearly_change = ((recent_avg - old_avg) / old_avg) * 100
                yearly_trend = round(yearly_change, 2)

    # Determine health status
    health_status = "unknown"
    if health_percent:
        if health_percent >= 90:
            health_status = "excellent"
        elif health_percent >= 80:
            health_status = "good"
        elif health_percent >= 70:
            health_status = "fair"
        else:
            health_status = "degraded"

    return jsonify(
        {
            "current_capacity_kwh": round(current_capacity, 2) if current_capacity else None,
            "original_capacity_kwh": original_capacity,
            "health_percent": health_percent,
            "health_status": health_status,
            "yearly_trend_percent": yearly_trend,
            "readings_count": capacity_readings_count,
            "has_data": capacity_readings_count > 0,
            "degradation_warning_threshold": Config.BATTERY_DEGRADATION_WARNING_PERCENT,
        }
    )


@battery_bp.route("/battery/cells", methods=["GET"])
def get_battery_cell_readings():
    """
    Get battery cell voltage readings.

    Query params:
        - limit: Max readings to return (default 10)
        - days: Filter to last N days
    """
    db = get_db()

    limit = request.args.get("limit", 10, type=int)
    days = request.args.get("days", type=int)

    query = db.query(BatteryCellReading).order_by(desc(BatteryCellReading.timestamp))

    if days:
        cutoff = utc_now() - timedelta(days=days)
        query = query.filter(BatteryCellReading.timestamp >= cutoff)

    readings = query.limit(min(limit, 100)).all()

    return jsonify({"readings": [r.to_dict() for r in readings], "count": len(readings)})


@battery_bp.route("/battery/cells/latest", methods=["GET"])
def get_latest_cell_reading():
    """Get the most recent cell voltage reading."""
    db = get_db()

    reading = db.query(BatteryCellReading).order_by(desc(BatteryCellReading.timestamp)).first()

    if not reading:
        return jsonify({"reading": None, "message": "No cell readings available"})

    return jsonify({"reading": reading.to_dict()})


@battery_bp.route("/battery/cells/analysis", methods=["GET"])
def get_cell_analysis():
    """
    Get battery cell health analysis.

    Analyzes voltage delta trends, weak cells, and module balance.
    """
    db = get_db()

    days = request.args.get("days", 30, type=int)
    cutoff = utc_now() - timedelta(days=days)

    readings = (
        db.query(BatteryCellReading)
        .filter(BatteryCellReading.timestamp >= cutoff)
        .order_by(BatteryCellReading.timestamp)
        .all()
    )

    if not readings:
        return jsonify({"message": "No cell readings in the specified period", "analysis": None})

    # Calculate statistics
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
                    weak_cells.append(
                        {"cell_index": i + 1, "voltage": v, "deviation": round(v - latest.avg_voltage, 4)}
                    )

    analysis = {
        "period_days": days,
        "reading_count": len(readings),
        "avg_voltage_delta": round(statistics.mean(deltas), 4) if deltas else None,
        "max_voltage_delta": round(max(deltas), 4) if deltas else None,
        "min_voltage_delta": round(min(deltas), 4) if deltas else None,
        "avg_cell_voltage": round(statistics.mean(avg_voltages), 4) if avg_voltages else None,
        "weak_cells": weak_cells[:5],  # Top 5 weakest cells
        "health_status": "good" if deltas and max(deltas) < 0.05 else "monitor",
    }

    # Module balance analysis
    if readings:
        latest = readings[-1]
        if all([latest.module1_avg, latest.module2_avg, latest.module3_avg]):
            module_avgs = [latest.module1_avg, latest.module2_avg, latest.module3_avg]
            module_delta = max(module_avgs) - min(module_avgs)
            analysis["module_balance"] = {
                "module1_avg": latest.module1_avg,
                "module2_avg": latest.module2_avg,
                "module3_avg": latest.module3_avg,
                "module_delta": round(module_delta, 4),
                "balanced": module_delta < 0.02,
            }

    return jsonify({"analysis": analysis})


@battery_bp.route("/battery/cells/add", methods=["POST"])
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

    if not data or "cell_voltages" not in data:
        return jsonify({"error": "cell_voltages array is required"}), 400

    cell_voltages = data["cell_voltages"]
    if not isinstance(cell_voltages, list) or len(cell_voltages) == 0:
        return jsonify({"error": "cell_voltages must be a non-empty array"}), 400

    # Validate cell count (Chevy Volt Gen 2 has 96 cells)
    expected_cell_count = 96
    if len(cell_voltages) != expected_cell_count:
        return jsonify({"error": f"Expected {expected_cell_count} cell voltages, got {len(cell_voltages)}"}), 400

    # Validate voltage range (Li-ion cells typically 3.0V-4.2V)
    min_valid_voltage = 2.5  # Allow some margin for safety
    max_valid_voltage = 4.5
    for i, voltage in enumerate(cell_voltages):
        if voltage is None:
            continue
        if not isinstance(voltage, (int, float)):
            return jsonify({"error": f"Cell {i + 1} voltage must be a number"}), 400
        if voltage < min_valid_voltage or voltage > max_valid_voltage:
            return (
                jsonify(
                    {
                        "error": f"Cell {i + 1} voltage {voltage}V outside range "
                        f"({min_valid_voltage}-{max_valid_voltage}V)"
                    }
                ),
                400,
            )

    timestamp_str = data.get("timestamp")
    if timestamp_str:
        try:
            timestamp = datetime.fromisoformat(timestamp_str.replace("Z", "+00:00"))
        except ValueError:
            return jsonify({"error": "Invalid timestamp format"}), 400
    else:
        timestamp = utc_now()

    reading = BatteryCellReading.from_cell_voltages(
        timestamp=timestamp,
        cell_voltages=cell_voltages,
        ambient_temp_f=data.get("ambient_temp_f"),
        state_of_charge=data.get("state_of_charge"),
        is_charging=data.get("is_charging", False),
    )

    if not reading:
        return jsonify({"error": "Could not create reading from provided data"}), 400

    db.add(reading)
    db.commit()

    logger.info(f"Added cell reading: delta={reading.voltage_delta}V")

    return jsonify({"message": "Cell reading added", "reading": reading.to_dict()}), 201
