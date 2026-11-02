-- Model admission is independent of business task leases. No prompt bodies are stored.
create table model_capacity_pools (
 id varchar(64) primary key, tenant_id varchar(64) not null, zone varchar(64) not null,
 name varchar(120) not null, concurrency_limit integer not null check(concurrency_limit between 1 and 1024),
 queue_limit integer not null check(queue_limit between 1 and 10000),
 user_concurrency integer not null check(user_concurrency between 1 and 1024),
 user_queue integer not null check(user_queue between 1 and 1000),
 wait_ms bigint not null, paused boolean not null, version integer not null,
 turn_counter bigint not null default 0,
 unique(tenant_id,id)
);
create table model_deployments (
 id varchar(64) primary key, tenant_id varchar(64) not null, pool_id varchar(64) not null,
 name varchar(120) not null, model varchar(120) not null, protocol varchar(32) not null,
 base_url varchar(1000) not null, credential_ref varchar(100) not null,
 context_tokens integer not null, max_output_tokens integer not null,
 capabilities_json text not null, enabled boolean not null, created_at bigint not null,
 foreign key(tenant_id,pool_id) references model_capacity_pools(tenant_id,id),
 unique(tenant_id,id)
);
create table model_call_tickets (
 id varchar(64) primary key, tenant_id varchar(64) not null, pool_id varchar(64) not null,
 deployment_id varchar(64) not null, owner_id varchar(64) not null, auth_version bigint not null,
 zone varchar(64) not null, call_id varchar(128) not null, logical_call_id varchar(128) not null, device_id varchar(128) not null,
 session_id varchar(128) not null, task_id varchar(128) not null, parent_task_id varchar(128) not null,
 purpose varchar(40) not null, request_digest varchar(64) not null,
 max_output_tokens integer not null, state varchar(24) not null,
 created_at bigint not null, expires_at bigint not null, granted_at bigint, grant_expires_at bigint,
 started_at bigint, first_byte_at bigint, ended_at bigint, execution_deadline bigint,
 fair_turn bigint not null default 0, execution_owner varchar(64),
 input_tokens bigint, output_tokens bigint, cached_tokens bigint,
 usage_json text, usage_quality varchar(32) not null default 'UNKNOWN', outcome varchar(64),
 unique(tenant_id,owner_id,call_id),
 foreign key(tenant_id,pool_id) references model_capacity_pools(tenant_id,id),
 foreign key(tenant_id,deployment_id) references model_deployments(tenant_id,id)
);
create index model_queue_pool on model_call_tickets(tenant_id,pool_id,state,created_at);
create index model_usage_time on model_call_tickets(tenant_id,zone,created_at);
create table model_pool_user_turns (
 tenant_id varchar(64) not null, pool_id varchar(64) not null, owner_id varchar(64) not null,
 last_turn bigint not null, primary key(tenant_id,pool_id,owner_id),
 foreign key(tenant_id,pool_id) references model_capacity_pools(tenant_id,id)
);
