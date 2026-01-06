"""
Elevation Analytics Service for VoltTracker

Provides aggregation queries and correlation analysis between
elevation changes and trip efficiency.
"""

import logging
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional

from sqlalchemy import and_, case, func
from sqlalchemy.orm import Session

from models import Route, Trip
from utils.timezone import utc_now

logger = logging.getLogger(__name__)

# Elevation change categories (meters per mile)
# Flat: <10m gain per mile, Moderate: 10-30m, Hilly: 30-60m, Steep: >60m
BASELINE_KWH_PER_MILE = 0.32


def _get_base_trip_filter():
    """Return base filter for valid trips with efficiency and elevation data."""
    return and_(
        Trip.is_closed == True,  # noqa: E712
        Trip.deleted_at.is_(None),
        Trip.kwh_per_mile.isnot(None),
        Trip.kwh_per_mile > 0,
        Trip.kwh_per_mile < 1.0,
        Trip.electric_miles.isnot(None),
        Trip.electric_miles > 0.5,
        Trip.elevation_gain_m.isnot(None),
    )


def _calculate_efficiency_impact(avg_efficiency: float) -> float:
    """Calculate efficiency impact as percentage vs baseline."""
    if avg_efficiency is None or BASELINE_KWH_PER_MILE == 0:
        return 0.0
    return round(((avg_efficiency - BASELINE_KWH_PER_MILE) / BASELINE_KWH_PER_MILE) * 100, 1)


def get_efficiency_by_elevation_change(
    db: Session,
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
) -> Dict[str, Any]:
    """
    Get efficiency statistics grouped by net elevation change categories.

    Categories:
        - steep_downhill: < -50m net
        - moderate_downhill: -50 to -10m net
        - flat: -10 to +10m net
        - moderate_uphill: +10 to +50m net
        - steep_uphill: > +50m net

    Returns:
        Dictionary with elevation category analysis
    """
    # Build elevation category case expression
    elevation_case = case(
        (Trip.elevation_net_change_m < -50, "steep_downhill"),
        (Trip.elevation_net_change_m < -10, "moderate_downhill"),
        (Trip.elevation_net_change_m < 10, "flat"),
        (Trip.elevation_net_change_m < 50, "moderate_uphill"),
        else_="steep_uphill",
    )

    filters = [_get_base_trip_filter()]

    if start_date:
        filters.append(Trip.start_time >= start_date)
    if end_date:
        filters.append(Trip.start_time <= end_date)

    results = (
        db.query(
            elevation_case.label("category"),
            func.avg(Trip.kwh_per_mile).label("avg_efficiency"),
            func.count(Trip.id).label("sample_count"),
            func.avg(Trip.elevation_net_change_m).label("avg_net_change"),
            func.avg(Trip.elevation_gain_m).label("avg_gain"),
            func.avg(Trip.elevation_loss_m).label("avg_loss"),
            func.sum(Trip.electric_miles).label("total_miles"),
        )
        .filter(and_(*filters))
        .group_by(elevation_case)
        .all()
    )

    category_labels = {
        "steep_downhill": "Steep downhill (<-50m)",
        "moderate_downhill": "Moderate downhill (-50 to -10m)",
        "flat": "Flat (-10 to +10m)",
        "moderate_uphill": "Moderate uphill (+10 to +50m)",
        "steep_uphill": "Steep uphill (>+50m)",
    }
    category_order = ["steep_downhill", "moderate_downhill", "flat", "moderate_uphill", "steep_uphill"]

    categories_data = {}
    for row in results:
        avg_eff = round(row.avg_efficiency, 4) if row.avg_efficiency else None
        categories_data[row.category] = {
            "category": row.category,
            "label": category_labels.get(row.category, row.category),
            "avg_kwh_per_mile": avg_eff,
            "sample_count": row.sample_count,
            "avg_net_change_m": round(row.avg_net_change, 1) if row.avg_net_change else None,
            "avg_gain_m": round(row.avg_gain, 1) if row.avg_gain else None,
            "avg_loss_m": round(row.avg_loss, 1) if row.avg_loss else None,
            "total_miles": round(row.total_miles, 1) if row.total_miles else None,
            "efficiency_impact_percent": _calculate_efficiency_impact(avg_eff) if avg_eff else None,
        }

    # Build ordered list
    categories_list = [categories_data[cat] for cat in category_order if cat in categories_data]

    return {
        "elevation_categories": categories_list,
        "baseline_kwh_per_mile": BASELINE_KWH_PER_MILE,
        "total_trips_analyzed": sum(c["sample_count"] for c in categories_list),
    }


