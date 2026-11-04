-- INT-06：把"受理时核验过的配置版本引用"钉在会话与运行事实上。
--
-- 语义：
-- 1) config_release_id/config_digest 只由引擎服务端解析写入：浏览器可以提议 releaseId，
--    但摘要与环境绑定修订必须来自 app 的激活配置投影，任何客户端字段都不能覆盖。
-- 2) 轮次沿会话创建时固定的引用继续执行；旧会话/旧客户端没有引用时列保持 null，
--    读侧按"未绑定配置版本"处理，不改变既有行为。
-- 3) versionRefs 仍是版本引用的唯一权威记录，本表列是并列的配置发布事实，不替换它。
--
-- 回滚：
--   drop index if exists engine.run_config_release_idx;
--   drop index if exists engine.chat_session_config_release_idx;
--   alter table engine.run drop constraint if exists run_environment_binding_revs_array;
--   alter table engine.run drop constraint if exists run_config_digest_shape;
--   alter table engine.run drop constraint if exists run_config_reference_pair;
--   alter table engine.run drop column if exists config_release_id, drop column if exists config_digest,
--       drop column if exists environment_binding_revs, drop column if exists config_environment;
--   alter table engine.chat_session drop constraint if exists chat_session_environment_binding_revs_array;
--   alter table engine.chat_session drop constraint if exists chat_session_config_digest_shape;
--   alter table engine.chat_session drop constraint if exists chat_session_config_reference_pair;
--   alter table engine.chat_session drop column if exists config_release_id, drop column if exists config_digest,
--       drop column if exists environment_binding_revs, drop column if exists config_environment;

alter table engine.run add column if not exists config_release_id varchar(64);
alter table engine.run add column if not exists config_digest char(64);
alter table engine.run add column if not exists environment_binding_revs jsonb not null default '[]'::jsonb;
alter table engine.run add column if not exists config_environment varchar(32);

alter table engine.run drop constraint if exists run_config_digest_shape;
alter table engine.run
    add constraint run_config_digest_shape
    check (config_digest is null or config_digest ~ '^[0-9a-f]{64}$');

alter table engine.run drop constraint if exists run_config_reference_pair;
alter table engine.run
    add constraint run_config_reference_pair
    check ((config_release_id is null) = (config_digest is null));

alter table engine.run drop constraint if exists run_environment_binding_revs_array;
alter table engine.run
    add constraint run_environment_binding_revs_array
    check (jsonb_typeof(environment_binding_revs) = 'array');

create index if not exists run_config_release_idx
    on engine.run (tenant_id, config_release_id)
    where config_release_id is not null;

alter table engine.chat_session add column if not exists config_release_id varchar(64);
alter table engine.chat_session add column if not exists config_digest char(64);
alter table engine.chat_session add column if not exists environment_binding_revs jsonb not null default '[]'::jsonb;
alter table engine.chat_session add column if not exists config_environment varchar(32);

alter table engine.chat_session drop constraint if exists chat_session_config_digest_shape;
alter table engine.chat_session
    add constraint chat_session_config_digest_shape
    check (config_digest is null or config_digest ~ '^[0-9a-f]{64}$');

alter table engine.chat_session drop constraint if exists chat_session_config_reference_pair;
alter table engine.chat_session
    add constraint chat_session_config_reference_pair
    check ((config_release_id is null) = (config_digest is null));

alter table engine.chat_session drop constraint if exists chat_session_environment_binding_revs_array;
alter table engine.chat_session
    add constraint chat_session_environment_binding_revs_array
    check (jsonb_typeof(environment_binding_revs) = 'array');

create index if not exists chat_session_config_release_idx
    on engine.chat_session (tenant_id, config_release_id)
    where config_release_id is not null;
