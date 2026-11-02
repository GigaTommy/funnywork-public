-- Company directory uses existing centralized sources, not a second transcript store.
CREATE INDEX sync_sessions_tenant_directory ON sync_sessions(tenant_id,id);
CREATE INDEX mobile_chats_tenant_directory ON mobile_chats(tenant_id,id);
CREATE INDEX employee_chats_tenant_directory ON employee_chats(tenant_id,id);
ALTER TABLE sync_session_events ADD COLUMN event_type VARCHAR(128);
