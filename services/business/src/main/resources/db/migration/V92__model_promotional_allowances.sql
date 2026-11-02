-- Issuance budgets are independent of provider spending budgets. Disabled until configured.
create table model_promotion_policy (
 id integer primary key check(id=1), tenant_id varchar(64) not null,
 mode varchar(16) not null check(mode in ('DISABLED','WELCOME','DAILY')),
 grant_micros bigint not null check(grant_micros>0), balance_cap bigint not null check(balance_cap>0),
 total_limit bigint not null check(total_limit>=0), daily_limit bigint not null check(daily_limit>=0),
 issued bigint not null default 0 check(issued>=0), user_daily_limit bigint not null check(user_daily_limit>0),
 validity_days integer not null check(validity_days between 1 and 3650),
 concurrency_limit integer not null check(concurrency_limit between 1 and 32),
 calls_per_minute integer not null check(calls_per_minute between 1 and 600),
 max_output_tokens integer not null check(max_output_tokens between 1 and 262144),
 version integer not null default 0 check(version>=0)
);
insert into model_promotion_policy values(1,'','DISABLED',1000000,5000000,0,0,0,5000000,365,1,5,2048,0);
create table model_allowance_credits (
 tenant_id varchar(64) not null, owner_id varchar(64) not null, credit_key varchar(100) not null,
 kind varchar(16) not null check(kind in ('WELCOME','DAILY','TOPUP')), amount bigint not null check(amount>=0),
 actor_id varchar(64) not null, created_at bigint not null, primary key(tenant_id,owner_id,credit_key)
);
-- Existing accounts are not new users. Changing campaigns must never grant welcome credit twice.
insert into model_allowance_credits(tenant_id,owner_id,credit_key,kind,amount,actor_id,created_at)
 select tenant_id,id,'welcome','WELCOME',0,'migration',0 from principals where kind='USER';
create index model_allowance_issued_day on model_allowance_credits(kind,created_at);
