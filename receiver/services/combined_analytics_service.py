"""
Combined Analytics Service for VoltTracker

Provides multi-factor efficiency analysis combining weather, elevation,
and other factors for comprehensive insights.
"""

import logging
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional

from sqlalchemy import and_, case, extract, func
from sqlalchemy.orm import Session

from models import Trip
from services import elevation_analytics_service, weather_analytics_service
from utils.timezone import utc_now

logger = logging.getLogger(__name__)

# Baseline efficiency for comparison
BASELINE_KWH_PER_MILE = 0.32


def get_multi_factor_analysis(
    db: Session,
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
) -> Dict[str, Any]:
    """
    Get comprehensive multi-factor efficiency analysis.

    Combines weather, elevation, and other factors to show
    their individual and combined impacts on efficiency.

    Returns:
        Dictionary with factor impacts and correlations
    """
    # Get individual factor analyses
    weather_data = weather_analytics_service.get_weather_efficiency_correlation(
        db, start_date=start_date, end_date=end_date
    )
    elevation_data = elevation_analytics_service.get_efficiency_by_elevation_change(
        db, start_date=start_date, end_date=end_date
    )

    # Calculate overall statistics
    filters = _get_base_filters(start_date, end_date)

    overall_stats = (
        db.query(
            func.count(Trip.id).label("total_trips"),
            func.avg(Trip.kwh_per_mile).label("avg_efficiency"),
            func.min(Trip.kwh_per_mile).label("best_efficiency"),
            func.max(Trip.kwh_per_mile).label("worst_efficiency"),
            func.sum(Trip.electric_miles).label("total_miles"),
        )
        .filter(and_(*filters))
        .first()
    )

    # Calculate factor impacts
    factor_impacts = {
        "temperature": _calculate_temperature_impact(weather_data),
        "elevation": _calculate_elevation_impact(elevation_data),
        "precipitation": _calculate_precipitation_impact(
            weather_analytics_service.get_efficiency_by_precipitation(
                db, start_date=start_date, end_date=end_date
            )
        ),
    }

    return {
        "overall": {
            "total_trips": overall_stats.total_trips or 0,
            "avg_efficiency_kwh_per_mile": (
                round(overall_stats.avg_efficiency, 4) if overall_stats.avg_efficiency else None
            ),
            "best_efficiency_kwh_per_mile": (
                round(overall_stats.best_efficiency, 4) if overall_stats.best_efficiency else None
            ),
            "worst_efficiency_kwh_per_mile": (
                round(overall_stats.worst_efficiency, 4) if overall_stats.worst_efficiency else None
            ),
            "total_miles": round(overall_stats.total_miles, 1) if overall_stats.total_miles else 0,
            "baseline_kwh_per_mile": BASELINE_KWH_PER_MILE,
        },
        "factor_impacts": factor_impacts,
        "recommendations": _generate_recommendations(factor_impacts),
    }


def get_efficiency_predictions(
    db: Session,
    temperature_f: Optional[float] = None,
    elevation_change_m: Optional[float] = None,
    is_raining: bool = False,
) -> Dict[str, Any]:
    """
    Predict efficiency for given conditions based on historical data.

    Args:
        temperature_f: Expected temperature in Fahrenheit
        elevation_change_m: Expected net elevation change in meters
        is_raining: Whether precipitation is expected

    Returns:
        Predicted efficiency and confidence
    """
    base_efficiency = BASELINE_KWH_PER_MILE
    adjustments = []
    total_adjustment = 0.0

    # Temperature adjustment
    if temperature_f is not None:
        temp_factor = _get_temperature_factor(temperature_f)
        temp_adjustment = (temp_factor - 1.0) * base_efficiency
        adjustments.append({
            "factor": "temperature",
            "value": f"{temperature_f:.0f}°F",
            "impact_percent": round((temp_factor - 1.0) * 100, 1),
            "adjustment_kwh": round(temp_adjustment, 4),
        })
        total_adjustment += temp_adjustment

    # Elevation adjustment
    if elevation_change_m is not None:
        elev_factor = _get_elevation_factor(elevation_change_m)
        elev_adjustment = (elev_factor - 1.0) * base_efficiency
        adjustments.append({
            "factor": "elevation",
            "value": f"{elevation_change_m:+.0f}m",
            "impact_percent": round((elev_factor - 1.0) * 100, 1),
            "adjustment_kwh": round(elev_adjustment, 4),
        })
        total_adjustment += elev_adjustment

    # Precipitation adjustment
    if is_raining:
        precip_factor = 1.05  # ~5% worse in rain
        precip_adjustment = (precip_factor - 1.0) * base_efficiency
        adjustments.append({
            "factor": "precipitation",
            "value": "rain",
            "impact_percent": round((precip_factor - 1.0) * 100, 1),
            "adjustment_kwh": round(precip_adjustment, 4),
        })
        total_adjustment += precip_adjustment

    predicted_efficiency = base_efficiency + total_adjustment

    return {
        "predicted_kwh_per_mile": round(predicted_efficiency, 4),
        "baseline_kwh_per_mile": BASELINE_KWH_PER_MILE,
        "total_adjustment_percent": round((total_adjustment / base_efficiency) * 100, 1),
        "adjustments": adjustments,
        "confidence": "medium" if len(adjustments) > 0 else "low",
    }


