"""
Services module for VoltTracker business logic.

This module contains service classes that encapsulate business logic
separate from the Flask route handlers.
"""

from services.trip_service import (
    finalize_trip,
    calculate_trip_basics,
    process_gas_mode,
    calculate_electric_efficiency,
    fetch_trip_weather,
)
from services.charging_service import (
    detect_and_finalize_charging_session,
)
from services.scheduler import (
    init_scheduler,
    close_stale_trips,
    check_refuel_events,
    check_charging_sessions,
)

__all__ = [
    # Trip service
    'finalize_trip',
    'calculate_trip_basics',
    'process_gas_mode',
    'calculate_electric_efficiency',
    'fetch_trip_weather',
    # Charging service
    'detect_and_finalize_charging_session',
    # Scheduler
    'init_scheduler',
    'close_stale_trips',
    'check_refuel_events',
    'check_charging_sessions',
]
