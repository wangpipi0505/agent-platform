-- 多轮检查点仍在原报告results中；短租约仅防并发恢复及旧代写回，不保存主体角色作为凭据。
ALTER TABLE monitor.evaluation_report
 ADD COLUMN execution_token uuid,
 ADD COLUMN execution_until timestamptz;
