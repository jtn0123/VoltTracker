-- Migration 004: Add elevation columns and new tables
-- Run: docker exec -i volt-tracker-db psql -U volt -d volt_tracker < db/migrations/004_add_elevation_and_new_tables.sql

-- =====================================================
-- TelemetryRaw: Add elevation column
-- =====================================================
ALTER TABLE telemetry_raw ADD COLUMN IF NOT EXISTS elevation_meters FLOAT;

-- =====================================================
-- Trips: Add elevation and weather columns
-- =====================================================
ALTER TABLE trips
ADD COLUMN IF NOT EXISTS extreme_weather BOOLEAN DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS elevation_start_m FLOAT,
ADD COLUMN IF NOT EXISTS elevation_end_m FLOAT,
ADD COLUMN IF NOT EXISTS elevation_gain_m FLOAT,
ADD COLUMN IF NOT EXISTS elevation_loss_m FLOAT,
ADD COLUMN IF NOT EXISTS elevation_net_change_m FLOAT,
ADD COLUMN IF NOT EXISTS elevation_max_m FLOAT,
ADD COLUMN IF NOT EXISTS elevation_min_m FLOAT;

-- =====================================================
-- BatteryCellReadings: New table for cell voltage tracking
-- =====================================================
CREATE TABLE IF NOT EXISTS battery_cell_readings (
    id SERIAL PRIMARY KEY,
    timestamp TIMESTAMPTZ NOT NULL,
    cell_voltages JSONB,
    min_voltage FLOAT,
    max_voltage FLOAT,
    avg_voltage FLOAT,
    voltage_delta FLOAT,
    module1_avg FLOAT,
    module2_avg FLOAT,
    module3_avg FLOAT,
    ambient_temp_f FLOAT,
    state_of_charge FLOAT,
    is_charging BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_battery_cell_readings_timestamp ON battery_cell_readings(timestamp);

-- =====================================================
-- WebVitals: Performance metrics from frontend
-- =====================================================
CREATE TABLE IF NOT EXISTS web_vitals (
    id SERIAL PRIMARY KEY,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    name VARCHAR(50) NOT NULL,
    value FLOAT NOT NULL,
    rating VARCHAR(20),
    metric_id VARCHAR(100),
    navigation_type VARCHAR(50),
    url VARCHAR(500),
    user_agent TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_web_vitals_name_timestamp ON web_vitals(name, timestamp);
CREATE INDEX IF NOT EXISTS ix_web_vitals_rating ON web_vitals(rating);

-- =====================================================
-- MaintenanceRecords: Track maintenance items
-- =====================================================
CREATE TABLE IF NOT EXISTS maintenance_records (
    id SERIAL PRIMARY KEY,
    maintenance_type VARCHAR(100) NOT NULL,
    service_date TIMESTAMPTZ NOT NULL,
    odometer_miles FLOAT,
    engine_hours FLOAT,
    cost FLOAT,
    location VARCHAR(200),
    notes TEXT,
    next_due_date TIMESTAMPTZ,
    next_due_miles FLOAT,
    next_due_engine_hours FLOAT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_maintenance_type_date ON maintenance_records(maintenance_type, service_date);
CREATE INDEX IF NOT EXISTS idx_maintenance_service_date ON maintenance_records(service_date);

-- =====================================================
-- Routes: Common routes detected from GPS patterns
-- =====================================================
CREATE TABLE IF NOT EXISTS routes (
    id SERIAL PRIMARY KEY,
    name VARCHAR(200),
    start_lat FLOAT NOT NULL,
    start_lon FLOAT NOT NULL,
    end_lat FLOAT NOT NULL,
    end_lon FLOAT NOT NULL,
    trip_count INTEGER DEFAULT 1,
    avg_distance_miles FLOAT,
    avg_efficiency_kwh_per_mile FLOAT,
    avg_duration_minutes FLOAT,
    best_efficiency FLOAT,
    worst_efficiency FLOAT,
    last_traveled TIMESTAMPTZ,
    elevation_profile JSONB,
    avg_elevation_gain_m FLOAT,
    avg_elevation_loss_m FLOAT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- =====================================================
-- WeatherCache: Persistent cache for weather API
-- =====================================================
CREATE TABLE IF NOT EXISTS weather_cache (
    id SERIAL PRIMARY KEY,
    latitude_key FLOAT NOT NULL,
    longitude_key FLOAT NOT NULL,
    timestamp_hour VARCHAR(16) NOT NULL,
    temperature_f FLOAT,
    precipitation_in FLOAT,
    wind_speed_mph FLOAT,
    weather_code INTEGER,
    conditions VARCHAR(50),
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    api_source VARCHAR(20),
    UNIQUE(latitude_key, longitude_key, timestamp_hour)
);

CREATE INDEX IF NOT EXISTS ix_weather_cache_lookup ON weather_cache(latitude_key, longitude_key, timestamp_hour);
CREATE INDEX IF NOT EXISTS ix_weather_cache_fetched_at ON weather_cache(fetched_at);

-- =====================================================
-- TripDailyStats: Daily aggregation for fast analytics
-- =====================================================
CREATE TABLE IF NOT EXISTS trip_daily_stats (
    id SERIAL PRIMARY KEY,
    date DATE NOT NULL UNIQUE,
    total_trips INTEGER DEFAULT 0,
    ev_only_trips INTEGER DEFAULT 0,
    gas_mode_trips INTEGER DEFAULT 0,
    extreme_weather_trips INTEGER DEFAULT 0,
    total_distance_miles FLOAT DEFAULT 0,
    total_electric_miles FLOAT DEFAULT 0,
    total_gas_miles FLOAT DEFAULT 0,
    avg_trip_distance FLOAT,
    avg_kwh_per_mile FLOAT,
    best_kwh_per_mile FLOAT,
    worst_kwh_per_mile FLOAT,
    avg_mpg FLOAT,
    total_elevation_gain_m FLOAT DEFAULT 0,
    avg_elevation_gain_m FLOAT,
    avg_temp_f FLOAT,
    min_temp_f FLOAT,
    max_temp_f FLOAT,
    avg_wind_mph FLOAT,
    total_precipitation_in FLOAT DEFAULT 0,
    avg_speed_mph FLOAT,
    max_speed_mph FLOAT,
    total_kwh_used FLOAT DEFAULT 0,
    avg_weather_impact_factor FLOAT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- =====================================================
-- ChargingHourlyStats: Hourly aggregation of charging
-- =====================================================
CREATE TABLE IF NOT EXISTS charging_hourly_stats (
    id SERIAL PRIMARY KEY,
    hour_timestamp TIMESTAMPTZ NOT NULL UNIQUE,
    total_sessions INTEGER DEFAULT 0,
    l1_sessions INTEGER DEFAULT 0,
    l2_sessions INTEGER DEFAULT 0,
    dcfc_sessions INTEGER DEFAULT 0,
    completed_sessions INTEGER DEFAULT 0,
    total_kwh_added FLOAT DEFAULT 0,
    avg_kwh_per_session FLOAT,
    avg_peak_power_kw FLOAT,
    avg_avg_power_kw FLOAT,
    avg_start_soc FLOAT,
    avg_end_soc FLOAT,
    avg_soc_gained FLOAT,
    avg_session_duration FLOAT,
    total_charging_minutes FLOAT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- =====================================================
-- MonthlySummary: Monthly summary statistics
-- =====================================================
CREATE TABLE IF NOT EXISTS monthly_summary (
    id SERIAL PRIMARY KEY,
    year INTEGER NOT NULL,
    month INTEGER NOT NULL,
    total_trips INTEGER DEFAULT 0,
    total_distance_miles FLOAT DEFAULT 0,
    total_electric_miles FLOAT DEFAULT 0,
    total_gas_miles FLOAT DEFAULT 0,
    electric_percentage FLOAT,
    avg_kwh_per_mile FLOAT,
    avg_mpg FLOAT,
    total_kwh_used FLOAT DEFAULT 0,
    total_gallons_used FLOAT DEFAULT 0,
    total_charging_sessions INTEGER DEFAULT 0,
    total_kwh_charged FLOAT DEFAULT 0,
    l1_sessions INTEGER DEFAULT 0,
    l2_sessions INTEGER DEFAULT 0,
    dcfc_sessions INTEGER DEFAULT 0,
    estimated_electricity_cost FLOAT,
    estimated_gas_cost FLOAT,
    co2_avoided_lbs FLOAT,
    avg_temp_f FLOAT,
    extreme_weather_trips INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(year, month)
);

-- =====================================================
-- AuditLogs: Track data changes and operations
-- =====================================================
CREATE TABLE IF NOT EXISTS audit_logs (
    id SERIAL PRIMARY KEY,
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(50) NOT NULL,
    action VARCHAR(20) NOT NULL,
    old_data JSON,
    new_data JSON,
    details TEXT,
    user_id VARCHAR(50),
    username VARCHAR(100),
    ip_address VARCHAR(50),
    user_agent TEXT,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_audit_logs_entity ON audit_logs(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS ix_audit_logs_action ON audit_logs(action, timestamp);
CREATE INDEX IF NOT EXISTS ix_audit_logs_timestamp ON audit_logs(timestamp);
CREATE INDEX IF NOT EXISTS ix_audit_logs_ip ON audit_logs(ip_address);

-- =====================================================
-- Add unique constraint on charging_sessions if missing
-- =====================================================
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uq_charging_session_start_time'
    ) THEN
        ALTER TABLE charging_sessions ADD CONSTRAINT uq_charging_session_start_time UNIQUE (start_time);
    END IF;
EXCEPTION WHEN others THEN
    RAISE NOTICE 'Constraint may already exist or table structure differs';
END $$;

-- =====================================================
-- Add composite indexes for trips table if missing
-- =====================================================
CREATE INDEX IF NOT EXISTS ix_trips_is_closed_start_time ON trips(is_closed, start_time);
CREATE INDEX IF NOT EXISTS ix_trips_gas_mode_start_time ON trips(gas_mode_entered, start_time);
CREATE INDEX IF NOT EXISTS ix_trips_closed_deleted_time ON trips(is_closed, deleted_at, start_time);

-- =====================================================
-- Add composite index for telemetry_raw if missing
-- =====================================================
CREATE INDEX IF NOT EXISTS ix_telemetry_session_timestamp ON telemetry_raw(session_id, timestamp);

-- =====================================================
-- Add composite index for charging_sessions if missing
-- =====================================================
CREATE INDEX IF NOT EXISTS ix_charging_sessions_is_complete_start_time ON charging_sessions(is_complete, start_time);

-- Done!
DO $$
BEGIN
    RAISE NOTICE 'Migration 004 completed successfully!';
END $$;
