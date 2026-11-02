CREATE TABLE node_certificates (
 tenant_id VARCHAR(64) NOT NULL, node_id VARCHAR(64) PRIMARY KEY,
 fingerprint VARCHAR(64) NOT NULL UNIQUE, enabled BOOLEAN NOT NULL,
 FOREIGN KEY (tenant_id,node_id) REFERENCES nodes(tenant_id,id)
);
