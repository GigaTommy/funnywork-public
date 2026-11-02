-- Previously editable tenant prices require explicit platform-operator approval.
-- Existing in-flight charges retain their frozen rates and reservations.
alter table model_budget_prices add column approved_by varchar(64);
create index model_ticket_user_admissions on model_call_tickets(tenant_id,owner_id,created_at);
create index model_ticket_user_permits on model_call_tickets(tenant_id,owner_id,state);
