create table model_connections (
 profile_id varchar(64) primary key,
 tenant_id varchar(64) not null,
 credential_ref varchar(120) not null,
 version integer not null,
 updated_at bigint not null
);
