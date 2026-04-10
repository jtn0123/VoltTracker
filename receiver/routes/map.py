"""
Map routes for VoltTracker.

Handles GPS track visualization, route clustering, and map data export.
"""

import logging
from typing import List, Dict, Any, Optional
from xml.sax.saxutils import escape as xml_escape

from flask import Blueprint, jsonify, request, Response

from database import get_db
from models import Trip, TelemetryRaw
from utils.time_utils import parse_query_date_range, parse_date_shortcut
from utils.route_clustering import find_similar_trips

logger = logging.getLogger(__name__)

_ERR_TRIP_NOT_FOUND = 'Trip not found'
_ERR_NO_GPS_DATA = 'No GPS data for this trip'
_KML_STYLE_END = '    </Style>'
_KML_PLACEMARK_START = '    <Placemark>'
_KML_PLACEMARK_END = '    </Placemark>'

map_bp = Blueprint("map", __name__)


def perpendicular_distance(point: Dict[str, Any], line_start: Dict[str, Any],
                           line_end: Dict[str, Any]) -> float:
    """
    Calculate perpendicular distance from a point to a line segment.

    Uses the formula for point-to-line distance in 2D coordinates.
    For GPS coordinates, this is accurate enough for small distances.
    """
    # If line start and end are the same point, return distance to that point
    if line_start['lat'] == line_end['lat'] and line_start['lon'] == line_end['lon']:
        return ((point['lat'] - line_start['lat']) ** 2 +
                (point['lon'] - line_start['lon']) ** 2) ** 0.5

    dx = line_end['lon'] - line_start['lon']
    dy = line_end['lat'] - line_start['lat']
    line_length = (dx ** 2 + dy ** 2) ** 0.5
    if line_length == 0:
        return ((point['lat'] - line_start['lat']) ** 2 +
                (point['lon'] - line_start['lon']) ** 2) ** 0.5

    # Cross product gives the perpendicular distance
    distance = abs(
        dx * (line_start['lat'] - point['lat']) -
        (line_start['lon'] - point['lon']) * dy
    ) / line_length

    return distance


def rdp_simplify(points: List[Dict[str, Any]], epsilon: float = 0.00005) -> List[Dict[str, Any]]:
    """
    Ramer-Douglas-Peucker polyline simplification.

    Preserves route shape by keeping points that deviate significantly from
    straight lines, while removing points that are nearly collinear.

    Args:
        points: List of GPS points with 'lat' and 'lon' keys
        epsilon: Maximum deviation threshold in degrees (~5.5m at equator for 0.00005)
    """
    if len(points) <= 2:
        return points

    max_distance = 0.0
    max_index = 0

    for i in range(1, len(points) - 1):
        distance = perpendicular_distance(points[i], points[0], points[-1])
        if distance > max_distance:
            max_distance = distance
            max_index = i

    if max_distance > epsilon:
        left_simplified = rdp_simplify(points[:max_index + 1], epsilon)
        right_simplified = rdp_simplify(points[max_index:], epsilon)
        # Avoid duplicating the split point
        return left_simplified[:-1] + right_simplified
    return [points[0], points[-1]]


def filter_stationary_points(points: List[Dict[str, Any]],
                             min_distance_meters: float = 5.0) -> List[Dict[str, Any]]:
    """
    Collapse consecutive GPS points within ``min_distance_meters`` of each other.

    Handles GPS stuck/jitter where the device emits thousands of identical
    fixes (e.g. a trip with 17k points all at the same location). Always keeps
    the first and last points.
    """
    if len(points) <= 1:
        return points

    # Approximate degrees per meter at mid-latitudes (~32-40°)
    meters_per_degree = 111000
    min_degrees = min_distance_meters / meters_per_degree

    filtered = [points[0]]
    for p in points[1:]:
        lat_diff = abs(p['lat'] - filtered[-1]['lat'])
        lon_diff = abs(p['lon'] - filtered[-1]['lon'])
        if lat_diff > min_degrees or lon_diff > min_degrees:
            filtered.append(p)

    # Always include the last point if not already preserved
    last_point = points[-1]
    if (abs(last_point['lat'] - filtered[-1]['lat']) > min_degrees or
            abs(last_point['lon'] - filtered[-1]['lon']) > min_degrees):
        filtered.append(last_point)

    return filtered


