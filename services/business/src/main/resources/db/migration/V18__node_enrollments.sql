CREATE TABLE node_enrollments (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL,
 issuer_id VARCHAR(64) NOT NULL, issuer_version BIGINT NOT NULL,
 token_hash VARCHAR(64) NOT NULL UNIQUE, name VARCHAR(120) NOT NULL,
 zone VARCHAR(64) NOT NULL, capacity INTEGER NOT NULL, capability VARCHAR(64) NOT NULL,
 expires_at BIGINT NOT NULL, state VARCHAR(16) NOT NULL, node_id VARCHAR(64),
 FOREIGN KEY (tenant_id,issuer_id) REFERENCES principals(tenant_id,id),
 FOREIGN KEY (tenant_id,node_id) REFERENCES nodes(tenant_id,id)
);
CREATE INDEX enrollments_tenant_state ON node_enrollments(tenant_id,state,expires_at);
