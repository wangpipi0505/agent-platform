-- 先保存预留意图，再准入Redis；发送边界使用PG CAS，故障恢复只退款已证明未发送的调用。
CREATE TABLE model.quota_reservation (
 tenant_id varchar(128) NOT NULL, reservation_id varchar(64) NOT NULL,
 principal_id varchar(128) NOT NULL, invocation_ref varchar(160) NOT NULL,
 model_entry_id bigint NOT NULL, provider_id bigint NOT NULL, app_code varchar(64) NOT NULL,
 quota_receipt jsonb NOT NULL, status varchar(16) NOT NULL DEFAULT 'RESERVING'
  CHECK(status IN ('RESERVING','SENT','NO_SEND')),
 usage_reconciliation varchar(24) NOT NULL DEFAULT 'UNAVAILABLE', error_code varchar(40),
 lease_until timestamptz NOT NULL DEFAULT now()+interval '15 minutes',
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,reservation_id)
);
CREATE INDEX quota_reservation_recovery ON model.quota_reservation(status,usage_reconciliation,lease_until);
