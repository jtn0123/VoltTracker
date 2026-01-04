"""
Background scheduler service for VoltTracker.

Handles periodic background tasks for trip finalization, refuel detection,
and charging session management.
"""

import logging
from datetime import timedelta
from apscheduler.schedulers.background import BackgroundScheduler
from sqlalchemy import func, desc
from sqlalchemy.exc import IntegrityError, OperationalError

from config import Config
from database import SessionLocal
from exceptions import DatabaseError, ChargingSessionError
from models import Trip, TelemetryRaw, FuelEvent, ChargingSession
from utils import detect_refuel_event, detect_charging_session, utc_now, normalize_datetime
from services.trip_service import finalize_trip

logger = logging.getLogger(__name__)

# Module-level scheduler instance
scheduler = None


def get_scheduler_db():
    """Get a database session for scheduler tasks."""
    return SessionLocal()


def close_stale_trips():
    """
    Close trips that have no new data for TRIP_TIMEOUT_SECONDS.
    Calculate trip statistics and detect gas mode transitions.
    """
    db = get_scheduler_db()
    try:
        cutoff_time = utc_now() - timedelta(seconds=Config.TRIP_TIMEOUT_SECONDS)

        # Find open trips with no recent telemetry
        open_trips = db.query(Trip).filter(
            Trip.is_closed.is_(False)
        ).all()

        for trip in open_trips:
            # Get latest telemetry for this trip
            latest = db.query(TelemetryRaw).filter(
                TelemetryRaw.session_id == trip.session_id
            ).order_by(desc(TelemetryRaw.timestamp)).first()

            if latest:
                # normalize_datetime handles both naive and timezone-aware datetimes
                latest_ts = normalize_datetime(latest.timestamp)

                if latest_ts < cutoff_time:
                    logger.info(f"Closing stale trip {trip.id} (session: {trip.session_id})")
                    finalize_trip(db, trip)

        db.commit()
    except (IntegrityError, OperationalError) as e:
        error = DatabaseError(f"Failed to close stale trips: {e}")
        logger.error(str(error), exc_info=True)
        db.rollback()
    except Exception as e:
        logger.exception(f"Unexpected error closing stale trips: {e}")
        db.rollback()
    finally:
        SessionLocal.remove()


def check_refuel_events():
    """Check for refueling events based on fuel level jumps."""
    db = get_scheduler_db()
    try:
        # Get recent telemetry ordered by timestamp
        recent = db.query(TelemetryRaw).filter(
            TelemetryRaw.fuel_level_percent.isnot(None)
        ).order_by(desc(TelemetryRaw.timestamp)).limit(100).all()

        if len(recent) < 2:
            return

        # Check for fuel level jumps
        for i in range(len(recent) - 1):
            current = recent[i]
            previous = recent[i + 1]

            if detect_refuel_event(
                current.fuel_level_percent,
                previous.fuel_level_percent
            ):
                # Check if we already logged this refuel
                existing = db.query(FuelEvent).filter(
                    FuelEvent.timestamp >= previous.timestamp,
                    FuelEvent.timestamp <= current.timestamp
                ).first()

                if not existing:
                    fuel_event = FuelEvent(
                        timestamp=current.timestamp,
                        odometer_miles=current.odometer_miles,
                        fuel_level_before=previous.fuel_level_percent,
                        fuel_level_after=current.fuel_level_percent,
                        gallons_added=(
                            (current.fuel_level_percent - previous.fuel_level_percent)
                            / 100 * Config.TANK_CAPACITY_GALLONS
                        )
                    )
                    db.add(fuel_event)
                    logger.info(
                        f"Refuel detected: {fuel_event.gallons_added:.2f} gal "
                        f"at {fuel_event.odometer_miles:.1f} mi"
                    )

        db.commit()
    except (IntegrityError, OperationalError) as e:
        error = DatabaseError(f"Failed to check refuel events: {e}")
        logger.error(str(error), exc_info=True)
        db.rollback()
    except Exception as e:
        logger.exception(f"Unexpected error checking refuel events: {e}")
        db.rollback()
    finally:
        SessionLocal.remove()


