CREATE TABLE engine.run_authority (
 run_id bigint PRIMARY KEY REFERENCES engine.run(id), tenant_id varchar(64) NOT NULL, principal_id varchar(64) NOT NULL,
 grant_ref text NOT NULL, grant_expires_at timestamptz NOT NULL, initial_ceiling jsonb NOT NULL CHECK(jsonb_typeof(initial_ceiling)='object'),
 resource_digest char(64) NOT NULL CHECK(resource_digest ~ '^[a-f0-9]{64}$'), source_kind text NOT NULL CHECK(source_kind='PORTAL'),
 origin_authentication_id varchar(128) NOT NULL, app_version_id bigint NOT NULL CHECK(app_version_id>0), app_version_no bigint NOT NULL CHECK(app_version_no>0),
 root_kind text NOT NULL CHECK(root_kind='app-version'), root_id text NOT NULL,
 initial_ceiling_sha256 char(64) NOT NULL CHECK(initial_ceiling_sha256 ~ '^[a-f0-9]{64}$'),
 accepted_snapshot_sha256 char(64) NOT NULL CHECK(accepted_snapshot_sha256 ~ '^[a-f0-9]{64}$'),
 admission_decision_id text NOT NULL, authorization_version text NOT NULL, operation_id text NOT NULL,
 last_decision_id text, last_checked_at timestamptz, blocked_reason text CHECK(blocked_reason IN ('AUTHORITY_DENIED','AUTHORITY_EXPIRED','AUTHORITY_UNAVAILABLE')),
 retry_not_before timestamptz, check_version bigint NOT NULL DEFAULT 0 CHECK(check_version>=0),
 CHECK((blocked_reason='AUTHORITY_UNAVAILABLE')=(retry_not_before IS NOT NULL) OR (blocked_reason IS NULL AND retry_not_before IS NULL))
);
CREATE TABLE engine.material_scope (
 run_id bigint NOT NULL REFERENCES engine.run_authority(run_id), tenant_id varchar(64) NOT NULL,
 producer text NOT NULL CHECK(producer='engine'), kind text NOT NULL CHECK(kind IN ('run-snapshot','run-inputs','run-version-refs')),
 material_id varchar(80) NOT NULL, material_version char(64) NOT NULL CHECK(material_version ~ '^[a-f0-9]{64}$'),
 payload_sha256 char(64) NOT NULL CHECK(payload_sha256 ~ '^[a-f0-9]{64}$'), scope_sha256 char(64) NOT NULL CHECK(scope_sha256 ~ '^[a-f0-9]{64}$'),
 scope jsonb NOT NULL CHECK(jsonb_typeof(scope)='object'),
 PRIMARY KEY(tenant_id,producer,kind,material_id,material_version), UNIQUE(run_id,kind), UNIQUE(run_id,scope_sha256)
);
-- Admission bindings have no ordinary reauthorization update path. A future explicit generation
-- switch must retain historical initial/scope and audit; current checks only mutate check fields.
CREATE FUNCTION engine.protect_run_authority() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'RUN_AUTHORITY_IMMUTABLE'; END IF;
 IF (to_jsonb(NEW)-ARRAY['last_decision_id','last_checked_at','blocked_reason','retry_not_before','check_version'])
 IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['last_decision_id','last_checked_at','blocked_reason','retry_not_before','check_version'])
 THEN RAISE EXCEPTION 'RUN_AUTHORITY_IMMUTABLE'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER run_authority_immutable BEFORE UPDATE OR DELETE ON engine.run_authority FOR EACH ROW EXECUTE FUNCTION engine.protect_run_authority();
CREATE FUNCTION engine.protect_material_scope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'MATERIAL_SCOPE_IMMUTABLE'; END $$;
CREATE TRIGGER material_scope_immutable BEFORE UPDATE OR DELETE ON engine.material_scope FOR EACH ROW EXECUTE FUNCTION engine.protect_material_scope();
CREATE FUNCTION engine.protect_authorized_run() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM engine.run_authority WHERE run_id=OLD.id) AND
 (NEW.id,NEW.public_id,NEW.tenant_id,NEW.principal_id,NEW.app_code,NEW.app_version_id,NEW.snapshot,NEW.inputs,NEW.version_refs,NEW.trigger_type,NEW.debug_snapshot,NEW.eval_candidate)
 IS DISTINCT FROM
 (OLD.id,OLD.public_id,OLD.tenant_id,OLD.principal_id,OLD.app_code,OLD.app_version_id,OLD.snapshot,OLD.inputs,OLD.version_refs,OLD.trigger_type,OLD.debug_snapshot,OLD.eval_candidate)
 THEN RAISE EXCEPTION 'AUTHORIZED_RUN_IMMUTABLE'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER authorized_run_immutable BEFORE UPDATE ON engine.run FOR EACH ROW EXECUTE FUNCTION engine.protect_authorized_run();
