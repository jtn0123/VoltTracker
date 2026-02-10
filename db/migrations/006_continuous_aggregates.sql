-- Migration: Add continuous aggregates for common dashboard queries
--
-- Prerequisites: TimescaleDB extension enabled, telemetry_raw is a hypertable
-- Run: docker exec -i volt-tracker-db psql -U volt -d volt_tracker < db/migrations/006_continuous_aggregates.sql

-- Hourly telemetry aggregates (used by dashboard summary, efficiency trends)
CREATE MATERIALIZED VIEW IF NOT EXISTS telemetry_hourly
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 hour', timestamp) AS bucket,
    session_id,
    AVG(speed_mph) AS avg_speed_mph,
    MAX(speed_mph) AS max_speed_mph,
    AVG(state_of_charge) AS avg_soc,
    MIN(state_of_charge) AS min_soc,
    MAX(state_of_charge) AS max_soc,
    AVG(hv_battery_power_kw) AS avg_power_kw,
    AVG(fuel_level_percent) AS avg_fuel_percent,
    COUNT(*) AS sample_count
FROM telemetry_raw
GROUP BY bucket, session_id
WITH NO DATA;

-- Refresh policy: continuously refresh hourly aggregates (lag 1 hour)
SELECT add_continuous_aggregate_policy('telemetry_hourly',
    start_offset => INTERVAL '3 days',
    end_offset => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour',
    if_not_exists => TRUE
);

-- Daily telemetry aggregates (used by trend charts, long-term analytics)
CREATE MATERIALIZED VIEW IF NOT EXISTS telemetry_daily
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 day', timestamp) AS bucket,
    session_id,
    AVG(speed_mph) AS avg_speed_mph,
    MAX(speed_mph) AS max_speed_mph,
    AVG(state_of_charge) AS avg_soc,
    MIN(state_of_charge) AS min_soc,
    MAX(state_of_charge) AS max_soc,
    SUM(CASE WHEN hv_battery_power_kw > 0 THEN hv_battery_power_kw ELSE 0 END) AS total_discharge_kw,
    SUM(CASE WHEN hv_battery_power_kw < 0 THEN ABS(hv_battery_power_kw) ELSE 0 END) AS total_regen_kw,
    COUNT(*) AS sample_count
FROM telemetry_raw
GROUP BY bucket, session_id
WITH NO DATA;

SELECT add_continuous_aggregate_policy('telemetry_daily',
    start_offset => INTERVAL '30 days',
    end_offset => INTERVAL '1 day',
    schedule_interval => INTERVAL '1 day',
    if_not_exists => TRUE
);

-- Initial refresh of aggregates
CALL refresh_continuous_aggregate('telemetry_hourly', NULL, NULL);
CALL refresh_continuous_aggregate('telemetry_daily', NULL, NULL);
