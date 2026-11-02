alter table mobile_chats add column archived boolean not null default false;
alter table mobile_chats add column history_revision bigint not null default 0;
create index mobile_chats_history on mobile_chats(tenant_id,owner_id,archived,updated_at,id);
create table mobile_chat_history_updates (
 tenant_id varchar(64) not null, owner_id varchar(64) not null, request_id varchar(64) not null,
 chat_id varchar(64) not null references mobile_chats(id), request_hash varchar(64) not null,
 result_json text not null, created_at bigint not null,
 primary key(tenant_id,owner_id,request_id)
);
