-- Product-owned operations alerts. Only alerts raised by an explicit evaluation are stored;
-- health checks themselves are live reads, never fabricated history. One active row per
-- tenant/rule/subject; resolved rows remain for audit until retention expires.
CREATE TABLE monitor.ops_alert (
 tenant_id varchar(128) NOT NULL,
 id varchar(36) NOT NULL,
 rule_code varchar(64) NOT NULL,
 subject varchar(160) NOT NULL,
 severity varchar(16) NOT NULL CHECK (severity IN ('WARNING','CRITICAL')),
 title varchar(200) NOT NULL,
 detail varchar(2000) NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ACK','RESOLVED')),
 first_seen_at timestamptz NOT NULL DEFAULT now(),
 last_seen_at timestamptz NOT NULL DEFAULT now(),
 acknowledged_at timestamptz,
 acknowledged_by varchar(128),
 resolved_at timestamptz,
 PRIMARY KEY (tenant_id,id)
);
CREATE UNIQUE INDEX ops_alert_one_active ON monitor.ops_alert(tenant_id,rule_code,subject) WHERE resolved_at IS NULL;
CREATE INDEX ops_alert_history ON monitor.ops_alert(tenant_id,status,last_seen_at DESC);
