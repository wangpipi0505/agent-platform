-- 发布门禁分层与显式豁免：报告记录当次门禁模式与生效豁免，豁免按候选精确绑定并受24小时上限约束。
-- 只增不改：既有报告一律回填 BLOCKING，历史发布结论不因新增列而改变。
ALTER TABLE app.publish_report ADD COLUMN gate_mode varchar(16) NOT NULL DEFAULT 'BLOCKING' CHECK (gate_mode IN ('BLOCKING','ADVISORY','OFF'));
ALTER TABLE app.publish_report ADD COLUMN waiver_id varchar(64);
CREATE TABLE app.publish_waiver (
 id varchar(64) NOT NULL, tenant_id varchar(128) NOT NULL, app_code varchar(64) NOT NULL,
 draft_rev bigint NOT NULL CHECK (draft_rev > 0), candidate_sha256 char(64) NOT NULL,
 reason varchar(512) NOT NULL CHECK (char_length(reason) >= 10),
 approved_by varchar(128) NOT NULL, approved_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz NOT NULL, revoked_at timestamptz, revoked_by varchar(128),
 PRIMARY KEY(tenant_id,id),
 -- 时限在库层再兜一次：应用层已校验24小时上限，绕过API的写入同样不得放宽恢复窗口。
 CONSTRAINT publish_waiver_ttl CHECK (expires_at > approved_at AND expires_at <= approved_at + interval '24 hours'),
 FOREIGN KEY(tenant_id,app_code) REFERENCES app.application(tenant_id,code)
);
-- 有效豁免按(租户,应用,修订,候选)精确命中；部分索引避免撤销/过期行参与发布路径查询。
CREATE INDEX publish_waiver_active ON app.publish_waiver(tenant_id,app_code,draft_rev,candidate_sha256) WHERE revoked_at IS NULL;
