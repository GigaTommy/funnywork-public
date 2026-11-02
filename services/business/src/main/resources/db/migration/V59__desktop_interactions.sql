ALTER TABLE sync_devices ADD COLUMN runtime_epoch VARCHAR(64);
ALTER TABLE sync_devices ADD COLUMN state_json TEXT;
ALTER TABLE sync_devices ADD COLUMN state_seen BIGINT NOT NULL DEFAULT 0;
CREATE INDEX sync_sessions_directory ON sync_sessions(tenant_id,principal_id,id);
CREATE TABLE sync_commands (
 id VARCHAR(64) PRIMARY KEY, session_id VARCHAR(64) NOT NULL REFERENCES sync_sessions(id),
 principal_id VARCHAR(64) NOT NULL REFERENCES principals(id), tenant_id VARCHAR(64) NOT NULL,
 device_id VARCHAR(64) NOT NULL REFERENCES sync_devices(id), epoch VARCHAR(64) NOT NULL,
 kind VARCHAR(20) NOT NULL, payload_json TEXT NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'queued', result_json TEXT,
 created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL,
 UNIQUE(principal_id,id)
);
CREATE INDEX sync_commands_delivery ON sync_commands(device_id,status,created_at);
