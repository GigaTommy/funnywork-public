alter table model_entry_credentials add column device_id varchar(128) not null default '';
create index model_entry_device_scope on model_entry_credentials(tenant_id,owner_id,device_id);
