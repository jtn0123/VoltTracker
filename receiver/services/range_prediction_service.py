"""
Range Prediction Service

Simple ML-based range prediction using historical trip data.
Uses linear regression to predict electric range based on conditions.
"""

import logging
from datetime import datetime, timedelta, timezone
from typing import Dict, List, Tuple

from models import BatteryHealthReading, Trip
from sqlalchemy import and_
from sqlalchemy.orm import Session

logger = logging.getLogger(__name__)


def get_historical_efficiency(db: Session, days: int = 90) -> List[Tuple[float, float, float, float]]:
    """
    Get historical efficiency data for training.

    Returns: List of (temperature, battery_health, avg_speed, efficiency)
    """
    cutoff_date = datetime.now(timezone.utc) - timedelta(days=days)

    trips = (
        db.query(Trip)
        .filter(
            and_(
                Trip.start_time >= cutoff_date,
                Trip.electric_miles > 1.0,
                Trip.kwh_per_mile.isnot(None),
                Trip.kwh_per_mile > 0,
            )
        )
        .all()
    )

    # Get battery health near each trip
    data = []
    for trip in trips:
        battery_health = (
            db.query(BatteryHealthReading)
            .filter(BatteryHealthReading.timestamp <= trip.start_time + timedelta(days=7))
            .order_by(BatteryHealthReading.timestamp.desc())
            .first()
        )

        # Calculate capacity percent from kWh (18.4 kWh is 100% for Volt Gen 2)
        if battery_health:
            capacity_kwh = battery_health.normalized_capacity_kwh or battery_health.capacity_kwh
            capacity_pct = (capacity_kwh / 18.4) * 100.0 if capacity_kwh else 100.0
        else:
            capacity_pct = 100.0  # Default 100%

        # Use ambient temp if available, otherwise use weather temp
        temp = trip.ambient_temp_avg_f or trip.weather_temp_f or 70.0

        # Calculate average speed from distance and duration
        if trip.distance_miles and trip.start_time and trip.end_time:
            duration_hours = (trip.end_time - trip.start_time).total_seconds() / 3600.0
            speed = trip.distance_miles / duration_hours if duration_hours > 0 else 30.0
        else:
            speed = 30.0  # Default

        efficiency = 1.0 / trip.kwh_per_mile if trip.kwh_per_mile > 0 else 0

        if efficiency > 0:
            data.append((float(temp), float(capacity_pct), float(speed), float(efficiency)))

    return data


def _get_temperature_factor(temperature: float) -> float:
    """Get efficiency factor based on temperature."""
    if temperature < 32:
        return 0.65  # 35% loss in freezing
    if temperature < 50:
        return 0.80  # 20% loss in cold
    if temperature > 90:
        return 0.90  # 10% loss in heat
    return 1.0


def _get_speed_factor(avg_speed: float) -> float:
    """Get efficiency factor based on average speed."""
    if avg_speed > 65:
        return 0.75  # 25% loss at highway speeds
    if avg_speed > 50:
        return 0.85  # 15% loss at moderate highway
    if avg_speed < 30:
        return 1.10  # 10% gain in city driving
    return 1.0


def _calculate_confidence(historical, baseline_efficiency, predicted_range):
    """Calculate prediction confidence and range bounds."""
    if len(historical) >= 3:
        efficiencies = [eff for _, _, _, eff in historical]
        variance = sum((e - baseline_efficiency) ** 2 for e in efficiencies) / len(efficiencies)
        std_dev = variance**0.5
        data_confidence = min(1.0, len(historical) / 20.0)
        variance_confidence = max(0.5, 1.0 - (std_dev / baseline_efficiency))
        confidence = max(0.5, min(0.95, data_confidence * variance_confidence))
        range_min = predicted_range * (1 - std_dev / baseline_efficiency)
        range_max = predicted_range * (1 + std_dev / baseline_efficiency)
    else:
        std_dev = baseline_efficiency * 0.3
        confidence = 0.5 if len(historical) > 0 else 0.3
        range_min = predicted_range * 0.7
        range_max = predicted_range * 1.3
    return confidence, range_min, range_max, std_dev


