CREATE TABLE artifact_requests (
 tenant_id VARCHAR(64) NOT NULL, owner_id VARCHAR(64) NOT NULL, request_key VARCHAR(120) NOT NULL,
 request_hash VARCHAR(64) NOT NULL, artifact_id VARCHAR(64) NOT NULL,
 PRIMARY KEY(tenant_id,owner_id,request_key),
 FOREIGN KEY(tenant_id,owner_id) REFERENCES principals(tenant_id,id)
);
