-- EX-10 Phase 2: one immutable selected-route binding plan per registered package version.
ALTER TABLE app.capability_package_version DROP CONSTRAINT capability_package_version_binding_status_check;
ALTER TABLE app.capability_package_version ADD CONSTRAINT capability_package_version_binding_status_check
    CHECK (binding_status IN ('UNRESOLVED','RESOLVED'));
ALTER TABLE app.capability_package_version
    ADD COLUMN request_plan_sha256 char(64),
    ADD COLUMN resolved_mapping jsonb,
    ADD COLUMN resolved_by varchar(128),
    ADD COLUMN resolved_at timestamptz;
ALTER TABLE app.capability_package_version ADD CONSTRAINT capability_resolution_complete CHECK (
    (binding_status='UNRESOLVED' AND request_plan_sha256 IS NULL AND resolved_mapping IS NULL AND resolved_by IS NULL AND resolved_at IS NULL)
    OR (binding_status='RESOLVED' AND request_plan_sha256 IS NOT NULL AND resolved_mapping IS NOT NULL AND request_plan_sha256 ~ '^[0-9a-f]{64}$'
        AND jsonb_typeof(resolved_mapping)='object' AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL));
