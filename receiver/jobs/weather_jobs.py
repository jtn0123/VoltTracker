"""
Background jobs for weather data fetching.

These jobs run asynchronously to avoid blocking trip finalization.
"""

import logging
from datetime import datetime
from typing import Optional, List, Dict, Any
from sqlalchemy.orm import Session

logger = logging.getLogger(__name__)


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
    from models import Trip
    from services.weather_service import fetch_and_store_weather

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

        # Fetch weather using existing service
        weather_data = fetch_and_store_weather(db_session, trip)

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
    results = {"success": 0, "failed": 0, "trip_results": []}

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
    from models import Trip, TelemetryRaw
    from services.elevation_service import fetch_and_update_elevations

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

        # Get telemetry points for the trip
        telemetry_points = db_session.query(TelemetryRaw).filter(
            TelemetryRaw.trip_id == trip_id,
            TelemetryRaw.latitude.isnot(None),
            TelemetryRaw.longitude.isnot(None)
        ).all()

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
    results = {
        "weather_success": 0,
        "weather_failed": 0,
        "elevation_success": 0,
        "elevation_failed": 0,
        "trip_results": []
    }

    try:
        for trip_id in trip_ids:
            trip_result = {"trip_id": trip_id}

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
