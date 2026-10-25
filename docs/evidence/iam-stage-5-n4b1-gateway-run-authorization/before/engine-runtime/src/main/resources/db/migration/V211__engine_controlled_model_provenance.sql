ALTER TABLE engine.model_invocation ADD COLUMN is_mock boolean NOT NULL DEFAULT false;
ALTER TABLE engine.model_invocation ADD COLUMN source_invocation_id bigint REFERENCES engine.model_invocation(id);