def check_charging_sessions():
    """Detect and track charging sessions from telemetry data."""
    db = get_scheduler_db()
    try:
        # Get recent telemetry with charger data
        recent = db.query(TelemetryRaw).filter(
            TelemetryRaw.charger_connected.is_(True)
        ).order_by(desc(TelemetryRaw.timestamp)).limit(50).all()

        if not recent:
            # No active charging - check if we need to close any active sessions
            active_session = db.query(ChargingSession).filter(
                ChargingSession.is_complete.is_(False)
            ).first()

            if active_session:
                # Charger disconnected - finalize session
                active_session.end_time = utc_now()
                active_session.is_complete = True

                # Calculate kWh added from SOC change
                if active_session.start_soc is not None and active_session.end_soc is not None:
                    soc_gained = active_session.end_soc - active_session.start_soc
                    if soc_gained > 0:
                        active_session.kwh_added = (soc_gained / 100) * Config.BATTERY_CAPACITY_KWH

                db.commit()
                logger.info(
                    f"Charging session completed (no charger data): "
                    f"{active_session.kwh_added or 0:.2f} kWh added"
                )
            return

        # Convert to dicts for the detection function
        points = [t.to_dict() for t in recent]
        session_info = detect_charging_session(points)

        if session_info and session_info.get('is_charging'):
            # Check for existing active charging session
            active_session = db.query(ChargingSession).filter(
                ChargingSession.is_complete.is_(False)
            ).order_by(desc(ChargingSession.start_time)).first()

            if not active_session:
                # Create new charging session
                first_point = recent[-1]  # Oldest in the set
                active_session = ChargingSession(
                    start_time=first_point.timestamp,
                    start_soc=session_info.get('start_soc'),
                    latitude=first_point.latitude,
                    longitude=first_point.longitude,
                    charge_type=session_info.get('charge_type', 'L1')
                )
                db.add(active_session)
                logger.info(f"Charging session started: {session_info.get('charge_type')}")

            # Update with latest data
            active_session.end_soc = session_info.get('current_soc')
            active_session.peak_power_kw = session_info.get('peak_power_kw')
            active_session.avg_power_kw = session_info.get('avg_power_kw')

            db.commit()

        else:
            # Check if we need to close an active session
            active_session = db.query(ChargingSession).filter(
                ChargingSession.is_complete.is_(False)
            ).first()

            if active_session:
                # Charger disconnected - finalize session
                active_session.end_time = db.query(func.max(TelemetryRaw.timestamp)).scalar()
                active_session.is_complete = True

                # Calculate kWh added from SOC change
                if active_session.start_soc is not None and active_session.end_soc is not None:
                    soc_gained = active_session.end_soc - active_session.start_soc
                    if soc_gained > 0:
                        active_session.kwh_added = (soc_gained / 100) * Config.BATTERY_CAPACITY_KWH

                db.commit()
                logger.info(
                    f"Charging session completed: {active_session.kwh_added or 0:.2f} kWh added, "
                    f"SOC {active_session.start_soc:.0f}% -> {active_session.end_soc:.0f}%"
                )

    except (IntegrityError, OperationalError) as e:
        error = ChargingSessionError(f"Failed to check charging sessions: {e}")
        logger.error(str(error), exc_info=True)
        db.rollback()
    except Exception as e:
        logger.exception(f"Unexpected error checking charging sessions: {e}")
        db.rollback()
    finally:
        SessionLocal.remove()


def init_scheduler():
    """
    Initialize and start the background scheduler.

    Returns:
        The BackgroundScheduler instance
    """
    global scheduler
    scheduler = BackgroundScheduler()
    scheduler.add_job(close_stale_trips, 'interval', minutes=1)
    scheduler.add_job(check_refuel_events, 'interval', minutes=5)
    scheduler.add_job(check_charging_sessions, 'interval', minutes=2)
    scheduler.start()
    logger.info("Background scheduler initialized")
    return scheduler


def shutdown_scheduler():
    """Shutdown the background scheduler gracefully."""
    global scheduler
    if scheduler:
        scheduler.shutdown()
        logger.info("Background scheduler shut down")