def subsample_gps_points(points: List[Dict[str, Any]], max_points: int = 300) -> List[Dict[str, Any]]:
    """
    Subsample GPS points to reduce payload size while preserving route shape.

    Strategy:
        1. Collapse stationary/duplicate points (handles GPS-stuck trips).
        2. Apply Ramer-Douglas-Peucker simplification to preserve curves and turns.
        3. If still over the limit, increase epsilon iteratively, then fall back
           to systematic sampling on the simplified set.

    Args:
        points: List of GPS points with lat, lon, and optional metadata
        max_points: Maximum number of points to return (default 300)
    """
    # Collapse GPS-stuck periods first
    points = filter_stationary_points(points, min_distance_meters=5.0)

    if len(points) <= max_points:
        return points

    # RDP simplification — start moderate, widen if needed
    epsilon = 0.00003  # ~3.3m at equator
    simplified = rdp_simplify(points, epsilon)

    while len(simplified) > max_points and epsilon < 0.001:
        epsilon *= 1.5
        simplified = rdp_simplify(points, epsilon)

    # Long trips: fall back to systematic sampling on the simplified set
    if len(simplified) > max_points:
        step = len(simplified) / max_points
        sampled = [simplified[0]]
        for i in range(1, max_points - 1):
            index = int(i * step)
            if index < len(simplified):
                sampled.append(simplified[index])
        sampled.append(simplified[-1])
        return sampled

    return simplified


def calculate_efficiency_color(kwh_per_mile: Optional[float], _speed_mph: Optional[float] = None) -> str:
    """
    Calculate color code for route segment based on efficiency.

    Args:
        kwh_per_mile: Energy consumption in kWh/mile
        speed_mph: Speed in mph

    Returns:
        Hex color code (green = efficient, yellow = moderate, red = inefficient)
    """
    if kwh_per_mile is None:
        return '#999999'  # Gray for unknown

    # Efficiency thresholds (kWh/mile)
    # < 0.25: Very efficient (green)
    # 0.25-0.35: Moderate (yellow)
    # > 0.35: Inefficient (red)

    if kwh_per_mile < 0.25:
        return '#10b981'  # Green
    elif kwh_per_mile < 0.35:
        return '#f59e0b'  # Yellow/Orange
    else:
        return '#ef4444'  # Red


def _apply_map_date_filters(query):
    """Apply date range filters from request args to a trip query."""
    date_range_shortcut = request.args.get("date_range")
    if date_range_shortcut:
        date_range = parse_date_shortcut(date_range_shortcut)
        if date_range:
            start_date_dt, end_date_dt = date_range
            query = query.filter(
                Trip.start_time >= start_date_dt,
                Trip.start_time <= end_date_dt
            )
    else:
        start_date = request.args.get("start_date")
        end_date = request.args.get("end_date")
        if start_date or end_date:
            start_date_dt, end_date_dt = parse_query_date_range(request.args, default_days=90)
            if start_date:
                query = query.filter(Trip.start_time >= start_date_dt)
            if end_date:
                query = query.filter(Trip.start_time <= end_date_dt)
    return query, request.args.get("date_range")


# JTN-491: allow-listed filter field names. Only these exact values can
# ever appear in an "invalid_filter" error, so the error payload can only
# ever contain these literal strings — never user-supplied text.
_MAP_FILTER_NUMERIC_FIELDS = (
    "min_efficiency",
    "max_efficiency",
    "min_mpg",
    "min_distance",
    "max_distance",
)

# (lower, upper) pairs where lower must be <= upper when both present.
_MAP_FILTER_RANGE_PAIRS = (
    ("min_efficiency", "max_efficiency"),
    ("min_distance", "max_distance"),
)

# Error codes for _parse_map_filter_numerics. Strings instead of an Enum
# so the route handler keeps the tuple pattern lightweight.
_MAP_FILTER_ERR_NOT_NUMBER = "not_a_number"
_MAP_FILTER_ERR_NEGATIVE = "negative_value"
_MAP_FILTER_ERR_INVERTED_RANGE = "inverted_range"


