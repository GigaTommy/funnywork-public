CREATE TABLE tool_versions (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, name VARCHAR(120) NOT NULL,
 version VARCHAR(40) NOT NULL, zone VARCHAR(64) NOT NULL, contract_json TEXT NOT NULL,
 contract_hash VARCHAR(64) NOT NULL, enabled BOOLEAN NOT NULL, created_by VARCHAR(64) NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,name,version),
 FOREIGN KEY(tenant_id,created_by) REFERENCES principals(tenant_id,id)
);
CREATE TABLE task_tool_grants (
 tenant_id VARCHAR(64) NOT NULL, task_id VARCHAR(64) NOT NULL, tool_version_id VARCHAR(64) NOT NULL,
 PRIMARY KEY(task_id,tool_version_id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES tasks(tenant_id,id),
 FOREIGN KEY(tenant_id,tool_version_id) REFERENCES tool_versions(tenant_id,id)
);
ALTER TABLE external_operations ADD COLUMN tool_version_id VARCHAR(64);
ALTER TABLE external_operations ADD CONSTRAINT operation_tool_tenant FOREIGN KEY(tenant_id,tool_version_id) REFERENCES tool_versions(tenant_id,id);
