-- Migration: Add Analytics Aggregation Tables
-- Created: 2026-01-06
-- Description: Creates daily/hourly aggregation tables for fast analytics without querying raw telemetry
--
-- Strategy: Instead of deleting old data, we aggregate it into summary tables.
-- This provides fast analytics queries while preserving detailed data.

-- ===================================================================
-- Daily Trip Aggregations
-- ===================================================================

CREATE TABLE IF NOT EXISTS trip_daily_stats (
    id SERIAL PRIMARY KEY,
    date DATE NOT NULL UNIQUE,

    -- Trip counts
    total_trips INTEGER DEFAULT 0,
    ev_only_trips INTEGER DEFAULT 0,
    gas_mode_trips INTEGER DEFAULT 0,
    extreme_weather_trips INTEGER DEFAULT 0,

    -- Distance aggregations
    total_distance_miles FLOAT DEFAULT 0,
    total_electric_miles FLOAT DEFAULT 0,
    total_gas_miles FLOAT DEFAULT 0,
    avg_trip_distance FLOAT,

    -- Efficiency metrics
    avg_kwh_per_mile FLOAT,
    best_kwh_per_mile FLOAT,
    worst_kwh_per_mile FLOAT,
    avg_mpg FLOAT,

    -- Elevation metrics
    total_elevation_gain_m FLOAT DEFAULT 0,
    avg_elevation_gain_m FLOAT,

    -- Weather metrics
    avg_temp_f FLOAT,
    min_temp_f FLOAT,
    max_temp_f FLOAT,
    avg_wind_mph FLOAT,
    total_precipitation_in FLOAT DEFAULT 0,

    -- Speed metrics
    avg_speed_mph FLOAT,
    max_speed_mph FLOAT,

    -- Energy metrics
    total_kwh_used FLOAT DEFAULT 0,
    avg_weather_impact_factor FLOAT,

    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_trip_daily_stats_date ON trip_daily_stats (date DESC);

COMMENT ON TABLE trip_daily_stats IS 'Daily aggregation of trip statistics for fast analytics queries';

-- ===================================================================
-- Hourly Charging Aggregations
-- ===================================================================

CREATE TABLE IF NOT EXISTS charging_hourly_stats (
    id SERIAL PRIMARY KEY,
    hour_timestamp TIMESTAMP WITH TIME ZONE NOT NULL UNIQUE,

    -- Session counts
    total_sessions INTEGER DEFAULT 0,
    l1_sessions INTEGER DEFAULT 0,
    l2_sessions INTEGER DEFAULT 0,
    dcfc_sessions INTEGER DEFAULT 0,
    completed_sessions INTEGER DEFAULT 0,

    -- Energy metrics
    total_kwh_added FLOAT DEFAULT 0,
    avg_kwh_per_session FLOAT,
    avg_peak_power_kw FLOAT,
    avg_avg_power_kw FLOAT,

    -- SOC metrics
    avg_start_soc FLOAT,
    avg_end_soc FLOAT,
    avg_soc_gained FLOAT,

    -- Duration metrics (in minutes)
    avg_session_duration FLOAT,
    total_charging_minutes FLOAT DEFAULT 0,

    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_charging_hourly_stats_hour ON charging_hourly_stats (hour_timestamp DESC);

COMMENT ON TABLE charging_hourly_stats IS 'Hourly aggregation of charging statistics';

-- ===================================================================
-- Monthly Summary Stats
-- ===================================================================

CREATE TABLE IF NOT EXISTS monthly_summary (
    id SERIAL PRIMARY KEY,
    year INTEGER NOT NULL,
    month INTEGER NOT NULL,

    -- Trip summary
    total_trips INTEGER DEFAULT 0,
    total_distance_miles FLOAT DEFAULT 0,
    total_electric_miles FLOAT DEFAULT 0,
    total_gas_miles FLOAT DEFAULT 0,
    electric_percentage FLOAT,

    -- Efficiency summary
    avg_kwh_per_mile FLOAT,
    avg_mpg FLOAT,
    total_kwh_used FLOAT DEFAULT 0,
    total_gallons_used FLOAT DEFAULT 0,

    -- Charging summary
    total_charging_sessions INTEGER DEFAULT 0,
    total_kwh_charged FLOAT DEFAULT 0,
    l1_sessions INTEGER DEFAULT 0,
    l2_sessions INTEGER DEFAULT 0,
    dcfc_sessions INTEGER DEFAULT 0,

    -- Cost estimates (if tracked)
    estimated_electricity_cost FLOAT,
    estimated_gas_cost FLOAT,

    -- Environmental impact
    co2_avoided_lbs FLOAT,

    -- Weather summary
    avg_temp_f FLOAT,
    extreme_weather_trips INTEGER DEFAULT 0,

    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

    UNIQUE (year, month)
);

CREATE INDEX IF NOT EXISTS ix_monthly_summary_year_month ON monthly_summary (year DESC, month DESC);

COMMENT ON TABLE monthly_summary IS 'Monthly rollup for dashboard overview';

-- ===================================================================
-- Battery Health Aggregations
-- ===================================================================

CREATE TABLE IF NOT EXISTS battery_daily_health (
    id SERIAL PRIMARY KEY,
    date DATE NOT NULL UNIQUE,

    -- Capacity metrics
    avg_capacity_ah FLOAT,
    min_capacity_ah FLOAT,
    max_capacity_ah FLOAT,
    capacity_readings_count INTEGER DEFAULT 0,

    -- Health metrics
    avg_state_of_health FLOAT,

    -- Temperature metrics
    avg_battery_temp_c FLOAT,
    min_battery_temp_c FLOAT,
    max_battery_temp_c FLOAT,

    -- Usage patterns
    avg_min_cell_voltage FLOAT,
    avg_max_cell_voltage FLOAT,
    avg_voltage_delta FLOAT,

    -- Degradation tracking
    estimated_degradation_percent FLOAT,

    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_battery_daily_health_date ON battery_daily_health (date DESC);

COMMENT ON TABLE battery_daily_health IS 'Daily battery health metrics for trend analysis';

-- ===================================================================
-- Route Performance Aggregations
-- ===================================================================

CREATE TABLE IF NOT EXISTS route_performance_history (
    id SERIAL PRIMARY KEY,
    route_id INTEGER REFERENCES routes(id) ON DELETE CASCADE,
    trip_date DATE NOT NULL,

    -- Performance metrics for this trip
    distance_miles FLOAT,
    kwh_per_mile FLOAT,
    duration_minutes FLOAT,
    avg_speed_mph FLOAT,

    -- Weather on this trip
    weather_temp_f FLOAT,
    weather_wind_mph FLOAT,
    weather_conditions VARCHAR(50),

    -- Elevation
    elevation_gain_m FLOAT,

    -- Mode
    gas_mode_entered BOOLEAN DEFAULT FALSE,

    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_route_performance_route_date
    ON route_performance_history (route_id, trip_date DESC);

COMMENT ON TABLE route_performance_history IS 'Historical performance for each route - enables trend detection';

-- ===================================================================
-- Aggregation Helper Functions
-- ===================================================================

-- Function to aggregate daily trip stats
CREATE OR REPLACE FUNCTION aggregate_daily_trip_stats(target_date DATE)
RETURNS VOID AS $$
BEGIN
    INSERT INTO trip_daily_stats (
        date,
        total_trips,
        ev_only_trips,
        gas_mode_trips,
        extreme_weather_trips,
        total_distance_miles,
        total_electric_miles,
        total_gas_miles,
        avg_trip_distance,
        avg_kwh_per_mile,
        best_kwh_per_mile,
        worst_kwh_per_mile,
        avg_mpg,
        total_elevation_gain_m,
        avg_elevation_gain_m,
        avg_temp_f,
        min_temp_f,
        max_temp_f,
        avg_wind_mph,
        total_precipitation_in,
        avg_speed_mph,
        max_speed_mph,
        total_kwh_used,
        avg_weather_impact_factor
    )
    SELECT
        target_date,
        COUNT(*),
        SUM(CASE WHEN gas_mode_entered = FALSE THEN 1 ELSE 0 END),
        SUM(CASE WHEN gas_mode_entered = TRUE THEN 1 ELSE 0 END),
        SUM(CASE WHEN extreme_weather = TRUE THEN 1 ELSE 0 END),
        SUM(distance_miles),
        SUM(electric_miles),
        SUM(gas_miles),
        AVG(distance_miles),
        AVG(kwh_per_mile),
        MIN(kwh_per_mile),
        MAX(kwh_per_mile),
        AVG(mpg),
        SUM(elevation_gain_m),
        AVG(elevation_gain_m),
        AVG(weather_temp_f),
        MIN(weather_temp_f),
        MAX(weather_temp_f),
        AVG(weather_wind_mph),
        SUM(weather_precipitation_in),
        AVG(avg_speed_mph),
        MAX(max_speed_mph),
        SUM(kwh_used),
        AVG(weather_impact_factor)
    FROM trips
    WHERE DATE(start_time) = target_date
        AND is_closed = TRUE
        AND deleted_at IS NULL
    ON CONFLICT (date) DO UPDATE SET
        total_trips = EXCLUDED.total_trips,
        ev_only_trips = EXCLUDED.ev_only_trips,
        gas_mode_trips = EXCLUDED.gas_mode_trips,
        extreme_weather_trips = EXCLUDED.extreme_weather_trips,
        total_distance_miles = EXCLUDED.total_distance_miles,
        total_electric_miles = EXCLUDED.total_electric_miles,
        total_gas_miles = EXCLUDED.total_gas_miles,
        avg_trip_distance = EXCLUDED.avg_trip_distance,
        avg_kwh_per_mile = EXCLUDED.avg_kwh_per_mile,
        best_kwh_per_mile = EXCLUDED.best_kwh_per_mile,
        worst_kwh_per_mile = EXCLUDED.worst_kwh_per_mile,
        avg_mpg = EXCLUDED.avg_mpg,
        total_elevation_gain_m = EXCLUDED.total_elevation_gain_m,
        avg_elevation_gain_m = EXCLUDED.avg_elevation_gain_m,
        avg_temp_f = EXCLUDED.avg_temp_f,
        min_temp_f = EXCLUDED.min_temp_f,
        max_temp_f = EXCLUDED.max_temp_f,
        avg_wind_mph = EXCLUDED.avg_wind_mph,
        total_precipitation_in = EXCLUDED.total_precipitation_in,
        avg_speed_mph = EXCLUDED.avg_speed_mph,
        max_speed_mph = EXCLUDED.max_speed_mph,
        total_kwh_used = EXCLUDED.total_kwh_used,
        avg_weather_impact_factor = EXCLUDED.avg_weather_impact_factor,
        updated_at = NOW();
END;
$$ LANGUAGE plpgsql;

-- Function to aggregate hourly charging stats
CREATE OR REPLACE FUNCTION aggregate_hourly_charging_stats(target_hour TIMESTAMP WITH TIME ZONE)
RETURNS VOID AS $$
BEGIN
    INSERT INTO charging_hourly_stats (
        hour_timestamp,
        total_sessions,
        l1_sessions,
        l2_sessions,
        dcfc_sessions,
        completed_sessions,
        total_kwh_added,
        avg_kwh_per_session,
        avg_peak_power_kw,
        avg_avg_power_kw,
        avg_start_soc,
        avg_end_soc,
        avg_soc_gained,
        avg_session_duration,
        total_charging_minutes
    )
    SELECT
        DATE_TRUNC('hour', target_hour),
        COUNT(*),
        SUM(CASE WHEN charge_type = 'L1' THEN 1 ELSE 0 END),
        SUM(CASE WHEN charge_type = 'L2' THEN 1 ELSE 0 END),
        SUM(CASE WHEN charge_type = 'DCFC' THEN 1 ELSE 0 END),
        SUM(CASE WHEN is_complete = TRUE THEN 1 ELSE 0 END),
        SUM(kwh_added),
        AVG(kwh_added),
        AVG(peak_power_kw),
        AVG(avg_power_kw),
        AVG(start_soc),
        AVG(end_soc),
        AVG(end_soc - start_soc),
        AVG(EXTRACT(EPOCH FROM (end_time - start_time)) / 60),
        SUM(EXTRACT(EPOCH FROM (end_time - start_time)) / 60)
    FROM charging_sessions
    WHERE DATE_TRUNC('hour', start_time) = DATE_TRUNC('hour', target_hour)
        AND is_complete = TRUE
    ON CONFLICT (hour_timestamp) DO UPDATE SET
        total_sessions = EXCLUDED.total_sessions,
        l1_sessions = EXCLUDED.l1_sessions,
        l2_sessions = EXCLUDED.l2_sessions,
        dcfc_sessions = EXCLUDED.dcfc_sessions,
        completed_sessions = EXCLUDED.completed_sessions,
        total_kwh_added = EXCLUDED.total_kwh_added,
        avg_kwh_per_session = EXCLUDED.avg_kwh_per_session,
        avg_peak_power_kw = EXCLUDED.avg_peak_power_kw,
        avg_avg_power_kw = EXCLUDED.avg_avg_power_kw,
        avg_start_soc = EXCLUDED.avg_start_soc,
        avg_end_soc = EXCLUDED.avg_end_soc,
        avg_soc_gained = EXCLUDED.avg_soc_gained,
        avg_session_duration = EXCLUDED.avg_session_duration,
        total_charging_minutes = EXCLUDED.total_charging_minutes,
        updated_at = NOW();
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION aggregate_daily_trip_stats IS 'Aggregates trip data for a specific day into trip_daily_stats table';
COMMENT ON FUNCTION aggregate_hourly_charging_stats IS 'Aggregates charging data for a specific hour into charging_hourly_stats table';
