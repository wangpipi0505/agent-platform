-- INT-02 子步2：skill 侧"受控连接解析"回执。
--
-- 语义：
--   1) 每次从 app 解析连接档案，都留一条回执：谁、为哪个技能、解析了哪一版、当时的摘要是什么。
--      运行期排查"这次调用用的是哪份配置"时以此为准，而不是靠调用日志拼。
--   2) 只存事实，不存凭据：secretRef 仍在 app 的环境绑定里，这里连引用都不复制，避免多一处泄漏面。
--   3) 追加型表：不提供更新入口；同一 (技能, 档案, 修订) 解析多次就是多行，时间是区分它们的唯一依据。
--
-- 回滚：drop table skill.connection_resolution;

create table skill.connection_resolution (
    id bigint generated always as identity primary key,
    tenant_id varchar(64) not null,
    skill_code varchar(64) not null,
    profile_id varchar(64) not null,
    profile_rev integer not null check (profile_rev > 0),
    environment varchar(64),
    resolution_digest char(64) not null,
    auth_kind varchar(16) not null,
    granted_uses jsonb not null default '[]'::jsonb,
    resolved_at timestamptz not null default now(),
    requested_by varchar(64) not null,
    constraint connection_resolution_digest_format check (resolution_digest ~ '^[0-9a-f]{64}$'),
    constraint connection_resolution_uses_array check (jsonb_typeof(granted_uses) = 'array')
);

create index connection_resolution_lookup
    on skill.connection_resolution (tenant_id, skill_code, profile_id, profile_rev, resolved_at desc);
