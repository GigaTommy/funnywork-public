CREATE TABLE artifacts (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, owner_id VARCHAR(64) NOT NULL,
 zone VARCHAR(64) NOT NULL, name VARCHAR(120) NOT NULL, current_version INTEGER NOT NULL,
 created_at BIGINT NOT NULL,
 UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,owner_id) REFERENCES principals(tenant_id,id)
);
CREATE TABLE artifact_versions (
 artifact_id VARCHAR(64) NOT NULL REFERENCES artifacts(id), version INTEGER NOT NULL,
 content_base64 TEXT NOT NULL, sha256 VARCHAR(64) NOT NULL, byte_size INTEGER NOT NULL,
 created_at BIGINT NOT NULL, PRIMARY KEY(artifact_id,version)
);
CREATE TABLE artifact_grants (
 tenant_id VARCHAR(64) NOT NULL, artifact_id VARCHAR(64) NOT NULL, principal_id VARCHAR(64) NOT NULL,
 PRIMARY KEY(artifact_id,principal_id),
 FOREIGN KEY(tenant_id,artifact_id) REFERENCES artifacts(tenant_id,id),
 FOREIGN KEY(tenant_id,principal_id) REFERENCES principals(tenant_id,id)
);
