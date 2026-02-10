CREATE UNIQUE INDEX IF NOT EXISTS idx_telemetry_session_timestamp ON telemetry_raw(session_id, timestamp);
