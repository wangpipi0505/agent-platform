CREATE TABLE engine.chat_session (
 public_id varchar(64) PRIMARY KEY,
 tenant_id varchar(64) NOT NULL,
 principal_id varchar(64) NOT NULL,
 app_code varchar(64) NOT NULL,
 snapshot jsonb NOT NULL,
 next_turn int NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE engine.chat_turn (
 session_id varchar(64) NOT NULL REFERENCES engine.chat_session(public_id),
 turn_no int NOT NULL,
 request_id varchar(128) NOT NULL,
 inputs jsonb NOT NULL,
 run_public_id varchar(64),
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(session_id,turn_no),
 UNIQUE(session_id,request_id)
);
