-- Volt Efficiency Tracker Database Schema
-- PostgreSQL 15 with TimescaleDB

-- Enable TimescaleDB extension
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- Table: telemetry_raw
-- Stores every data point received from Torque Pro
CREATE TABLE telemetry_raw (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL,
    latitude DECIMAL(9,6),
    longitude DECIMAL(9,6),
    speed_mph DECIMAL(5,1),
    engine_rpm DECIMAL(6,1),
    throttle_position DECIMAL(5,2),
    coolant_temp_f DECIMAL(5,1),
    intake_air_temp_f DECIMAL(5,1),
    fuel_level_percent DECIMAL(5,2),
    fuel_remaining_gallons DECIMAL(4,2),
    state_of_charge DECIMAL(5,2),
    battery_voltage DECIMAL(5,2),
    ambient_temp_f DECIMAL(5,1),
    odometer_miles DECIMAL(10,1),
    -- HV Battery tracking for kWh calculations
    hv_battery_power_kw DECIMAL(6,2),
    hv_battery_current_a DECIMAL(6,2),
    hv_battery_voltage_v DECIMAL(6,2),
    -- Charging status
    charger_ac_power_kw DECIMAL(6,2),
    charger_connected BOOLEAN,
    raw_data JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_telemetry_timestamp ON telemetry_raw(timestamp);
CREATE INDEX idx_telemetry_session ON telemetry_raw(session_id);
CREATE INDEX idx_telemetry_soc ON telemetry_raw(state_of_charge);
CREATE INDEX idx_telemetry_rpm ON telemetry_raw(engine_rpm);

-- Table: trips
-- Aggregated trip summaries, auto-generated from telemetry_raw
CREATE TABLE trips (
    id SERIAL PRIMARY KEY,
    session_id UUID UNIQUE NOT NULL,
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ,
    start_odometer DECIMAL(10,1),
    end_odometer DECIMAL(10,1),
    distance_miles DECIMAL(6,1),

    -- Electric portion
    start_soc DECIMAL(5,2),
    soc_at_gas_transition DECIMAL(5,2),
    electric_miles DECIMAL(6,1),
    electric_kwh_used DECIMAL(6,2),
    kwh_per_mile DECIMAL(6,3),

    -- Gas portion
    gas_mode_entered BOOLEAN DEFAULT FALSE,
    gas_mode_entry_time TIMESTAMPTZ,
    gas_miles DECIMAL(6,1),
    fuel_used_gallons DECIMAL(4,2),
    gas_mpg DECIMAL(5,1),

    -- Fuel levels for calculation
    fuel_level_at_gas_entry DECIMAL(5,2),
    fuel_level_at_end DECIMAL(5,2),

    -- Metadata
    ambient_temp_avg_f DECIMAL(5,1),
    is_closed BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_trips_start_time ON trips(start_time);
CREATE INDEX idx_trips_gas_mode ON trips(gas_mode_entered);
CREATE INDEX idx_trips_is_closed ON trips(is_closed);

-- Table: fuel_events
-- Tracks refueling events for tank-based efficiency calculations
CREATE TABLE fuel_events (
    id SERIAL PRIMARY KEY,
    timestamp TIMESTAMPTZ NOT NULL,
    odometer_miles DECIMAL(10,1),
    gallons_added DECIMAL(4,2),
    fuel_level_before DECIMAL(5,2),
    fuel_level_after DECIMAL(5,2),
    price_per_gallon DECIMAL(4,3),
    total_cost DECIMAL(6,2),
    notes TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_fuel_events_timestamp ON fuel_events(timestamp);

-- Table: soc_transitions
-- Records every electric-to-gas transition for SOC floor analysis
CREATE TABLE soc_transitions (
    id SERIAL PRIMARY KEY,
    trip_id INTEGER REFERENCES trips(id),
    timestamp TIMESTAMPTZ NOT NULL,
    soc_at_transition DECIMAL(5,2),
    ambient_temp_f DECIMAL(5,1),
    odometer_miles DECIMAL(10,1),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_soc_transitions_timestamp ON soc_transitions(timestamp);

-- Table: charging_sessions
-- Tracks charging sessions for energy analysis
CREATE TABLE charging_sessions (
    id SERIAL PRIMARY KEY,
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ,
    start_soc DECIMAL(5,2),
    end_soc DECIMAL(5,2),
    -- Energy tracking
    kwh_added DECIMAL(6,2),
    peak_power_kw DECIMAL(6,2),
    avg_power_kw DECIMAL(6,2),
    -- Location
    latitude DECIMAL(9,6),
    longitude DECIMAL(9,6),
    location_name VARCHAR(255),
    -- Charging type
    charge_type VARCHAR(50),
    -- Cost tracking
    cost DECIMAL(6,2),
    cost_per_kwh DECIMAL(6,4),
    notes TEXT,
    -- Status
    is_complete BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_charging_sessions_start_time ON charging_sessions(start_time);
CREATE INDEX idx_charging_sessions_is_complete ON charging_sessions(is_complete);

-- Function to update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Trigger for trips table
CREATE TRIGGER update_trips_updated_at
    BEFORE UPDATE ON trips
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- Trigger for charging_sessions table
CREATE TRIGGER update_charging_sessions_updated_at
    BEFORE UPDATE ON charging_sessions
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- TimescaleDB Hypertable Configuration
-- ============================================================================

-- Convert telemetry_raw to a hypertable for efficient time-series queries
-- Partitioned by timestamp with 1-day chunks
SELECT create_hypertable('telemetry_raw', 'timestamp',
    chunk_time_interval => INTERVAL '1 day',
    if_not_exists => TRUE
);

-- Enable compression on telemetry_raw for storage efficiency
-- Compress data older than 7 days, segment by session for efficient queries
ALTER TABLE telemetry_raw SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'session_id'
);

-- Add compression policy to automatically compress old data
SELECT add_compression_policy('telemetry_raw', INTERVAL '7 days', if_not_exists => TRUE);

-- Optional: Add retention policy (uncomment to auto-delete data older than 2 years)
-- SELECT add_retention_policy('telemetry_raw', INTERVAL '2 years', if_not_exists => TRUE);
