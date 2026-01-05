"""
Route Analysis Service

Detect common routes from GPS data using clustering.
Simplified implementation without complex ML libraries.
"""

import logging
from math import asin, cos, radians, sin, sqrt
from typing import Dict, List, Optional, Tuple

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

    # Get first and last telemetry points with GPS
    telemetry = (
        db.query(TelemetryRaw)
        .filter(
            and_(
                TelemetryRaw.session_id == trip.session_id,
                TelemetryRaw.latitude.isnot(None),
                TelemetryRaw.longitude.isnot(None),
            )
        )
        .order_by(TelemetryRaw.timestamp)
        .all()
    )

    if len(telemetry) < 2:
        return None

    start = telemetry[0]
    end = telemetry[-1]

    return (start.latitude, start.longitude, end.latitude, end.longitude)


def find_matching_route(
    db: Session, start_lat: float, start_lon: float, end_lat: float, end_lon: float, threshold_miles: float = 0.5
) -> Optional[Route]:
    """
    Find existing route that matches these endpoints.

    Args:
        threshold_miles: Max distance in miles to consider a match
    """
    routes = db.query(Route).all()

    for route in routes:
        start_dist = haversine_distance(start_lat, start_lon, route.start_lat, route.start_lon)
        end_dist = haversine_distance(end_lat, end_lon, route.end_lat, route.end_lon)

        # Match if both endpoints are within threshold
        if start_dist <= threshold_miles and end_dist <= threshold_miles:
            return route

    return None


def detect_routes(db: Session, min_trips: int = 3) -> List[Dict]:
    """
    Detect common routes from trip history.

    Simplified clustering: group trips with similar start/end points.
    """
    # Get trips with GPS data
    trips = (
        (db.query(Trip).filter(and_(Trip.distance_miles > 0.5, Trip.electric_miles > 0)))
        .order_by(Trip.start_time.desc())
        .limit(100)
        .all()
    )

    routes_data = []

    for trip in trips:
        endpoints = get_trip_endpoints(db, trip.id)
        if not endpoints:
            continue

        start_lat, start_lon, end_lat, end_lon = endpoints

        # Check if matches existing route
        existing_route = find_matching_route(db, start_lat, start_lon, end_lat, end_lon)

        if existing_route:
            # Update existing route statistics
            existing_route.trip_count += 1

            # Update averages
            n = existing_route.trip_count
            existing_route.avg_distance_miles = (
                (existing_route.avg_distance_miles or 0) * (n - 1) + trip.distance_miles
            ) / n

            if trip.kwh_per_mile:
                existing_route.avg_efficiency_kwh_per_mile = (
                    (existing_route.avg_efficiency_kwh_per_mile or 0) * (n - 1) + trip.kwh_per_mile
                ) / n

                # Track best/worst
                if not existing_route.best_efficiency or trip.kwh_per_mile < existing_route.best_efficiency:
                    existing_route.best_efficiency = trip.kwh_per_mile

                if not existing_route.worst_efficiency or trip.kwh_per_mile > existing_route.worst_efficiency:
                    existing_route.worst_efficiency = trip.kwh_per_mile

            duration = (trip.end_time - trip.start_time).total_seconds() / 60
            existing_route.avg_duration_minutes = ((existing_route.avg_duration_minutes or 0) * (n - 1) + duration) / n
            existing_route.last_traveled = trip.start_time

            db.commit()
        else:
            # Create new route
            new_route = Route(
                name=f"Route {len(routes_data) + 1}",  # Auto-name
                start_lat=start_lat,
                start_lon=start_lon,
                end_lat=end_lat,
                end_lon=end_lon,
                trip_count=1,
                avg_distance_miles=trip.distance_miles,
                avg_efficiency_kwh_per_mile=trip.kwh_per_mile,
                avg_duration_minutes=(trip.end_time - trip.start_time).total_seconds() / 60,
                best_efficiency=trip.kwh_per_mile,
                worst_efficiency=trip.kwh_per_mile,
                last_traveled=trip.start_time,
            )
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
