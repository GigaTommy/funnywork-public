-- Only current enterprise personas are persisted; publishing replaces their contents.
create table enterprise_agent_presets (
 tenant_id varchar(64) not null, id varchar(64) not null, zone varchar(64) not null,
 version integer not null, enabled boolean not null, updated_at bigint not null,
 name varchar(120) not null, description varchar(1000) not null, persona text not null,
 created_by varchar(64) not null,
 primary key(tenant_id,id)
);
create table enterprise_agent_policies (
 tenant_id varchar(64) not null, zone varchar(64) not null, version integer not null,
 allow_personal boolean not null, default_preset varchar(64) not null, updated_at bigint not null,
 primary key(tenant_id,zone)
);
