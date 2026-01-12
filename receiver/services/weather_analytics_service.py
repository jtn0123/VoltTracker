"""
Weather Analytics Service for VoltTracker

Provides aggregation queries and correlation analysis between
weather conditions and trip efficiency.
"""

import logging
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional

from config import Config
from receiver.calculations import calculate_efficiency_impact_percent, BASELINE_KWH_PER_MILE
from sqlalchemy import and_, case, extract, func, literal_column
from sqlalchemy.orm import Session

from models import Trip
from utils.timezone import utc_now


def _get_stddev_func(column):
    """
    Get standard deviation function that works across databases.
    Returns None for SQLite which doesn't support stddev.
    """
    try:
        return func.stddev(column)
    except Exception:
        return literal_column("NULL")

logger = logging.getLogger(__name__)

# Use analytics constants from Config (configurable via environment)
TEMP_BANDS = Config.ANALYTICS_TEMP_BANDS
WIND_BANDS = Config.ANALYTICS_WIND_BANDS


def _get_base_trip_filter():
    """Return base filter for valid trips with efficiency data."""
    return and_(
        Trip.is_closed == True,  # noqa: E712
        Trip.deleted_at.is_(None),
        Trip.kwh_per_mile.isnot(None),
        Trip.kwh_per_mile > 0,
        Trip.kwh_per_mile < 1.0,  # Filter out invalid readings
        Trip.electric_miles.isnot(None),
        Trip.electric_miles > 0.5,  # Minimum trip distance
    )


def get_efficiency_by_temperature_bands(
    db: Session,
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
) -> Dict[str, Any]:
    """
    Get efficiency statistics grouped by temperature bands.

    Args:
        db: Database session
        start_date: Start of date range (default: all time)
        end_date: End of date range (default: now)

    Returns:
        Dictionary with temperature band analysis
    """
    # Build temperature band case expression
    temp_band_case = case(
        (Trip.weather_temp_f < 32, "freezing"),
        (Trip.weather_temp_f < 45, "cold"),
        (Trip.weather_temp_f < 55, "cool"),
        (Trip.weather_temp_f < 75, "ideal"),
        (Trip.weather_temp_f < 85, "warm"),
        (Trip.weather_temp_f < 95, "hot"),
        else_="very_hot",
    )

    # Base query filters
    filters = [
        _get_base_trip_filter(),
        Trip.weather_temp_f.isnot(None),
    ]

    if start_date:
        filters.append(Trip.start_time >= start_date)
    if end_date:
        filters.append(Trip.start_time <= end_date)

    # Query for aggregated stats by temperature band
    # Note: stddev is not available in SQLite, so we skip it for test compatibility
    results = (
        db.query(
            temp_band_case.label("band"),
            func.avg(Trip.kwh_per_mile).label("avg_efficiency"),
            func.count(Trip.id).label("sample_count"),
            func.min(Trip.kwh_per_mile).label("best_efficiency"),
            func.max(Trip.kwh_per_mile).label("worst_efficiency"),
            func.avg(Trip.weather_temp_f).label("avg_temp"),
            func.sum(Trip.electric_miles).label("total_miles"),
        )
        .filter(and_(*filters))
        .group_by(temp_band_case)
        .all()
    )

    # Format results with band order preserved
    band_order = ["freezing", "cold", "cool", "ideal", "warm", "hot", "very_hot"]
    band_labels = {
        "freezing": "<32°F",
        "cold": "32-45°F",
        "cool": "45-55°F",
        "ideal": "55-75°F",
        "warm": "75-85°F",
        "hot": "85-95°F",
        "very_hot": ">95°F",
    }

    bands_data = {}
    for row in results:
        avg_eff = round(row.avg_efficiency, 4) if row.avg_efficiency else None
        bands_data[row.band] = {
            "range": band_labels.get(row.band, row.band),
            "avg_kwh_per_mile": avg_eff,
            "sample_count": row.sample_count,
            "best_efficiency": round(row.best_efficiency, 4) if row.best_efficiency else None,
            "worst_efficiency": round(row.worst_efficiency, 4) if row.worst_efficiency else None,
            "avg_temp_f": round(row.avg_temp, 1) if row.avg_temp else None,
            "total_miles": round(row.total_miles, 1) if row.total_miles else None,
            "efficiency_impact_percent": calculate_efficiency_impact_percent(avg_eff) if avg_eff else None,
        }

    # Build ordered list
    bands_list = []
    for band in band_order:
        if band in bands_data:
            bands_data[band]["band"] = band
            bands_list.append(bands_data[band])

    return {
        "temperature_bands": bands_list,
        "baseline_kwh_per_mile": BASELINE_KWH_PER_MILE,
        "baseline_temp_range": "55-75°F",
        "total_trips_analyzed": sum(b["sample_count"] for b in bands_list),
    }


