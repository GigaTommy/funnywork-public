CREATE TABLE sync_results (
 session_id VARCHAR(64) NOT NULL REFERENCES sync_sessions(id),
 result_id VARCHAR(64) NOT NULL, version INTEGER NOT NULL,
 original_hash VARCHAR(64) NOT NULL, manifest_json TEXT NOT NULL,
 event_seq BIGINT NOT NULL, created_at BIGINT NOT NULL,
 PRIMARY KEY(session_id,result_id,version)
);
CREATE INDEX sync_results_event_cursor ON sync_results(session_id,event_seq,result_id,version);