def get_efficiency_time_series(
    db: Session,
    days: int = 90,
    group_by: str = "week",
) -> Dict[str, Any]:
    """
    Get efficiency data formatted for time series charts.

    Args:
        days: Number of days to include
        group_by: Grouping period ("day", "week", "month")

    Returns:
        Time series data with efficiency and factor averages
    """
    end_date = utc_now()
    start_date = end_date - timedelta(days=days)

    filters = _get_base_filters(start_date, end_date)

    # Build grouping based on period
    if group_by == "day":
        date_key = func.date(Trip.start_time)
    elif group_by == "month":
        # Use year and month for monthly grouping
        year_part = extract("year", Trip.start_time)
        month_part = extract("month", Trip.start_time)
        date_key = func.concat(year_part, "-", month_part)
    else:  # week (default)
        # Use year and week for weekly grouping
        year_part = extract("year", Trip.start_time)
        week_part = extract("week", Trip.start_time)
        date_key = func.concat(year_part, "-W", week_part)

    results = (
        db.query(
            date_key.label("period"),
            func.avg(Trip.kwh_per_mile).label("avg_efficiency"),
            func.count(Trip.id).label("trip_count"),
            func.sum(Trip.electric_miles).label("total_miles"),
            func.avg(Trip.weather_temp_f).label("avg_temp"),
            func.avg(Trip.elevation_net_change_m).label("avg_elevation_change"),
        )
        .filter(and_(*filters))
        .group_by(date_key)
        .order_by(date_key)
        .all()
    )

    time_series = []
    for row in results:
        time_series.append({
            "period": str(row.period),
            "avg_kwh_per_mile": round(row.avg_efficiency, 4) if row.avg_efficiency else None,
            "trip_count": row.trip_count,
            "total_miles": round(row.total_miles, 1) if row.total_miles else 0,
            "avg_temp_f": round(row.avg_temp, 1) if row.avg_temp else None,
            "avg_elevation_change_m": (
                round(row.avg_elevation_change, 1) if row.avg_elevation_change else None
            ),
        })

    return {
        "time_series": time_series,
        "period_count": len(time_series),
        "group_by": group_by,
        "date_range": {
            "start": start_date.isoformat(),
            "end": end_date.isoformat(),
        },
    }


def get_best_driving_conditions_combined(db: Session) -> Dict[str, Any]:
    """
    Find the best combined conditions for efficiency.

    Returns:
        Optimal conditions based on historical data
    """
    # Get best conditions from weather
    weather_best = weather_analytics_service.get_best_driving_conditions(db)

    # Get efficiency by elevation
    elevation_data = elevation_analytics_service.get_efficiency_by_elevation_change(db)

    # Find best elevation category
    best_elevation = None
    best_elev_efficiency = float("inf")
    for cat in elevation_data.get("elevation_categories", []):
        if cat.get("avg_kwh_per_mile") and cat["avg_kwh_per_mile"] < best_elev_efficiency:
            best_elev_efficiency = cat["avg_kwh_per_mile"]
            best_elevation = cat

    return {
        "optimal_conditions": {
            "temperature_range_f": weather_best.get("optimal_conditions", {}).get(
                "temperature_range", "55-75°F"
            ),
            "precipitation": "dry",
            "elevation_profile": best_elevation.get("label", "flat") if best_elevation else "flat",
            "wind": "calm (<10 mph)",
        },
        "expected_efficiency_kwh_per_mile": (
            round(best_elev_efficiency, 4) if best_elev_efficiency < float("inf") else None
        ),
        "efficiency_vs_baseline_percent": (
            round((1 - best_elev_efficiency / BASELINE_KWH_PER_MILE) * 100, 1)
            if best_elev_efficiency < float("inf")
            else None
        ),
        "based_on_trips": elevation_data.get("total_trips_analyzed", 0),
    }


