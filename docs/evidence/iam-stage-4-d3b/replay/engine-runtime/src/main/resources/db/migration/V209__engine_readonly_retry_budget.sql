ALTER TABLE engine.tool_call ADD COLUMN readonly_retry_count int NOT NULL DEFAULT 0 CHECK(readonly_retry_count>=0);
