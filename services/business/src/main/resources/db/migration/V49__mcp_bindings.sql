create table mcp_bindings (
 tool_version_id varchar(64) primary key,
 tenant_id varchar(64) not null,
 zone varchar(64) not null,
 endpoint varchar(1000) not null,
 remote_tool varchar(120) not null,
 credential_ref varchar(120) not null,
 enabled boolean not null,
 version integer not null,
 updated_at bigint not null
);
