"""
Background jobs for weather and elevation data fetching.

These jobs run asynchronously to avoid blocking trip finalization.

Both helpers are defined inline rather than delegating to a separate
``services.weather_service`` module — that module never existed in this
codebase, and the function-local import was a latent ImportError that only
the test mocks were hiding (see JTN-453). Weather and elevation are now
fetched directly from the existing ``utils.weather`` and
``services.elevation_service`` modules.
"""

import logging
from typing import Any, Dict, List, Optional

from sqlalchemy.orm import Session

from models import TelemetryRaw, Trip
from services.elevation_service import fetch_and_update_elevations
from utils.weather import get_weather_for_location

logger = logging.getLogger(__name__)


def _fetch_weather_for_trip_data(db_session: Session, trip: Trip) -> Optional[List[Dict[str, Any]]]:
    """
    Fetch weather for a trip using its first GPS-bearing telemetry point.

    Persists temperature / precipitation / wind / conditions onto the trip
    row when a sample is returned, so the data flows through to the analytics
    services that read ``Trip.weather_*`` columns.

    Returns a list of weather sample dicts (currently length 0 or 1) so the
    caller can report a "weather_points" count, mirroring how
    ``services.trip_service._collect_weather_samples`` works for the
    synchronous trip-finalization path.
    """
    first_point = (
        db_session.query(TelemetryRaw)
        .filter(
            TelemetryRaw.session_id == trip.session_id,
            TelemetryRaw.latitude.isnot(None),
            TelemetryRaw.longitude.isnot(None),
        )
        .order_by(TelemetryRaw.timestamp)
        .first()
    )

    if first_point is None:
        return []

    sample = get_weather_for_location(
        first_point.latitude,
        first_point.longitude,
        trip.start_time,
        db_session=db_session,
    )

    if not sample:
        return []

    trip.weather_temp_f = sample.get("temperature_f")
    trip.weather_precipitation_in = sample.get("precipitation_in")
    trip.weather_wind_mph = sample.get("wind_speed_mph")
    trip.weather_conditions = sample.get("conditions")

    return [sample]


def fetch_weather_for_trip(trip_id: int, db_session: Optional[Session] = None) -> Dict[str, Any]:
    """
    Fetch weather data for a trip in the background.

    Args:
        trip_id: The trip ID to fetch weather for
        db_session: Optional database session (creates new if not provided)

    Returns:
        Dict with result status and metadata

    This job can be enqueued when a trip is finalized, allowing the main
    request to return quickly while weather data is fetched asynchronously.
    """
    from database import SessionLocal

    if db_session is None:
        db_session = SessionLocal()
        should_close = True
    else:
        should_close = False

    try:
        trip = db_session.query(Trip).filter(Trip.id == trip_id).first()
        if not trip:
            logger.warning(f"Trip {trip_id} not found for weather fetch")
            return {"status": "failed", "reason": "trip_not_found"}

        weather_data = _fetch_weather_for_trip_data(db_session, trip)

        db_session.commit()

        logger.info(f"Successfully fetched weather for trip {trip_id}")
        return {
            "status": "success",
            "trip_id": trip_id,
            "weather_points": len(weather_data) if weather_data else 0,
        }

    except Exception as e:
        logger.error(f"Failed to fetch weather for trip {trip_id}: {e}", exc_info=True)
        db_session.rollback()
        return {
            "status": "failed",
            "trip_id": trip_id,
            "error": str(e),
        }

    finally:
        if should_close:
            db_session.close()


def batch_fetch_weather(trip_ids: List[int]) -> Dict[str, Any]:
    """
    Batch fetch weather data for multiple trips.

    Args:
        trip_ids: List of trip IDs

    Returns:
        Dict with batch results
    """
    from database import SessionLocal

    db_session = SessionLocal()
    results: Dict[str, Any] = {"success": 0, "failed": 0, "trip_results": []}

    try:
        for trip_id in trip_ids:
            result = fetch_weather_for_trip(trip_id, db_session)
            results["trip_results"].append(result)

            if result["status"] == "success":
                results["success"] += 1
            else:
                results["failed"] += 1

        logger.info(
            f"Batch weather fetch completed: {results['success']} success, "
            f"{results['failed']} failed"
        )

        return results

    finally:
        db_session.close()


def fetch_elevation_for_trip(trip_id: int, db_session: Optional[Session] = None) -> Dict[str, Any]:
    """
    Fetch elevation data for a trip in the background.

    Args:
        trip_id: The trip ID to fetch elevation for
        db_session: Optional database session

    Returns:
        Dict with result status and metadata
    """
    from database import SessionLocal

    if db_session is None:
        db_session = SessionLocal()
        should_close = True
    else:
        should_close = False

    try:
        trip = db_session.query(Trip).filter(Trip.id == trip_id).first()
        if not trip:
            logger.warning(f"Trip {trip_id} not found for elevation fetch")
            return {"status": "failed", "reason": "trip_not_found"}

        # Get telemetry points for the trip using session_id
        telemetry_points = db_session.query(TelemetryRaw).filter(
            TelemetryRaw.session_id == trip.session_id,
            TelemetryRaw.latitude.isnot(None),
            TelemetryRaw.longitude.isnot(None)
        ).order_by(TelemetryRaw.timestamp).all()

        if not telemetry_points:
            return {"status": "skipped", "reason": "no_gps_data"}

        # Fetch elevations
        updated_count = fetch_and_update_elevations(db_session, telemetry_points)

        db_session.commit()

        logger.info(f"Successfully fetched elevation for trip {trip_id}: {updated_count} points")
        return {
            "status": "success",
            "trip_id": trip_id,
            "elevation_points": updated_count,
        }

    except Exception as e:
        logger.error(f"Failed to fetch elevation for trip {trip_id}: {e}", exc_info=True)
        db_session.rollback()
        return {
            "status": "failed",
            "trip_id": trip_id,
            "error": str(e),
        }

    finally:
        if should_close:
            db_session.close()


def batch_fetch_weather_and_elevation(trip_ids: List[int]) -> Dict[str, Any]:
    """
    Batch fetch both weather and elevation data for multiple trips.

    Args:
        trip_ids: List of trip IDs

    Returns:
        Dict with batch results
    """
    from database import SessionLocal

    db_session = SessionLocal()
    results: Dict[str, Any] = {
        "weather_success": 0,
        "weather_failed": 0,
        "elevation_success": 0,
        "elevation_failed": 0,
        "trip_results": []
    }

    try:
        for trip_id in trip_ids:
            trip_result: Dict[str, Any] = {"trip_id": trip_id}

            # Fetch weather
            weather_result = fetch_weather_for_trip(trip_id, db_session)
            trip_result["weather"] = weather_result
            if weather_result["status"] == "success":
                results["weather_success"] += 1
            else:
                results["weather_failed"] += 1

            # Fetch elevation
            elevation_result = fetch_elevation_for_trip(trip_id, db_session)
            trip_result["elevation"] = elevation_result
            if elevation_result["status"] == "success":
                results["elevation_success"] += 1
            else:
                results["elevation_failed"] += 1

            results["trip_results"].append(trip_result)

        logger.info(
            f"Batch fetch completed: Weather ({results['weather_success']}/"
            f"{results['weather_failed']}), Elevation ({results['elevation_success']}/"
            f"{results['elevation_failed']})"
        )

        return results

    finally:
        db_session.close()
