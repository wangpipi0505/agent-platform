-- INT-02 子步3：把解析回执挂到"哪一次调用"上。
--
-- 子步2 的回执只回答"谁在什么时候解析了哪一版"，回答不了"这次调用实际用的是哪份解析"——
-- 一次运行里同名技能可能被调多次、可能中途换绑定。补上 run/call/attempt 与具体操作名之后，
-- 回执才能与引擎的 engine.tool_call 逐条对齐，用于事后解释"这条结果凭什么连到那个端点"。
--
-- 语义：
--   1) 三个新列可空：子步2 已写入的历史行不补、不改，读侧按"调用范围未知"处理。
--   2) operation 是本次解析面向的具体操作名（一条 binding 引用对应一个操作），与
--      profile 的 operationAllowlist 同词汇表，便于直接比对。
--   3) 仍然只追加、不更新；同一 call 多个受控引用就是多行，按 operation 区分。
--
-- 回滚：
--   drop index if exists skill.connection_resolution_call;
--   alter table skill.connection_resolution
--       drop column if exists operation, drop column if exists attempt_id,
--       drop column if exists call_id, drop column if exists run_public_id;
alter table skill.connection_resolution add column if not exists run_public_id varchar(64);
alter table skill.connection_resolution add column if not exists call_id bigint;
alter table skill.connection_resolution add column if not exists attempt_id bigint;
alter table skill.connection_resolution add column if not exists operation varchar(128);

create index if not exists connection_resolution_call
    on skill.connection_resolution (tenant_id, call_id, resolved_at desc);
