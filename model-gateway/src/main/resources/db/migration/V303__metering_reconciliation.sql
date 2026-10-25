ALTER TABLE model.call_log ADD COLUMN admission_tokens integer;
ALTER TABLE model.call_log ADD COLUMN metering_snapshot jsonb NOT NULL DEFAULT '{}';
ALTER TABLE model.call_log ADD COLUMN model_snapshot jsonb NOT NULL DEFAULT '{}';
ALTER TABLE model.call_log ADD COLUMN egress jsonb NOT NULL DEFAULT '{}';
ALTER TABLE model.call_log ADD COLUMN calibration_scope varchar(64);
ALTER TABLE model.call_log ADD COLUMN quota_receipt jsonb NOT NULL DEFAULT '{}';
ALTER TABLE model.call_log ADD COLUMN usage_reconciliation varchar(24) NOT NULL DEFAULT 'UNAVAILABLE';
CREATE TABLE model.metering_calibration (
 tenant_id varchar(128) NOT NULL, model_entry_id bigint NOT NULL,
 calibration_scope varchar(64) NOT NULL, samples bigint NOT NULL DEFAULT 0,
 estimated_tokens bigint NOT NULL DEFAULT 0, actual_prompt_tokens bigint NOT NULL DEFAULT 0,
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,model_entry_id,calibration_scope)
);
