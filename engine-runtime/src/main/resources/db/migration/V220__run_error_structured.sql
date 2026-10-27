-- PLAT-04：引擎侧外部任务态。
-- engine.run.error 自 V200 起已是 jsonb，故不新增 run 列；结构化等待态直接写进原列，
-- 这里只增一列保存"技能事实/对账回执确认过的外部任务快照"，使 GET
-- /internal/tool-calls/{callId} 与 run 诊断在事实尚未终结时也能展示
-- remoteJobId/state/submittedAt/lastPolledAt。可空：无外部任务的调用与回滚后的旧行都合法。
ALTER TABLE engine.tool_call ADD COLUMN IF NOT EXISTS external_job jsonb;

COMMENT ON COLUMN engine.tool_call.external_job IS
    'PLAT-04: latest verified external job snapshot {remoteJobId,state,submittedAt,lastPolledAt,remoteCode,remoteMessage}; NULL when the call has no external job';