def _get_base_filters(
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
) -> list:
    """Build base query filters for valid trips."""
    filters = [
        Trip.is_closed == True,  # noqa: E712
        Trip.deleted_at.is_(None),
        Trip.kwh_per_mile.isnot(None),
        Trip.kwh_per_mile > 0,
        Trip.kwh_per_mile < 1.0,
        Trip.electric_miles.isnot(None),
        Trip.electric_miles > 0.5,
    ]

    if start_date:
        filters.append(Trip.start_time >= start_date)
    if end_date:
        filters.append(Trip.start_time <= end_date)

    return filters


def _calculate_temperature_impact(weather_data: Dict[str, Any]) -> Dict[str, Any]:
    """Calculate temperature impact from weather data."""
    temp_bands = weather_data.get("temperature_correlation", {}).get("temperature_bands", [])

    if not temp_bands:
        return {"impact_range": None, "optimal": None}

    impacts = []
    for band in temp_bands:
        if band.get("efficiency_impact_percent") is not None:
            impacts.append(band["efficiency_impact_percent"])

    return {
        "impact_range_percent": [min(impacts), max(impacts)] if impacts else None,
        "optimal_range": "55-75°F",
        "worst_conditions": "Below freezing (<32°F)",
    }


def _calculate_elevation_impact(elevation_data: Dict[str, Any]) -> Dict[str, Any]:
    """Calculate elevation impact from elevation data."""
    categories = elevation_data.get("elevation_categories", [])

    if not categories:
        return {"impact_range": None, "optimal": None}

    impacts = []
    for cat in categories:
        if cat.get("efficiency_impact_percent") is not None:
            impacts.append(cat["efficiency_impact_percent"])

    return {
        "impact_range_percent": [min(impacts), max(impacts)] if impacts else None,
        "optimal": "Downhill or flat",
        "worst": "Steep uphill (>50m gain)",
    }


def _calculate_precipitation_impact(precip_data: Dict[str, Any]) -> Dict[str, Any]:
    """Calculate precipitation impact."""
    dry_eff = None
    wet_eff = None

    for condition in precip_data.get("precipitation_impact", []):
        if condition.get("condition") == "dry":
            dry_eff = condition.get("avg_kwh_per_mile")
        elif condition.get("condition") == "rain":
            wet_eff = condition.get("avg_kwh_per_mile")

    impact_percent = None
    if dry_eff and wet_eff:
        impact_percent = round(((wet_eff - dry_eff) / dry_eff) * 100, 1)

    return {
        "rain_impact_percent": impact_percent,
        "recommendation": "Avoid driving in heavy rain when possible",
    }


def _get_temperature_factor(temp_f: float) -> float:
    """Get efficiency factor based on temperature."""
    # Based on typical EV efficiency curves
    if temp_f < 32:
        return 1.25  # 25% worse in freezing
    elif temp_f < 45:
        return 1.15  # 15% worse in cold
    elif temp_f < 55:
        return 1.08  # 8% worse in cool
    elif temp_f <= 75:
        return 1.00  # Optimal range
    elif temp_f <= 85:
        return 1.03  # 3% worse (AC)
    elif temp_f <= 95:
        return 1.08  # 8% worse
    else:
        return 1.15  # 15% worse in extreme heat


def _get_elevation_factor(elevation_change_m: float) -> float:
    """Get efficiency factor based on elevation change."""
    # Roughly 2% per 50m of net elevation change
    if elevation_change_m < -50:
        return 0.92  # 8% better downhill
    elif elevation_change_m < -10:
        return 0.96  # 4% better
    elif elevation_change_m <= 10:
        return 1.00  # Flat
    elif elevation_change_m <= 50:
        return 1.04  # 4% worse
    else:
        return 1.10  # 10% worse steep uphill


def _generate_recommendations(factor_impacts: Dict[str, Any]) -> List[str]:
    """Generate driving recommendations based on factor impacts."""
    recommendations = []

    temp_impact = factor_impacts.get("temperature", {})
    if temp_impact.get("impact_range_percent"):
        recommendations.append(
            "Temperature has the highest impact on efficiency. "
            "Pre-condition your car while plugged in during extreme weather."
        )

    elev_impact = factor_impacts.get("elevation", {})
    if elev_impact.get("impact_range_percent"):
        recommendations.append(
            "Choose routes with less elevation gain when possible. "
            "Downhill routes significantly improve efficiency through regenerative braking."
        )

    precip_impact = factor_impacts.get("precipitation", {})
    if precip_impact.get("rain_impact_percent"):
        recommendations.append(
            f"Rain impacts efficiency by approximately {precip_impact['rain_impact_percent']}%. "
            "Consider timing trips to avoid heavy rain."
        )

    return recommendations
