ALTER TABLE skill.execution_fact ADD COLUMN delivery_owner varchar(64);
ALTER TABLE skill.execution_fact ADD COLUMN delivery_lease_until timestamptz;
ALTER TABLE skill.execution_fact ADD COLUMN next_delivery_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE skill.execution_fact ADD COLUMN delivery_attempts integer NOT NULL DEFAULT 0;
CREATE INDEX execution_fact_due_outbox ON skill.execution_fact(callback_state,next_delivery_at,tenant_id) WHERE callback_state='PENDING';
