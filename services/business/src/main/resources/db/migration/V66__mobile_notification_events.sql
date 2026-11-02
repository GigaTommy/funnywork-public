ALTER TABLE sync_devices ADD COLUMN notification_baseline_json TEXT;

CREATE TABLE sync_notification_events (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    principal_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    epoch VARCHAR(64) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    source_id VARCHAR(256) NOT NULL,
    created_at BIGINT NOT NULL,
    delivery_state VARCHAR(16) NOT NULL DEFAULT 'queued',
    FOREIGN KEY (device_id) REFERENCES sync_devices(id),
    FOREIGN KEY (session_id) REFERENCES sync_sessions(id)
);
CREATE INDEX sync_notification_delivery ON sync_notification_events(delivery_state, created_at, id);
CREATE INDEX sync_notification_owner ON sync_notification_events(tenant_id, principal_id, created_at, id);
