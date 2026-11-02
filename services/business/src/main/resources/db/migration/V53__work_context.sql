CREATE TABLE employee_work_contexts(chat_id VARCHAR(64) PRIMARY KEY,version INTEGER NOT NULL,context_json TEXT,updated_at BIGINT NOT NULL);
CREATE TABLE employee_context_receipts(chat_id VARCHAR(64) NOT NULL,request_key VARCHAR(120) NOT NULL,request_hash VARCHAR(64) NOT NULL,version INTEGER NOT NULL,PRIMARY KEY(chat_id,request_key));
ALTER TABLE employee_chat_turns ADD COLUMN context_version INTEGER NOT NULL DEFAULT 0;
