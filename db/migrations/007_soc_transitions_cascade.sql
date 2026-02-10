-- Migration 007: Add ON DELETE CASCADE to soc_transitions.trip_id FK
-- Run: docker exec -i volt-tracker-db psql -U volt -d volt_tracker < db/migrations/007_soc_transitions_cascade.sql

-- Drop existing FK and re-add with CASCADE
ALTER TABLE soc_transitions
    DROP CONSTRAINT IF EXISTS soc_transitions_trip_id_fkey;

ALTER TABLE soc_transitions
    ADD CONSTRAINT soc_transitions_trip_id_fkey
    FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE CASCADE;
