CREATE TABLE sync_push_installations (
    id VARCHAR(64) PRIMARY KEY,
    secret_hash VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    principal_id VARCHAR(64) NOT NULL,
    auth_version BIGINT NOT NULL,
    session_hash VARCHAR(64) NOT NULL,
    management_session BOOLEAN NOT NULL,
    binding_id VARCHAR(64) NOT NULL,
    token VARCHAR(512) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    environment VARCHAR(16) NOT NULL,
    locale VARCHAR(8) NOT NULL,
    approvals BOOLEAN NOT NULL,
    questions BOOLEAN NOT NULL,
    turn_ended BOOLEAN NOT NULL,
    subscribed_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    UNIQUE(environment, token_hash)
);
CREATE INDEX sync_push_owner ON sync_push_installations(tenant_id, principal_id, expires_at);
CREATE INDEX sync_push_session ON sync_push_installations(session_hash);
