-- Volt Efficiency Tracker Database Schema
-- PostgreSQL 15

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
