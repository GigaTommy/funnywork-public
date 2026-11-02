ALTER TABLE employee_chats ADD COLUMN parent_chat_id VARCHAR(64);
CREATE TABLE employee_chat_continuations(parent_chat_id VARCHAR(64) NOT NULL,request_key VARCHAR(120) NOT NULL,request_hash VARCHAR(64) NOT NULL,child_chat_id VARCHAR(64) NOT NULL,PRIMARY KEY(parent_chat_id,request_key));
CREATE TABLE employee_chat_context_origins(chat_id VARCHAR(64) NOT NULL,source_task_id VARCHAR(64) NOT NULL,PRIMARY KEY(chat_id,source_task_id));
CREATE INDEX employee_chat_turns_task ON employee_chat_turns(task_id);
