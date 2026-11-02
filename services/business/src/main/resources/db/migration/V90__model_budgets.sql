-- Money is integer micro-CNY (1 CNY = 1000000). New installations deny paid calls.
create table model_budget_guard (
 id integer primary key check(id=1), total_limit bigint not null check(total_limit>=0),
 daily_limit bigint not null check(daily_limit>=0), spent bigint not null default 0 check(spent>=0),
 held bigint not null default 0 check(held>=0), paused boolean not null, version integer not null check(version>=0)
);
insert into model_budget_guard(id,total_limit,daily_limit,paused,version) values(1,0,0,true,0);
create table model_user_budgets (
 tenant_id varchar(64) not null, owner_id varchar(64) not null,
 total_limit bigint not null check(total_limit>=0), daily_limit bigint not null check(daily_limit>=0),
 spent bigint not null default 0 check(spent>=0), held bigint not null default 0 check(held>=0),
 concurrency_limit integer not null check(concurrency_limit between 1 and 32), calls_per_minute integer not null check(calls_per_minute between 1 and 600), max_output_tokens integer not null check(max_output_tokens between 1 and 262144),
 expires_at bigint not null check(expires_at>0), enabled boolean not null, version integer not null check(version>=1),
 primary key(tenant_id,owner_id)
);
create table model_budget_prices (
 tenant_id varchar(64) not null, deployment_id varchar(64) not null,
 input_rate bigint not null check(input_rate>0), output_rate bigint not null check(output_rate>0),
 input_ceiling integer not null check(input_ceiling between 128 and 2000000), version integer not null check(version>=1),
 primary key(tenant_id,deployment_id),
 foreign key(tenant_id,deployment_id) references model_deployments(tenant_id,id)
);
create table model_budget_days (
 scope_id varchar(150) not null, day_key varchar(10) not null, spent bigint not null default 0 check(spent>=0),
 primary key(scope_id,day_key)
);
create table model_budget_charges (
 ticket_id varchar(64) primary key, tenant_id varchar(64) not null, owner_id varchar(64) not null,
 day_key varchar(10) not null, reserved bigint not null check(reserved>0), charged bigint check(charged>=0),
 input_rate bigint not null, output_rate bigint not null, input_ceiling integer not null,
 output_ceiling integer not null, created_at bigint not null, settled_at bigint,
 state varchar(16) not null check(state in ('HELD','SETTLED','RELEASED')),
 foreign key(ticket_id) references model_call_tickets(id)
);
create index model_budget_user_rate on model_budget_charges(tenant_id,owner_id,created_at);
create index model_budget_user_held on model_budget_charges(tenant_id,owner_id,state);
