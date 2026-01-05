"""
Powertrain Analysis Service

Analyzes motor and generator RPM data to identify Volt operating modes
and provide insights into hybrid system operation.
"""

import logging
from typing import Dict, Optional

from models import TelemetryRaw
from sqlalchemy.orm import Session

logger = logging.getLogger(__name__)


class PowertrainMode:
    """Volt Gen 2 Operating Modes"""

    EV_MODE = "ev"  # Pure electric
    HOLD_MODE = "hold"  # Engine running, saving battery
    MOUNTAIN_MODE = "mountain"  # Engine running, charging battery
    ENGINE_DIRECT = "engine_direct"  # Engine directly driving wheels
    HYBRID_ASSIST = "hybrid_assist"  # Battery + engine combined
    UNKNOWN = "unknown"


def detect_operating_mode(
    motor_a_rpm: float,
    motor_b_rpm: float,
    generator_rpm: float,
    engine_rpm: float,
    hv_battery_power_kw: Optional[float] = None,
) -> str:
    """
    Detect Volt operating mode from powertrain RPM data.

    Args:
        motor_a_rpm: Front motor RPM
        motor_b_rpm: Rear motor RPM
        generator_rpm: Generator RPM
        engine_rpm: Engine RPM
        hv_battery_power_kw: Battery power (positive = discharging)

    Returns:
        Operating mode string
    """
    # Thresholds
    MOTOR_ACTIVE_THRESHOLD = 100  # RPM
    ENGINE_ACTIVE_THRESHOLD = 400  # RPM
    GENERATOR_ACTIVE_THRESHOLD = 100  # RPM

    motor_a_active = motor_a_rpm and motor_a_rpm > MOTOR_ACTIVE_THRESHOLD
    motor_b_active = motor_b_rpm and motor_b_rpm > MOTOR_ACTIVE_THRESHOLD
    generator_active = generator_rpm and generator_rpm > GENERATOR_ACTIVE_THRESHOLD
    engine_active = engine_rpm and engine_rpm > ENGINE_ACTIVE_THRESHOLD

    # EV Mode: Motors only, no engine
    if motor_a_active and not engine_active and not generator_active:
        return PowertrainMode.EV_MODE

    # Hold Mode: Engine and generator running, motors active
    if motor_a_active and engine_active and generator_active:
        # Check if battery is charging (Mountain mode)
        if hv_battery_power_kw and hv_battery_power_kw < -1.0:  # Charging
            return PowertrainMode.MOUNTAIN_MODE
        return PowertrainMode.HOLD_MODE

    # Engine Direct: Engine running, motor B only (high speed)
    if not motor_a_active and motor_b_active and engine_active:
        return PowertrainMode.ENGINE_DIRECT

    # Hybrid Assist: All systems active
    if motor_a_active and motor_b_active and engine_active and generator_active:
        return PowertrainMode.HYBRID_ASSIST

    return PowertrainMode.UNKNOWN


def analyze_trip_powertrain(db: Session, session_id: str) -> Dict:
    """
    Analyze powertrain operation for a trip.

    Returns timeline of operating modes and statistics.
    """
    telemetry = (
        db.query(TelemetryRaw).filter(TelemetryRaw.session_id == session_id).order_by(TelemetryRaw.timestamp).all()
    )

    if not telemetry:
        return {"error": "No telemetry data found"}

    # Analyze each point
    timeline = []
    mode_durations = {
        PowertrainMode.EV_MODE: 0,
        PowertrainMode.HOLD_MODE: 0,
        PowertrainMode.MOUNTAIN_MODE: 0,
        PowertrainMode.ENGINE_DIRECT: 0,
        PowertrainMode.HYBRID_ASSIST: 0,
        PowertrainMode.UNKNOWN: 0,
    }

    for i, point in enumerate(telemetry):
        mode = detect_operating_mode(
            point.motor_a_rpm or 0,
            point.motor_b_rpm or 0,
            point.generator_rpm or 0,
            point.engine_rpm or 0,
            point.hv_battery_power_kw,
        )

        timeline.append(
            {
                "timestamp": point.timestamp.isoformat(),
                "mode": mode,
                "motor_a_rpm": point.motor_a_rpm,
                "motor_b_rpm": point.motor_b_rpm,
                "generator_rpm": point.generator_rpm,
                "engine_rpm": point.engine_rpm,
                "speed_mph": point.speed_mph,
                "soc": point.state_of_charge,
            }
        )

        # Calculate duration (time since last point)
        if i > 0:
            duration_seconds = (point.timestamp - telemetry[i - 1].timestamp).total_seconds()
            mode_durations[mode] += duration_seconds

    # Calculate percentages
    total_duration = sum(mode_durations.values())
    mode_percentages = {
        mode: (duration / total_duration * 100) if total_duration > 0 else 0
        for mode, duration in mode_durations.items()
    }

    # Find mode transitions
    transitions = []
    prev_mode = None
    for point in timeline:
        if point["mode"] != prev_mode and prev_mode is not None:
            transitions.append(
                {
                    "timestamp": point["timestamp"],
                    "from_mode": prev_mode,
                    "to_mode": point["mode"],
                    "speed_mph": point["speed_mph"],
                    "soc": point["soc"],
                }
            )
        prev_mode = point["mode"]

    return {
        "session_id": session_id,
        "timeline": timeline,
        "statistics": {
            "duration_seconds": mode_durations,
            "percentages": mode_percentages,
            "total_points": len(telemetry),
            "transitions": len(transitions),
        },
        "transitions": transitions,
        "mode_descriptions": {
            PowertrainMode.EV_MODE: "Pure Electric - Motors Only",
            PowertrainMode.HOLD_MODE: "Hold Mode - Engine Maintaining Battery",
            PowertrainMode.MOUNTAIN_MODE: "Mountain Mode - Engine Charging Battery",
            PowertrainMode.ENGINE_DIRECT: "Engine Direct Drive",
            PowertrainMode.HYBRID_ASSIST: "Hybrid Assist - Battery + Engine",
            PowertrainMode.UNKNOWN: "Unknown/Transition",
        },
    }


def get_powertrain_summary(db: Session, trip_id: int) -> Optional[Dict]:
    """
    Get powertrain mode summary for a trip.

    Simplified version for dashboard display.
    """
    from models import Trip

    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if not trip:
        return None

    analysis = analyze_trip_powertrain(db, str(trip.session_id))

    return {
        "trip_id": trip_id,
        "session_id": str(trip.session_id),
        "mode_percentages": analysis["statistics"]["percentages"],
        "primary_mode": max(analysis["statistics"]["percentages"].items(), key=lambda x: x[1])[0],
        "transitions": analysis["statistics"]["transitions"],
    }
