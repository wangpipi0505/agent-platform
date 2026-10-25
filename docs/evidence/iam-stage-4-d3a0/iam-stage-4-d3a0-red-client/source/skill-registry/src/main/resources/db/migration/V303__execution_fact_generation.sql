ALTER TABLE skill.execution_fact ADD COLUMN attempt_duration_ms bigint NOT NULL DEFAULT 0;
UPDATE skill.execution_fact SET attempt_duration_ms=duration_ms;
CREATE TABLE skill.execution_fact_history (
 tenant_id varchar(128) NOT NULL,call_id bigint NOT NULL,attempt_id bigint NOT NULL,
 fact jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(tenant_id,call_id,attempt_id),
 FOREIGN KEY(tenant_id,call_id) REFERENCES skill.execution_fact(tenant_id,call_id)
);
