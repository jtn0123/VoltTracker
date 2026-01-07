"""
12V Auxiliary Battery Health Service

Analyzes 12V battery voltage trends, detects anomalies, and forecasts replacement timing.
"""

import logging
from datetime import datetime, timedelta
from typing import Dict, List, Optional, Tuple

from models import AuxBatteryEvent, AuxBatteryHealthReading, TelemetryRaw, Trip
from sqlalchemy import and_, desc, func
from sqlalchemy.orm import Session
from utils.timezone import utc_now

logger = logging.getLogger(__name__)


# Voltage thresholds based on GM Volt forum recommendations
VOLTAGE_HEALTHY_REST = 12.4  # Healthy at rest
VOLTAGE_WARNING_REST = 12.0  # Warning threshold at rest
VOLTAGE_CRITICAL_REST = 11.5  # Critical at rest

VOLTAGE_HEALTHY_CHARGING = 13.2  # Healthy when charging
VOLTAGE_WARNING_CHARGING = 12.6  # Warning when charging
VOLTAGE_CRITICAL_CHARGING = 12.0  # Critical when charging

VOLTAGE_OVERCHARGE = 14.8  # Potential alternator/charger issue

# Typical AGM battery lifespan in days (3-5 years)
BATTERY_LIFESPAN_MIN_DAYS = 3 * 365
BATTERY_LIFESPAN_MAX_DAYS = 5 * 365


def get_latest_voltage_reading(db: Session) -> Optional[AuxBatteryHealthReading]:
    """Get the most recent 12V battery voltage reading."""
    return db.query(AuxBatteryHealthReading).order_by(desc(AuxBatteryHealthReading.timestamp)).first()


def get_voltage_history(db: Session, days: int = 30) -> List[AuxBatteryHealthReading]:
    """
    Get 12V battery voltage history for the specified period.

    Args:
        db: Database session
        days: Number of days to look back

    Returns:
        List of voltage readings ordered by timestamp
    """
    cutoff = utc_now() - timedelta(days=days)
    return (
        db.query(AuxBatteryHealthReading)
        .filter(AuxBatteryHealthReading.timestamp >= cutoff)
        .order_by(AuxBatteryHealthReading.timestamp)
        .all()
    )


def get_voltage_at_rest_history(db: Session, days: int = 30) -> List[AuxBatteryHealthReading]:
    """
    Get 12V battery voltage history when at rest (not charging, engine off).

    These readings are most accurate for health assessment.
    """
    cutoff = utc_now() - timedelta(days=days)
    return (
        db.query(AuxBatteryHealthReading)
        .filter(
            and_(
                AuxBatteryHealthReading.timestamp >= cutoff,
                AuxBatteryHealthReading.is_charging == False,  # noqa: E712
                AuxBatteryHealthReading.charger_connected == False,  # noqa: E712
                AuxBatteryHealthReading.engine_running == False,  # noqa: E712
            )
        )
        .order_by(AuxBatteryHealthReading.timestamp)
        .all()
    )


