CREATE TABLE node_source_deployments (
 tenant_id VARCHAR(64) NOT NULL,
 node_id VARCHAR(64) NOT NULL,
 principal_id VARCHAR(64) NOT NULL,
 source_id VARCHAR(120) NOT NULL,
 deployment_hash VARCHAR(64) NOT NULL,
 PRIMARY KEY(node_id,principal_id,source_id),
 FOREIGN KEY(tenant_id,node_id) REFERENCES nodes(tenant_id,id) ON DELETE CASCADE,
 FOREIGN KEY(tenant_id,principal_id) REFERENCES principals(tenant_id,id) ON DELETE CASCADE
);
CREATE INDEX node_sources_matching ON node_source_deployments(tenant_id,principal_id,source_id,deployment_hash,node_id);
