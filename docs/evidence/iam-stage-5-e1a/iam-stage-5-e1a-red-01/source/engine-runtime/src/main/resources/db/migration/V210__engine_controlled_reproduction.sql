CREATE TABLE engine.reproduction (
 public_id varchar(64) PRIMARY KEY,
 source_run_id bigint NOT NULL REFERENCES engine.run(id),
 tenant_id varchar(64) NOT NULL,
 principal_id varchar(64) NOT NULL,
 request_id varchar(128) NOT NULL,
 source_digest char(64) NOT NULL,
 material jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,principal_id,request_id)
);
