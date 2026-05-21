"""
Services module for VoltTracker business logic.

This module contains service classes that encapsulate business logic
separate from the Flask route handlers.
"""

from services.charging_service import (
    detect_and_finalize_charging_session,
    start_charging_session,
    update_charging_session,
)
from services.scheduler import (
    check_charging_sessions,
    check_refuel_events,
    close_stale_trips,
    init_scheduler,
    shutdown_scheduler,
)
from services.trip_service import (
    calculate_electric_efficiency,
    calculate_trip_basics,
    fetch_trip_weather,
    finalize_trip,
    process_gas_mode,
)

__all__ = [
    # Trip service
    "finalize_trip",
    "calculate_trip_basics",
    "process_gas_mode",
    "calculate_electric_efficiency",
    "fetch_trip_weather",
    # Charging service
    "detect_and_finalize_charging_session",
    "start_charging_session",
    "update_charging_session",
    # Scheduler
    "init_scheduler",
    "shutdown_scheduler",
    "close_stale_trips",
    "check_refuel_events",
    "check_charging_sessions",
]
