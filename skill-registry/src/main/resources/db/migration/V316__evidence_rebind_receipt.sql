-- EX30：只为新重绑定记录固化请求指纹与原回执；旧记录保持 NULL，不猜测或回填历史请求。
ALTER TABLE skill.evidence_rebind
 ADD COLUMN request_sha256 char(64) CHECK (request_sha256 IS NULL OR request_sha256 ~ '^[0-9a-f]{64}$'),
 ADD COLUMN receipt jsonb CHECK (receipt IS NULL OR jsonb_typeof(receipt)='object');
COMMENT ON COLUMN skill.evidence_rebind.request_sha256 IS
 'EX30 v1: tenant/code/source+target ids/current SHA/digest rule version/semantic digest/trimmed reason; null legacy cannot prove an idempotent retry';
