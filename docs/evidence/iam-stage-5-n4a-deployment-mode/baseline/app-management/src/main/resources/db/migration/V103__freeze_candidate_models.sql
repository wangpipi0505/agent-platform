CREATE TABLE app.candidate_model_snapshot (
 tenant_id varchar(128) NOT NULL, app_code varchar(64) NOT NULL,
 draft_rev bigint NOT NULL, candidate_sha256 varchar(64) NOT NULL,
 model_refs jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,app_code,draft_rev,candidate_sha256)
);
ALTER TABLE app.publish_report ADD COLUMN model_refs jsonb NOT NULL DEFAULT '[]';
