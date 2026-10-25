CREATE TABLE skill.reconciliation_note (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,tenant_id varchar(128) NOT NULL,call_id bigint NOT NULL,
 principal_id varchar(128) NOT NULL,outcome varchar(32) NOT NULL,query_duration_ms bigint NOT NULL,
 detail jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,call_id) REFERENCES skill.execution_fact(tenant_id,call_id)
);
