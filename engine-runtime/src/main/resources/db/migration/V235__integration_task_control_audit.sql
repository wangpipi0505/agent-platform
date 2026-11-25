CREATE TABLE engine.integration_task_control_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id varchar(64) NOT NULL,
    principal_id varchar(64) NOT NULL,
    credential_id bigint NOT NULL CHECK (credential_id > 0),
    app_code varchar(64) NOT NULL,
    source_software varchar(64) NOT NULL,
    target_software varchar(64),
    task_public_id varchar(64) NOT NULL,
    run_public_id varchar(64),
    outcome varchar(16) NOT NULL CHECK (outcome IN ('CANCELLED', 'DENIED')),
    trace_id text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK ((outcome = 'CANCELLED') = (run_public_id IS NOT NULL))
);

CREATE INDEX integration_task_control_audit_tenant_recent
    ON engine.integration_task_control_audit (tenant_id, created_at DESC, id DESC);