def calculate_battery_health(db: Session) -> Dict:
    """
    Calculate current 12V battery health status.

    Returns health percentage, status, and recommendations.
    """
    latest = get_latest_voltage_reading(db)

    if not latest:
        return {
            "error": "No 12V battery voltage data available",
            "status": "unknown",
        }

    # Get historical voltage trends
    rest_history = get_voltage_at_rest_history(db, days=30)

    # Calculate average at-rest voltage
    avg_rest_voltage = None
    if rest_history:
        avg_rest_voltage = sum(r.voltage_v for r in rest_history) / len(rest_history)

    # Determine health status
    health_status = latest.health_status
    health_percentage = latest.health_percentage

    # Calculate trend
    trend = None
    if len(rest_history) >= 10:
        # Compare first half vs second half
        mid = len(rest_history) // 2
        first_half_avg = sum(r.voltage_v for r in rest_history[:mid]) / mid
        second_half_avg = sum(r.voltage_v for r in rest_history[mid:]) / (len(rest_history) - mid)
        voltage_change = second_half_avg - first_half_avg
        trend = "improving" if voltage_change > 0.1 else "declining" if voltage_change < -0.1 else "stable"

    # Get unresolved events
    unresolved_events = (
        db.query(AuxBatteryEvent)
        .filter(
            and_(
                AuxBatteryEvent.resolved_at.is_(None),
                AuxBatteryEvent.severity.in_(["warning", "critical"]),
            )
        )
        .count()
    )

    # Recommendations
    recommendations = []
    if health_status == "critical":
        recommendations.append("URGENT: Replace 12V battery immediately to avoid vehicle issues")
    elif health_status == "warning":
        recommendations.append("Replace 12V battery soon - voltage is below healthy threshold")
        recommendations.append("Monitor for fault codes and starting issues")
    elif trend == "declining":
        recommendations.append("Voltage trend is declining - consider proactive replacement")

    if unresolved_events > 0:
        recommendations.append(f"{unresolved_events} unresolved battery event(s) detected")

    return {
        "current_voltage": latest.voltage_v,
        "timestamp": latest.timestamp.isoformat(),
        "health_status": health_status,
        "health_percentage": health_percentage,
        "is_charging": latest.is_charging or latest.charger_connected or latest.engine_running,
        "avg_rest_voltage_30d": round(avg_rest_voltage, 2) if avg_rest_voltage else None,
        "voltage_trend": trend,
        "rest_readings_30d": len(rest_history),
        "unresolved_events": unresolved_events,
        "recommendations": recommendations,
        "thresholds": {
            "healthy_rest": VOLTAGE_HEALTHY_REST,
            "warning_rest": VOLTAGE_WARNING_REST,
            "critical_rest": VOLTAGE_CRITICAL_REST,
            "healthy_charging": VOLTAGE_HEALTHY_CHARGING,
        },
    }


def detect_voltage_anomalies(db: Session, telemetry_data: List[TelemetryRaw]) -> List[Dict]:
    """
    Detect voltage anomalies from a batch of telemetry readings.

    Returns list of anomaly events to be logged.
    """
    if not telemetry_data:
        return []

    anomalies = []

    # Sort by timestamp
    sorted_data = sorted(telemetry_data, key=lambda x: x.timestamp)

    # Track voltage drops
    prev_voltage = None
    low_voltage_start = None
    low_voltage_count = 0

    for reading in sorted_data:
        if not reading.battery_voltage:
            continue

        voltage = reading.battery_voltage
        is_charging = reading.charger_connected or reading.engine_running

        # Detect sudden voltage drop (> 0.5V)
        if prev_voltage and abs(voltage - prev_voltage) > 0.5:
            anomalies.append(
                {
                    "event_type": "voltage_drop",
                    "severity": "warning" if abs(voltage - prev_voltage) < 1.0 else "critical",
                    "voltage_v": voltage,
                    "voltage_change_v": voltage - prev_voltage,
                    "timestamp": reading.timestamp,
                    "is_charging": is_charging,
                    "description": f"Sudden voltage {'drop' if voltage < prev_voltage else 'spike'} of {abs(voltage - prev_voltage):.2f}V",
                }
            )

        # Detect low voltage at rest
        if not is_charging:
            if voltage < VOLTAGE_CRITICAL_REST:
                if low_voltage_start is None:
                    low_voltage_start = reading.timestamp
                low_voltage_count += 1
            else:
                # End of low voltage period
                if low_voltage_count >= 5:  # At least 5 consecutive readings
                    duration = (reading.timestamp - low_voltage_start).total_seconds()
                    anomalies.append(
                        {
                            "event_type": "low_voltage",
                            "severity": "critical",
                            "voltage_v": voltage,
                            "timestamp": low_voltage_start,
                            "duration_seconds": int(duration),
                            "is_charging": False,
                            "description": f"Critical low voltage (< {VOLTAGE_CRITICAL_REST}V) for {duration:.0f} seconds",
                        }
                    )
                low_voltage_start = None
                low_voltage_count = 0

        # Detect overcharge (alternator/charger issue)
        if is_charging and voltage > VOLTAGE_OVERCHARGE:
            anomalies.append(
                {
                    "event_type": "charging_issue",
                    "severity": "warning",
                    "voltage_v": voltage,
                    "timestamp": reading.timestamp,
                    "is_charging": is_charging,
                    "description": f"Overcharge detected ({voltage:.2f}V) - possible alternator or charger issue",
                }
            )

        # Detect parasitic drain (voltage drop when not charging)
        if not is_charging and prev_voltage and voltage < prev_voltage - 0.3:
            # Significant voltage drop at rest could indicate parasitic drain
            anomalies.append(
                {
                    "event_type": "parasitic_drain",
                    "severity": "info",
                    "voltage_v": voltage,
                    "voltage_change_v": voltage - prev_voltage,
                    "timestamp": reading.timestamp,
                    "is_charging": False,
                    "description": f"Possible parasitic drain - voltage dropped {prev_voltage - voltage:.2f}V at rest",
                }
            )

        prev_voltage = voltage

    return anomalies


