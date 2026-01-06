"""
Background scheduler service for VoltTracker.

Handles periodic background tasks for trip finalization, refuel detection,
and charging session management.
"""

import logging
from datetime import timedelta

from apscheduler.schedulers.background import BackgroundScheduler
from config import Config
from database import SessionLocal
from exceptions import ChargingSessionError, DatabaseError
from models import ChargingSession, FuelEvent, TelemetryRaw, Trip
from services.trip_service import finalize_trip
from sqlalchemy import desc, func
from sqlalchemy.exc import IntegrityError, OperationalError
from utils import detect_charging_session, detect_refuel_event, normalize_datetime, utc_now

logger = logging.getLogger(__name__)

# Module-level scheduler instance
scheduler = None


def get_scheduler_db():
    """Get a database session for scheduler tasks."""
    return SessionLocal()


def _finalize_charging_session(db, active_session, end_time=None, reason="completed"):
    """
    Finalize a charging session by setting end time, calculating kWh added, and logging.

    Args:
        db: Database session
        active_session: ChargingSession object to finalize
        end_time: Optional end time (defaults to now)
        reason: Reason for finalization (for logging)
    """
    active_session.end_time = end_time or utc_now()
    active_session.is_complete = True

    # Calculate kWh added from SOC change
    if active_session.start_soc is not None and active_session.end_soc is not None:
        from utils import soc_to_kwh
        soc_gained = active_session.end_soc - active_session.start_soc
        if soc_gained > 0:
            active_session.kwh_added = soc_to_kwh(soc_gained)

    db.commit()

    # Safely format SOC values (may be None)
    start_soc_str = f"{active_session.start_soc:.0f}" if active_session.start_soc is not None else "?"
    end_soc_str = f"{active_session.end_soc:.0f}" if active_session.end_soc is not None else "?"
    logger.info(
        f"Charging session {reason}: {active_session.kwh_added or 0:.2f} kWh added, "
        f"SOC {start_soc_str}% -> {end_soc_str}%"
    )


