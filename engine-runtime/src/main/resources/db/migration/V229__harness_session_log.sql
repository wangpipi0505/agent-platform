-- H1-1（台账 §6 阶段 1；数据库稿《05-数据库设计-20261112》§4.1 为字段唯一权威）：
-- 会话事件日志一次交付四对象——chat_session 扩列 + session_event + agent_inbox + subagent_catalog。
-- 纪律：只增不改；ADD COLUMN IF NOT EXISTS；事件目录枚举扩展一律走新迁移追加 CHECK 值（§6 迁移纪律）。

-- ① chat_session 扩列（会话身份字段并入 chat_session，不建 session_header 新表——库稿 §3 差异说明）
ALTER TABLE engine.chat_session ADD COLUMN IF NOT EXISTS origin varchar(16) NOT NULL DEFAULT 'USER';
ALTER TABLE engine.chat_session ADD COLUMN IF NOT EXISTS parent_session_id varchar(64) NULL;
ALTER TABLE engine.chat_session ADD COLUMN IF NOT EXISTS delegation_depth int NOT NULL DEFAULT 0;
ALTER TABLE engine.chat_session ADD COLUMN IF NOT EXISTS event_watermark int NOT NULL DEFAULT 0;

DO $$ BEGIN
  ALTER TABLE engine.chat_session
    ADD CONSTRAINT chat_session_origin_check
    CHECK (origin IN ('USER','SUBAGENT','SCHEDULE','API','TASK_INTERNAL'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  ALTER TABLE engine.chat_session
    ADD CONSTRAINT chat_session_delegation_depth_check CHECK (delegation_depth >= 0);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- ② session_event——会话事实源，追加式（契约四条见 harness.session.SessionStore javadoc）
CREATE TABLE IF NOT EXISTS engine.session_event (
  session_id  varchar(64) NOT NULL REFERENCES engine.chat_session(public_id),
  seq         int NOT NULL,
  type        varchar(32) NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  data        jsonb NOT NULL,
  surface_op  jsonb NULL,
  PRIMARY KEY (session_id, seq)
);

-- 事件目录初始枚举（27 类，权威=数据库稿 §4.1；扩展走新迁移）
DO $$ BEGIN
  ALTER TABLE engine.session_event
    ADD CONSTRAINT session_event_type_check
    CHECK (type IN (
      'turn/start','turn/end','step/start','step/end',
      'system/message','user/message','assistant/message','assistant/attempt',
      'tool/call','tool/result','request/header','request/context',
      'plan/mode','approval/asked','approval/decided','approval/policy',
      'todo/write','compaction/start','compaction/summary','compaction/end',
      'subagent/registered','subagent/settled','job/registered','job/settled',
      'inbox/enqueue','inbox/consume','working-directory/change'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- 大小分配（库稿 §4.1）：默认 256KB；assistant/message 内嵌压缩流放宽 2MB。
-- 注：库稿原文的 OR 表达式对非 assistant 类型不设上限，按字段表本意收紧为下式（台账 X2-4）。
DO $$ BEGIN
  ALTER TABLE engine.session_event
    ADD CONSTRAINT session_event_data_size_check
    CHECK (octet_length(data::text) <= 262144
           OR (type = 'assistant/message' AND octet_length(data::text) <= 2097152));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- surface_op：null 或 object 且 op IN ('append','replace')；replace 需带 startSeq/endSeq
DO $$ BEGIN
  ALTER TABLE engine.session_event
    ADD CONSTRAINT session_event_surface_op_check
    CHECK (surface_op IS NULL OR (
      jsonb_typeof(surface_op) = 'object'
      AND surface_op->>'op' IN ('append','replace')
      AND ( (surface_op->>'op') = 'append'
            OR ( (surface_op->>'op') = 'replace'
                 AND surface_op->>'startSeq' IS NOT NULL
                 AND surface_op->>'endSeq' IS NOT NULL ) )
    ));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- 投影扫描部分索引（库稿 §4.1）
CREATE INDEX IF NOT EXISTS session_event_surface_idx
  ON engine.session_event (session_id, seq) WHERE surface_op IS NOT NULL;

-- tool/call 条件唯一索引：登记事务内按 (session_id, callId) 去重，重放复用首个锚点（库稿 §4.1）
CREATE UNIQUE INDEX IF NOT EXISTS session_event_tool_call_callid_uk
  ON engine.session_event (session_id, (data->>'callId'))
  WHERE type = 'tool/call' AND data->>'callId' IS NOT NULL;

-- 追加不可变：触发器禁 UPDATE/DELETE（先例 authorized_run_immutable）
CREATE OR REPLACE FUNCTION engine.session_event_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'session_event is append-only (H1-1): % on %', TG_OP, TG_TABLE_NAME;
END; $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS session_event_append_only_guard ON engine.session_event;
CREATE TRIGGER session_event_append_only_guard
  BEFORE UPDATE OR DELETE ON engine.session_event
  FOR EACH ROW EXECUTE FUNCTION engine.session_event_append_only();

-- ③ agent_inbox——待处理输入投影（由 inbox/enqueue|consume 事件物化，可随时重建）
CREATE TABLE IF NOT EXISTS engine.agent_inbox (
  session_id        varchar(64) NOT NULL REFERENCES engine.chat_session(public_id),
  queue             varchar(16) NOT NULL,
  dedupe_key        varchar(128) NOT NULL,
  position          bigint NOT NULL,
  message           jsonb NOT NULL,
  enqueued_at       timestamptz NOT NULL DEFAULT now(),
  claimed_by        varchar(64) NULL,
  claim_generation  int NOT NULL DEFAULT 0,
  claim_lease_until timestamptz NULL,
  consumed_at       timestamptz NULL,
  PRIMARY KEY (session_id, queue, position),
  UNIQUE (session_id, queue, dedupe_key)
);

DO $$ BEGIN
  ALTER TABLE engine.agent_inbox
    ADD CONSTRAINT agent_inbox_queue_check CHECK (queue IN ('NEXT_TURN','NEXT_STEP'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  ALTER TABLE engine.agent_inbox
    ADD CONSTRAINT agent_inbox_message_size_check
    CHECK (octet_length(message::text) <= 262144);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- ④ subagent_catalog——子代理目录（T2-7 消费，迁移按归口规则随本卡提前交付）
CREATE TABLE IF NOT EXISTS engine.subagent_catalog (
  parent_session_id varchar(64) NOT NULL REFERENCES engine.chat_session(public_id),
  child_session_id  varchar(64) NOT NULL REFERENCES engine.chat_session(public_id),
  label             varchar(128) NOT NULL,
  start_seq         int NOT NULL,
  started_at        timestamptz,
  ended_at          timestamptz,
  stop_reason       varchar(24),
  PRIMARY KEY (parent_session_id, child_session_id)
);

DO $$ BEGIN
  ALTER TABLE engine.subagent_catalog
    ADD CONSTRAINT subagent_catalog_stop_reason_check
    CHECK (stop_reason IS NULL
           OR stop_reason IN ('COMPLETED','ABORTED','ERROR','MAX_TOKENS','REFUSAL'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE INDEX IF NOT EXISTS subagent_catalog_parent_started_idx
  ON engine.subagent_catalog (parent_session_id, started_at);
