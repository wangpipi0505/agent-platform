-- V200..V217 validated in a fresh owned PG database before assigning this next version.
CREATE TABLE engine.model_input_snapshot (
 material_id varchar(80) PRIMARY KEY CHECK(material_id ~ '^m_[a-f0-9]{32}$'),
 run_id bigint NOT NULL UNIQUE REFERENCES engine.run_authority(run_id),
 tenant_id varchar(64) NOT NULL CHECK(length(tenant_id)>0), principal_id varchar(64) NOT NULL CHECK(length(principal_id)>0),
 accepted_snapshot_sha256 char(64) NOT NULL CHECK(accepted_snapshot_sha256 ~ '^[a-f0-9]{64}$'),
 model_entry_id bigint NOT NULL CHECK(model_entry_id>0), binding_id bigint NOT NULL CHECK(binding_id>0),
 binding_sha256 char(64) NOT NULL CHECK(binding_sha256 ~ '^[a-f0-9]{64}$'),
 request jsonb NOT NULL CHECK(jsonb_typeof(request)='object' AND octet_length(request::text)<=2097152),
 request_sha256 char(64) NOT NULL CHECK(request_sha256 ~ '^[a-f0-9]{64}$'),
 scope_sha256 char(64) NOT NULL CHECK(scope_sha256 ~ '^[a-f0-9]{64}$'),
 manifest jsonb NOT NULL CHECK(jsonb_typeof(manifest)='object' AND octet_length(manifest::text)<=262144),
 manifest_sha256 char(64) NOT NULL CHECK(manifest_sha256 ~ '^[a-f0-9]{64}$'),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION engine.protect_model_input_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'MODEL_INPUT_SNAPSHOT_IMMUTABLE'; END $$;
CREATE TRIGGER model_input_snapshot_immutable BEFORE UPDATE OR DELETE ON engine.model_input_snapshot FOR EACH ROW EXECUTE FUNCTION engine.protect_model_input_snapshot();
ALTER TABLE engine.material_scope DROP CONSTRAINT material_scope_kind_check;
ALTER TABLE engine.material_scope DROP CONSTRAINT material_scope_material_version_check;
ALTER TABLE engine.material_scope ADD CONSTRAINT material_scope_kind_check CHECK(kind IN (
 'run-snapshot','run-inputs','run-version-refs',
 'run-state','run-budget','run-output','run-error',
 'node-definition','node-state','node-output','node-error',
 'attempt-state','attempt-request','attempt-checkpoint','attempt-error','run-event','model-input'));
ALTER TABLE engine.material_scope ADD CONSTRAINT material_scope_material_version_check CHECK(
 CASE WHEN kind IN ('run-snapshot','run-inputs','run-version-refs','model-input') THEN material_version ~ '^[a-f0-9]{64}$'
 ELSE material_version ~ '^[1-9][0-9]*$' AND material_version=material_revision::text END);
CREATE UNIQUE INDEX material_scope_model_input_once ON engine.material_scope(run_id) WHERE kind='model-input';
