"""
Map routes for VoltTracker.

Handles GPS track visualization, route clustering, and map data export.
"""

import logging
from datetime import datetime
from typing import List, Dict, Any, Optional

from flask import Blueprint, jsonify, request, Response
from sqlalchemy import func, and_, or_
from sqlalchemy.orm import Session

from database import get_db
from models import Trip, TelemetryRaw
from utils.time_utils import parse_query_date_range, parse_date_shortcut
from utils.route_clustering import find_similar_trips, calculate_route_bounds

logger = logging.getLogger(__name__)

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


def calculate_efficiency_color(kwh_per_mile: Optional[float], speed_mph: Optional[float]) -> str:
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


@map_bp.route("/api/trips/map", methods=["GET"])
def get_trips_map_data():
    """
    Get aggregated GPS data for all trips to display on map.

    Query params:
        start_date: Filter trips after this date (ISO format)
        end_date: Filter trips before this date (ISO format)
        date_range: Date shortcut (today, yesterday, last_7_days, etc.)
        min_efficiency: Minimum kWh/mile
        max_efficiency: Maximum kWh/mile
        min_mpg: Minimum gas MPG
        min_distance: Minimum distance in miles
        max_distance: Maximum distance in miles
        gas_only: If true, only gas-mode trips
        ev_only: If true, only EV trips
        max_points_per_trip: Maximum GPS points per trip (default 100)

    Returns:
        JSON with trips list containing:
        - trip_id, start_time, distance, efficiency metrics
        - points: List of [lat, lon, efficiency, speed] for route
        - bounds: {north, south, east, west} for quick filtering
    """
    db = get_db()

    # Base query for closed, non-deleted trips
    query = db.query(Trip).filter(
        Trip.is_closed.is_(True),
        Trip.deleted_at.is_(None)
    )

    # Date filters
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

    # Efficiency filters
    min_efficiency = request.args.get("min_efficiency", type=float)
    if min_efficiency:
        query = query.filter(Trip.kwh_per_mile >= min_efficiency)

    max_efficiency = request.args.get("max_efficiency", type=float)
    if max_efficiency:
        query = query.filter(Trip.kwh_per_mile <= max_efficiency)

    min_mpg = request.args.get("min_mpg", type=float)
    if min_mpg:
        query = query.filter(Trip.gas_mpg >= min_mpg)

    # Distance filters
    min_distance = request.args.get("min_distance", type=float)
    if min_distance:
        query = query.filter(Trip.distance_miles >= min_distance)

    max_distance = request.args.get("max_distance", type=float)
    if max_distance:
        query = query.filter(Trip.distance_miles <= max_distance)

    # Mode filters
    gas_only = request.args.get("gas_only", "").lower() == "true"
    if gas_only:
        query = query.filter(Trip.gas_mode_entered.is_(True))

    ev_only = request.args.get("ev_only", "").lower() == "true"
    if ev_only:
        query = query.filter(Trip.gas_mode_entered.is_(False))

    # Limit to recent trips to avoid overwhelming the map (default 100 trips)
    max_trips = request.args.get("max_trips", default=100, type=int)
    if max_trips > 500:
        max_trips = 500  # Hard limit

    max_points_per_trip = request.args.get("max_points_per_trip", default=100, type=int)
    if max_points_per_trip > 500:
        max_points_per_trip = 500

    # Get trips ordered by start time (most recent first)
    trips = query.order_by(Trip.start_time.desc()).limit(max_trips).all()

    # Build response
    trips_data = []

    for trip in trips:
        # Get GPS points for this trip
        telemetry = db.query(TelemetryRaw).filter(
            TelemetryRaw.session_id == trip.session_id,
            TelemetryRaw.latitude.isnot(None),
            TelemetryRaw.longitude.isnot(None)
        ).order_by(TelemetryRaw.timestamp).all()

        if not telemetry or len(telemetry) < 2:
            continue  # Skip trips without GPS data

        # Build points list with efficiency/speed data
        points = []
        for t in telemetry:
            # Calculate instantaneous efficiency if data available
            efficiency = None
            if t.hv_battery_power_kw and t.speed_mph and t.speed_mph > 5:
                # kW to kWh (power * time), distance = speed * time
                # Simplified: kWh/mile ≈ kW / mph
                efficiency = abs(t.hv_battery_power_kw) / t.speed_mph if t.hv_battery_power_kw > 0 else None

            points.append({
                'lat': float(t.latitude),
                'lon': float(t.longitude),
                'speed': float(t.speed_mph) if t.speed_mph else 0,
                'efficiency': round(efficiency, 3) if efficiency else None,
                'timestamp': t.timestamp.isoformat() if t.timestamp else None
            })

        # Subsample points to reduce data size
        points = subsample_gps_points(points, max_points_per_trip)

        # Calculate route bounds
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

        trips_data.append({
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
            'point_count': len(telemetry)  # Original point count before subsampling
        })

    return jsonify({
        'trips': trips_data,
        'total_trips': len(trips_data),
        'filters_applied': {
            'date_range': date_range_shortcut or 'custom',
            'min_efficiency': min_efficiency,
            'max_efficiency': max_efficiency,
            'min_mpg': min_mpg,
            'min_distance': min_distance,
            'max_distance': max_distance,
            'gas_only': gas_only,
            'ev_only': ev_only
        }
    })


@map_bp.route("/api/trips/<trip_id>/route", methods=["GET"])
def get_trip_route_detailed(trip_id: str):
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
        return jsonify({'error': 'Trip not found'}), 404

    include_telemetry = request.args.get("include_telemetry", "").lower() == "true"

    # Get all GPS points
    query = db.query(TelemetryRaw).filter(
        TelemetryRaw.session_id == trip.session_id,
        TelemetryRaw.latitude.isnot(None),
        TelemetryRaw.longitude.isnot(None)
    ).order_by(TelemetryRaw.timestamp)

    telemetry = query.all()

    if not telemetry:
        return jsonify({'error': 'No GPS data for this trip'}), 404

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


