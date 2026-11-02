CREATE INDEX agent_versions_tenant_status_id ON agent_versions(tenant_id,status,id);
CREATE INDEX agent_versions_tenant_status_capability_id ON agent_versions(tenant_id,status,capability,id);
