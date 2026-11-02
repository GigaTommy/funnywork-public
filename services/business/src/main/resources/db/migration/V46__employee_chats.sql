CREATE TABLE employee_chats(id VARCHAR(64) PRIMARY KEY,tenant_id VARCHAR(64) NOT NULL,owner_id VARCHAR(64) NOT NULL,zone VARCHAR(64) NOT NULL,agent_id VARCHAR(64) NOT NULL,title VARCHAR(120) NOT NULL,created_at BIGINT NOT NULL);
CREATE TABLE employee_chat_turns(chat_id VARCHAR(64) NOT NULL,ordinal INTEGER NOT NULL,task_id VARCHAR(64) NOT NULL,prompt TEXT NOT NULL,request_key VARCHAR(120) NOT NULL,request_hash VARCHAR(64) NOT NULL,PRIMARY KEY(chat_id,ordinal),UNIQUE(chat_id,request_key));
CREATE INDEX employee_chats_owner ON employee_chats(tenant_id,owner_id,id);
CREATE TABLE employee_client_policy(tenant_id VARCHAR(64) PRIMARY KEY,version INTEGER NOT NULL,policy_json TEXT NOT NULL);
