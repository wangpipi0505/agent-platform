-- Output retries are independent paid model calls, not SDK retries of the same invocation.
ALTER TABLE engine.model_invocation
 ADD COLUMN output_attempt_no integer NOT NULL DEFAULT 0 CHECK(output_attempt_no >= 0),
 ADD COLUMN output_validation jsonb;
ALTER TABLE engine.model_invocation DROP CONSTRAINT model_invocation_node_instance_id_round_no_kind_key;
ALTER TABLE engine.model_invocation ADD UNIQUE(node_instance_id,round_no,kind,output_attempt_no);
