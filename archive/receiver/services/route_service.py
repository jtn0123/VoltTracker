"""
Route Analysis Service

Detect common routes from GPS data using clustering.
Simplified implementation without complex ML libraries.
"""

import logging
from math import asin, cos, radians, sin, sqrt
from typing import Any, Dict, List, Optional, Tuple

from models import Route, TelemetryRaw, Trip
from sqlalchemy import and_
from sqlalchemy.orm import Session

logger = logging.getLogger(__name__)


def haversine_distance(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """
    Calculate distance between two GPS coordinates in miles.
    """
    # Convert to radians
    lat1, lon1, lat2, lon2 = map(radians, [lat1, lon1, lat2, lon2])

    # Haversine formula
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    a = sin(dlat / 2) ** 2 + cos(lat1) * cos(lat2) * sin(dlon / 2) ** 2
    c = 2 * asin(sqrt(a))

    # Earth radius in miles
    miles = 3956 * c
    return miles


def get_trip_endpoints(db: Session, trip_id: int) -> Optional[Tuple]:
    """
    Get start and end coordinates for a trip.

    Returns: (start_lat, start_lon, end_lat, end_lon) or None
    """
    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if not trip:
        return None

    # Get first GPS point
    start_point = (
        db.query(TelemetryRaw)
        .filter(
            and_(
                TelemetryRaw.session_id == trip.session_id,
                TelemetryRaw.latitude.isnot(None),
                TelemetryRaw.longitude.isnot(None),
            )
        )
        .order_by(TelemetryRaw.timestamp)
        .first()
    )

    # Get last GPS point
    end_point = (
        db.query(TelemetryRaw)
        .filter(
            and_(
                TelemetryRaw.session_id == trip.session_id,
                TelemetryRaw.latitude.isnot(None),
                TelemetryRaw.longitude.isnot(None),
            )
        )
        .order_by(TelemetryRaw.timestamp.desc())
        .first()
    )

    if not start_point or not end_point:
        return None

    # If same point (only one GPS point in trip), return None
    if start_point.id == end_point.id:
        return None

    return (start_point.latitude, start_point.longitude, end_point.latitude, end_point.longitude)


def find_matching_route(
    db: Session, start_lat: float, start_lon: float, end_lat: float, end_lon: float, threshold_miles: float = 0.5
) -> Optional[Route]:
    """
    Find existing route that matches these endpoints.

    Args:
        threshold_miles: Max distance in miles to consider a match

    Returns:
        Closest matching route, or None if no routes within threshold
    """
    routes = db.query(Route).all()

    best_match = None
    best_distance = float("inf")

    for route in routes:
        start_dist = haversine_distance(start_lat, start_lon, route.start_lat, route.start_lon)
        end_dist = haversine_distance(end_lat, end_lon, route.end_lat, route.end_lon)

        # Match if both endpoints are within threshold
        if start_dist <= threshold_miles and end_dist <= threshold_miles:
            total_dist = start_dist + end_dist
            if total_dist < best_distance:
                best_distance = total_dist
                best_match = route

    return best_match  # type: ignore[no-any-return]  # SQLAlchemy query result typed as Any


def _update_existing_route(route, trip):
    """Update an existing route's rolling statistics with a new trip."""
    route.trip_count += 1
    n = route.trip_count
    route.avg_distance_miles = ((route.avg_distance_miles or 0) * (n - 1) + trip.distance_miles) / n

    if trip.kwh_per_mile:
        route.avg_efficiency_kwh_per_mile = (
            (route.avg_efficiency_kwh_per_mile or 0) * (n - 1) + trip.kwh_per_mile
        ) / n
        if not route.best_efficiency or trip.kwh_per_mile < route.best_efficiency:
            route.best_efficiency = trip.kwh_per_mile
        if not route.worst_efficiency or trip.kwh_per_mile > route.worst_efficiency:
            route.worst_efficiency = trip.kwh_per_mile

    duration = (trip.end_time - trip.start_time).total_seconds() / 60
    route.avg_duration_minutes = ((route.avg_duration_minutes or 0) * (n - 1) + duration) / n
    route.last_traveled = trip.start_time


def _create_new_route(start_lat, start_lon, end_lat, end_lon, trip, route_num):
    """Create a new Route model from a trip's endpoints."""
    return Route(
        name=f"Route {route_num}",
        start_lat=start_lat, start_lon=start_lon,
        end_lat=end_lat, end_lon=end_lon,
        trip_count=1,
        avg_distance_miles=trip.distance_miles,
        avg_efficiency_kwh_per_mile=trip.kwh_per_mile,
        avg_duration_minutes=(trip.end_time - trip.start_time).total_seconds() / 60,
        best_efficiency=trip.kwh_per_mile,
        worst_efficiency=trip.kwh_per_mile,
        last_traveled=trip.start_time,
    )


def detect_routes(db: Session, min_trips: int = 3) -> List[Dict]:
    """
    Detect common routes from trip history.

    Simplified clustering: group trips with similar start/end points.
    """
    # Get trips with GPS data. Only closed trips with an end_time can be
    # turned into routes — open trips have end_time=None and would crash the
    # duration calculation in _create_new_route / _update_existing_route.
    trips = (
        (
            db.query(Trip).filter(
                and_(
                    Trip.distance_miles > 0.5,
                    Trip.electric_miles > 0,
                    Trip.is_closed.is_(True),
                    Trip.end_time.isnot(None),
                )
            )
        )
        .order_by(Trip.start_time.desc())
        .limit(100)
        .all()
    )

    routes_data: list[Dict[str, Any]] = []

    for trip in trips:
        endpoints = get_trip_endpoints(db, trip.id)
        if not endpoints:
            continue

        start_lat, start_lon, end_lat, end_lon = endpoints
        existing_route = find_matching_route(db, start_lat, start_lon, end_lat, end_lon)

        if existing_route:
            _update_existing_route(existing_route, trip)
            db.commit()
        else:
            new_route = _create_new_route(start_lat, start_lon, end_lat, end_lon,
                                          trip, len(routes_data) + 1)
            db.add(new_route)
            db.commit()
            routes_data.append(new_route.to_dict())

    # Get all routes that meet minimum trip threshold
    all_routes = db.query(Route).filter(Route.trip_count >= min_trips).order_by(Route.trip_count.desc()).all()

    return [route.to_dict() for route in all_routes]


def get_route_summary(db: Session) -> Dict:
    """
    Get summary of detected routes.
    """
    routes = db.query(Route).order_by(Route.trip_count.desc()).all()

    if not routes:
        return {
            "total_routes": 0,
            "message": "No routes detected yet. Drive more trips to build route history!",
        }

    return {
        "total_routes": len(routes),
        "most_frequent": routes[0].to_dict() if routes else None,
        "routes": [r.to_dict() for r in routes[:10]],  # Top 10
    }
