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


def subsample_gps_points(points: List[Dict[str, Any]], max_points: int = 100) -> List[Dict[str, Any]]:
    """
    Subsample GPS points to reduce data size while preserving route shape.
    Uses systematic sampling (every Nth point).

    Args:
        points: List of GPS points with lat, lon, and optional metadata
        max_points: Maximum number of points to return

    Returns:
        Subsampled list of GPS points
    """
    if len(points) <= max_points:
        return points

    # Calculate step size
    step = len(points) / max_points

    # Always include first and last points
    sampled = [points[0]]

    # Sample intermediate points
    for i in range(1, max_points - 1):
        index = int(i * step)
        if index < len(points):
            sampled.append(points[index])

    # Always include last point
    sampled.append(points[-1])

    return sampled


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


def _apply_map_metric_filters(query):
    """Apply efficiency, distance, and mode filters from request args."""
    min_efficiency = request.args.get("min_efficiency", type=float)
    if min_efficiency:
        query = query.filter(Trip.kwh_per_mile >= min_efficiency)

    max_efficiency = request.args.get("max_efficiency", type=float)
    if max_efficiency:
        query = query.filter(Trip.kwh_per_mile <= max_efficiency)

    min_mpg = request.args.get("min_mpg", type=float)
    if min_mpg:
        query = query.filter(Trip.gas_mpg >= min_mpg)

    min_distance = request.args.get("min_distance", type=float)
    if min_distance:
        query = query.filter(Trip.distance_miles >= min_distance)

    max_distance = request.args.get("max_distance", type=float)
    if max_distance:
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
    }


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
        efficiency = None
        if t.hv_battery_power_kw and t.speed_mph and t.speed_mph > 5:
            efficiency = abs(t.hv_battery_power_kw) / t.speed_mph if t.hv_battery_power_kw > 0 else None

        points.append({
            'lat': float(t.latitude),
            'lon': float(t.longitude),
            'speed': float(t.speed_mph) if t.speed_mph else 0,
            'efficiency': round(efficiency, 3) if efficiency else None,
            'timestamp': t.timestamp.isoformat() if t.timestamp else None
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


def _serialize_trip_for_map(trip: 'Trip', points: list, bounds: dict, raw_count: int) -> Dict[str, Any]:
    """Serialize a trip and its GPS data for the map response."""
    return {
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
    query, filter_info = _apply_map_metric_filters(query)

    max_trips = min(request.args.get("max_trips", default=100, type=int), 500)
    max_points_per_trip = min(request.args.get("max_points_per_trip", default=100, type=int), 500)

    trips = query.order_by(Trip.start_time.desc()).limit(max_trips).all()

    telemetry_by_session = _fetch_telemetry_by_session(db, [trip.session_id for trip in trips])

    trips_data = []
    for trip in trips:
        telemetry = telemetry_by_session.get(trip.session_id, [])
        if len(telemetry) < 2:
            continue

        points = _build_trip_points(telemetry)
        points = subsample_gps_points(points, max_points_per_trip)
        bounds = _calculate_bounds(points)
        trips_data.append(_serialize_trip_for_map(trip, points, bounds, len(telemetry)))

    filter_info['date_range'] = date_range_shortcut or 'custom'
    return jsonify({
        'trips': trips_data,
        'total_trips': len(trips_data),
        'filters_applied': filter_info,
    })


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
    points = []
    for t in telemetry:
        point = {
            'lat': float(t.latitude),
            'lon': float(t.longitude),
            'timestamp': t.timestamp.isoformat() if t.timestamp else None
        }

        if include_telemetry:
            point.update({
                'speed_mph': float(t.speed_mph) if t.speed_mph else 0,
                'soc': float(t.state_of_charge) if t.state_of_charge else None,
                'hv_power': float(t.hv_battery_power_kw) if t.hv_battery_power_kw else None,
                'engine_rpm': int(t.engine_rpm) if t.engine_rpm else 0,
                'ambient_temp': float(t.ambient_temp_f) if t.ambient_temp_f else None
            })

        points.append(point)

    # Calculate bounds
    lats = [p['lat'] for p in points]
    lons = [p['lon'] for p in points]
    bounds = {
        'north': max(lats),
        'south': min(lats),
        'east': max(lons),
        'west': min(lons),
        'center': {
            'lat': sum(lats) / len(lats),
            'lon': sum(lons) / len(lons)
        }
    }

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
        gpx.append(f'      <trkpt lat="{t.latitude}" lon="{t.longitude}">')

        # Add elevation if available (already in meters)
        if t.elevation_meters:
            elevation_m = t.elevation_meters
            gpx.append(f'        <ele>{elevation_m:.1f}</ele>')

        # Add timestamp
        if t.timestamp:
            gpx.append(f'        <time>{t.timestamp.isoformat()}Z</time>')

        # Add extensions with Volt-specific data
        gpx.append('        <extensions>')
        if t.speed_mph:
            gpx.append(f'          <speed>{t.speed_mph * 0.44704:.2f}</speed>')  # Convert to m/s
        if t.state_of_charge is not None:
            gpx.append(f'          <soc>{t.state_of_charge:.1f}</soc>')
        if t.hv_battery_power_kw is not None:
            gpx.append(f'          <power>{t.hv_battery_power_kw:.2f}</power>')
        if t.ambient_temp_f is not None:
            gpx.append(f'          <temp>{t.ambient_temp_f:.1f}</temp>')
        gpx.append('        </extensions>')

        gpx.append('      </trkpt>')

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
