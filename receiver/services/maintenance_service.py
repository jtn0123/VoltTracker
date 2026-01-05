"""
Maintenance Tracking Service

Calculate engine hours, predict maintenance due dates for Volt Gen 2.
"""

import logging
from datetime import datetime, timedelta
from typing import Dict, Optional

from models import MaintenanceRecord, TelemetryRaw, Trip
from sqlalchemy.orm import Session

logger = logging.getLogger(__name__)

# Volt Gen 2 maintenance intervals
MAINTENANCE_INTERVALS = {
    "oil_change": {
        "name": "Engine Oil Change",
        "interval_months": 24,
        "interval_engine_hours": 24,
        "description": "Change every 2 years OR 24 engine hours",
    },
    "tire_rotation": {
        "name": "Tire Rotation",
        "interval_miles": 7500,
        "description": "Rotate every 7,500 miles",
    },
    "cabin_filter": {
        "name": "Cabin Air Filter",
        "interval_months": 24,
        "description": "Replace every 2 years",
    },
    "brake_fluid": {
        "name": "Brake Fluid",
        "interval_months": 60,
        "description": "Replace every 5 years",
    },
    "coolant_engine": {
        "name": "Engine Coolant",
        "interval_months": 60,
        "description": "Replace every 5 years",
    },
    "coolant_battery": {
        "name": "Battery Coolant",
        "interval_months": 60,
        "description": "Replace every 5 years",
    },
    "transmission_fluid": {
        "name": "Transmission Fluid",
        "interval_months": 96,
        "description": "Replace every 8 years",
    },
    "spark_plugs": {
        "name": "Spark Plugs",
        "interval_miles": 97500,
        "description": "Replace every 97,500 miles",
    },
}


def calculate_engine_hours(db: Session, since_date: Optional[datetime] = None) -> float:
    """
    Calculate total engine hours from telemetry.

    Engine hour = 1 hour when engine_rpm > 400
    """
    query = db.query(TelemetryRaw).filter(TelemetryRaw.engine_rpm > 400)

    if since_date:
        query = query.filter(TelemetryRaw.timestamp >= since_date)

    telemetry = query.order_by(TelemetryRaw.timestamp).all()

    if len(telemetry) < 2:
        return 0.0

    total_hours = 0.0
    for i in range(1, len(telemetry)):
        duration_seconds = (telemetry[i].timestamp - telemetry[i - 1].timestamp).total_seconds()

        # Only count if engine was running and duration is reasonable
        if telemetry[i].engine_rpm > 400 and duration_seconds < 600:  # < 10 min
            total_hours += duration_seconds / 3600.0

    return total_hours


def get_current_odometer(db: Session) -> float:
    """Get current odometer reading from latest trip."""
    latest_trip = db.query(Trip).filter(Trip.odometer_miles.isnot(None)).order_by(Trip.start_time.desc()).first()

    return latest_trip.odometer_miles if latest_trip else 0.0


def calculate_next_due(
    maintenance_type: str,
    last_service_date: datetime,
    last_service_miles: float,
    current_miles: float,
    engine_hours: float,
) -> Dict:
    """
    Calculate when maintenance is next due.
    """
    interval = MAINTENANCE_INTERVALS.get(maintenance_type)
    if not interval:
        return {}

    result = {"due_by": [], "days_remaining": None, "miles_remaining": None}

    # Time-based interval
    if "interval_months" in interval:
        next_due_date = last_service_date + timedelta(days=interval["interval_months"] * 30)
        days_remaining = (next_due_date - datetime.utcnow()).days
        result["due_by"].append(f"{abs(days_remaining)} days")
        result["days_remaining"] = days_remaining
        result["next_due_date"] = next_due_date.isoformat()

    # Mileage-based interval
    if "interval_miles" in interval:
        next_due_miles = last_service_miles + interval["interval_miles"]
        miles_remaining = next_due_miles - current_miles
        result["due_by"].append(f"{int(abs(miles_remaining))} miles")
        result["miles_remaining"] = miles_remaining
        result["next_due_miles"] = next_due_miles

    # Engine hours-based interval (oil changes)
    if "interval_engine_hours" in interval:
        result["due_by"].append(f"{interval['interval_engine_hours']} engine hours")
        result["interval_engine_hours"] = interval["interval_engine_hours"]

    # Determine if overdue
    overdue = False
    if result.get("days_remaining") and result["days_remaining"] < 0:
        overdue = True
    if result.get("miles_remaining") and result["miles_remaining"] < 0:
        overdue = True

    result["overdue"] = overdue
    result["status"] = (
        "overdue"
        if overdue
        else "upcoming"
        if (result.get("days_remaining", 999) < 30 or result.get("miles_remaining", 999) < 500)
        else "ok"
    )

    return result


def get_maintenance_summary(db: Session) -> Dict:
    """
    Get summary of all maintenance items and their status.
    """
    current_miles = get_current_odometer(db)
    total_engine_hours = calculate_engine_hours(db)

    # Get all maintenance records
    records = db.query(MaintenanceRecord).all()

    # Get latest service for each type
    latest_services = {}
    for record in records:
        mtype = record.maintenance_type
        if mtype not in latest_services or record.service_date > latest_services[mtype].service_date:
            latest_services[mtype] = record

    # Build summary
    summary = []

    for mtype, interval_config in MAINTENANCE_INTERVALS.items():
        last_service = latest_services.get(mtype)

        if last_service:
            # Calculate next due based on last service
            next_due = calculate_next_due(
                mtype,
                last_service.service_date,
                last_service.odometer_miles or 0,
                current_miles,
                total_engine_hours,
            )

            summary.append(
                {
                    "type": mtype,
                    "name": interval_config["name"],
                    "description": interval_config["description"],
                    "last_service_date": last_service.service_date.isoformat(),
                    "last_service_miles": last_service.odometer_miles,
                    "next_due": next_due,
                    "has_history": True,
                }
            )
        else:
            # Never serviced - recommend initial service
            summary.append(
                {
                    "type": mtype,
                    "name": interval_config["name"],
                    "description": interval_config["description"],
                    "last_service_date": None,
                    "next_due": {"status": "no_history", "due_by": ["Not tracked yet"]},
                    "has_history": False,
                }
            )

    return {
        "current_odometer": current_miles,
        "total_engine_hours": round(total_engine_hours, 1),
        "maintenance_items": summary,
        "overdue_count": sum(1 for item in summary if item["next_due"].get("overdue", False)),
        "upcoming_count": sum(1 for item in summary if item["next_due"].get("status") == "upcoming"),
    }
