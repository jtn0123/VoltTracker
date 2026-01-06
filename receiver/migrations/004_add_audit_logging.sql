-- Migration: Add Audit Logging
-- Created: 2026-01-06
-- Description: Creates audit_logs table for tracking data changes and compliance

-- Create audit_logs table
CREATE TABLE IF NOT EXISTS audit_logs (
    id SERIAL PRIMARY KEY,

    -- What was changed
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(50) NOT NULL,
    action VARCHAR(20) NOT NULL,

    -- Change details
    old_data JSON,
    new_data JSON,
    details TEXT,

    -- Who made the change
    user_id VARCHAR(50),
    username VARCHAR(100),
    ip_address VARCHAR(50),
    user_agent TEXT,

    -- When
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Create indexes for fast lookups
CREATE INDEX IF NOT EXISTS ix_audit_logs_entity
    ON audit_logs (entity_type, entity_id);

CREATE INDEX IF NOT EXISTS ix_audit_logs_action
    ON audit_logs (action, timestamp);

CREATE INDEX IF NOT EXISTS ix_audit_logs_timestamp
    ON audit_logs (timestamp DESC);

CREATE INDEX IF NOT EXISTS ix_audit_logs_ip
    ON audit_logs (ip_address);

-- Add table comments
COMMENT ON TABLE audit_logs IS 'Audit trail for tracking data changes, deletions, exports, and critical operations';
COMMENT ON COLUMN audit_logs.entity_type IS 'Type of entity changed (trips, charging_sessions, etc.)';
COMMENT ON COLUMN audit_logs.entity_id IS 'ID of the entity that was changed';
COMMENT ON COLUMN audit_logs.action IS 'Action performed (create, update, delete, soft_delete, export, import)';
COMMENT ON COLUMN audit_logs.old_data IS 'Previous state of the entity (for updates)';
COMMENT ON COLUMN audit_logs.new_data IS 'New state of the entity (for creates/updates)';
COMMENT ON COLUMN audit_logs.ip_address IS 'IP address of the client making the request';

-- Optional: Add retention policy (delete logs older than 1 year)
-- Uncomment if you want automatic cleanup:
-- CREATE OR REPLACE FUNCTION cleanup_old_audit_logs()
-- RETURNS void AS $$
-- BEGIN
--     DELETE FROM audit_logs WHERE timestamp < NOW() - INTERVAL '1 year';
-- END;
-- $$ LANGUAGE plpgsql;
