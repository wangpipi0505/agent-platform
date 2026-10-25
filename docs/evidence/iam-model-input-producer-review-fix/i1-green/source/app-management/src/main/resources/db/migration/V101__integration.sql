CREATE TABLE app.integration_credential (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,tenant_id varchar(128) NOT NULL,app_code varchar(64) NOT NULL,
 biz_system_code varchar(64) NOT NULL,secret_ref varchar(512) NOT NULL,secret_sha256 char(64) NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('ACTIVE','REVOKED')),claim_limits jsonb NOT NULL,allowed_origins jsonb NOT NULL,
 issued_by varchar(128) NOT NULL,issued_at timestamptz NOT NULL DEFAULT now(),revoked_at timestamptz,
 UNIQUE(tenant_id,app_code,biz_system_code),FOREIGN KEY(tenant_id,app_code) REFERENCES app.application(tenant_id,code)
);
