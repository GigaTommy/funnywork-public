create index model_call_owner_time on model_call_tickets(tenant_id,owner_id,created_at,id);
create index model_call_deployment_time on model_call_tickets(tenant_id,deployment_id,created_at,id);
create index model_call_queue_cursor on model_call_tickets(tenant_id,pool_id,created_at,id);
