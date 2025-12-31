-- Migration: Add weather data columns to trips table
--
-- Run this migration: docker exec -i volt-tracker-db psql -U volt -d volt_tracker < db/migrations/003_add_weather_columns.sql

-- Add weather columns to trips table
ALTER TABLE trips
ADD COLUMN IF NOT EXISTS weather_temp_f DECIMAL(5,1),
ADD COLUMN IF NOT EXISTS weather_precipitation_in DECIMAL(5,3),
ADD COLUMN IF NOT EXISTS weather_wind_mph DECIMAL(5,1),
ADD COLUMN IF NOT EXISTS weather_conditions VARCHAR(50),
ADD COLUMN IF NOT EXISTS weather_impact_factor DECIMAL(4,3);

-- Create index for weather-based queries
CREATE INDEX IF NOT EXISTS idx_trips_weather_conditions ON trips(weather_conditions);
CREATE INDEX IF NOT EXISTS idx_trips_weather_temp ON trips(weather_temp_f);

-- Add comment describing the columns
COMMENT ON COLUMN trips.weather_temp_f IS 'Temperature in Fahrenheit from Open-Meteo API';
COMMENT ON COLUMN trips.weather_precipitation_in IS 'Precipitation in inches during the trip';
COMMENT ON COLUMN trips.weather_wind_mph IS 'Wind speed in mph during the trip';
COMMENT ON COLUMN trips.weather_conditions IS 'Weather conditions (e.g., Clear, Rain, Snow)';
COMMENT ON COLUMN trips.weather_impact_factor IS 'Estimated efficiency impact factor (1.0 = ideal, >1.0 = worse conditions)';
