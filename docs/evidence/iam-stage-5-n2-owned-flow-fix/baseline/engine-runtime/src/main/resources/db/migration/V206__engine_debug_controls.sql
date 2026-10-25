ALTER TABLE engine.run ADD COLUMN debug_controls_revision int NOT NULL DEFAULT 0;
ALTER TABLE engine.tool_call ADD COLUMN mock_source_call_id bigint REFERENCES engine.tool_call(id);
ALTER TABLE engine.tool_call ADD COLUMN mock_source_digest char(64);
ALTER TABLE engine.tool_call ADD COLUMN debug_controls_revision int;
ALTER TABLE engine.tool_call ADD COLUMN mock_snapshot jsonb;
