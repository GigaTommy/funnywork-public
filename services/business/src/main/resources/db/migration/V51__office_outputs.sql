create table office_outputs (
 id varchar(64) primary key,
 tenant_id varchar(64) not null,
 task_id varchar(64) not null,
 attempt_id varchar(64) not null,
 tool_version_id varchar(64) not null,
 request_key varchar(64) not null,
 request_hash varchar(64) not null,
 artifact_id varchar(64) not null,
 source_id varchar(64),
 source_version integer,
 source_hash varchar(64),
 created_at bigint not null,
 unique(tenant_id,attempt_id,tool_version_id,request_key)
);