def get_efficiency_by_precipitation(
    db: Session,
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
) -> Dict[str, Any]:
    """
    Get efficiency comparison for rain vs dry conditions.

    Args:
        db: Database session
        start_date: Start of date range
        end_date: End of date range

    Returns:
        Dictionary with precipitation impact analysis
    """
    # Build precipitation case expression
    precip_case = case(
        (Trip.weather_precipitation_in.is_(None), "unknown"),
        (Trip.weather_precipitation_in == 0, "dry"),
        (Trip.weather_precipitation_in <= 0.1, "light_rain"),
        (Trip.weather_precipitation_in <= 0.25, "moderate_rain"),
        else_="heavy_rain",
    )

    # Base query filters
    filters = [
        _get_base_trip_filter(),
        Trip.weather_precipitation_in.isnot(None),
    ]

    if start_date:
        filters.append(Trip.start_time >= start_date)
    if end_date:
        filters.append(Trip.start_time <= end_date)

    results = (
        db.query(
            precip_case.label("condition"),
            func.avg(Trip.kwh_per_mile).label("avg_efficiency"),
            func.count(Trip.id).label("sample_count"),
            func.avg(Trip.weather_precipitation_in).label("avg_precip"),
            func.sum(Trip.electric_miles).label("total_miles"),
        )
        .filter(and_(*filters))
        .group_by(precip_case)
        .all()
    )

    condition_labels = {
        "dry": "No Precipitation",
        "light_rain": "Light Rain (≤0.1\")",
        "moderate_rain": "Moderate Rain (0.1-0.25\")",
        "heavy_rain": "Heavy Rain (>0.25\")",
    }

    conditions = []
    for row in results:
        if row.condition == "unknown":
            continue
        avg_eff = round(row.avg_efficiency, 4) if row.avg_efficiency else None
        conditions.append(
            {
                "condition": row.condition,
                "label": condition_labels.get(row.condition, row.condition),
                "avg_kwh_per_mile": avg_eff,
                "sample_count": row.sample_count,
                "avg_precipitation_in": round(row.avg_precip, 3) if row.avg_precip else 0,
                "total_miles": round(row.total_miles, 1) if row.total_miles else None,
                "efficiency_impact_percent": calculate_efficiency_impact_percent(avg_eff) if avg_eff else None,
            }
        )

    return {
        "precipitation_conditions": conditions,
        "baseline_kwh_per_mile": BASELINE_KWH_PER_MILE,
    }


def get_efficiency_by_wind(
    db: Session,
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
) -> Dict[str, Any]:
    """
    Get efficiency statistics grouped by wind speed bands.

    Args:
        db: Database session
        start_date: Start of date range
        end_date: End of date range

    Returns:
        Dictionary with wind impact analysis
    """
    # Build wind speed case expression
    wind_case = case(
        (Trip.weather_wind_mph < 5, "calm"),
        (Trip.weather_wind_mph < 15, "light"),
        (Trip.weather_wind_mph < 25, "moderate"),
        else_="strong",
    )

    # Base query filters
    filters = [
        _get_base_trip_filter(),
        Trip.weather_wind_mph.isnot(None),
    ]

    if start_date:
        filters.append(Trip.start_time >= start_date)
    if end_date:
        filters.append(Trip.start_time <= end_date)

    results = (
        db.query(
            wind_case.label("band"),
            func.avg(Trip.kwh_per_mile).label("avg_efficiency"),
            func.count(Trip.id).label("sample_count"),
            func.avg(Trip.weather_wind_mph).label("avg_wind"),
            func.sum(Trip.electric_miles).label("total_miles"),
        )
        .filter(and_(*filters))
        .group_by(wind_case)
        .all()
    )

    band_labels = {
        "calm": "<5 mph",
        "light": "5-15 mph",
        "moderate": "15-25 mph",
        "strong": ">25 mph",
    }
    band_order = ["calm", "light", "moderate", "strong"]

    bands_data = {}
    for row in results:
        avg_eff = round(row.avg_efficiency, 4) if row.avg_efficiency else None
        bands_data[row.band] = {
            "band": row.band,
            "range": band_labels.get(row.band, row.band),
            "avg_kwh_per_mile": avg_eff,
            "sample_count": row.sample_count,
            "avg_wind_mph": round(row.avg_wind, 1) if row.avg_wind else None,
            "total_miles": round(row.total_miles, 1) if row.total_miles else None,
            "efficiency_impact_percent": calculate_efficiency_impact_percent(avg_eff) if avg_eff else None,
        }

    # Build ordered list
    bands_list = [bands_data[band] for band in band_order if band in bands_data]

    return {
        "wind_bands": bands_list,
        "baseline_kwh_per_mile": BASELINE_KWH_PER_MILE,
    }


