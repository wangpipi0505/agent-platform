ALTER TABLE engine.tool_call ADD COLUMN recovery_attempts integer NOT NULL DEFAULT 0;
ALTER TABLE engine.tool_call ADD COLUMN recovery_next_at timestamptz;
ALTER TABLE engine.tool_call ADD COLUMN recovery_error varchar(512);
