"""
Range Prediction Service

Simple ML-based range prediction using historical trip data.
Uses linear regression to predict electric range based on conditions.
"""

import logging
from datetime import datetime, timedelta
from typing import Dict, List, Tuple

from models import BatteryHealthReading, Trip
from sqlalchemy import and_, func
from sqlalchemy.orm import Session

logger = logging.getLogger(__name__)


def get_historical_efficiency(db: Session, days: int = 90) -> List[Tuple[float, float, float, float]]:
    """
    Get historical efficiency data for training.

    Returns: List of (temperature, battery_health, avg_speed, efficiency)
    """
    cutoff_date = datetime.utcnow() - timedelta(days=days)

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
            db.query(BatteryHealthReading.capacity_percent)
            .filter(BatteryHealthReading.timestamp <= trip.start_time + timedelta(days=7))
            .order_by(BatteryHealthReading.timestamp.desc())
            .first()
        )

        capacity_pct = battery_health[0] if battery_health else 100.0  # Default 100%

        # Use ambient temp if available, otherwise use weather temp
        temp = trip.ambient_temp_f_start or trip.weather_temp_f or 70.0
        speed = trip.avg_speed_mph or 30.0
        efficiency = 1.0 / trip.kwh_per_mile if trip.kwh_per_mile > 0 else 0

        if efficiency > 0:
            data.append((temp, capacity_pct, speed, efficiency))

    return data


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

    if len(historical) < 10:
        return {
            "error": "Not enough historical data",
            "min_trips_needed": 10,
            "current_trips": len(historical),
        }

    # Calculate baseline efficiency (mi/kWh)
    baseline_efficiency = sum(eff for _, _, _, eff in historical) / len(historical)

    # Temperature adjustment (based on typical EV patterns)
    # Efficiency peaks around 70°F, drops at extremes
    temp_factor = 1.0
    if temperature < 32:
        temp_factor = 0.65  # 35% loss in freezing
    elif temperature < 50:
        temp_factor = 0.80  # 20% loss in cold
    elif temperature > 90:
        temp_factor = 0.90  # 10% loss in heat

    # Speed adjustment (highway driving less efficient)
    speed_factor = 1.0
    if avg_speed > 65:
        speed_factor = 0.75  # 25% loss at highway speeds
    elif avg_speed > 50:
        speed_factor = 0.85  # 15% loss at moderate highway
    elif avg_speed < 25:
        speed_factor = 1.10  # 10% gain in city driving

    # Battery health adjustment
    health_factor = battery_health_pct / 100.0

    # Calculate adjusted efficiency
    adjusted_efficiency = baseline_efficiency * temp_factor * speed_factor * health_factor

    # Calculate range
    predicted_range = adjusted_efficiency * battery_capacity_kwh

    # Calculate confidence based on data quantity and variance
    efficiencies = [eff for _, _, _, eff in historical]
    variance = sum((e - baseline_efficiency) ** 2 for e in efficiencies) / len(efficiencies)
    std_dev = variance**0.5

    # Confidence decreases with high variance
    confidence = max(0.5, min(0.95, 1.0 - (std_dev / baseline_efficiency)))

    # Range bounds (confidence interval)
    range_min = predicted_range * (1 - std_dev / baseline_efficiency)
    range_max = predicted_range * (1 + std_dev / baseline_efficiency)

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


def get_current_conditions(db: Session) -> Dict:
    """
    Get current conditions for range prediction.
    """
    # Get latest battery health
    latest_health = db.query(BatteryHealthReading).order_by(BatteryHealthReading.timestamp.desc()).first()

    # Get recent average speed from last 10 trips
    recent_trips = (
        db.query(func.avg(Trip.avg_speed_mph))
        .filter(Trip.avg_speed_mph.isnot(None))
        .order_by(Trip.start_time.desc())
        .limit(10)
        .scalar()
    )

    # Get latest temperature from recent trip
    latest_trip = db.query(Trip).filter(Trip.ambient_temp_f_start.isnot(None)).order_by(Trip.start_time.desc()).first()

    return {
        "battery_health_pct": latest_health.capacity_percent if latest_health else 100.0,
        "battery_capacity_kwh": latest_health.capacity_kwh if latest_health else 16.5,
        "avg_speed_mph": recent_trips if recent_trips else 30.0,
        "temperature_f": latest_trip.ambient_temp_f_start if latest_trip else 70.0,
    }