def forecast_replacement_timing(db: Session) -> Dict:
    """
    Forecast when the 12V battery should be replaced.

    Uses both time-based (age) and voltage-based (health) prediction.
    """
    # Get voltage history
    all_history = get_voltage_history(db, days=365)  # Up to 1 year

    if len(all_history) < 10:
        return {
            "error": "Not enough voltage data for forecasting",
            "min_readings_needed": 10,
            "current_readings": len(all_history),
        }

    # Get earliest and latest readings to estimate battery age
    earliest = min(all_history, key=lambda x: x.timestamp)
    latest = max(all_history, key=lambda x: x.timestamp)

    data_span_days = (latest.timestamp - earliest.timestamp).days
    estimated_battery_age_days = data_span_days  # Conservative estimate

    # Calculate voltage degradation rate
    rest_readings = [r for r in all_history if not (r.is_charging or r.charger_connected or r.engine_running)]

    if len(rest_readings) < 5:
        voltage_trend = None
        voltage_based_forecast = None
    else:
        # Simple linear regression on voltage over time
        data_points = [(r.timestamp.timestamp(), r.voltage_v) for r in rest_readings]
        slope, intercept = simple_linear_regression(data_points)

        # Calculate degradation rate (volts per year)
        degradation_per_year = slope * (365 * 24 * 60 * 60)  # Convert from per-second to per-year

        # Estimate when voltage will reach critical threshold
        current_voltage = latest.voltage_v if not latest.is_charging else None
        if current_voltage and degradation_per_year < -0.01:  # Declining
            # Days until critical voltage
            days_to_critical = (VOLTAGE_CRITICAL_REST - current_voltage) / (degradation_per_year / 365)
            voltage_based_forecast = {
                "days_remaining": int(max(0, days_to_critical)),
                "estimated_date": (utc_now() + timedelta(days=max(0, days_to_critical))).date().isoformat(),
                "degradation_rate_per_year": round(degradation_per_year, 3),
            }
        else:
            voltage_based_forecast = {"status": "stable", "degradation_rate_per_year": round(degradation_per_year, 3)}

        voltage_trend = "declining" if degradation_per_year < -0.05 else "stable"

    # Time-based forecast (typical AGM battery lifespan)
    days_to_3yr = max(0, BATTERY_LIFESPAN_MIN_DAYS - estimated_battery_age_days)
    days_to_5yr = max(0, BATTERY_LIFESPAN_MAX_DAYS - estimated_battery_age_days)

    time_based_forecast = {
        "estimated_age_days": estimated_battery_age_days,
        "estimated_age_years": round(estimated_battery_age_days / 365, 1),
        "typical_lifespan_years": "3-5 years",
        "days_to_minimum_lifespan": days_to_3yr,
        "days_to_maximum_lifespan": days_to_5yr,
        "replacement_window_start": (utc_now() + timedelta(days=days_to_3yr)).date().isoformat(),
        "replacement_window_end": (utc_now() + timedelta(days=days_to_5yr)).date().isoformat(),
    }

    # Overall recommendation
    if voltage_based_forecast and isinstance(voltage_based_forecast, dict) and "days_remaining" in voltage_based_forecast:
        voltage_days = voltage_based_forecast["days_remaining"]
        if voltage_days < days_to_3yr:
            recommendation = f"Voltage-based forecast indicates replacement needed in ~{voltage_days} days"
            urgency = "high" if voltage_days < 30 else "medium"
        else:
            recommendation = f"Time-based forecast suggests replacement window starts in ~{days_to_3yr} days"
            urgency = "low" if days_to_3yr > 180 else "medium"
    else:
        recommendation = f"Based on typical AGM lifespan, consider replacement in {days_to_3yr}-{days_to_5yr} days"
        urgency = "low" if days_to_3yr > 180 else "medium"

    return {
        "time_based_forecast": time_based_forecast,
        "voltage_based_forecast": voltage_based_forecast,
        "voltage_trend": voltage_trend,
        "recommendation": recommendation,
        "urgency": urgency,
        "data_points": len(rest_readings),
        "data_span_days": data_span_days,
    }


