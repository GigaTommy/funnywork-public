create table mobile_chats (
 id varchar(64) primary key, tenant_id varchar(64) not null, owner_id varchar(64) not null,
 title varchar(160) not null, model_id varchar(64) not null, revision bigint not null default 0,
 created_at bigint not null, updated_at bigint not null
);
create index mobile_chats_owner on mobile_chats(tenant_id,owner_id,updated_at,id);
create table mobile_chat_turns (
 id varchar(64) primary key, chat_id varchar(64) not null references mobile_chats(id),
 ordinal integer not null, request_hash varchar(64) not null, user_text text not null,
 reply_text text not null, request_json text not null, ticket_id varchar(64) not null,
 state varchar(24) not null, created_at bigint not null,
 unique(chat_id,ordinal), unique(ticket_id)
);
