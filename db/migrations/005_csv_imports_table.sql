-- Migration 005: CSV Imports tracking table for import hardening
-- Run: docker exec -i volt-tracker-db psql -U volt -d volt_tracker < db/migrations/005_csv_imports_table.sql

-- =====================================================
-- CsvImports: Track every import attempt for audit trail
-- =====================================================
CREATE TABLE IF NOT EXISTS csv_imports (
    id SERIAL PRIMARY KEY,
    import_code VARCHAR(20) NOT NULL UNIQUE,  -- e.g., "IMP-20260107-A1B2C3"
    filename VARCHAR(255) NOT NULL,
    file_hash VARCHAR(64) NOT NULL,           -- SHA-256 of content
    file_size_bytes INTEGER NOT NULL,

    -- Results
    status VARCHAR(20) NOT NULL,              -- 'success', 'partial', 'failed', 'duplicate'
    failure_reason VARCHAR(100),
    failure_details JSONB,                    -- Full error context
    suggestion TEXT,

    -- Stats
    total_rows INTEGER DEFAULT 0,
    parsed_rows INTEGER DEFAULT 0,
    skipped_rows INTEGER DEFAULT 0,
    duplicate_rows INTEGER DEFAULT 0,

    -- Column info
    columns_detected JSONB,
    columns_mapped JSONB,
    timestamp_range_start TIMESTAMPTZ,
    timestamp_range_end TIMESTAMPTZ,

    -- Links
    trip_id INTEGER REFERENCES trips(id) ON DELETE SET NULL,
    session_id VARCHAR(36),

    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Unique constraint on file_hash to prevent exact duplicate imports
CREATE UNIQUE INDEX IF NOT EXISTS ix_csv_imports_hash ON csv_imports(file_hash);

-- Index for looking up by import code
CREATE INDEX IF NOT EXISTS ix_csv_imports_code ON csv_imports(import_code);

-- Index for listing recent imports
CREATE INDEX IF NOT EXISTS ix_csv_imports_created ON csv_imports(created_at DESC);

-- Index for filtering by status
CREATE INDEX IF NOT EXISTS ix_csv_imports_status ON csv_imports(status);

-- Done!
DO $$
BEGIN
    RAISE NOTICE 'Migration 005 completed successfully!';
END $$;