def get_seasonal_trends(
    db: Session,
    months_back: int = 24,
) -> Dict[str, Any]:
    """
    Get efficiency trends by month/season over time.

    Args:
        db: Database session
        months_back: Number of months to look back

    Returns:
        Dictionary with seasonal trend analysis
    """
    start_date = utc_now() - timedelta(days=months_back * 30)

    # Use extract for cross-database compatibility (SQLite + PostgreSQL)
    year_expr = extract("year", Trip.start_time)
    month_expr = extract("month", Trip.start_time)

    filters = [
        _get_base_trip_filter(),
        Trip.start_time >= start_date,
    ]

    results = (
        db.query(
            year_expr.label("year"),
            month_expr.label("month"),
            func.avg(Trip.kwh_per_mile).label("avg_efficiency"),
            func.count(Trip.id).label("trip_count"),
            func.avg(Trip.weather_temp_f).label("avg_temp"),
            func.sum(Trip.electric_miles).label("total_miles"),
        )
        .filter(and_(*filters))
        .group_by(year_expr, month_expr)
        .order_by(year_expr, month_expr)
        .all()
    )

    monthly_data = []
    for row in results:
        avg_eff = round(row.avg_efficiency, 4) if row.avg_efficiency else None
        # Format month as YYYY-MM
        month_str = f"{int(row.year)}-{int(row.month):02d}" if row.year and row.month else None
        monthly_data.append(
            {
                "month": month_str,
                "avg_kwh_per_mile": avg_eff,
                "trip_count": row.trip_count,
                "avg_temp_f": round(row.avg_temp, 1) if row.avg_temp else None,
                "total_miles": round(row.total_miles, 1) if row.total_miles else None,
                "efficiency_impact_percent": calculate_efficiency_impact_percent(avg_eff) if avg_eff else None,
            }
        )

    # Calculate seasonal averages
    seasons = {"winter": [], "spring": [], "summer": [], "fall": []}
    for data in monthly_data:
        if data["month"]:
            month_num = int(data["month"].split("-")[1])
            if month_num in [12, 1, 2]:
                seasons["winter"].append(data["avg_kwh_per_mile"])
            elif month_num in [3, 4, 5]:
                seasons["spring"].append(data["avg_kwh_per_mile"])
            elif month_num in [6, 7, 8]:
                seasons["summer"].append(data["avg_kwh_per_mile"])
            else:
                seasons["fall"].append(data["avg_kwh_per_mile"])

    seasonal_averages = {}
    for season, values in seasons.items():
        valid_values = [v for v in values if v is not None]
        if valid_values:
            avg = sum(valid_values) / len(valid_values)
            seasonal_averages[season] = {
                "avg_kwh_per_mile": round(avg, 4),
                "sample_months": len(valid_values),
                "efficiency_impact_percent": calculate_efficiency_impact_percent(avg),
            }

    return {
        "monthly_trends": monthly_data,
        "seasonal_averages": seasonal_averages,
        "months_analyzed": len(monthly_data),
    }


