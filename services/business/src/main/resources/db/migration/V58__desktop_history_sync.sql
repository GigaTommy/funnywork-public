CREATE TABLE sync_devices (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL,
 principal_id VARCHAR(64) NOT NULL REFERENCES principals(id),
 name VARCHAR(120) NOT NULL, platform VARCHAR(20) NOT NULL,
 revoked BOOLEAN NOT NULL DEFAULT FALSE, created_at BIGINT NOT NULL, last_seen BIGINT NOT NULL
);
CREATE TABLE sync_sessions (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL,
 principal_id VARCHAR(64) NOT NULL REFERENCES principals(id),
 device_id VARCHAR(64) NOT NULL REFERENCES sync_devices(id),
 native_id VARCHAR(128) NOT NULL, header_json TEXT NOT NULL,
 inherited_event_count BIGINT NOT NULL, next_seq BIGINT NOT NULL DEFAULT 0,
 title VARCHAR(160) NOT NULL DEFAULT '',
 updated_at BIGINT NOT NULL,
 UNIQUE(device_id,native_id)
);
CREATE INDEX sync_sessions_owner ON sync_sessions(tenant_id,principal_id,updated_at,id);
CREATE TABLE sync_session_events (
 session_id VARCHAR(64) NOT NULL REFERENCES sync_sessions(id),
 seq BIGINT NOT NULL, event_json TEXT NOT NULL,
 PRIMARY KEY(session_id,seq)
);