@map_bp.route("/api/trips/similar/<trip_id>", methods=["GET"])
def find_similar_trip_routes(trip_id: str):
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
        return jsonify({'error': 'Trip not found'}), 404

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


@map_bp.route("/api/trips/<trip_id>/gpx", methods=["GET"])
def export_trip_as_gpx(trip_id: str):
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
        return jsonify({'error': 'Trip not found'}), 404

    # Get telemetry with GPS data
    telemetry = db.query(TelemetryRaw).filter(
        TelemetryRaw.session_id == trip.session_id,
        TelemetryRaw.latitude.isnot(None),
        TelemetryRaw.longitude.isnot(None)
    ).order_by(TelemetryRaw.timestamp).all()

    if not telemetry:
        return jsonify({'error': 'No GPS data for this trip'}), 404

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


@map_bp.route("/api/trips/<trip_id>/kml", methods=["GET"])
def export_trip_as_kml(trip_id: str):
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
        return jsonify({'error': 'Trip not found'}), 404

    # Get telemetry with GPS data
    telemetry = db.query(TelemetryRaw).filter(
        TelemetryRaw.session_id == trip.session_id,
        TelemetryRaw.latitude.isnot(None),
        TelemetryRaw.longitude.isnot(None)
    ).order_by(TelemetryRaw.timestamp).all()

    if not telemetry:
        return jsonify({'error': 'No GPS data for this trip'}), 404

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
    from xml.sax.saxutils import escape

    # GPX header
    gpx = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<gpx version="1.1" creator="VoltTracker" xmlns="http://www.topografix.com/GPX/1/1">',
        '  <metadata>',
        f'    <name>Volt Trip - {trip.start_time.strftime("%Y-%m-%d %H:%M")}</name>',
        f'    <desc>Distance: {trip.distance_miles:.2f} mi'
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
    gpx.append('  <trk>')
    gpx.append(f'    <name>Trip {trip.start_time.strftime("%Y-%m-%d %H:%M")}</name>')
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
    from xml.sax.saxutils import escape

    # KML header
    kml = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<kml xmlns="http://www.opengis.net/kml/2.2">',
        '  <Document>',
        f'    <name>Volt Trip - {trip.start_time.strftime("%Y-%m-%d %H:%M")}</name>',
        f'    <description>VoltTracker trip export</description>',
        '',
        '    <!-- Styles -->',
        '    <Style id="routeStyle">',
        '      <LineStyle>',
        '        <color>ff00d4aa</color>',  # Electric green in ABGR
        '        <width>4</width>',
        '      </LineStyle>',
        '    </Style>',
        '    <Style id="startPoint">',
        '      <IconStyle>',
        '        <color>ff00ff00</color>',  # Green
        '        <scale>1.2</scale>',
        '        <Icon>',
        '          <href>http://maps.google.com/mapfiles/kml/paddle/grn-circle.png</href>',
        '        </Icon>',
        '      </IconStyle>',
        '    </Style>',
        '    <Style id="endPoint">',
        '      <IconStyle>',
        '        <color>ff0000ff</color>',  # Red
        '        <scale>1.2</scale>',
        '        <Icon>',
        '          <href>http://maps.google.com/mapfiles/kml/paddle/red-circle.png</href>',
        '        </Icon>',
        '      </IconStyle>',
        '    </Style>',
        ''
    ]

    # Start placemark
    if telemetry:
        first = telemetry[0]
        kml.append('    <Placemark>')
        kml.append('      <name>Start</name>')
        kml.append(f'      <description>Trip start: {trip.start_time.strftime("%Y-%m-%d %H:%M")}</description>')
        kml.append('      <styleUrl>#startPoint</styleUrl>')
        kml.append('      <Point>')
        kml.append(f'        <coordinates>{first.longitude},{first.latitude},0</coordinates>')
        kml.append('      </Point>')
        kml.append('    </Placemark>')
        kml.append('')

        # End placemark
        last = telemetry[-1]
        kml.append('    <Placemark>')
        kml.append('      <name>End</name>')
        end_time = trip.end_time if trip.end_time else last.timestamp
        kml.append(f'      <description>Trip end: {end_time.strftime("%Y-%m-%d %H:%M") if end_time else "Unknown"}</description>')
        kml.append('      <styleUrl>#endPoint</styleUrl>')
        kml.append('      <Point>')
        kml.append(f'        <coordinates>{last.longitude},{last.latitude},0</coordinates>')
        kml.append('      </Point>')
        kml.append('    </Placemark>')
        kml.append('')

    # Route line
    kml.append('    <Placemark>')
    kml.append(f'      <name>Trip Route</name>')

    # Build description with trip stats
    desc_parts = [f'Distance: {trip.distance_miles:.2f} mi']
    if trip.kwh_per_mile:
        desc_parts.append(f'Efficiency: {trip.kwh_per_mile:.3f} kWh/mi')
    if trip.gas_mpg:
        desc_parts.append(f'MPG: {trip.gas_mpg:.1f}')
    if trip.ambient_temp_avg_f:
        desc_parts.append(f'Avg Temp: {trip.ambient_temp_avg_f:.0f}°F')

    kml.append(f'      <description>{", ".join(desc_parts)}</description>')
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
    kml.append('    </Placemark>')

    kml.append('  </Document>')
    kml.append('</kml>')

    return '\n'.join(kml)