def get_efficiency_by_gradient(
    db: Session,
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
) -> Dict[str, Any]:
    """
    Get efficiency statistics by average gradient (meters gained per mile).

    This measures how "hilly" a trip is overall.
    """
    filters = [
        _get_base_trip_filter(),
        Trip.distance_miles.isnot(None),
        Trip.distance_miles > 0,
    ]

    if start_date:
        filters.append(Trip.start_time >= start_date)
    if end_date:
        filters.append(Trip.start_time <= end_date)

    # Get trips with calculated gradient
    trips = (
        db.query(
            Trip.id,
            Trip.kwh_per_mile,
            Trip.elevation_gain_m,
            Trip.electric_miles,
            (Trip.elevation_gain_m / Trip.electric_miles).label("gradient_m_per_mile"),
        )
        .filter(and_(*filters))
        .all()
    )

    if not trips:
        return {"gradient_analysis": [], "total_trips_analyzed": 0}

    # Group by gradient bands
    bands = {
        "very_flat": {"min": 0, "max": 10, "label": "Very flat (<10m/mi)", "trips": []},
        "gentle": {"min": 10, "max": 30, "label": "Gentle (10-30m/mi)", "trips": []},
        "moderate": {"min": 30, "max": 60, "label": "Moderate (30-60m/mi)", "trips": []},
        "hilly": {"min": 60, "max": 100, "label": "Hilly (60-100m/mi)", "trips": []},
        "mountainous": {"min": 100, "max": float("inf"), "label": "Mountainous (>100m/mi)", "trips": []},
    }

    for trip in trips:
        gradient = trip.gradient_m_per_mile or 0
        for band_name, band_info in bands.items():
            if band_info["min"] <= gradient < band_info["max"]:
                band_info["trips"].append(trip.kwh_per_mile)
                break

    # Calculate statistics for each band
    gradient_list = []
    for band_name, band_info in bands.items():
        efficiencies = band_info["trips"]
        if efficiencies:
            avg_eff = sum(efficiencies) / len(efficiencies)
            gradient_list.append(
                {
                    "band": band_name,
                    "label": band_info["label"],
                    "avg_kwh_per_mile": round(avg_eff, 4),
                    "sample_count": len(efficiencies),
                    "efficiency_impact_percent": _calculate_efficiency_impact(avg_eff),
                }
            )

    return {
        "gradient_analysis": gradient_list,
        "total_trips_analyzed": sum(len(b["trips"]) for b in bands.values()),
    }


def get_elevation_summary(db: Session) -> Dict[str, Any]:
    """
    Get overall elevation statistics summary.
    """
    filters = [_get_base_trip_filter()]

    # Overall stats
    stats = (
        db.query(
            func.count(Trip.id).label("total_trips"),
            func.avg(Trip.elevation_gain_m).label("avg_gain"),
            func.avg(Trip.elevation_loss_m).label("avg_loss"),
            func.max(Trip.elevation_gain_m).label("max_gain"),
            func.max(Trip.elevation_loss_m).label("max_loss"),
            func.avg(Trip.kwh_per_mile).label("avg_efficiency"),
        )
        .filter(and_(*filters))
        .first()
    )

    # Trips with data
    trips_with_elevation = db.query(func.count(Trip.id)).filter(and_(*filters)).scalar()

    trips_without = (
        db.query(func.count(Trip.id))
        .filter(
            and_(
                Trip.is_closed == True,  # noqa: E712
                Trip.deleted_at.is_(None),
                Trip.elevation_gain_m.is_(None),
            )
        )
        .scalar()
    )

    return {
        "summary": {
            "trips_with_elevation": trips_with_elevation or 0,
            "trips_without_elevation": trips_without or 0,
            "coverage_percent": (
                round(trips_with_elevation / (trips_with_elevation + trips_without) * 100, 1)
                if (trips_with_elevation + trips_without) > 0
                else 0
            ),
        },
        "statistics": {
            "avg_elevation_gain_m": round(stats.avg_gain, 1) if stats and stats.avg_gain else None,
            "avg_elevation_loss_m": round(stats.avg_loss, 1) if stats and stats.avg_loss else None,
            "max_elevation_gain_m": round(stats.max_gain, 1) if stats and stats.max_gain else None,
            "max_elevation_loss_m": round(stats.max_loss, 1) if stats and stats.max_loss else None,
            "avg_efficiency_kwh_per_mile": round(stats.avg_efficiency, 4) if stats and stats.avg_efficiency else None,
        },
    }


def get_route_elevation_comparison(db: Session) -> List[Dict[str, Any]]:
    """
    Compare routes by their elevation characteristics.
    """
    routes = (
        db.query(Route)
        .filter(
            Route.avg_elevation_gain_m.isnot(None),
            Route.trip_count > 1,
        )
        .order_by(Route.avg_elevation_gain_m.desc())
        .limit(20)
        .all()
    )

    return [
        {
            "id": r.id,
            "name": r.name,
            "trip_count": r.trip_count,
            "avg_distance_miles": r.avg_distance_miles,
            "avg_elevation_gain_m": r.avg_elevation_gain_m,
            "avg_elevation_loss_m": r.avg_elevation_loss_m,
            "avg_efficiency": r.avg_efficiency_kwh_per_mile,
        }
        for r in routes
    ]
