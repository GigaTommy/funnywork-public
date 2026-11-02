CREATE TABLE agent_scheduling_limits (
 tenant_id VARCHAR(64) NOT NULL,
 agent_id VARCHAR(64) NOT NULL,
 zone VARCHAR(64) NOT NULL,
 max_concurrency INTEGER NOT NULL CHECK (max_concurrency BETWEEN 1 AND 10000),
 PRIMARY KEY(tenant_id,agent_id,zone),
 FOREIGN KEY(tenant_id,agent_id) REFERENCES agent_versions(tenant_id,id) ON DELETE CASCADE
);
CREATE INDEX tasks_agent_zone ON tasks(tenant_id,agent_id,zone,id);
