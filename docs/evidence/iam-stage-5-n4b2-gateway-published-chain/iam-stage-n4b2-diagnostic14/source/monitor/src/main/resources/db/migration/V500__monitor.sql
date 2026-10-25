CREATE SCHEMA IF NOT EXISTS monitor;
CREATE TABLE monitor.dataset (
 tenant_id varchar(128) NOT NULL, app_code varchar(64) NOT NULL,dataset_version varchar(128) NOT NULL,
 cases jsonb NOT NULL,sha256 char(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,app_code,dataset_version)
);
CREATE TABLE monitor.evaluation_report (
 tenant_id varchar(128) NOT NULL,id varchar(64) NOT NULL,app_code varchar(64) NOT NULL,draft_rev bigint NOT NULL,
 candidate_sha256 char(64) NOT NULL,dataset_version varchar(128) NOT NULL,candidate jsonb NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('RUNNING','ACCEPTED','REJECTED','ERROR')),
 threshold numeric NOT NULL,results jsonb NOT NULL DEFAULT '[]',baseline_report_id varchar(64),detail text,
 created_by varchar(128) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),completed_at timestamptz,
 PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,app_code,dataset_version) REFERENCES monitor.dataset(tenant_id,app_code,dataset_version)
);
CREATE INDEX evaluation_candidate ON monitor.evaluation_report(tenant_id,app_code,draft_rev,candidate_sha256,status);
