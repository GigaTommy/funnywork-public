CREATE TABLE mobile_chat_result_links (
 chat_id VARCHAR(64) NOT NULL REFERENCES mobile_chats(id),
 session_id VARCHAR(64) NOT NULL REFERENCES sync_sessions(id),
 created_at BIGINT NOT NULL,
 PRIMARY KEY(chat_id,session_id)
);
