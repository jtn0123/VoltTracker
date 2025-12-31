-- Migration 001: Add expanded telemetry fields and battery health tracking
-- Run this on existing databases to add new columns

-- =====================================================
-- TelemetryRaw: Add new HV Battery fields
-- =====================================================
ALTER TABLE telemetry_raw
ADD COLUMN IF NOT EXISTS hv_discharge_amps FLOAT,
ADD COLUMN IF NOT EXISTS battery_temp_f FLOAT,
ADD COLUMN IF NOT EXISTS battery_coolant_temp_f FLOAT;

-- =====================================================
-- TelemetryRaw: Add expanded charging fields
-- =====================================================
ALTER TABLE telemetry_raw
ADD COLUMN IF NOT EXISTS charger_status FLOAT,
ADD COLUMN IF NOT EXISTS charger_power_kw FLOAT,
ADD COLUMN IF NOT EXISTS charger_power_w FLOAT,
ADD COLUMN IF NOT EXISTS charger_ac_voltage FLOAT,
ADD COLUMN IF NOT EXISTS charger_ac_current FLOAT,
ADD COLUMN IF NOT EXISTS charger_hv_voltage FLOAT,
ADD COLUMN IF NOT EXISTS charger_hv_current FLOAT,
ADD COLUMN IF NOT EXISTS last_charge_wh FLOAT;

-- =====================================================
-- TelemetryRaw: Add Motor/Generator fields
-- =====================================================
ALTER TABLE telemetry_raw
ADD COLUMN IF NOT EXISTS motor_a_rpm FLOAT,
ADD COLUMN IF NOT EXISTS motor_b_rpm FLOAT,
ADD COLUMN IF NOT EXISTS generator_rpm FLOAT,
ADD COLUMN IF NOT EXISTS motor_temp_max_f FLOAT;

-- =====================================================
-- TelemetryRaw: Add Engine detail fields
-- =====================================================
ALTER TABLE telemetry_raw
ADD COLUMN IF NOT EXISTS engine_oil_temp_f FLOAT,
ADD COLUMN IF NOT EXISTS engine_torque_nm FLOAT,
ADD COLUMN IF NOT EXISTS engine_running BOOLEAN,
ADD COLUMN IF NOT EXISTS transmission_temp_f FLOAT;

-- =====================================================
-- TelemetryRaw: Add Battery health field
-- =====================================================
ALTER TABLE telemetry_raw
ADD COLUMN IF NOT EXISTS battery_capacity_kwh FLOAT;

-- =====================================================
-- TelemetryRaw: Add Lifetime counters
-- =====================================================
ALTER TABLE telemetry_raw
ADD COLUMN IF NOT EXISTS lifetime_ev_miles FLOAT,
ADD COLUMN IF NOT EXISTS lifetime_gas_miles FLOAT,
ADD COLUMN IF NOT EXISTS lifetime_fuel_gal FLOAT,
ADD COLUMN IF NOT EXISTS lifetime_kwh FLOAT,
ADD COLUMN IF NOT EXISTS dte_electric_miles FLOAT,
ADD COLUMN IF NOT EXISTS dte_gas_miles FLOAT;

-- =====================================================
-- ChargingSession: Add cost tracking and curve data
-- =====================================================
ALTER TABLE charging_sessions
ADD COLUMN IF NOT EXISTS electricity_rate FLOAT,
ADD COLUMN IF NOT EXISTS charging_curve JSONB;

-- =====================================================
-- BatteryHealthReading: New table for degradation tracking
-- =====================================================
CREATE TABLE IF NOT EXISTS battery_health_readings (
    id SERIAL PRIMARY KEY,
    timestamp TIMESTAMPTZ NOT NULL,
    capacity_kwh FLOAT,
    normalized_capacity_kwh FLOAT,
    soc_at_reading FLOAT,
    ambient_temp_f FLOAT,
    odometer_miles FLOAT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_battery_health_timestamp
    ON battery_health_readings(timestamp);

-- =====================================================
-- Useful indexes for new queries
-- =====================================================
CREATE INDEX IF NOT EXISTS idx_telemetry_motor_a_rpm
    ON telemetry_raw(motor_a_rpm)
    WHERE motor_a_rpm IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_telemetry_charger_status
    ON telemetry_raw(charger_status)
    WHERE charger_status IS NOT NULL;
