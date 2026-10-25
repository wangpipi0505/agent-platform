-- A rebuild reads authoritative HTTP projections first; only a complete report is persisted.
-- The report and active pointer switch in one monitor transaction. Cross-service snapshots
-- are deliberately identified as PER_SOURCE_SNAPSHOT, never claimed as a distributed snapshot.
CREATE TABLE monitor.statistics_snapshot (
 tenant_id varchar(128) NOT NULL,id varchar(36) NOT NULL,request_id varchar(128) NOT NULL,
 query_hash char(64) NOT NULL,filters jsonb NOT NULL,report jsonb NOT NULL,
 created_by varchar(128) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,request_id),
 CHECK(octet_length(report::text)<=16777216)
);
CREATE TABLE monitor.statistics_active (
 tenant_id varchar(128) NOT NULL,query_hash char(64) NOT NULL,snapshot_id varchar(36) NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(tenant_id,query_hash),
 FOREIGN KEY(tenant_id,snapshot_id) REFERENCES monitor.statistics_snapshot(tenant_id,id)
);
CREATE INDEX statistics_snapshot_history ON monitor.statistics_snapshot(tenant_id,created_at DESC);
