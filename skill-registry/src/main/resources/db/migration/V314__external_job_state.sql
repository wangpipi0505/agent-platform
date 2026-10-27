-- PLAT-04：外部异步任务态。V307 已允许 external_job_id 为空（远端可能延迟返回），
-- 这里只增列，保留旧列与旧语义：
--   external_state        远端最近一次可核实的任务态原文（SUBMITTED/RUNNING/FAILED/...）
--   submitted_at          首次取得远端任务标识的提交时刻（恢复窗口的起点）
--   last_polled_at        最近一次按原幂等键/任务标识轮询的完成时刻（未知态可观测的活性证明）
--   remote_error_code     平台稳定错误码（如 REMOTE_FAILED / SUBMIT_NO_TASK_ID）
--   remote_error_message  远端原文，有界（<=512），用于界面直接展示
-- 全部可空：旧行、未提交行与回滚后的写入都保持合法；不加唯一约束，避免把"远端无 id"变成插入失败。
ALTER TABLE skill.external_job_binding
    ADD COLUMN IF NOT EXISTS external_state varchar(32),
    ADD COLUMN IF NOT EXISTS submitted_at timestamptz,
    ADD COLUMN IF NOT EXISTS last_polled_at timestamptz,
    ADD COLUMN IF NOT EXISTS remote_error_code varchar(64),
    ADD COLUMN IF NOT EXISTS remote_error_message varchar(512);

-- 轮询活性只看"仍有未终结绑定"的行；按租户+状态扫描，避免全表顺序读。
CREATE INDEX IF NOT EXISTS external_job_binding_open_idx
    ON skill.external_job_binding (tenant_id, status, last_polled_at);
