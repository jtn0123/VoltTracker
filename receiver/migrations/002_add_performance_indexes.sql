-- Migration: Add Performance Indexes
-- Created: 2026-01-06
-- Description: Adds indexes for frequently queried columns to improve query performance

-- ===================================================================
-- TelemetryRaw Indexes
-- ===================================================================

-- Index for engine hours calculation (maintenance_service.py)
CREATE INDEX IF NOT EXISTS ix_telemetry_engine_rpm
    ON telemetry_raw (engine_rpm)
    WHERE engine_rpm > 400;

-- Index for charging session detection (scheduler.py)
CREATE INDEX IF NOT EXISTS ix_telemetry_charger_connected
    ON telemetry_raw (charger_connected, timestamp)
    WHERE charger_connected = TRUE;

-- Index for fuel level queries (refuel detection in scheduler.py)
CREATE INDEX IF NOT EXISTS ix_telemetry_fuel_level
    ON telemetry_raw (fuel_level_percent, timestamp)
    WHERE fuel_level_percent IS NOT NULL;

-- Composite index for GPS queries (route detection, weather)
CREATE INDEX IF NOT EXISTS ix_telemetry_gps_timestamp
    ON telemetry_raw (latitude, longitude, timestamp)
    WHERE latitude IS NOT NULL AND longitude IS NOT NULL;

-- ===================================================================
-- Trip Analytics Indexes
-- ===================================================================

-- Index for weather-based analytics and filtering
CREATE INDEX IF NOT EXISTS ix_trips_weather_temp
    ON trips (weather_temp_f)
    WHERE weather_temp_f IS NOT NULL;

-- Index for extreme weather filtering
CREATE INDEX IF NOT EXISTS ix_trips_extreme_weather
    ON trips (extreme_weather, start_time)
    WHERE extreme_weather = TRUE;

-- Index for elevation analytics
CREATE INDEX IF NOT EXISTS ix_trips_elevation_gain
    ON trips (elevation_gain_m)
    WHERE elevation_gain_m IS NOT NULL;

-- Index for efficiency analytics and filtering
CREATE INDEX IF NOT EXISTS ix_trips_efficiency
    ON trips (kwh_per_mile)
    WHERE kwh_per_mile IS NOT NULL;

-- Composite index for closed trips with efficiency data
CREATE INDEX IF NOT EXISTS ix_trips_closed_efficiency
    ON trips (is_closed, kwh_per_mile, start_time)
    WHERE is_closed = TRUE AND kwh_per_mile IS NOT NULL;

-- Index for distance-based queries
CREATE INDEX IF NOT EXISTS ix_trips_distance
    ON trips (distance_miles)
    WHERE distance_miles > 0;

-- ===================================================================
-- ChargingSession Indexes
-- ===================================================================

-- Index for charge type analytics
CREATE INDEX IF NOT EXISTS ix_charging_charge_type
    ON charging_sessions (charge_type, start_time);

-- Index for efficiency queries
CREATE INDEX IF NOT EXISTS ix_charging_kwh_added
    ON charging_sessions (kwh_added)
    WHERE kwh_added IS NOT NULL;

-- ===================================================================
-- FuelEvent Indexes
-- ===================================================================

-- Index for fuel analytics (gallons added)
CREATE INDEX IF NOT EXISTS ix_fuel_events_gallons
    ON fuel_events (gallons_added, timestamp);

-- ===================================================================
-- SocTransition Indexes
-- ===================================================================

-- Index for SOC analysis queries
CREATE INDEX IF NOT EXISTS ix_soc_transitions_soc
    ON soc_transitions (soc_at_transition, timestamp);

-- Add table comments explaining index strategy
COMMENT ON INDEX ix_telemetry_engine_rpm IS 'Partial index for engine hours calculation - only indexes rows where engine is running';
COMMENT ON INDEX ix_telemetry_charger_connected IS 'Partial index for charging session detection - only indexes rows where charger is connected';
COMMENT ON INDEX ix_telemetry_fuel_level IS 'Partial index for refuel detection - only indexes rows with fuel level data';
COMMENT ON INDEX ix_telemetry_gps_timestamp IS 'Composite index for GPS-based queries (routes, weather) - only indexes rows with GPS data';
COMMENT ON INDEX ix_trips_extreme_weather IS 'Partial index for extreme weather filtering - only indexes extreme weather trips';
COMMENT ON INDEX ix_trips_closed_efficiency IS 'Composite index for efficiency analytics - only indexes closed trips with efficiency data';
