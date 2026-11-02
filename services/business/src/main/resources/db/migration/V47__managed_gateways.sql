create table managed_gateways (
 id varchar(120) not null,
 tenant_id varchar(64) not null,
 zone varchar(64) not null,
 name varchar(120) not null,
 base_url varchar(1000) not null,
 credential_ref varchar(120) not null,
 contract_hash varchar(64) not null,
 enabled boolean not null,
 version integer not null,
 updated_at bigint not null,
 primary key (tenant_id,id)
);
