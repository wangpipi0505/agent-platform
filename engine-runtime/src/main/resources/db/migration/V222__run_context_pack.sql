-- PLAT-13：运行冻结时记录本体上下文包的审计信息（space/releaseId/sha256/fetchedAt/truncated）。
-- 只存审计字段，不存包正文；正文只出现在冻结后的 config.ontologyContext。
alter table engine.run add column if not exists context_pack jsonb;
