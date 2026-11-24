CREATE TABLE engine.evaluation_grant_generation (
    run_id bigint NOT NULL REFERENCES engine.run_authority(run_id),
    generation bigint NOT NULL CHECK (generation > 0),
    grant_ref text NOT NULL,
    grant_expires_at timestamptz NOT NULL,
    origin_authentication_id varchar(128) NOT NULL,
    decision_id text NOT NULL,
    authorization_version text NOT NULL,
    operation_id text NOT NULL,
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (run_id, generation),
    UNIQUE (run_id, grant_ref),
    CHECK (grant_expires_at > recorded_at)
);

CREATE TRIGGER evaluation_grant_generation_immutable
    BEFORE UPDATE OR DELETE ON engine.evaluation_grant_generation
    FOR EACH ROW EXECUTE FUNCTION engine.protect_material_scope();

ALTER TABLE engine.run_authority ADD COLUMN active_generation bigint,
    ADD CONSTRAINT run_authority_evaluation_generation
        CHECK (active_generation IS NULL OR (root_kind = 'evaluation-candidate' AND active_generation > 0)),
    ADD CONSTRAINT run_authority_active_generation_fk
        FOREIGN KEY (run_id, active_generation) REFERENCES engine.evaluation_grant_generation(run_id, generation);

CREATE OR REPLACE FUNCTION engine.protect_run_authority() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'RUN_AUTHORITY_IMMUTABLE'; END IF;
    IF (to_jsonb(NEW)-ARRAY['last_decision_id','last_checked_at','blocked_reason','retry_not_before','check_version','active_generation'])
        IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['last_decision_id','last_checked_at','blocked_reason','retry_not_before','check_version','active_generation'])
    THEN RAISE EXCEPTION 'RUN_AUTHORITY_IMMUTABLE'; END IF;
    IF NEW.active_generation IS DISTINCT FROM OLD.active_generation AND
        (NEW.active_generation IS NULL OR NEW.active_generation <> coalesce(OLD.active_generation,0)+1
         OR NEW.check_version <> OLD.check_version+1)
    THEN RAISE EXCEPTION 'RUN_AUTHORITY_GENERATION_INVALID'; END IF;
    RETURN NEW;
END $$;