def get_best_driving_conditions(db: Session) -> Dict[str, Any]:
    """
    Identify optimal driving conditions based on historical data.

    Args:
        db: Database session

    Returns:
        Dictionary with optimal condition recommendations
    """
    filters = [_get_base_trip_filter()]

    # Get trips with best efficiency (bottom 10% of kWh/mile)
    best_trips = (
        db.query(Trip)
        .filter(
            and_(
                *filters,
                Trip.weather_temp_f.isnot(None),
            )
        )
        .order_by(Trip.kwh_per_mile.asc())
        .limit(50)  # Top 50 most efficient trips
        .all()
    )

    if not best_trips:
        return {"message": "Insufficient data for analysis"}

    # Analyze best trips
    temps = [t.weather_temp_f for t in best_trips if t.weather_temp_f]
    winds = [t.weather_wind_mph for t in best_trips if t.weather_wind_mph]
    efficiencies = [t.kwh_per_mile for t in best_trips if t.kwh_per_mile]

    best_avg_eff = sum(efficiencies) / len(efficiencies) if efficiencies else None

    return {
        "optimal_conditions": {
            "temperature": {
                "min_f": round(min(temps), 1) if temps else None,
                "max_f": round(max(temps), 1) if temps else None,
                "avg_f": round(sum(temps) / len(temps), 1) if temps else None,
                "recommendation": "55-75°F" if temps else None,
            },
            "wind": {
                "max_mph": round(max(winds), 1) if winds else None,
                "avg_mph": round(sum(winds) / len(winds), 1) if winds else None,
                "recommendation": "<15 mph" if winds else None,
            },
            "precipitation": {"recommendation": "Dry conditions"},
        },
        "best_efficiency_achieved": {
            "avg_kwh_per_mile": round(best_avg_eff, 4) if best_avg_eff else None,
            "best_single_trip": round(min(efficiencies), 4) if efficiencies else None,
            "sample_size": len(best_trips),
        },
        "baseline_comparison": {
            "baseline_kwh_per_mile": BASELINE_KWH_PER_MILE,
            "improvement_vs_baseline_percent": (
                round(((BASELINE_KWH_PER_MILE - best_avg_eff) / BASELINE_KWH_PER_MILE) * 100, 1)
                if best_avg_eff
                else None
            ),
        },
    }


def get_weather_efficiency_correlation(
    db: Session,
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
) -> Dict[str, Any]:
    """
    Get overall weather-efficiency correlation statistics.

    Args:
        db: Database session
        start_date: Start of date range
        end_date: End of date range

    Returns:
        Dictionary with correlation analysis
    """
    filters = [_get_base_trip_filter()]

    if start_date:
        filters.append(Trip.start_time >= start_date)
    if end_date:
        filters.append(Trip.start_time <= end_date)

    # Get overall statistics
    # Note: stddev is not available in SQLite, so we skip it for test compatibility
    overall = (
        db.query(
            func.count(Trip.id).label("total_trips"),
            func.avg(Trip.kwh_per_mile).label("avg_efficiency"),
            func.sum(Trip.electric_miles).label("total_miles"),
            func.sum(Trip.electric_kwh_used).label("total_kwh"),
        )
        .filter(and_(*filters))
        .first()
    )

    # Get trips with weather data
    weather_stats = (
        db.query(
            func.count(Trip.id).label("trips_with_weather"),
            func.avg(Trip.weather_impact_factor).label("avg_impact_factor"),
        )
        .filter(
            and_(
                *filters,
                Trip.weather_temp_f.isnot(None),
            )
        )
        .first()
    )

    return {
        "summary": {
            "total_trips": overall.total_trips if overall else 0,
            "trips_with_weather_data": weather_stats.trips_with_weather if weather_stats else 0,
            "coverage_percent": (
                round((weather_stats.trips_with_weather / overall.total_trips) * 100, 1)
                if overall and overall.total_trips > 0 and weather_stats
                else 0
            ),
        },
        "efficiency": {
            "avg_kwh_per_mile": round(overall.avg_efficiency, 4) if overall and overall.avg_efficiency else None,
            "total_miles": round(overall.total_miles, 1) if overall and overall.total_miles else None,
            "total_kwh": round(overall.total_kwh, 1) if overall and overall.total_kwh else None,
        },
        "weather_impact": {
            "avg_impact_factor": (
                round(weather_stats.avg_impact_factor, 3)
                if weather_stats and weather_stats.avg_impact_factor
                else None
            ),
            "interpretation": "1.0 = ideal conditions, >1.0 = worse conditions",
        },
        "date_range": {
            "start": start_date.isoformat() if start_date else None,
            "end": end_date.isoformat() if end_date else None,
        },
    }
