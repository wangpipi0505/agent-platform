-- EX24：旧计划不重算正文/digest/execute_key，默认标识保留其历史政策来源。
-- 新 HIGH 在盖章材料内声明 TWO_PRINCIPALS_V1，再固定到该列；两人事实随原计划行持久化。
ALTER TABLE engine.action_plan
 ADD COLUMN approval_policy varchar(32) NOT NULL DEFAULT 'LEGACY_DECLARATION'
  CHECK (approval_policy IN ('LEGACY_DECLARATION','TWO_PRINCIPALS_V1')),
 ADD COLUMN approvals jsonb NOT NULL DEFAULT '[]'
  CHECK (jsonb_typeof(approvals)='array' AND jsonb_array_length(approvals)<=2);
COMMENT ON COLUMN engine.action_plan.approvals IS
 'EX24: up to two different verified tenant/principal approvals, serialized by existing run/node/plan locks; never caller supplied';