def _parse_map_filter_numerics():
    """Parse and validate the numeric map filters.

    JTN-491: The endpoint previously accepted negative values and inverted
    ranges silently, which produced empty result sets with no explanation.
    This helper only returns structured error metadata (field name from the
    hardcoded allow-list plus an error code); the route handler builds the
    JSON response from static strings. That way no request data ever flows
    into the response body, which is both correct and avoids tripping
    CodeQL's reflected-XSS taint flow across the jsonify boundary.

    Returns:
        (filters_dict, error_info) where exactly one is None. error_info,
        when set, is a tuple of ``(field_name, error_code)`` with both
        values drawn from module-level constants.
    """
    filters: Dict[str, Optional[float]] = {}
    for field in _MAP_FILTER_NUMERIC_FIELDS:
        raw = request.args.get(field)
        if raw is None or raw == "":
            filters[field] = None
            continue
        try:
            value = float(raw)
        except (TypeError, ValueError):
            return None, (field, _MAP_FILTER_ERR_NOT_NUMBER)
        if value < 0:
            return None, (field, _MAP_FILTER_ERR_NEGATIVE)
        filters[field] = value

    for lower_key, _upper_key in _MAP_FILTER_RANGE_PAIRS:
        lower = filters[lower_key]
        upper = filters[_upper_key]
        if lower is not None and upper is not None and lower > upper:
            return None, (lower_key, _MAP_FILTER_ERR_INVERTED_RANGE)

    return filters, None


# Static error messages keyed on (field, error_code). Built from literals
# only — nothing from the request ever reaches the response body.
_MAP_FILTER_ERROR_MESSAGES = {
    ("min_efficiency", _MAP_FILTER_ERR_NOT_NUMBER): "min_efficiency must be a number",
    ("max_efficiency", _MAP_FILTER_ERR_NOT_NUMBER): "max_efficiency must be a number",
    ("min_mpg", _MAP_FILTER_ERR_NOT_NUMBER): "min_mpg must be a number",
    ("min_distance", _MAP_FILTER_ERR_NOT_NUMBER): "min_distance must be a number",
    ("max_distance", _MAP_FILTER_ERR_NOT_NUMBER): "max_distance must be a number",
    ("min_efficiency", _MAP_FILTER_ERR_NEGATIVE): "min_efficiency must be >= 0",
    ("max_efficiency", _MAP_FILTER_ERR_NEGATIVE): "max_efficiency must be >= 0",
    ("min_mpg", _MAP_FILTER_ERR_NEGATIVE): "min_mpg must be >= 0",
    ("min_distance", _MAP_FILTER_ERR_NEGATIVE): "min_distance must be >= 0",
    ("max_distance", _MAP_FILTER_ERR_NEGATIVE): "max_distance must be >= 0",
    ("min_efficiency", _MAP_FILTER_ERR_INVERTED_RANGE): "min_efficiency must be <= max_efficiency",
    ("min_distance", _MAP_FILTER_ERR_INVERTED_RANGE): "min_distance must be <= max_distance",
}


def _build_map_filter_error_response(error_info):
    """Build a 400 JSON response from a ``(field, code)`` error tuple.

    The lookup table is keyed on hardcoded constants so every possible
    response body is a literal string — no request data ever reaches the
    output. The ``invalid_filter`` field is only ever one of the known
    filter names from ``_MAP_FILTER_NUMERIC_FIELDS``.
    """
    field, code = error_info
    message = _MAP_FILTER_ERROR_MESSAGES.get(
        (field, code), "Invalid map filter"
    )
    return jsonify({"error": message, "invalid_filter": field}), 400


