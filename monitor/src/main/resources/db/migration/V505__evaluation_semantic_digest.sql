-- PLAT-03：评测证据的语义摘要绑定。
--
-- 背景：candidate_sha256 覆盖整份候选快照，描述文案改一个标点它就变，导致旧评测证据全部失效。
-- 语义摘要（应用侧 AppDigest 计算）只覆盖结构与行为，用来判断"证据能不能复用"。
--
-- 兼容：列可空。历史报告没有摘要，只走 sha 精确匹配；新报告由创建方从候选里带上来。
-- 索引：按 (租户, 应用, 草稿修订, 摘要) 查最近一次通过的证据，与 sha 查询并发独立。
alter table monitor.evaluation_report add column if not exists candidate_semantic_digest varchar(64);

create index if not exists evaluation_report_semantic_idx
    on monitor.evaluation_report (tenant_id, app_code, draft_rev, candidate_semantic_digest)
    where status = 'ACCEPTED';
