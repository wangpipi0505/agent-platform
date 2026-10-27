-- PLAT-01：可配置断言模型与判分快照。只增不改：历史报告默认 EXACT，语义仍是"整份输出精确相等"。
-- assertion_snapshot 冻结每条用例的规范化断言配置，使恢复执行只认冻结值，不受后续数据集或默认配置变化影响。
ALTER TABLE monitor.evaluation_report ADD COLUMN assertion_model varchar(16) NOT NULL DEFAULT 'EXACT',ADD COLUMN assertion_snapshot jsonb;
ALTER TABLE monitor.evaluation_report ADD CONSTRAINT evaluation_assertion_model CHECK(assertion_model IN ('EXACT','CONFIGURABLE'));
-- 旧报告没有断言配置；新报告必须带快照。不允许"标记为CONFIGURABLE却没有可复算的配置"。
ALTER TABLE monitor.evaluation_report ADD CONSTRAINT evaluation_assertion_snapshot CHECK(
 (assertion_model='EXACT' AND assertion_snapshot IS NULL)
 OR (assertion_model='CONFIGURABLE' AND assertion_snapshot IS NOT NULL)
);
