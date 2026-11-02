CREATE TABLE sync_push_deliveries (
    id VARCHAR(64) PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    installation_id VARCHAR(64) NOT NULL,
    binding_id VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'queued',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt BIGINT NOT NULL,
    response_status INTEGER,
    FOREIGN KEY (event_id) REFERENCES sync_notification_events(id),
    UNIQUE(event_id, binding_id)
);
CREATE INDEX sync_push_due ON sync_push_deliveries(state, next_attempt, id);