def _apply_map_metric_filters(query):
    """Apply efficiency, distance, and mode filters from request args.

    Returns a tuple of ``(query, filter_info, error_info)``. If
    ``error_info`` is not ``None``, the caller should build and return a
    400 response via ``_build_map_filter_error_response`` without running
    the query.
    """
    numerics, error_info = _parse_map_filter_numerics()
    if error_info is not None:
        return query, None, error_info

    min_efficiency = numerics["min_efficiency"]
    if min_efficiency is not None:
        query = query.filter(Trip.kwh_per_mile >= min_efficiency)

    max_efficiency = numerics["max_efficiency"]
    if max_efficiency is not None:
        query = query.filter(Trip.kwh_per_mile <= max_efficiency)

    min_mpg = numerics["min_mpg"]
    if min_mpg is not None:
        query = query.filter(Trip.gas_mpg >= min_mpg)

    min_distance = numerics["min_distance"]
    if min_distance is not None:
        query = query.filter(Trip.distance_miles >= min_distance)

    max_distance = numerics["max_distance"]
    if max_distance is not None:
        query = query.filter(Trip.distance_miles <= max_distance)

    gas_only = request.args.get("gas_only", "").lower() == "true"
    if gas_only:
        query = query.filter(Trip.gas_mode_entered.is_(True))

    ev_only = request.args.get("ev_only", "").lower() == "true"
    if ev_only:
        query = query.filter(Trip.gas_mode_entered.is_(False))

    return query, {
        'min_efficiency': min_efficiency,
        'max_efficiency': max_efficiency,
        'min_mpg': min_mpg,
        'min_distance': min_distance,
        'max_distance': max_distance,
        'gas_only': gas_only,
        'ev_only': ev_only,
    }, None


def _fetch_telemetry_by_session(db, session_ids: list) -> dict:
    """Batch-fetch GPS telemetry for multiple sessions, grouped by session_id."""
    from collections import defaultdict

    if not session_ids:
        return {}

    all_telemetry = (
        db.query(TelemetryRaw)
        .filter(
            TelemetryRaw.session_id.in_(session_ids),
            TelemetryRaw.latitude.isnot(None),
            TelemetryRaw.longitude.isnot(None),
        )
        .order_by(TelemetryRaw.session_id, TelemetryRaw.timestamp)
        .all()
    )

    telemetry_by_session: dict = defaultdict(list)
    for t in all_telemetry:
        telemetry_by_session[t.session_id].append(t)
    return telemetry_by_session


def _build_trip_points(telemetry: list) -> List[Dict[str, Any]]:
    """Build GPS point dicts with instantaneous efficiency from telemetry records."""
    points = []
    for t in telemetry:
        # Efficiency only meaningful when speed > 5 mph (excludes stopped /
        # crawling traffic noise) AND we have a non-null power reading.
        # Use `is not None` for power so a coasting moment with hv_power == 0
        # doesn't get treated as missing data.
        efficiency = None
        if (
            t.hv_battery_power_kw is not None
            and t.speed_mph is not None
            and t.speed_mph > 5
            and t.hv_battery_power_kw > 0
        ):
            efficiency = abs(t.hv_battery_power_kw) / t.speed_mph

        points.append({
            'lat': float(t.latitude),
            'lon': float(t.longitude),
            'speed': float(t.speed_mph) if t.speed_mph is not None else 0,
            'efficiency': round(efficiency, 3) if efficiency is not None else None,
            'timestamp': t.timestamp.isoformat() if t.timestamp else None,
        })
    return points


