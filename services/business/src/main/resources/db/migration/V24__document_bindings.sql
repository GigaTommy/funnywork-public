CREATE TABLE task_document_bindings (
 tenant_id VARCHAR(64) NOT NULL,
 task_id VARCHAR(64) NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
 tool_version_id VARCHAR(64) NOT NULL REFERENCES tool_versions(id) ON DELETE CASCADE,
 source_id VARCHAR(120) NOT NULL,
 deployment_hash VARCHAR(64) NOT NULL,
 enabled BOOLEAN NOT NULL,
 PRIMARY KEY(task_id,tool_version_id)
);
