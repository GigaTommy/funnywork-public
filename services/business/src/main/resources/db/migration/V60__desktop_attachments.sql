CREATE TABLE sync_attachment_refs (
 session_id VARCHAR(64) NOT NULL REFERENCES sync_sessions(id), hash VARCHAR(64) NOT NULL,
 metadata_json TEXT NOT NULL, PRIMARY KEY(session_id,hash)
);
CREATE TABLE sync_attachment_objects (
 tenant_id VARCHAR(64) NOT NULL, principal_id VARCHAR(64) NOT NULL REFERENCES principals(id),
 hash VARCHAR(64) NOT NULL, size_bytes BIGINT NOT NULL, next_offset BIGINT NOT NULL DEFAULT 0,
 verified BOOLEAN NOT NULL DEFAULT FALSE, PRIMARY KEY(tenant_id,principal_id,hash)
);
CREATE TABLE sync_attachment_chunks (
 tenant_id VARCHAR(64) NOT NULL, principal_id VARCHAR(64) NOT NULL, hash VARCHAR(64) NOT NULL,
 byte_offset BIGINT NOT NULL, encoded_data TEXT NOT NULL,
 PRIMARY KEY(tenant_id,principal_id,hash,byte_offset),
 FOREIGN KEY(tenant_id,principal_id,hash) REFERENCES sync_attachment_objects(tenant_id,principal_id,hash)
);
