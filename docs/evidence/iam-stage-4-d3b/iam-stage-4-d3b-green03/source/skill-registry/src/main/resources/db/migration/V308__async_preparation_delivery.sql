-- Registration is durable recovery evidence but must not race submitted as a deliverable fact.
ALTER TABLE skill.execution_fact DROP CONSTRAINT execution_fact_callback_state_check;
ALTER TABLE skill.execution_fact ADD CONSTRAINT execution_fact_callback_state_check
 CHECK (callback_state IN ('PREPARING','PENDING','DELIVERED','LATE'));

-- Quarantine undelivered placeholders produced before this gate existed. Never rewrite a sent
-- UNKNOWN or a completed fact; the engine remains the authority for recovery/lease decisions.
UPDATE skill.execution_fact f SET callback_state='PREPARING'
 FROM skill.external_job_binding b
 WHERE f.tenant_id=b.tenant_id AND f.call_id=b.call_id
 AND f.status='UNKNOWN' AND f.callback_state='PENDING'
 AND b.status='REGISTERED' AND b.send_started=false;
