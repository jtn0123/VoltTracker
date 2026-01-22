"""
Battery Degradation Forecasting Service

Predict future battery capacity based on historical degradation trends.
"""

import logging
from typing import Dict, List, Tuple

from models import BatteryHealthReading, Trip
from calculations import (
    capacity_kwh_to_percent,
    calculate_degradation_rate_per_10k_miles,
    is_degradation_rate_normal,
    predict_capacity_at_mileage,
)
from sqlalchemy import and_
from sqlalchemy.orm import Session

logger = logging.getLogger(__name__)


def get_degradation_history(db: Session) -> List[Tuple[float, float]]:
    """
    Get historical battery capacity data.

    Returns: List of (odometer_miles, capacity_kwh)
    """
    from sqlalchemy import or_

    readings = (
        db.query(BatteryHealthReading)
        .filter(
            or_(
                BatteryHealthReading.normalized_capacity_kwh.isnot(None),
                BatteryHealthReading.capacity_kwh.isnot(None),
            )
        )
        .order_by(BatteryHealthReading.timestamp)
        .all()
    )

    # Get odometer at time of each reading (approximate from nearby trip)
    data = []
    for reading in readings:
        # Use normalized capacity, fallback to raw capacity
        capacity_kwh = reading.normalized_capacity_kwh
        if capacity_kwh is None and reading.capacity_kwh:
            capacity_kwh = reading.capacity_kwh

        # Use odometer from reading if available, otherwise find nearest trip
        odometer = reading.odometer_miles
        if not odometer:
            nearby_trip = (
                db.query(Trip.end_odometer)
                .filter(
                    and_(
                        Trip.end_odometer.isnot(None),
                        Trip.start_time >= reading.timestamp,
                    )
                )
                .order_by(Trip.start_time)
                .first()
            )
            if nearby_trip and nearby_trip[0]:
                odometer = nearby_trip[0]

        if odometer and capacity_kwh:
            data.append((float(odometer), float(capacity_kwh)))

    return data


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
        return (0, 100)  # No degradation if insufficient data

    sum_x = sum(x for x, y in data)
    sum_y = sum(y for x, y in data)
    sum_xy = sum(x * y for x, y in data)
    sum_xx = sum(x * x for x, y in data)

    # Calculate slope and intercept
    denominator = n * sum_xx - sum_x * sum_x
    if denominator == 0:
        # All x values are identical - no slope can be calculated
        # Return flat line at mean y value
        return (0, sum_y / n)

    slope = (n * sum_xy - sum_x * sum_y) / denominator
    intercept = (sum_y - slope * sum_x) / n

    return (slope, intercept)


def forecast_degradation(db: Session) -> Dict:
    """
    Forecast battery degradation at key mileage milestones.
    """
    # Get historical data
    history = get_degradation_history(db)

    if len(history) < 2:
        return {
            "error": "Not enough battery health data",
            "min_readings_needed": 2,
            "current_readings": len(history),
        }

    # Perform regression
    slope, intercept = simple_linear_regression(history)

    # Get current status
    latest_reading = db.query(BatteryHealthReading).order_by(BatteryHealthReading.timestamp.desc()).first()

    current_miles = 0
    current_capacity_pct = 100

    if latest_reading:
        # Calculate capacity percent from normalized kWh
        if latest_reading.normalized_capacity_kwh:
            current_capacity_pct = capacity_kwh_to_percent(latest_reading.normalized_capacity_kwh)
        elif latest_reading.capacity_kwh:
            # Fallback to raw capacity if normalized not available
            current_capacity_pct = capacity_kwh_to_percent(latest_reading.capacity_kwh)

        # Get current mileage from reading or latest trip
        if latest_reading.odometer_miles:
            current_miles = latest_reading.odometer_miles
        else:
            latest_trip = db.query(Trip).filter(Trip.end_odometer.isnot(None)).order_by(Trip.start_time.desc()).first()
            if latest_trip:
                current_miles = latest_trip.end_odometer

    # Forecast at milestones
    milestones = [50000, 75000, 100000, 125000, 150000, 200000]
    forecasts = []

    for miles in milestones:
        if miles <= current_miles:
            continue

        # Predict capacity in kWh using regression model
        predicted_capacity_kwh = predict_capacity_at_mileage(miles, slope, intercept)

        # Convert to percent
        predicted_capacity_pct = capacity_kwh_to_percent(predicted_capacity_kwh)

        forecasts.append(
            {
                "odometer_miles": miles,
                "predicted_capacity_pct": round(predicted_capacity_pct, 1),
                "predicted_capacity_kwh": round(predicted_capacity_kwh, 2),
            }
        )

    # Calculate degradation rate in percent per 10k miles
    degradation_per_10k = calculate_degradation_rate_per_10k_miles(slope)

    # Check if degradation rate is normal
    is_normal = is_degradation_rate_normal(degradation_per_10k)

    return {
        "current_status": {
            "odometer_miles": current_miles,
            "capacity_pct": current_capacity_pct,
            "capacity_kwh": latest_reading.capacity_kwh if latest_reading else 16.5,
        },
        "degradation_rate": {
            "percent_per_10k_miles": round(degradation_per_10k, 2),
            "percent_per_50k_miles": round(degradation_per_10k * 5, 1),
            "is_normal": is_normal,
            "comparison": "Normal (2-3% per 50k)"
            if is_normal
            else "Faster than typical"
            if degradation_per_10k > 0.8
            else "Slower than typical",
        },
        "forecasts": forecasts,
        "data_points": len(history),
        "model": {"slope": slope, "intercept": intercept},
        "recommendation": "Battery health is normal"
        if is_normal
        else "Consider having battery inspected if degradation continues",
    }
