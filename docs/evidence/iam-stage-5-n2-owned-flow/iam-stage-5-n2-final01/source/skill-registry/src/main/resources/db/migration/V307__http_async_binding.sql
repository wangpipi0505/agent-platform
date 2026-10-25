-- A logical HTTP invocation has one binding before any send; an uncertain send is never replayed.
ALTER TABLE skill.external_job_binding ADD COLUMN idempotency_key text;
ALTER TABLE skill.external_job_binding ADD COLUMN principal_id varchar(128);
ALTER TABLE skill.external_job_binding ADD COLUMN run_id varchar(128);
ALTER TABLE skill.external_job_binding ADD COLUMN attempt_id bigint;
ALTER TABLE skill.external_job_binding ADD COLUMN skill_version_id bigint;
ALTER TABLE skill.external_job_binding ADD COLUMN send_started boolean NOT NULL DEFAULT false;
ALTER TABLE skill.external_job_binding ADD COLUMN request_snapshot jsonb;
ALTER TABLE skill.external_job_binding ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE skill.external_job_binding ALTER COLUMN external_job_id DROP NOT NULL;
CREATE UNIQUE INDEX http_async_idempotency ON skill.external_job_binding(tenant_id,idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX http_async_external_job ON skill.external_job_binding(tenant_id,skill_version_id,external_job_id) WHERE skill_version_id IS NOT NULL AND external_job_id IS NOT NULL;
