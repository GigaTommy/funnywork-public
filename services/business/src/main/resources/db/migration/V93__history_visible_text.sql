-- Derived visible content only; NULL retains read compatibility for old events.
-- Do not backfill large raw histories during deployment migrations.
ALTER TABLE sync_session_events ADD COLUMN visible_text TEXT;
ALTER TABLE sync_session_events ADD COLUMN projection_version INTEGER NOT NULL DEFAULT 0;
CREATE INDEX sync_events_projection_pending ON sync_session_events(projection_version,session_id,seq);
CREATE INDEX sync_sessions_device_directory ON sync_sessions(tenant_id,device_id,id);
