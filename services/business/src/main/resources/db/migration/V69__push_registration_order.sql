CREATE TABLE sync_push_registration_lock (id INTEGER PRIMARY KEY);
INSERT INTO sync_push_registration_lock(id) VALUES(1);
CREATE TABLE sync_push_registration_order (
    id VARCHAR(64) PRIMARY KEY,
    secret_hash VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    request_hash VARCHAR(64)
);
INSERT INTO sync_push_registration_order(id,secret_hash,revision)
SELECT id,secret_hash,0 FROM sync_push_installations;
CREATE TABLE sync_push_closed_sessions (
    session_hash VARCHAR(64) PRIMARY KEY,
    closed_at BIGINT NOT NULL
);
