-- PLAT-19 T19.1（X-6 缩水版：token 计量与三级配额，不做金额成本）。
-- 计量事实不新增表：唯一已计量 token 来源仍是既有 trace 写入
-- （engine.model_invocation.usage_accounted + response->'usage'->>'totalTokens'，
-- 由 TraceStore.responseInitial / ModelExchange.accountUsage 维护，本迁移不改其语义）。
-- tenant/app/credential 三级滑动窗口配额与按应用/用户/模型的聚合查询都按
-- mi.updated_at（响应落库≈计量时刻）在窗口内求和，这里补两个只增索引支撑窗口扫描。
-- 只增不改：不新增列、不删列、不改约束；旧数据无需回填即可通过（partial index 只覆盖新谓词）。
CREATE INDEX IF NOT EXISTS idx_model_invocation_usage_window
    ON engine.model_invocation (updated_at)
    WHERE usage_accounted AND response->'usage'->>'totalTokens' IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_run_app_started
    ON engine.run (app_code, started_at DESC);

-- 回滚（down，手工执行）：
-- DROP INDEX IF EXISTS engine.idx_model_invocation_usage_window;
-- DROP INDEX IF EXISTS engine.idx_run_app_started;
-- 行为回滚：agent.engine.quota-enabled=false（engine-runtime application.yml，默认即 false），
-- 关闭后准入不再做配额判定；聚合读路径为纯只读查询，无持久化副作用。
