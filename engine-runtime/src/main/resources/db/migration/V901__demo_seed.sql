-- engine V901：演示种子（demo-seed）——台账 M5-1（阶段 5）。
-- 演示种子，生产经 placeholder 跳过；幂等 ON CONFLICT DO NOTHING。
-- 数据权威：contracts/demo-seed-v901-design.md（2026-11-13 用户评审补正版）§2 种子范围总表、§3 manifest 示例
-- （冻结 manifest.schema.json 已过 ajv 校验）、§3.1 ACTIVE 发布与激活指针、§4 两场景数据映射表；
-- 字段逐一对照数据库稿《05-数据库设计-20261112》§4（M5-1 领卡须知），并以各模块既有迁移（V100-V123/V300-V322/V200-V231）实际列为准。
-- 本体线剔除（C0-1）：不含 ontology releaseRef、语义绑定、planning-ontology-* / um-ontology-check 本体对象；
-- 业务侧数据（departments/actors/accounts）不入平台种子；model-gateway 不动（红线）：manifest.model 仅引用编码。
-- 幂等（A16 验收项“迁移可重复”）：全部 INSERT ... ON CONFLICT DO NOTHING；状态推进 UPDATE 带 WHERE 守卫，
-- 重复执行零行变化；只增不改（不触碰既有行）；demo 租户隔离（全部行 tenant_id='demo'）。
-- 种子行清单：memory_entry 2（1 PRIVATE/PREFERENCE + 1 SHARED/EXPERIENCE，§4.4）。
-- 不种 session/chat/task 运行时事实（设计稿 §2 明确排除：运行时事实由演示链路真实产生）。

-- 生产跳过开关（数据库稿 §6“生产经 Flyway placeholder 跳过”的可执行实现，占位符接线属 G5-0）：
-- 以 PostgreSQL 会话/库级自定义设置 agent.demo_seed_skip 控制，未设置=执行（默认演示/验证库行为不变）；
-- 生产库执行 ALTER DATABASE <prod> SET agent.demo_seed_skip = 'on' 后，本迁移整段 no-op。
DO $demo_seed$
BEGIN
  IF coalesce(current_setting('agent.demo_seed_skip', true), 'off') = 'on' THEN
    RAISE NOTICE 'V901 demo seed skipped (agent.demo_seed_skip=on)';
    RETURN;
  END IF;

  -- §4.4：memory_entry 2 条（V231）。tenant_id='demo'；source_task_id 空（非任务派生）、source_kind=USER_EXPLICIT
  -- （用户显式保存→ACTIVE，与 V231 注释口径一致）；status=ACTIVE、expires_at 空；ON CONFLICT (memory_id)（主键幂等键）。
  -- workspace_code 引用演示工作区编码（逻辑引用，与 §4.4 权限过滤索引 tenant/workspace/scope/status 一致）。
  INSERT INTO engine.memory_entry(memory_id, tenant_id, workspace_code, scope, owner_id, kind, content, tags, source_task_id, source_kind, version, status)
     VALUES ('demo-mem-pref-0001', 'demo', 'satellite-planning-assistant', 'PRIVATE', 'demo-user', 'PREFERENCE',
             '演示用户偏好：输出偏好简明观测窗口表，云量上限默认30%，推荐窗口需说明评分理由。',
             '["演示","偏好","输出格式"]'::jsonb, NULL, 'USER_EXPLICIT', 1, 'ACTIVE')
     ON CONFLICT (memory_id) DO NOTHING;
  INSERT INTO engine.memory_entry(memory_id, tenant_id, workspace_code, scope, owner_id, kind, content, tags, source_task_id, source_kind, version, status)
     VALUES ('demo-mem-exp-0001', 'demo', 'satellite-planning-assistant', 'SHARED', 'demo-operator', 'EXPERIENCE',
             '演示规划经验：星历年龄超过14天时窗口评分显著下降，规划前先核对星历时效；地面站下传容量在整点前后最紧张，建议错峰安排下传窗口。',
             '["演示","卫星","规划经验"]'::jsonb, NULL, 'USER_EXPLICIT', 1, 'ACTIVE')
     ON CONFLICT (memory_id) DO NOTHING;

END
$demo_seed$;
