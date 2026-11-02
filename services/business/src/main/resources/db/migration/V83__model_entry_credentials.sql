create table model_entry_credentials (
 token_hash varchar(64) primary key, tenant_id varchar(64) not null, owner_id varchar(64) not null,
 auth_version bigint not null, expires_at bigint not null
);
create index model_entry_credential_expiry on model_entry_credentials(expires_at);
