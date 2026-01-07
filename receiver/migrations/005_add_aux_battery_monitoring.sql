-- Migration: Add 12V Auxiliary Battery Monitoring
-- Created: 2026-01-07
-- Description: Creates tables for tracking 12V battery health, voltage trends, and events

-- Create aux_battery_health_readings table
CREATE TABLE IF NOT EXISTS aux_battery_health_readings (
    id SERIAL PRIMARY KEY,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL,

    -- 12V battery voltage
    voltage_v FLOAT NOT NULL,

    -- Charging status affects voltage interpretation
    is_charging BOOLEAN DEFAULT FALSE,
    charger_connected BOOLEAN DEFAULT FALSE,
    engine_running BOOLEAN DEFAULT FALSE,

    -- Current draw (if available from OBD)
    current_a FLOAT,

    -- Environmental context
    ambient_temp_f FLOAT,
    battery_temp_f FLOAT,

    -- Trip context
    odometer_miles FLOAT,
    state_of_charge FLOAT,  -- HV battery SOC (context)

    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Create index for time-series queries
CREATE INDEX IF NOT EXISTS ix_aux_battery_health_readings_timestamp
    ON aux_battery_health_readings (timestamp DESC);

-- Create aux_battery_events table
CREATE TABLE IF NOT EXISTS aux_battery_events (
    id SERIAL PRIMARY KEY,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL,

    -- Event classification
    event_type VARCHAR(50) NOT NULL,  -- 'low_voltage', 'voltage_drop', 'charging_issue', 'parasitic_drain'
    severity VARCHAR(20) NOT NULL,    -- 'info', 'warning', 'critical'

    -- Event details
    voltage_v FLOAT,
    voltage_change_v FLOAT,  -- For voltage drop events
    duration_seconds INTEGER, -- How long the event lasted

    -- Context
    description TEXT,
    is_charging BOOLEAN DEFAULT FALSE,
    charger_connected BOOLEAN DEFAULT FALSE,
    engine_running BOOLEAN DEFAULT FALSE,
    ambient_temp_f FLOAT,
    odometer_miles FLOAT,

    -- Resolution
    resolved_at TIMESTAMP WITH TIME ZONE,
    resolution_notes TEXT,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Create indexes for event queries
CREATE INDEX IF NOT EXISTS ix_aux_battery_events_timestamp
    ON aux_battery_events (timestamp DESC);

CREATE INDEX IF NOT EXISTS ix_aux_battery_events_type_timestamp
    ON aux_battery_events (event_type, timestamp);

CREATE INDEX IF NOT EXISTS ix_aux_battery_events_severity
    ON aux_battery_events (severity);

-- Add table comments
COMMENT ON TABLE aux_battery_health_readings IS 'Tracks 12V auxiliary battery voltage over time for health analysis and degradation monitoring';
COMMENT ON COLUMN aux_battery_health_readings.voltage_v IS '12V battery voltage in volts';
COMMENT ON COLUMN aux_battery_health_readings.is_charging IS 'True if HV charger is connected or engine is running (affects voltage)';
COMMENT ON COLUMN aux_battery_health_readings.current_a IS 'Current draw in amps (if available from OBD)';

COMMENT ON TABLE aux_battery_events IS 'Logs anomalies and events for the 12V auxiliary battery (voltage drops, charging issues, parasitic drain)';
COMMENT ON COLUMN aux_battery_events.event_type IS 'Type of event: low_voltage, voltage_drop, charging_issue, parasitic_drain, user_reported';
COMMENT ON COLUMN aux_battery_events.severity IS 'Severity level: info (informational), warning (needs attention), critical (urgent)';
COMMENT ON COLUMN aux_battery_events.voltage_change_v IS 'Voltage change for drop/spike events (can be negative)';
COMMENT ON COLUMN aux_battery_events.duration_seconds IS 'Duration of sustained event (e.g., low voltage for 300 seconds)';
COMMENT ON COLUMN aux_battery_events.resolved_at IS 'Timestamp when event was resolved or dismissed';

-- Migration success message
DO $$
BEGIN
    RAISE NOTICE '12V Auxiliary Battery Monitoring tables created successfully';
    RAISE NOTICE 'New tables: aux_battery_health_readings, aux_battery_events';
    RAISE NOTICE 'Use these tables to track 12V battery health, detect anomalies, and forecast replacement timing';
END $$;
