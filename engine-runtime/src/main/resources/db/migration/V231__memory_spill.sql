-- T2-8（台账 §7.3 V231 迁移交付卡；数据库稿《05-数据库设计-20261112》§4.4/§4.5 为字段唯一权威）：
-- 记忆与外溢同批建表（库稿 §3 总览行：V231 = memory_entry + spill_artifact，单迁移交付）。
--   ① memory_entry（跨会话记忆；A3-4 消费；source_task_id FK→V230 task，前序迁移已交付，基线内闭合）
--   → ② spill_artifact（大结果外溢；T2-9/T2-10 消费；session_id→chat_session（V208）、call_id→tool_call（V200））。
-- V232 schedule_task 为 P2 预留不实施（库稿 §4.5/§6），本迁移不建。
-- 纪律：只增不改；检索纪律=先权限过滤（tenant/scope/owner/status/有效期）再按相关性排序、最后按预算截取（库稿 §4.4）；
-- 删除先置 REVOKED（立即退出检索），正文按保留策略清理；历史运行的引用保留版本号（审计）。
-- 回滚（只记录不执行；历史迁移不回滚，只增不改）：
--   drop table if exists engine.spill_artifact, engine.memory_entry cascade;

-- ① memory_entry——跨会话记忆条目（§4.4；条目可变：status CAS/正文更新，带 updated_at）
CREATE TABLE IF NOT EXISTS engine.memory_entry (
  memory_id      varchar(64)  PRIMARY KEY,
  tenant_id      varchar(64)  NOT NULL,
  workspace_code varchar(64)  NOT NULL,
  scope          varchar(16)  NOT NULL,
  owner_id       varchar(64)  NOT NULL,
  kind           varchar(24)  NOT NULL,
  content        text         NOT NULL,
  tags           jsonb        NOT NULL DEFAULT '[]'::jsonb,
  source_task_id bigint       NULL REFERENCES engine.task(id),
  source_kind    varchar(16)  NOT NULL,
  version        int          NOT NULL DEFAULT 1,
  status         varchar(16)  NOT NULL,
  expires_at     timestamptz  NULL,
  created_at     timestamptz  NOT NULL DEFAULT now(),
  updated_at     timestamptz  NOT NULL DEFAULT now()
);

-- scope：私有=owner 个人；共享=工作区授权用户（§4.4）
DO $$ BEGIN
  ALTER TABLE engine.memory_entry
    ADD CONSTRAINT memory_entry_scope_check CHECK (scope IN ('PRIVATE','SHARED'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- kind：偏好/经验；manifest.memoryPolicy 限制可写种类（§4.4）
DO $$ BEGIN
  ALTER TABLE engine.memory_entry
    ADD CONSTRAINT memory_entry_kind_check CHECK (kind IN ('PREFERENCE','EXPERIENCE'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- 来源：用户显式保存→ACTIVE；模型提取→DRAFT 待确认（§4.4）
DO $$ BEGIN
  ALTER TABLE engine.memory_entry
    ADD CONSTRAINT memory_entry_source_kind_check
    CHECK (source_kind IN ('USER_EXPLICIT','MODEL_EXTRACTED'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- status：REVOKED=删除先撤销检索资格（§4.4）
DO $$ BEGIN
  ALTER TABLE engine.memory_entry
    ADD CONSTRAINT memory_entry_status_check CHECK (status IN ('ACTIVE','DRAFT','REVOKED'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- version：更新 CAS 期望版本号（§4.4）
DO $$ BEGIN
  ALTER TABLE engine.memory_entry
    ADD CONSTRAINT memory_entry_version_check CHECK (version > 0);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- 正文 <=64KB（§4.4：octet_length<=64KB）
DO $$ BEGIN
  ALTER TABLE engine.memory_entry
    ADD CONSTRAINT memory_entry_content_size_check CHECK (octet_length(content) <= 65536);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- tags：检索标签，array 形 + 256KB 上限（对齐 §1.2 jsonb 默认 256KB 分配）
DO $$ BEGIN
  ALTER TABLE engine.memory_entry
    ADD CONSTRAINT memory_entry_tags_check
    CHECK (jsonb_typeof(tags) = 'array' AND octet_length(tags::text) <= 262144);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- 库稿 §4.4 索引：权限过滤先行的主检索路径 + 我的记忆
CREATE INDEX IF NOT EXISTS memory_entry_retrieval_idx
  ON engine.memory_entry (tenant_id, workspace_code, scope, status, expires_at);
CREATE INDEX IF NOT EXISTS memory_entry_owner_idx
  ON engine.memory_entry (owner_id);

-- ② spill_artifact——大结果外溢工件（§4.5；落库后不改、到期按保留策略清理，无 updated_at）
CREATE TABLE IF NOT EXISTS engine.spill_artifact (
  spill_id    varchar(80)  PRIMARY KEY,
  session_id  varchar(64)  NOT NULL REFERENCES engine.chat_session(public_id),
  call_id     bigint       NULL REFERENCES engine.tool_call(id),
  kind        varchar(16)  NULL,
  storage_ref varchar(256) NOT NULL,
  bytes       bigint       NOT NULL,
  sha256      char(64)     NOT NULL,
  preview     text         NULL,
  expires_at  timestamptz  NOT NULL,
  created_at  timestamptz  NOT NULL DEFAULT now()
);

-- spill_id 形状：对齐 material_scope 命名风格（§4.5）
DO $$ BEGIN
  ALTER TABLE engine.spill_artifact
    ADD CONSTRAINT spill_artifact_id_shape CHECK (spill_id ~ '^s_[a-f0-9]{32}$');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- kind：tool/result 超限外溢或附件（§4.5；列可空，显式给出 NULL 分支）
DO $$ BEGIN
  ALTER TABLE engine.spill_artifact
    ADD CONSTRAINT spill_artifact_kind_check
    CHECK (kind IS NULL OR kind IN ('TOOL_RESULT','ATTACHMENT'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  ALTER TABLE engine.spill_artifact
    ADD CONSTRAINT spill_artifact_bytes_check CHECK (bytes > 0);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- 摘要列一律 char(64) + 形状 CHECK（§1.2 全局约定）
DO $$ BEGIN
  ALTER TABLE engine.spill_artifact
    ADD CONSTRAINT spill_artifact_sha256_shape CHECK (sha256 ~ '^[a-f0-9]{64}$');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- preview：回填上下文的摘要 <=4KB（§4.5）
DO $$ BEGIN
  ALTER TABLE engine.spill_artifact
    ADD CONSTRAINT spill_artifact_preview_size_check
    CHECK (preview IS NULL OR octet_length(preview) <= 4096);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
