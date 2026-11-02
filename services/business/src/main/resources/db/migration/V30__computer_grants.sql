CREATE TABLE computer_grants (
 tenant_id VARCHAR(64) NOT NULL, unit_id VARCHAR(64) PRIMARY KEY,
 node_id VARCHAR(64) NOT NULL, issuer_id VARCHAR(64) NOT NULL,
 issuer_version BIGINT NOT NULL, policy_json TEXT NOT NULL,
 enabled BOOLEAN NOT NULL, created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL,
 FOREIGN KEY(tenant_id,unit_id) REFERENCES work_units(tenant_id,id),
 FOREIGN KEY(tenant_id,node_id) REFERENCES nodes(tenant_id,id),
 FOREIGN KEY(tenant_id,issuer_id) REFERENCES principals(tenant_id,id),
 CHECK(expires_at>created_at)
);
CREATE INDEX computer_grants_node ON computer_grants(tenant_id,node_id,enabled,expires_at);
