-- Migration: Convert existing PostgreSQL database to TimescaleDB
--
-- IMPORTANT: Run this AFTER backing up your data!
-- This migration converts telemetry_raw to a TimescaleDB hypertable
--
-- Prerequisites:
-- 1. Update docker-compose.yml to use timescale/timescaledb:latest-pg15
-- 2. Restart the database container
-- 3. Run this migration manually: docker exec -i volt-tracker-db psql -U volt -d volt_tracker < db/migrations/002_timescaledb_migration.sql

-- Enable TimescaleDB extension
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- Check if already a hypertable (this will error if not, which is expected)
DO $$
DECLARE
    is_hypertable boolean;
BEGIN
    SELECT EXISTS (
        SELECT 1 FROM timescaledb_information.hypertables
        WHERE hypertable_name = 'telemetry_raw'
    ) INTO is_hypertable;

    IF is_hypertable THEN
        RAISE NOTICE 'telemetry_raw is already a hypertable, skipping conversion';
    ELSE
        RAISE NOTICE 'Converting telemetry_raw to hypertable...';

        -- Convert to hypertable, migrating existing data
        PERFORM create_hypertable('telemetry_raw', 'timestamp',
            chunk_time_interval => INTERVAL '1 day',
            migrate_data => true
        );

        RAISE NOTICE 'Hypertable conversion complete';

        -- Enable compression
        ALTER TABLE telemetry_raw SET (
            timescaledb.compress,
            timescaledb.compress_segmentby = 'session_id'
        );

        -- Add compression policy (compress data older than 7 days)
        PERFORM add_compression_policy('telemetry_raw', INTERVAL '7 days', if_not_exists => TRUE);

        RAISE NOTICE 'Compression policy added';
    END IF;
END $$;

-- Show hypertable info
SELECT * FROM timescaledb_information.hypertables WHERE hypertable_name = 'telemetry_raw';

-- Show compression settings
SELECT * FROM timescaledb_information.compression_settings WHERE hypertable_name = 'telemetry_raw';

RAISE NOTICE 'TimescaleDB migration complete!';
