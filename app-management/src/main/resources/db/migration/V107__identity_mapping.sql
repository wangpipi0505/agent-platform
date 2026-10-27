-- PLAT-12 / X-5：平台主体身份映射表。与本体侧 O-07 的 platform_actor_credential（V24）对齐，
-- 本表是平台侧"平台主体 → 外部 subject"的唯一权威源；后续 X-User-* 出站注入以该表为源。
-- subject_snapshot 固化验签后的 Actor 身份 claims（tenantId/principalId/roles/attributes）；
-- appCode/runId/credentialId 属每次调用的运行期上下文，随审计三元组（lifecycle_log 关联列）记录，不进身份快照。
-- mapping_key 是快照材料的不可逆 SHA-256（键与集合元素排序后进 canonical JSON），供外部系统无 PII 引用同一主体。
-- 写入语义（定死，UPSERT）：同一 (tenant_id, principal_id) 只保留一行当前映射；
--   快照未变（mapping_key 相同）重放为无操作，revision 不推进；快照变化整行替换，revision 单调 +1；
--   读取不存在的映射必须显式报 404（IDENTITY_MAPPING_NOT_FOUND），禁止静默造数据。
CREATE TABLE app.identity_mapping (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 tenant_id varchar(128) NOT NULL,
 principal_id varchar(128) NOT NULL,
 mapping_key char(64) NOT NULL,
 subject_snapshot jsonb NOT NULL,
 revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
 created_by varchar(128) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id, principal_id)
);
-- mapping_key 摘要材料含 tenant+principal，同键即同主体；唯一约束兜住绕过服务的脏写。
CREATE UNIQUE INDEX identity_mapping_key_unique ON app.identity_mapping(mapping_key);

-- 审计三元组（方案 §4.12 实现项 4）：runId↔principalId↔credentialId 落在既有 app.lifecycle_log 上，
-- 支持从业务用户/凭据反查运行，不新建重复表。两列可空：旧数据与既有写入路径语义不变；
-- run_id 形如 r_<32hex>（engine.run.public_id），credential_id 对应 integration_credential.id 的十进制串。
ALTER TABLE app.lifecycle_log ADD COLUMN run_id varchar(64);
ALTER TABLE app.lifecycle_log ADD COLUMN credential_id varchar(64);
-- 反查走部分索引：仅已关联行参与，历史行不增加索引负担。
CREATE INDEX lifecycle_log_principal_run ON app.lifecycle_log(tenant_id, actor, run_id) WHERE run_id IS NOT NULL;
CREATE INDEX lifecycle_log_credential ON app.lifecycle_log(tenant_id, credential_id) WHERE credential_id IS NOT NULL;

-- 回滚（down，需手动执行；drop 前先确认无在途写入窗口）：
-- DROP INDEX IF EXISTS app.lifecycle_log_credential;
-- DROP INDEX IF EXISTS app.lifecycle_log_principal_run;
-- ALTER TABLE app.lifecycle_log DROP COLUMN IF EXISTS credential_id;
-- ALTER TABLE app.lifecycle_log DROP COLUMN IF EXISTS run_id;
-- DROP INDEX IF EXISTS app.identity_mapping_key_unique;
-- DROP TABLE IF EXISTS app.identity_mapping;