def predict_range_simple(
    db: Session,
    temperature: float = 70.0,
    battery_health_pct: float = 100.0,
    avg_speed: float = 30.0,
    battery_capacity_kwh: float = 16.5,
) -> Dict:
    """
    Predict electric range using simple statistical model.

    Simplified approach - no sklearn needed for basic implementation.
    Uses historical averages with temperature and speed adjustments.
    """
    # Get historical data
    historical = get_historical_efficiency(db, days=90)

    # Calculate baseline efficiency (mi/kWh)
    # Use historical average if available, otherwise use default Volt efficiency
    if len(historical) >= 3:
        baseline_efficiency = sum(eff for _, _, _, eff in historical) / len(historical)
    else:
        # Default Volt Gen 2 efficiency: ~5.0 mi/kWh (0.2 kWh/mile)
        baseline_efficiency = 5.0

    temp_factor = _get_temperature_factor(temperature)
    speed_factor = _get_speed_factor(avg_speed)

    # Battery health adjustment
    # Clamp to 100% max (some readings might be slightly > 100%)
    health_factor = min(1.0, battery_health_pct / 100.0)

    # Calculate adjusted efficiency
    adjusted_efficiency = baseline_efficiency * temp_factor * speed_factor * health_factor

    # Calculate range
    predicted_range = adjusted_efficiency * battery_capacity_kwh

    confidence, range_min, range_max, std_dev = _calculate_confidence(
        historical, baseline_efficiency, predicted_range
    )

    return {
        "predicted_range_miles": round(predicted_range, 1),
        "range_min": round(max(0, range_min), 1),
        "range_max": round(range_max, 1),
        "confidence": round(confidence, 2),
        "factors": {
            "baseline_efficiency_mi_per_kwh": round(baseline_efficiency, 2),
            "temperature_factor": round(temp_factor, 2),
            "speed_factor": round(speed_factor, 2),
            "health_factor": round(health_factor, 2),
            "adjusted_efficiency": round(adjusted_efficiency, 2),
        },
        "conditions": {
            "temperature_f": temperature,
            "battery_health_pct": battery_health_pct,
            "avg_speed_mph": avg_speed,
            "battery_capacity_kwh": battery_capacity_kwh,
        },
        "data_quality": {
            "historical_trips": len(historical),
            "days_analyzed": 90,
            "std_dev": round(std_dev, 2),
        },
    }


def _calculate_avg_speed(trips) -> float:
    """Calculate average speed from recent trips."""
    if not trips:
        return 30.0
    speeds = []
    for trip in trips:
        duration_hours = (trip.end_time - trip.start_time).total_seconds() / 3600.0
        if duration_hours > 0:
            speeds.append(trip.distance_miles / duration_hours)
    return sum(speeds) / len(speeds) if speeds else 30.0


def _get_battery_health(latest_health):
    """Extract battery health percent and capacity from a reading."""
    if not latest_health:
        return 100.0, 16.5
    capacity_kwh = latest_health.normalized_capacity_kwh or latest_health.capacity_kwh
    health_pct = (capacity_kwh / 18.4) * 100.0 if capacity_kwh else 100.0
    return health_pct, capacity_kwh if capacity_kwh else 16.5


def get_current_conditions(db: Session) -> Dict:
    """
    Get current conditions for range prediction.
    """
    # Get latest battery health
    latest_health = db.query(BatteryHealthReading).order_by(BatteryHealthReading.timestamp.desc()).first()

    # Get recent average speed from last 10 trips
    # Calculate speed from distance and time for recent trips
    recent_trips_data = (
        db.query(Trip)
        .filter(
            Trip.distance_miles.isnot(None),
            Trip.start_time.isnot(None),
            Trip.end_time.isnot(None),
        )
        .order_by(Trip.start_time.desc())
        .limit(10)
        .all()
    )

    avg_speed = _calculate_avg_speed(recent_trips_data)

    # Get latest temperature from recent trip
    latest_trip = db.query(Trip).filter(Trip.ambient_temp_avg_f.isnot(None)).order_by(Trip.start_time.desc()).first()

    battery_health_pct, battery_capacity_kwh = _get_battery_health(latest_health)

    return {
        "battery_health_pct": battery_health_pct,
        "battery_capacity_kwh": battery_capacity_kwh,
        "avg_speed_mph": avg_speed,
        "temperature_f": latest_trip.ambient_temp_avg_f if latest_trip else 70.0,
    }
