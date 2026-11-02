CREATE TABLE employee_passwords (
 username VARCHAR(80) PRIMARY KEY,
 principal_id VARCHAR(64) NOT NULL UNIQUE REFERENCES principals(id),
 salt VARCHAR(64) NOT NULL, password_hash VARCHAR(128) NOT NULL,
 failures INTEGER NOT NULL DEFAULT 0, retry_after BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE employee_sessions (
 token_hash VARCHAR(64) PRIMARY KEY REFERENCES api_tokens(token_hash),
 device VARCHAR(120) NOT NULL
);
