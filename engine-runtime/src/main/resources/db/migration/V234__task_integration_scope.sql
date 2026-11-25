CREATE TABLE engine.task_integration_scope (
    task_id bigint PRIMARY KEY REFERENCES engine.task(id) ON DELETE CASCADE,
    credential_id bigint NOT NULL CHECK (credential_id > 0),
    biz_system_code varchar(64) NOT NULL CHECK (biz_system_code ~ '^[A-Za-z][A-Za-z0-9_-]{0,63}$'),
    data_labels jsonb NOT NULL CHECK (jsonb_typeof(data_labels) = 'array')
);

COMMENT ON TABLE engine.task_integration_scope IS
    'Admission provenance from the verified credential. Legacy rows without provenance are excluded from integration reads.';
