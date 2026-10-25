-- Evidence is bound to its original generation; it never grants a retry by itself.
CREATE TABLE skill.reconciliation_proof (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 tenant_id varchar(128) NOT NULL, principal_id varchar(128) NOT NULL,
 call_id bigint NOT NULL, attempt_id bigint NOT NULL,
 outcome varchar(16) NOT NULL CHECK(outcome IN ('NOT_APPLIED','NOT_SENT')),
 evidence jsonb NOT NULL, evidence_sha256 char(64) NOT NULL,
 created_at timestamptz NOT NULL,
 UNIQUE(tenant_id,call_id,attempt_id),
 FOREIGN KEY(tenant_id,call_id) REFERENCES skill.execution_fact(tenant_id,call_id)
);
CREATE FUNCTION skill.protect_reconciliation_proof() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 RAISE EXCEPTION 'reconciliation proof is immutable';
END $$;
CREATE TRIGGER immutable_reconciliation_proof BEFORE UPDATE OR DELETE ON skill.reconciliation_proof
 FOR EACH ROW EXECUTE FUNCTION skill.protect_reconciliation_proof();