def _calculate_bounds(points: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Calculate geographic bounding box from GPS points."""
    lats = [p['lat'] for p in points]
    lons = [p['lon'] for p in points]
    return {
        'north': max(lats),
        'south': min(lats),
        'east': max(lons),
        'west': min(lons),
        'center': {
            'lat': sum(lats) / len(lats),
            'lon': sum(lons) / len(lons)
        }
    }


def _serialize_trip_for_map(trip: 'Trip', points: list, bounds: dict, raw_count: int,
                            gps_quality: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """Serialize a trip and its GPS data for the map response."""
    payload = {
        'id': trip.id,
        'session_id': trip.session_id,
        'start_time': trip.start_time.isoformat(),
        'end_time': trip.end_time.isoformat() if trip.end_time else None,
        'distance_miles': round(trip.distance_miles, 2) if trip.distance_miles else 0,
        'kwh_per_mile': round(trip.kwh_per_mile, 3) if trip.kwh_per_mile else None,
        'gas_mpg': round(trip.gas_mpg, 1) if trip.gas_mpg else None,
        'electric_miles': round(trip.electric_miles, 2) if trip.electric_miles else 0,
        'gas_miles': round(trip.gas_miles, 2) if trip.gas_miles else 0,
        'avg_temp_f': round(trip.ambient_temp_avg_f, 1) if trip.ambient_temp_avg_f else None,
        'points': points,
        'bounds': bounds,
        'point_count': raw_count,
    }
    if gps_quality is not None:
        payload['gps_quality'] = gps_quality
    return payload


@map_bp.route("/api/trips/map", methods=["GET"])
def get_trips_map_data():
    """
    Get aggregated GPS data for all trips to display on map.

    Query params:
        start_date, end_date, date_range, min/max_efficiency, min_mpg,
        min/max_distance, gas_only, ev_only, max_trips, max_points_per_trip

    Returns:
        JSON with trips list containing GPS points, bounds, and efficiency metrics.
    """
    db = get_db()

    query = db.query(Trip).filter(Trip.is_closed.is_(True), Trip.deleted_at.is_(None))

    query, date_range_shortcut = _apply_map_date_filters(query)
    query, filter_info, error_info = _apply_map_metric_filters(query)
    if error_info is not None:
        return _build_map_filter_error_response(error_info)

    max_trips = min(request.args.get("max_trips", default=100, type=int), 500)
    max_points_per_trip = min(request.args.get("max_points_per_trip", default=300, type=int), 500)

    trips = query.order_by(Trip.start_time.desc()).limit(max_trips).all()

    telemetry_by_session = _fetch_telemetry_by_session(db, [trip.session_id for trip in trips])

    trips_data = []
    for trip in trips:
        telemetry = telemetry_by_session.get(trip.session_id, [])
        if len(telemetry) < 2:
            continue

        original_point_count = len(telemetry)
        points = _build_trip_points(telemetry)

        # Compute GPS quality from the unique-location ratio. <30% unique => poor.
        unique_points = filter_stationary_points(points, min_distance_meters=5.0)
        unique_point_count = len(unique_points)
        quality_ratio = (unique_point_count / original_point_count) if original_point_count > 0 else 0
        gps_quality_label = 'good' if quality_ratio > 0.3 else 'poor'

        points = subsample_gps_points(points, max_points_per_trip)
        bounds = _calculate_bounds(points)
        gps_quality = {
            'total_points': original_point_count,
            'unique_points': unique_point_count,
            'displayed_points': len(points),
            'quality': gps_quality_label,
        }
        trips_data.append(_serialize_trip_for_map(trip, points, bounds, original_point_count, gps_quality))

    filter_info['date_range'] = date_range_shortcut or 'custom'
    return jsonify({
        'trips': trips_data,
        'total_trips': len(trips_data),
        'filters_applied': filter_info,
    })


def _build_detailed_point(t, *, include_telemetry: bool) -> Dict[str, Any]:
    """Build a detailed GPS point dict from a telemetry record."""
    point = {
        'lat': float(t.latitude),
        'lon': float(t.longitude),
        'timestamp': t.timestamp.isoformat() if t.timestamp else None
    }
    if include_telemetry:
        # `is not None` rather than truthy checks: SOC=0%, hv_power=0kW
        # (idling charged), speed_mph=0 (stopped at light), engine_rpm=0
        # (electric mode), and ambient_temp=0°F are all *valid* readings
        # that we want to render. Truthy checks would silently drop them
        # and pretend the field was missing.
        point.update({
            'speed_mph': float(t.speed_mph) if t.speed_mph is not None else 0,
            'soc': float(t.state_of_charge) if t.state_of_charge is not None else None,
            'hv_power': float(t.hv_battery_power_kw) if t.hv_battery_power_kw is not None else None,
            'engine_rpm': int(t.engine_rpm) if t.engine_rpm is not None else 0,
            'ambient_temp': float(t.ambient_temp_f) if t.ambient_temp_f is not None else None,
        })
    return point


@map_bp.route("/api/trips/<int:trip_id>/route", methods=["GET"])
def get_trip_route_detailed(trip_id: int):
    """
    Get detailed GPS route for a single trip (no subsampling).

    Query params:
        include_telemetry: If true, include full telemetry data (speed, SOC, power, etc.)

    Returns:
        JSON with detailed route points and trip metadata
    """
    db = get_db()

    # Get trip
    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if not trip:
        return jsonify({'error': _ERR_TRIP_NOT_FOUND}), 404

    include_telemetry = request.args.get("include_telemetry", "").lower() == "true"

    # Get all GPS points
    query = db.query(TelemetryRaw).filter(
        TelemetryRaw.session_id == trip.session_id,
        TelemetryRaw.latitude.isnot(None),
        TelemetryRaw.longitude.isnot(None)
    ).order_by(TelemetryRaw.timestamp)

    telemetry = query.all()

    if not telemetry:
        return jsonify({'error': _ERR_NO_GPS_DATA}), 404

    # Build detailed points
    points = [_build_detailed_point(t, include_telemetry=include_telemetry) for t in telemetry]
    bounds = _calculate_bounds(points)

    return jsonify({
        'trip': {
            'id': trip.id,
            'start_time': trip.start_time.isoformat(),
            'end_time': trip.end_time.isoformat() if trip.end_time else None,
            'distance_miles': round(trip.distance_miles, 2) if trip.distance_miles else 0,
            'kwh_per_mile': round(trip.kwh_per_mile, 3) if trip.kwh_per_mile else None,
            'gas_mpg': round(trip.gas_mpg, 1) if trip.gas_mpg else None
        },
        'route': {
            'points': points,
            'bounds': bounds,
            'total_points': len(points)
        }
    })


@map_bp.route("/api/trips/similar/<int:trip_id>", methods=["GET"])
def find_similar_trip_routes(trip_id: int):
    """
    Find trips with similar routes based on GPS data.

    Query params:
        max_results: Maximum number of similar trips to return (default 10)
        min_similarity: Minimum similarity score 0-100 (default 70)

    Returns:
        JSON with list of similar trips and their similarity scores
    """
    db = get_db()

    # Get reference trip
    reference_trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if not reference_trip:
        return jsonify({'error': _ERR_TRIP_NOT_FOUND}), 404

    max_results = request.args.get("max_results", default=10, type=int)
    min_similarity = request.args.get("min_similarity", default=70, type=float)

    # Find similar trips using clustering utility
    similar_trips = find_similar_trips(
        db,
        reference_trip,
        max_results=max_results,
        min_similarity=min_similarity
    )

    return jsonify({
        'reference_trip_id': trip_id,
        'similar_trips': similar_trips,
        'total_found': len(similar_trips)
    })


@map_bp.route("/api/trips/<int:trip_id>/gpx", methods=["GET"])
def export_trip_as_gpx(trip_id: int):
    """
    Export trip as GPX (GPS Exchange Format) file.

    GPX is compatible with most GPS devices and mapping software.

    Returns:
        GPX XML file with route points, timestamps, elevation, and metadata
    """
    db = get_db()

    # Get trip
    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if not trip:
        return jsonify({'error': _ERR_TRIP_NOT_FOUND}), 404

    # Get telemetry with GPS data
    telemetry = db.query(TelemetryRaw).filter(
        TelemetryRaw.session_id == trip.session_id,
        TelemetryRaw.latitude.isnot(None),
        TelemetryRaw.longitude.isnot(None)
    ).order_by(TelemetryRaw.timestamp).all()

    if not telemetry:
        return jsonify({'error': _ERR_NO_GPS_DATA}), 404

    # Generate GPX XML
    gpx_content = generate_gpx(trip, telemetry)

    # Create filename
    trip_date = trip.start_time.strftime('%Y-%m-%d_%H-%M')
    filename = f"volttracker_trip_{trip_date}.gpx"

    return Response(
        gpx_content,
        mimetype='application/gpx+xml',
        headers={
            'Content-Disposition': f'attachment; filename="{filename}"',
            'Content-Type': 'application/gpx+xml; charset=utf-8'
        }
    )


@map_bp.route("/api/trips/<int:trip_id>/kml", methods=["GET"])
def export_trip_as_kml(trip_id: int):
    """
    Export trip as KML (Keyhole Markup Language) file.

    KML is compatible with Google Earth and Google Maps.

    Returns:
        KML XML file with route, placemarks, and styling
    """
    db = get_db()

    # Get trip
    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if not trip:
        return jsonify({'error': _ERR_TRIP_NOT_FOUND}), 404

    # Get telemetry with GPS data
    telemetry = db.query(TelemetryRaw).filter(
        TelemetryRaw.session_id == trip.session_id,
        TelemetryRaw.latitude.isnot(None),
        TelemetryRaw.longitude.isnot(None)
    ).order_by(TelemetryRaw.timestamp).all()

    if not telemetry:
        return jsonify({'error': _ERR_NO_GPS_DATA}), 404

    # Generate KML XML
    kml_content = generate_kml(trip, telemetry)

    # Create filename
    trip_date = trip.start_time.strftime('%Y-%m-%d_%H-%M')
    filename = f"volttracker_trip_{trip_date}.kml"

    return Response(
        kml_content,
        mimetype='application/vnd.google-earth.kml+xml',
        headers={
            'Content-Disposition': f'attachment; filename="{filename}"',
            'Content-Type': 'application/vnd.google-earth.kml+xml; charset=utf-8'
        }
    )


def _build_gpx_trackpoint(t) -> List[str]:
    """Build GPX XML lines for a single trackpoint."""
    lines = [f'      <trkpt lat="{t.latitude}" lon="{t.longitude}">']
    if t.elevation_meters is not None:
        lines.append(f'        <ele>{t.elevation_meters:.1f}</ele>')
    if t.timestamp:
        lines.append(f'        <time>{t.timestamp.isoformat()}Z</time>')
    lines.append('        <extensions>')
    if t.speed_mph is not None:
        lines.append(f'          <speed>{t.speed_mph * 0.44704:.2f}</speed>')
    if t.state_of_charge is not None:
        lines.append(f'          <soc>{t.state_of_charge:.1f}</soc>')
    if t.hv_battery_power_kw is not None:
        lines.append(f'          <power>{t.hv_battery_power_kw:.2f}</power>')
    if t.ambient_temp_f is not None:
        lines.append(f'          <temp>{t.ambient_temp_f:.1f}</temp>')
    lines.append('        </extensions>')
    lines.append('      </trkpt>')
    return lines


def generate_gpx(trip: Trip, telemetry: List[TelemetryRaw]) -> str:
    """
    Generate GPX XML content for a trip.

    Args:
        trip: Trip model instance
        telemetry: List of telemetry points with GPS data

    Returns:
        GPX XML string
    """

    trip_name_escaped = xml_escape("Volt Trip - " + trip.start_time.strftime("%Y-%m-%d %H:%M"))

    # GPX header
    gpx = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<gpx version="1.1" creator="VoltTracker" xmlns="http://www.topografix.com/GPX/1/1">',
        '  <metadata>',
        f'    <name>{trip_name_escaped}</name>',
        f'    <desc>Distance: {(trip.distance_miles or 0):.2f} mi'
    ]

    # Add efficiency info
    if trip.kwh_per_mile:
        gpx.append(f', Efficiency: {trip.kwh_per_mile:.3f} kWh/mi')
    if trip.gas_mpg:
        gpx.append(f', MPG: {trip.gas_mpg:.1f}')

    gpx.append('</desc>')
    gpx.append(f'    <time>{trip.start_time.isoformat()}Z</time>')
    gpx.append('  </metadata>')

    # Track segment
    seg_name_escaped = xml_escape("Trip " + trip.start_time.strftime("%Y-%m-%d %H:%M"))
    gpx.append('  <trk>')
    gpx.append(f'    <name>{seg_name_escaped}</name>')
    gpx.append('    <trkseg>')

    # Add track points
    for t in telemetry:
        gpx.extend(_build_gpx_trackpoint(t))

    gpx.append('    </trkseg>')
    gpx.append('  </trk>')
    gpx.append('</gpx>')

    return '\n'.join(gpx)


def generate_kml(trip: Trip, telemetry: List[TelemetryRaw]) -> str:
    """
    Generate KML XML content for a trip.

    Args:
        trip: Trip model instance
        telemetry: List of telemetry points with GPS data

    Returns:
        KML XML string
    """

    trip_name_escaped = xml_escape("Volt Trip - " + trip.start_time.strftime("%Y-%m-%d %H:%M"))

    # KML header
    kml = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<kml xmlns="http://www.opengis.net/kml/2.2">',
        '  <Document>',
        f'    <name>{trip_name_escaped}</name>',
        '    <description>VoltTracker trip export</description>',
        '',
        '    <!-- Styles -->',
        '    <Style id="routeStyle">',
        '      <LineStyle>',
        '        <color>ff00d4aa</color>',  # Electric green in ABGR
        '        <width>4</width>',
        '      </LineStyle>',
        _KML_STYLE_END,
        '    <Style id="startPoint">',
        '      <IconStyle>',
        '        <color>ff00ff00</color>',  # Green
        '        <scale>1.2</scale>',
        '        <Icon>',
        '          <href>http://maps.google.com/mapfiles/kml/paddle/grn-circle.png</href>',
        '        </Icon>',
        '      </IconStyle>',
        _KML_STYLE_END,
        '    <Style id="endPoint">',
        '      <IconStyle>',
        '        <color>ff0000ff</color>',  # Red
        '        <scale>1.2</scale>',
        '        <Icon>',
        '          <href>http://maps.google.com/mapfiles/kml/paddle/red-circle.png</href>',
        '        </Icon>',
        '      </IconStyle>',
        _KML_STYLE_END,
        ''
    ]

    # Start placemark
    if telemetry:
        first = telemetry[0]
        kml.append(_KML_PLACEMARK_START)
        kml.append('      <name>Start</name>')
        desc = xml_escape('Trip start: ' + trip.start_time.strftime('%Y-%m-%d %H:%M'))
        kml.append(f'      <description>{desc}</description>')
        kml.append('      <styleUrl>#startPoint</styleUrl>')
        kml.append('      <Point>')
        kml.append(f'        <coordinates>{first.longitude},{first.latitude},0</coordinates>')
        kml.append('      </Point>')
        kml.append(_KML_PLACEMARK_END)
        kml.append('')

        # End placemark
        last = telemetry[-1]
        kml.append(_KML_PLACEMARK_START)
        kml.append('      <name>End</name>')
        end_time = trip.end_time if trip.end_time else last.timestamp
        end_str = end_time.strftime("%Y-%m-%d %H:%M") if end_time else "Unknown"
        kml.append(f'      <description>{xml_escape(f"Trip end: {end_str}")}</description>')
        kml.append('      <styleUrl>#endPoint</styleUrl>')
        kml.append('      <Point>')
        kml.append(f'        <coordinates>{last.longitude},{last.latitude},0</coordinates>')
        kml.append('      </Point>')
        kml.append(_KML_PLACEMARK_END)
        kml.append('')

    # Route line
    kml.append(_KML_PLACEMARK_START)
    kml.append('      <name>Trip Route</name>')

    # Build description with trip stats
    desc_parts = [f'Distance: {(trip.distance_miles or 0):.2f} mi']
    if trip.kwh_per_mile:
        desc_parts.append(f'Efficiency: {trip.kwh_per_mile:.3f} kWh/mi')
    if trip.gas_mpg:
        desc_parts.append(f'MPG: {trip.gas_mpg:.1f}')
    if trip.ambient_temp_avg_f:
        desc_parts.append(f'Avg Temp: {trip.ambient_temp_avg_f:.0f}°F')

    kml.append(f'      <description>{xml_escape(", ".join(desc_parts))}</description>')
    kml.append('      <styleUrl>#routeStyle</styleUrl>')
    kml.append('      <LineString>')
    kml.append('        <tessellate>1</tessellate>')
    kml.append('        <coordinates>')

    # Add coordinates (lon,lat,alt format for KML)
    for t in telemetry:
        alt = t.elevation_meters if t.elevation_meters else 0
        kml.append(f'          {t.longitude},{t.latitude},{alt}')

    kml.append('        </coordinates>')
    kml.append('      </LineString>')
    kml.append(_KML_PLACEMARK_END)

    kml.append('  </Document>')
    kml.append('</kml>')

    return '\n'.join(kml)
