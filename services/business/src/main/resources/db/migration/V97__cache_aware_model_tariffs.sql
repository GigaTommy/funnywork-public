-- Preserve old fixed-price contracts; only verified direct Flash deployments adopt the supplier tariff.
alter table model_budget_prices add column cached_rate bigint;
alter table model_budget_prices add column tariff_profile varchar(64) not null default 'FIXED';
update model_budget_prices set cached_rate=input_rate;
alter table model_budget_prices alter column cached_rate set not null;
alter table model_budget_prices add constraint model_price_cache_rate check(cached_rate>=0 and cached_rate<=input_rate);
alter table model_budget_prices add constraint model_price_tariff check(tariff_profile in ('FIXED','DEEPSEEK_FLASH_CNY_20260910'));
alter table model_budget_charges add column cached_rate bigint;
alter table model_budget_charges add column tariff_profile varchar(64) not null default 'FIXED';
alter table model_budget_charges add column tariff_period varchar(16) not null default 'FIXED';
alter table model_budget_charges add column settlement_basis varchar(24) not null default 'LEGACY';
update model_budget_charges set cached_rate=input_rate;
alter table model_budget_charges alter column cached_rate set not null;
update model_budget_prices set input_rate=2000000,cached_rate=40000,output_rate=8000000,
 tariff_profile='DEEPSEEK_FLASH_CNY_20260910',version=version+1
where approved_by is not null and deployment_id in (
 select id from model_deployments where model='deepseek-flash'
 and base_url in ('https://api.deepseek.com','https://api.deepseek.com/','https://api.deepseek.com/v1','https://api.deepseek.com/v1/','https://api.deepseek.com/anthropic','https://api.deepseek.com/anthropic/')
);
-- Historical charges are immutable. Correct past errors with an audited reconciliation, not this migration.
create table model_budget_adjustments (
 ticket_id varchar(64) primary key references model_budget_charges(ticket_id),
 previous_charged bigint not null check(previous_charged>=0),
 corrected_charged bigint not null check(corrected_charged>=0),
 tariff_profile varchar(64) not null, actor_id varchar(64) not null,
 reason varchar(200) not null, created_at bigint not null
);
