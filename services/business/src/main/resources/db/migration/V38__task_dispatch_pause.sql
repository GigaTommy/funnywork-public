ALTER TABLE tasks ADD COLUMN dispatch_paused BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX tasks_dispatch_paused_idx ON tasks(tenant_id,state,dispatch_paused,id);
