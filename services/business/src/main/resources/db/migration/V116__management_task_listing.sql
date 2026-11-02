CREATE INDEX tasks_management_listing_idx ON tasks(tenant_id,zone,created_at,id);
CREATE INDEX tasks_management_owner_idx ON tasks(tenant_id,owner_id,created_at,id);
