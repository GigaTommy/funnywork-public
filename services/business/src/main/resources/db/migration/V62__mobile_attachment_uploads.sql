CREATE TABLE sync_mobile_uploads (
 session_id VARCHAR(64) NOT NULL REFERENCES sync_sessions(id),
 hash VARCHAR(64) NOT NULL,
 metadata_json TEXT NOT NULL,
 PRIMARY KEY(session_id,hash)
);
