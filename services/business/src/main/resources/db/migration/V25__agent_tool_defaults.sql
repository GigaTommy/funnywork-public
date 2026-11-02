CREATE TABLE agent_tool_defaults (
 tenant_id VARCHAR(64) NOT NULL,
 agent_id VARCHAR(64) NOT NULL REFERENCES agent_versions(id) ON DELETE CASCADE,
 tool_version_id VARCHAR(64) NOT NULL REFERENCES tool_versions(id) ON DELETE CASCADE,
 PRIMARY KEY(agent_id,tool_version_id)
);
