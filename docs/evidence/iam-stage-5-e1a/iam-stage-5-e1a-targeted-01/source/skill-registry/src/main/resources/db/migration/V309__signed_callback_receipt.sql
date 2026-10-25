-- Receipt acknowledges authenticated external evidence, never an engine logical commit.
CREATE TABLE skill.callback_receipt (
 tenant_id varchar(128) NOT NULL, call_id bigint NOT NULL, event_id varchar(128) NOT NULL,
 principal_id varchar(128) NOT NULL, run_id varchar(128) NOT NULL, attempt_id bigint NOT NULL,
 skill_version_id bigint NOT NULL, external_job_id varchar(128) NOT NULL,
 payload_digest char(64) NOT NULL, signed_at timestamptz NOT NULL, verified_at timestamptz NOT NULL DEFAULT now(),
 outcome varchar(32) NOT NULL, processing_owner varchar(64), processing_until timestamptz,
 updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,call_id,event_id),
 CHECK (outcome IN ('RECEIVED','WAITING_BINDING','AUTHORIZATION_DENIED','PROCESSING','STORED','LATE','OUTPUT_REJECTED','SUPERSEDED'))
);
CREATE UNIQUE INDEX callback_one_processor ON skill.callback_receipt(tenant_id,call_id) WHERE outcome='PROCESSING';