def simple_linear_regression(data: List[Tuple[float, float]]) -> Tuple[float, float]:
    """
    Simple linear regression: y = mx + b

    Args:
        data: List of (x, y) tuples

    Returns:
        (slope, intercept)
    """
    n = len(data)
    if n < 2:
        return (0, 12.5)  # No trend if insufficient data

    sum_x = sum(x for x, y in data)
    sum_y = sum(y for x, y in data)
    sum_xy = sum(x * y for x, y in data)
    sum_xx = sum(x * x for x, y in data)

    # Calculate slope and intercept
    denominator = n * sum_xx - sum_x * sum_x
    if denominator == 0:
        return (0, sum_y / n)  # Prevent division by zero

    slope = (n * sum_xy - sum_x * sum_y) / denominator
    intercept = (sum_y - slope * sum_x) / n

    return (slope, intercept)


def log_battery_event(
    db: Session,
    event_type: str,
    severity: str,
    voltage_v: float,
    timestamp: datetime,
    description: str,
    **kwargs,
) -> AuxBatteryEvent:
    """
    Log a battery event to the database.

    Args:
        db: Database session
        event_type: Type of event (low_voltage, voltage_drop, etc.)
        severity: Severity level (info, warning, critical)
        voltage_v: Voltage at time of event
        timestamp: When the event occurred
        description: Human-readable description
        **kwargs: Additional context fields

    Returns:
        Created AuxBatteryEvent instance
    """
    event = AuxBatteryEvent(
        event_type=event_type,
        severity=severity,
        voltage_v=voltage_v,
        timestamp=timestamp,
        description=description,
        voltage_change_v=kwargs.get("voltage_change_v"),
        duration_seconds=kwargs.get("duration_seconds"),
        is_charging=kwargs.get("is_charging", False),
        charger_connected=kwargs.get("charger_connected", False),
        engine_running=kwargs.get("engine_running", False),
        ambient_temp_f=kwargs.get("ambient_temp_f"),
        odometer_miles=kwargs.get("odometer_miles"),
    )

    db.add(event)
    db.commit()
    db.refresh(event)

    logger.info(f"Logged 12V battery event: {event_type} ({severity}) - {description}")

    return event


def get_recent_events(db: Session, days: int = 7, severity: Optional[str] = None) -> List[AuxBatteryEvent]:
    """
    Get recent battery events.

    Args:
        db: Database session
        days: Number of days to look back
        severity: Filter by severity (info, warning, critical) or None for all

    Returns:
        List of events ordered by timestamp (newest first)
    """
    cutoff = utc_now() - timedelta(days=days)
    query = db.query(AuxBatteryEvent).filter(AuxBatteryEvent.timestamp >= cutoff)

    if severity:
        query = query.filter(AuxBatteryEvent.severity == severity)

    return query.order_by(desc(AuxBatteryEvent.timestamp)).all()


def get_voltage_statistics(db: Session, days: int = 30) -> Dict:
    """
    Calculate voltage statistics for the specified period.

    Returns min, max, average voltages and distribution data.
    """
    history = get_voltage_history(db, days=days)
    rest_history = get_voltage_at_rest_history(db, days=days)

    if not history:
        return {"error": "No voltage data available"}

    all_voltages = [r.voltage_v for r in history]
    rest_voltages = [r.voltage_v for r in rest_history]

    stats = {
        "period_days": days,
        "total_readings": len(history),
        "rest_readings": len(rest_history),
        "all_voltages": {
            "min": round(min(all_voltages), 2),
            "max": round(max(all_voltages), 2),
            "avg": round(sum(all_voltages) / len(all_voltages), 2),
        },
    }

    if rest_voltages:
        stats["rest_voltages"] = {
            "min": round(min(rest_voltages), 2),
            "max": round(max(rest_voltages), 2),
            "avg": round(sum(rest_voltages) / len(rest_voltages), 2),
        }

    return stats
