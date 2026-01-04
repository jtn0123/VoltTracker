"""
Charging session service for VoltTracker.

Handles charging session detection and finalization.
"""

import logging
from datetime import datetime

from sqlalchemy.exc import IntegrityError, OperationalError

from config import Config
from models import ChargingSession, TelemetryRaw
from exceptions import ChargingSessionError
from utils.timezone import utc_now

logger = logging.getLogger(__name__)


def detect_and_finalize_charging_session(
    db,
    active_session: ChargingSession,
    latest_telemetry: TelemetryRaw = None
) -> None:
    """
    Finalize an active charging session when charging stops.

    Args:
        db: Database session
        active_session: The active ChargingSession to finalize
        latest_telemetry: Optional latest telemetry record for end data
    """
    if not active_session:
        return

    try:
        # Set end time
        if latest_telemetry:
            active_session.end_time = latest_telemetry.timestamp
            active_session.end_soc = latest_telemetry.state_of_charge
        else:
            active_session.end_time = utc_now()

        active_session.is_complete = True

        # Calculate kWh added from SOC change
        if active_session.start_soc is not None and active_session.end_soc is not None:
            soc_gained = active_session.end_soc - active_session.start_soc
            if soc_gained > 0:
                active_session.kwh_added = (
                    soc_gained / 100
                ) * Config.BATTERY_CAPACITY_KWH

        # Calculate cost if electricity rate is set
        if active_session.kwh_added and Config.ELECTRICITY_COST_PER_KWH:
            active_session.cost = (
                active_session.kwh_added * Config.ELECTRICITY_COST_PER_KWH
            )
            active_session.cost_per_kwh = Config.ELECTRICITY_COST_PER_KWH

        db.commit()

        # Safely format SOC values (may be None)
        start_soc_str = f"{active_session.start_soc:.0f}" if active_session.start_soc is not None else "?"
        end_soc_str = f"{active_session.end_soc:.0f}" if active_session.end_soc is not None else "?"
        logger.info(
            f"Charging session completed: "
            f"{active_session.kwh_added or 0:.2f} kWh added, "
            f"SOC {start_soc_str}% -> {end_soc_str}%"
        )
    except (IntegrityError, OperationalError) as e:
        error = ChargingSessionError(
            f"Failed to finalize charging session: {e}",
            session_id=active_session.id
        )
        logger.error(str(error), exc_info=True)
        db.rollback()
        raise
    except Exception as e:
        logger.exception(f"Unexpected error finalizing charging session: {e}")
        db.rollback()
        raise


def start_charging_session(
    db,
    telemetry: TelemetryRaw
) -> ChargingSession:
    """
    Start a new charging session from telemetry data.

    Args:
        db: Database session
        telemetry: TelemetryRaw record indicating charging start

    Returns:
        The newly created ChargingSession
    """
    session = ChargingSession(
        start_time=telemetry.timestamp,
        start_soc=telemetry.state_of_charge,
        latitude=telemetry.latitude,
        longitude=telemetry.longitude,
        peak_power_kw=telemetry.charger_power_kw or telemetry.charger_ac_power_kw,
        avg_power_kw=telemetry.charger_power_kw or telemetry.charger_ac_power_kw,
        is_complete=False
    )

    # Detect charging type based on power level
    power = telemetry.charger_power_kw or telemetry.charger_ac_power_kw or 0
    if power > 20:
        session.charge_type = 'DCFC'
    elif power > 3:
        session.charge_type = 'L2'
    else:
        session.charge_type = 'L1'

    db.add(session)
    db.flush()

    # Safely format SOC (may be None)
    soc_str = f"{session.start_soc:.0f}" if session.start_soc is not None else "?"
    logger.info(
        f"Started new charging session: {session.charge_type} at "
        f"{power:.1f} kW, SOC {soc_str}%"
    )

    return session


def update_charging_session(
    session: ChargingSession,
    telemetry: TelemetryRaw
) -> None:
    """
    Update an active charging session with new telemetry data.

    Args:
        session: Active ChargingSession to update
        telemetry: Latest TelemetryRaw record
    """
    # Update end SOC
    if telemetry.state_of_charge is not None:
        session.end_soc = telemetry.state_of_charge

    # Update peak power if higher
    current_power = telemetry.charger_power_kw or telemetry.charger_ac_power_kw or 0
    if current_power > (session.peak_power_kw or 0):
        session.peak_power_kw = current_power

    # Add to charging curve if we have data (limit to 1000 points max)
    # A typical L2 charge session is ~4 hours with 1-minute intervals = ~240 points
    # 1000 points allows for longer sessions while preventing unbounded growth
    MAX_CURVE_POINTS = 1000

    if session.charging_curve is None:
        session.charging_curve = []

    curve_point = {
        'timestamp': telemetry.timestamp.isoformat() if telemetry.timestamp else None,
        'power_kw': current_power,
        'soc': telemetry.state_of_charge
    }

    if len(session.charging_curve) < MAX_CURVE_POINTS:
        session.charging_curve.append(curve_point)
    elif len(session.charging_curve) == MAX_CURVE_POINTS:
        # Log once when we hit the limit
        logger.debug(f"Charging curve reached max size ({MAX_CURVE_POINTS} points)")
        session.charging_curve.append(curve_point)  # Allow one more to indicate truncation
