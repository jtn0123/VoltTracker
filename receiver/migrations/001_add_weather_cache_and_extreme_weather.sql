-- Migration: Add WeatherCache table and extreme_weather flag to trips
-- Created: 2026-01-06
-- Description: Adds persistent weather cache table and extreme weather flagging

-- Add extreme_weather column to trips table
ALTER TABLE trips ADD COLUMN IF NOT EXISTS extreme_weather BOOLEAN DEFAULT FALSE;

-- Create weather_cache table for persistent API response caching
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
    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    api_source VARCHAR(20),
    CONSTRAINT uq_weather_cache_key UNIQUE (latitude_key, longitude_key, timestamp_hour)
);

-- Create indexes for fast lookups
CREATE INDEX IF NOT EXISTS ix_weather_cache_lookup
    ON weather_cache (latitude_key, longitude_key, timestamp_hour);

CREATE INDEX IF NOT EXISTS ix_weather_cache_fetched_at
    ON weather_cache (fetched_at);

-- Add comment explaining the cache key strategy
COMMENT ON TABLE weather_cache IS 'Persistent cache for weather API responses. Coordinates rounded to 2 decimals (~1km precision) to increase cache hit rate.';
COMMENT ON COLUMN weather_cache.latitude_key IS 'Latitude rounded to 2 decimals';
COMMENT ON COLUMN weather_cache.longitude_key IS 'Longitude rounded to 2 decimals';
COMMENT ON COLUMN weather_cache.timestamp_hour IS 'Hour timestamp in format YYYY-MM-DD-HH';
COMMENT ON COLUMN weather_cache.api_source IS 'API source: forecast or historical';

-- Optional: Clean up old cache entries (older than 7 days)
-- DELETE FROM weather_cache WHERE fetched_at < NOW() - INTERVAL '7 days';
