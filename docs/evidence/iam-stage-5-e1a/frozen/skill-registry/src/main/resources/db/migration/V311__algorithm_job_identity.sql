-- Nullable for legacy algorithm and HTTP bindings; never infer identity from names or current actors.
ALTER TABLE skill.external_job_binding ADD COLUMN external_job_uid text;
ALTER TABLE skill.external_job_binding ADD COLUMN external_namespace varchar(63);
ALTER TABLE skill.external_job_binding ADD COLUMN external_api_sha256 char(64);
ALTER TABLE skill.external_job_binding ADD COLUMN external_identity_sha256 char(64);
CREATE UNIQUE INDEX algorithm_external_name ON skill.external_job_binding(external_api_sha256,external_namespace,external_job_id)
 WHERE metadata->>'protocol'='ALGORITHM';
CREATE UNIQUE INDEX algorithm_external_uid ON skill.external_job_binding(external_api_sha256,external_namespace,external_job_uid)
 WHERE metadata->>'protocol'='ALGORITHM' AND external_job_uid IS NOT NULL;
-- Only proven NOT_SENT readonly failures may enter the existing engine-authorized retry path.
CREATE TABLE skill.external_job_binding_history (
 tenant_id varchar(128) NOT NULL,call_id bigint NOT NULL,attempt_id bigint NOT NULL,
 binding jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,call_id,attempt_id)
);
CREATE FUNCTION skill.protect_algorithm_binding_history() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 RAISE EXCEPTION 'algorithm binding history is immutable';
END $$;
CREATE TRIGGER immutable_algorithm_binding_history BEFORE UPDATE OR DELETE ON skill.external_job_binding_history
 FOR EACH ROW EXECUTE FUNCTION skill.protect_algorithm_binding_history();
