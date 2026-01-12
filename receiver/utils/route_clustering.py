"""
Route clustering and similarity utilities for GPS track analysis.

Provides functions to find similar routes, calculate route similarity,
and cluster trips based on geographic proximity.
"""

import logging
import math
from typing import List, Dict, Any, Tuple, Optional

from sqlalchemy.orm import Session
from models import Trip, TelemetryRaw

logger = logging.getLogger(__name__)


def haversine_distance(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """
    Calculate the great circle distance between two points on Earth.
    Returns distance in miles.

    Args:
        lat1, lon1: Coordinates of first point (decimal degrees)
        lat2, lon2: Coordinates of second point (decimal degrees)

    Returns:
        Distance in miles
    """
    # Convert decimal degrees to radians
    lat1, lon1, lat2, lon2 = map(math.radians, [lat1, lon1, lat2, lon2])

    # Haversine formula
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    a = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    c = 2 * math.asin(math.sqrt(a))

    # Radius of Earth in miles
    r = 3959

    return c * r


def calculate_route_similarity(
    route1_points: List[Tuple[float, float]],
    route2_points: List[Tuple[float, float]],
    sample_size: int = 20
) -> float:
    """
    Calculate similarity score between two routes.

    Uses a simplified approach:
    1. Sample N points evenly from each route
    2. Calculate average distance between corresponding points
    3. Convert to similarity score (0-100)

    Args:
        route1_points: List of (lat, lon) tuples for route 1
        route2_points: List of (lat, lon) tuples for route 2
        sample_size: Number of points to sample from each route

    Returns:
        Similarity score from 0 (completely different) to 100 (identical)
    """
    if not route1_points or not route2_points:
        return 0.0

    # Sample points evenly from both routes
    def sample_route(points: List[Tuple[float, float]], n: int) -> List[Tuple[float, float]]:
        if len(points) <= n:
            return points
        step = len(points) / n
        return [points[int(i * step)] for i in range(n)]

    sampled1 = sample_route(route1_points, sample_size)
    sampled2 = sample_route(route2_points, sample_size)

    # Calculate average distance between corresponding points
    total_distance = 0
    for (lat1, lon1), (lat2, lon2) in zip(sampled1, sampled2):
        total_distance += haversine_distance(lat1, lon1, lat2, lon2)

    avg_distance = total_distance / len(sampled1)

    # Convert distance to similarity score
    # Routes within 0.5 miles average distance = 90+ similarity
    # Routes within 1 mile = 70+ similarity
    # Routes > 5 miles apart = <20 similarity

    if avg_distance < 0.5:
        similarity = 100 - (avg_distance * 20)
    elif avg_distance < 2:
        similarity = 90 - (avg_distance - 0.5) * 30
    elif avg_distance < 5:
        similarity = 45 - (avg_distance - 2) * 10
    else:
        similarity = max(0, 15 - (avg_distance - 5) * 2)

    return max(0, min(100, similarity))


def calculate_start_end_similarity(
    start1: Tuple[float, float],
    end1: Tuple[float, float],
    start2: Tuple[float, float],
    end2: Tuple[float, float]
) -> float:
    """
    Calculate similarity based on start and end points only.
    This is a fast pre-filter before doing full route comparison.

    Args:
        start1, end1: (lat, lon) for route 1
        start2, end2: (lat, lon) for route 2

    Returns:
        Similarity score 0-100
    """
    start_distance = haversine_distance(start1[0], start1[1], start2[0], start2[1])
    end_distance = haversine_distance(end1[0], end1[1], end2[0], end2[1])

    # Routes with start/end within 1 mile are potential matches
    avg_distance = (start_distance + end_distance) / 2

    if avg_distance < 0.5:
        return 100
    elif avg_distance < 1:
        return 90 - (avg_distance - 0.5) * 40
    elif avg_distance < 3:
        return 50 - (avg_distance - 1) * 20
    else:
        return max(0, 10 - avg_distance)


def get_trip_gps_points(db: Session, trip: Trip) -> List[Tuple[float, float]]:
    """
    Get GPS points for a trip as list of (lat, lon) tuples.

    Args:
        db: Database session
        trip: Trip model instance

    Returns:
        List of (lat, lon) tuples in chronological order
    """
    telemetry = db.query(TelemetryRaw).filter(
        TelemetryRaw.session_id == trip.session_id,
        TelemetryRaw.latitude.isnot(None),
        TelemetryRaw.longitude.isnot(None)
    ).order_by(TelemetryRaw.timestamp).all()

    return [(float(t.latitude), float(t.longitude)) for t in telemetry]


def calculate_route_bounds(points: List[Tuple[float, float]]) -> Dict[str, float]:
    """
    Calculate bounding box for a route.

    Args:
        points: List of (lat, lon) tuples

    Returns:
        Dict with 'north', 'south', 'east', 'west', 'center_lat', 'center_lon'
    """
    if not points:
        return {
            'north': 0, 'south': 0, 'east': 0, 'west': 0,
            'center_lat': 0, 'center_lon': 0
        }

    lats = [p[0] for p in points]
    lons = [p[1] for p in points]

    return {
        'north': max(lats),
        'south': min(lats),
        'east': max(lons),
        'west': min(lons),
        'center_lat': sum(lats) / len(lats),
        'center_lon': sum(lons) / len(lons)
    }


def find_similar_trips(
    db: Session,
    reference_trip: Trip,
    max_results: int = 10,
    min_similarity: float = 70.0
) -> List[Dict[str, Any]]:
    """
    Find trips with similar routes to the reference trip.

    Args:
        db: Database session
        reference_trip: Reference trip to compare against
        max_results: Maximum number of results to return
        min_similarity: Minimum similarity score (0-100)

    Returns:
        List of dicts with trip info and similarity score, sorted by similarity (highest first)
    """
    # Get GPS points for reference trip
    ref_points = get_trip_gps_points(db, reference_trip)

    if len(ref_points) < 2:
        logger.warning(f"Reference trip {reference_trip.id} has insufficient GPS data")
        return []

    ref_start = ref_points[0]
    ref_end = ref_points[-1]
    ref_bounds = calculate_route_bounds(ref_points)

    # Query candidate trips (exclude reference trip itself)
    # Filter by approximate location using database query for efficiency
    distance_tolerance = 0.2  # ~0.2 degrees latitude ≈ 14 miles

    candidate_trips = db.query(Trip).filter(
        Trip.id != reference_trip.id,
        Trip.is_closed.is_(True),
        Trip.deleted_at.is_(None)
    ).limit(500).all()  # Limit to recent trips for performance

    similar_trips = []

    for candidate in candidate_trips:
        # Get candidate GPS points
        candidate_points = get_trip_gps_points(db, candidate)

        if len(candidate_points) < 2:
            continue

        # Fast pre-filter: Check start/end point similarity
        cand_start = candidate_points[0]
        cand_end = candidate_points[-1]

        start_end_similarity = calculate_start_end_similarity(
            ref_start, ref_end, cand_start, cand_end
        )

        # Skip if start/end points are too different
        if start_end_similarity < 30:
            continue

        # Full route comparison for promising candidates
        route_similarity = calculate_route_similarity(ref_points, candidate_points)

        if route_similarity >= min_similarity:
            similar_trips.append({
                'trip_id': candidate.id,
                'start_time': candidate.start_time.isoformat(),
                'distance_miles': round(candidate.distance_miles, 2) if candidate.distance_miles else 0,
                'kwh_per_mile': round(candidate.kwh_per_mile, 3) if candidate.kwh_per_mile else None,
                'gas_mpg': round(candidate.gas_mpg, 1) if candidate.gas_mpg else None,
                'similarity_score': round(route_similarity, 1),
                'start_point': {'lat': cand_start[0], 'lon': cand_start[1]},
                'end_point': {'lat': cand_end[0], 'lon': cand_end[1]}
            })

    # Sort by similarity (highest first) and limit results
    similar_trips.sort(key=lambda x: x['similarity_score'], reverse=True)
    return similar_trips[:max_results]


def cluster_trips_by_route(
    db: Session,
    trips: List[Trip],
    similarity_threshold: float = 75.0
) -> List[List[str]]:
    """
    Cluster trips into groups with similar routes.

    Uses a simple greedy clustering approach:
    1. Start with first trip as cluster center
    2. Add similar trips to cluster
    3. Repeat with remaining trips

    Args:
        db: Database session
        trips: List of Trip objects to cluster
        similarity_threshold: Minimum similarity to be in same cluster

    Returns:
        List of clusters, where each cluster is a list of trip IDs
    """
    if not trips:
        return []

    # Get GPS points for all trips
    trip_points = {}
    for trip in trips:
        points = get_trip_gps_points(db, trip)
        if len(points) >= 2:
            trip_points[trip.id] = points

    if not trip_points:
        return []

    clusters = []
    unclustered = set(trip_points.keys())

    while unclustered:
        # Start new cluster with first unclustered trip
        center_id = next(iter(unclustered))
        cluster = [center_id]
        unclustered.remove(center_id)

        center_points = trip_points[center_id]

        # Find similar trips
        to_remove = []
        for trip_id in unclustered:
            similarity = calculate_route_similarity(center_points, trip_points[trip_id])
            if similarity >= similarity_threshold:
                cluster.append(trip_id)
                to_remove.append(trip_id)

        # Remove clustered trips from unclustered set
        for trip_id in to_remove:
            unclustered.remove(trip_id)

        clusters.append(cluster)

    return clusters
