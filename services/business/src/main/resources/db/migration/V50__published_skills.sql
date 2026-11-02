create table published_skills (
 id varchar(64) primary key,
 tenant_id varchar(64) not null,
 zone varchar(64) not null,
 name varchar(120) not null,
 version varchar(40) not null,
 instructions varchar(12000) not null,
 content_hash varchar(64) not null,
 enabled boolean not null,
 created_at bigint not null,
 unique(tenant_id,zone,name,version)
);
create table agent_skill_defaults (
 tenant_id varchar(64) not null,
 agent_id varchar(64) not null,
 skill_id varchar(64) not null,
 primary key(agent_id,skill_id)
);
create table task_skills (
 task_id varchar(64) not null,
 tenant_id varchar(64) not null,
 skill_id varchar(64) not null,
 primary key(task_id,skill_id)
);