def close_stale_trips():
    """
    Close trips that have no new data for TRIP_TIMEOUT_SECONDS.
    Calculate trip statistics and detect gas mode transitions.

    Optimized to avoid N+1 queries by using a subquery to get latest telemetry.
    """
    db = get_scheduler_db()
    try:
        cutoff_time = utc_now() - timedelta(seconds=Config.TRIP_TIMEOUT_SECONDS)

        # Use subquery to get latest telemetry timestamp for each session (avoids N+1)
        latest_telemetry_subq = (
            db.query(
                TelemetryRaw.session_id,
                func.max(TelemetryRaw.timestamp).label('latest_timestamp')
            )
            .group_by(TelemetryRaw.session_id)
            .subquery()
        )

        # Find open trips with their latest telemetry timestamp in one query
        stale_trips = (
            db.query(Trip)
            .outerjoin(
                latest_telemetry_subq,
                Trip.session_id == latest_telemetry_subq.c.session_id
            )
            .filter(Trip.is_closed.is_(False))
            .filter(
                # Either no telemetry OR telemetry older than cutoff
                (latest_telemetry_subq.c.latest_timestamp.is_(None)) |
                (latest_telemetry_subq.c.latest_timestamp < cutoff_time)
            )
            .all()
        )

        for trip in stale_trips:
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
        # Get recent telemetry from last 24 hours (more efficient than last 100 points)
        # This covers typical refueling patterns while limiting query scope
        cutoff_time = utc_now() - timedelta(hours=24)
        recent = (
            db.query(TelemetryRaw)
            .filter(
                TelemetryRaw.fuel_level_percent.isnot(None),
                TelemetryRaw.timestamp >= cutoff_time
            )
            .order_by(desc(TelemetryRaw.timestamp))
            .limit(200)  # Safety limit to prevent excessive processing
            .all()
        )

        if len(recent) < 2:
            return

        # Check for fuel level jumps
        for i in range(len(recent) - 1):
            current = recent[i]
            previous = recent[i + 1]

            if detect_refuel_event(current.fuel_level_percent, previous.fuel_level_percent):
                # Check if we already logged this refuel
                existing = (
                    db.query(FuelEvent)
                    .filter(FuelEvent.timestamp >= previous.timestamp, FuelEvent.timestamp <= current.timestamp)
                    .first()
                )

                if not existing:
                    fuel_event = FuelEvent(
                        timestamp=current.timestamp,
                        odometer_miles=current.odometer_miles,
                        fuel_level_before=previous.fuel_level_percent,
                        fuel_level_after=current.fuel_level_percent,
                        gallons_added=(
                            (current.fuel_level_percent - previous.fuel_level_percent)
                            / 100
                            * Config.TANK_CAPACITY_GALLONS
                        ),
                    )
                    db.add(fuel_event)
                    logger.info(
                        f"Refuel detected: {fuel_event.gallons_added:.2f} gal " f"at {fuel_event.odometer_miles:.1f} mi"
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
        recent = (
            db.query(TelemetryRaw)
            .filter(TelemetryRaw.charger_connected.is_(True))
            .order_by(desc(TelemetryRaw.timestamp))
            .limit(50)
            .all()
        )

        if not recent:
            # No active charging - check if we need to close any active sessions
            active_session = db.query(ChargingSession).filter(ChargingSession.is_complete.is_(False)).first()

            if active_session:
                # Charger disconnected - finalize session
                _finalize_charging_session(db, active_session, reason="completed (no charger data)")
            return

        # Convert to dicts for the detection function
        points = [t.to_dict() for t in recent]
        session_info = detect_charging_session(points)

        if session_info and session_info.get("is_charging"):
            # Check for existing active charging session with row lock
            # Use with_for_update() to prevent race conditions where multiple
            # scheduler instances could create duplicate sessions
            active_session = (
                db.query(ChargingSession)
                .filter(ChargingSession.is_complete.is_(False))
                .with_for_update(skip_locked=True)
                .order_by(desc(ChargingSession.start_time))
                .first()
            )

            if not active_session:
                # Double-check for existing session without lock (in case another
                # process just created one)
                existing = db.query(ChargingSession).filter(ChargingSession.is_complete.is_(False)).first()
                if existing:
                    active_session = existing
                else:
                    # Create new charging session
                    first_point = recent[-1]  # Oldest in the set
                    active_session = ChargingSession(
                        start_time=first_point.timestamp,
                        start_soc=session_info.get("start_soc"),
                        latitude=first_point.latitude,
                        longitude=first_point.longitude,
                        charge_type=session_info.get("charge_type", "L1"),
                    )
                    db.add(active_session)
                    logger.info(f"Charging session started: {session_info.get('charge_type')}")

            # Update with latest data
            active_session.end_soc = session_info.get("current_soc")
            active_session.peak_power_kw = session_info.get("peak_power_kw")
            active_session.avg_power_kw = session_info.get("avg_power_kw")

            try:
                db.commit()
            except IntegrityError as e:
                # Unique constraint violation - duplicate charging session
                # This can happen in rare race conditions, just log and continue
                db.rollback()
                logger.warning(f"Duplicate charging session detected (start_time constraint): {e}")
                # Re-query the existing session to continue tracking
                active_session = db.query(ChargingSession).filter(ChargingSession.is_complete.is_(False)).first()

        else:
            # Check if we need to close an active session
            active_session = db.query(ChargingSession).filter(ChargingSession.is_complete.is_(False)).first()

            if active_session:
                # Charger disconnected - finalize session
                end_time = db.query(func.max(TelemetryRaw.timestamp)).scalar()
                _finalize_charging_session(db, active_session, end_time=end_time, reason="completed")

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
    scheduler.add_job(close_stale_trips, "interval", minutes=1)
    scheduler.add_job(check_refuel_events, "interval", minutes=5)
    scheduler.add_job(check_charging_sessions, "interval", minutes=2)
    scheduler.start()
    logger.info("Background scheduler initialized")
    return scheduler


def shutdown_scheduler():
    """Shutdown the background scheduler gracefully."""
    if scheduler:
        scheduler.shutdown()
        logger.info("Background scheduler shut down")
