"""
Context Enrichment Utilities for Wide Events

Following loggingsucks.com recommendations for comprehensive business context:
- User/Vehicle lifetime statistics
- Account age and usage patterns
- Battery health metrics
- Usage tier classification (heavy/moderate/light)

This module provides helpers to enrich WideEvents with vehicle/user context.
"""

from typing import TYPE_CHECKING, Any, Dict

from models import Trip
from sqlalchemy import func
from sqlalchemy.orm import Session
from utils.timezone import normalize_datetime, utc_now

if TYPE_CHECKING:
    from utils.wide_events import WideEvent


def get_vehicle_statistics(db: Session) -> Dict[str, Any]:
    """
    Calculate lifetime vehicle statistics for context enrichment.

    Following loggingsucks.com pattern:
    - Account age (days since first trip)
    - Lifetime value (total trips, total miles driven)
    - Usage tier classification (heavy/moderate/light)

    Args:
        db: Database session

    Returns:
        Dictionary with vehicle context fields
    """
    # Count total trips
    total_trips = db.query(Trip).filter(Trip.is_closed.is_(True)).count()

    # Calculate total miles driven (sum of all trip distances)
    total_miles_result = db.query(func.sum(Trip.distance_miles)).filter(Trip.is_closed.is_(True)).scalar()
    total_miles = float(total_miles_result) if total_miles_result else 0.0

    # Get first trip timestamp for account age calculation
    first_trip = db.query(Trip).order_by(Trip.start_time.asc()).first()

    if first_trip:
        account_age_days = (utc_now() - normalize_datetime(first_trip.start_time)).days
    else:
        account_age_days = 0

    # Calculate usage tier based on trip count
    usage_tier = classify_usage_tier(total_trips)

    # Calculate average efficiency across all trips
    avg_kwh_per_mile = (
        db.query(func.avg(Trip.kwh_per_mile)).filter(Trip.is_closed.is_(True), Trip.kwh_per_mile.isnot(None)).scalar()
    )

    # Calculate average MPG for gas mode
    avg_gas_mpg = db.query(func.avg(Trip.gas_mpg)).filter(Trip.is_closed.is_(True), Trip.gas_mpg.isnot(None)).scalar()

    return {
        "total_trips": total_trips,
        "total_miles": round(total_miles, 1),
        "account_age_days": account_age_days,
        "usage_tier": usage_tier,
        "avg_kwh_per_mile": round(float(avg_kwh_per_mile), 3) if avg_kwh_per_mile else None,
        "avg_gas_mpg": round(float(avg_gas_mpg), 1) if avg_gas_mpg else None,
    }


def classify_usage_tier(total_trips: int) -> str:
    """
    Classify user into usage tier based on trip count.

    Following loggingsucks.com pattern for user segmentation:
    - Heavy users: 100+ trips (equivalent to "enterprise" tier)
    - Moderate users: 20-99 trips (equivalent to "premium" tier)
    - Light users: 1-19 trips (equivalent to "free" tier)
    - New users: 0 trips

    This enables tier-based sampling and debugging prioritization.

    Args:
        total_trips: Total number of completed trips

    Returns:
        Usage tier: "heavy", "moderate", "light", or "new"
    """
    if total_trips == 0:
        return "new"
    elif total_trips < 20:
        return "light"
    elif total_trips < 100:
        return "moderate"
    else:
        return "heavy"


def get_battery_health_metrics(db: Session) -> Dict[str, Any]:
    """
    Calculate battery health metrics from recent trips.

    Battery health indicators:
    - Recent average SOC usage
    - Recent electric miles per charge
    - Capacity degradation estimate

    Args:
        db: Database session

    Returns:
        Dictionary with battery health metrics
    """
    # Get last 30 trips for recent health assessment
    recent_trips = (
        db.query(Trip)
        .filter(Trip.is_closed.is_(True), Trip.electric_miles.isnot(None))
        .order_by(Trip.end_time.desc())
        .limit(30)
        .all()
    )

    if not recent_trips:
        return {}

    # Calculate average electric miles per trip
    electric_miles_avg = sum(t.electric_miles or 0 for t in recent_trips) / len(recent_trips)

    # Calculate average kWh/mile (efficiency indicator)
    kwh_per_mile_values = [t.kwh_per_mile for t in recent_trips if t.kwh_per_mile]
    if kwh_per_mile_values:
        avg_efficiency = sum(kwh_per_mile_values) / len(kwh_per_mile_values)
    else:
        avg_efficiency = None

    # Calculate average SOC drop per trip
    # Trip model doesn't have end_soc, so we use soc_at_gas_transition if available
    soc_drops = []
    for trip in recent_trips:
        if trip.start_soc is not None and trip.soc_at_gas_transition is not None:
            soc_drops.append(trip.start_soc - trip.soc_at_gas_transition)

    avg_soc_drop = sum(soc_drops) / len(soc_drops) if soc_drops else None

    return {
        "recent_avg_electric_miles": round(electric_miles_avg, 1),
        "recent_avg_efficiency_kwh_per_mile": round(avg_efficiency, 3) if avg_efficiency else None,
        "recent_avg_soc_drop_percent": round(avg_soc_drop, 1) if avg_soc_drop else None,
        "sample_size_trips": len(recent_trips),
    }


def get_current_trip_context(db: Session, session_id: str) -> Dict[str, Any]:
    """
    Get context for the current trip.

    Args:
        db: Database session
        session_id: Current trip session ID

    Returns:
        Dictionary with current trip context
    """
    trip = db.query(Trip).filter(Trip.session_id == session_id).first()

    if not trip:
        return {"trip_found": False}

    context = {
        "trip_found": True,
        "trip_id": trip.id,
        "is_closed": trip.is_closed,
    }

    if trip.start_time:
        duration_seconds = (utc_now() - normalize_datetime(trip.start_time)).total_seconds()
        context["trip_duration_seconds"] = int(duration_seconds)

    if trip.start_soc is not None:
        context["start_soc"] = trip.start_soc

    if trip.start_odometer is not None:
        context["start_odometer"] = trip.start_odometer

    return context


def enrich_event_with_vehicle_context(event: "WideEvent", db: Session, include_battery_health: bool = False) -> None:
    """
    Enrich a WideEvent with comprehensive vehicle context.

    Following loggingsucks.com recommendations:
    - Lifetime statistics (total trips, miles, account age)
    - Usage tier classification
    - Average efficiency metrics
    - Optional: Battery health metrics (expensive query)

    Args:
        event: WideEvent to enrich
        db: Database session
        include_battery_health: Whether to include battery health metrics (adds latency)
    """
    # Get lifetime statistics
    vehicle_stats = get_vehicle_statistics(db)
    event.add_vehicle_context(**vehicle_stats)

    # Optionally add battery health (more expensive query)
    if include_battery_health:
        battery_health = get_battery_health_metrics(db)
        if battery_health:
            event.add_vehicle_context(**battery_health)
