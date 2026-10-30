-- EX25：业务意图只由新契约显式启用；旧计划两列均 NULL，不改正文/digest/execute_key。
-- payload 指纹取既有 call.args_digest；business_key 含 tenant/固定版本及合同摘要/显式输入意图。
ALTER TABLE engine.action_plan
 ADD COLUMN business_key char(64),
 ADD COLUMN business_payload_sha char(64),
 ADD CONSTRAINT action_plan_business_binding CHECK (
   (business_key IS NULL AND business_payload_sha IS NULL)
   OR (business_key IS NOT NULL AND business_payload_sha IS NOT NULL
       AND business_key ~ '^[0-9a-f]{64}$' AND business_payload_sha ~ '^[0-9a-f]{64}$'));
-- 未授予的过期计划可以重 PREPARE；一次授予后永不因过期/UNKNOWN/FAILED 自动释放。
-- 原 execute_granted_at 继续充当授权事实，部分唯一约束独立兜住并发不同 callId 的重复授予。
CREATE UNIQUE INDEX action_plan_business_single_grant ON engine.action_plan(business_key)
 WHERE business_key IS NOT NULL AND execute_granted_at IS NOT NULL;
CREATE INDEX action_plan_business_payload ON engine.action_plan(business_key) WHERE business_key IS NOT NULL;
