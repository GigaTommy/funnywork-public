alter table events add column audit_zones text;
create index idx_events_audit_window on events(tenant_id,created_at,id);
