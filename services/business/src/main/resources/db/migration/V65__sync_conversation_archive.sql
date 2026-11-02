alter table sync_sessions add column archived boolean not null default false;
alter table sync_sessions add column archive_revision bigint not null default 0;
