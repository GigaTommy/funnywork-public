ALTER TABLE tenant_guards ADD COLUMN max_outstanding_units INTEGER NOT NULL DEFAULT 10000 CHECK (max_outstanding_units BETWEEN 1 AND 1000000);
ALTER TABLE principals ADD CONSTRAINT principals_tenant_identity UNIQUE (tenant_id,id);
ALTER TABLE agent_versions ADD CONSTRAINT agents_tenant_identity UNIQUE (tenant_id,id);
ALTER TABLE nodes ADD CONSTRAINT nodes_principal_tenant FOREIGN KEY (tenant_id,id) REFERENCES principals(tenant_id,id);
ALTER TABLE agent_versions ADD CONSTRAINT agents_creator_tenant FOREIGN KEY (tenant_id,created_by) REFERENCES principals(tenant_id,id);
ALTER TABLE tasks ADD CONSTRAINT tasks_owner_tenant FOREIGN KEY (tenant_id,owner_id) REFERENCES principals(tenant_id,id);
ALTER TABLE tasks ADD CONSTRAINT tasks_agent_tenant FOREIGN KEY (tenant_id,agent_id) REFERENCES agent_versions(tenant_id,id);
