-- INT-02 子步2：连接档案的"被授予版本"。
--
-- 语义边界（与 contracts/configuration-profile.md §10.3 一致）：
--   1) 批准（connection_profile.status=APPROVED）只表示"这份修订可以是使用对象"，不等于任何使用方被授权；
--      授权是这里一行一个 grantee 的独立事实，撤销靠 revoked_at 留痕，不物理删除。
--   2) grant 只指向已存在的修订（外键钉到 connection_profile_version），因此删除/改写修订会被外键阻断，
--      不会出现"授权悬空"。
--   3) 同一 (租户, 档案, 修订, 使用方) 只能有一条有效授权；重复授予走应用层的幂等回放，不在库里静默覆盖。
--   4) 授权行不保存凭据：凭据仍只以 secret_ref 出现在环境绑定修订里。
--
-- 回滚：drop table app.connection_profile_grant;

create table app.connection_profile_grant (
    id bigint generated always as identity primary key,
    tenant_id varchar(64) not null,
    profile_id varchar(64) not null,
    profile_rev integer not null check (profile_rev > 0),
    grantee varchar(64) not null,
    allowed_uses jsonb not null default '[]'::jsonb,
    granted_by varchar(64) not null,
    granted_at timestamptz not null default now(),
    revoked_at timestamptz,
    revoke_reason varchar(256),
    constraint connection_profile_grant_profile_fk
        foreign key (tenant_id, profile_id, profile_rev)
        references app.connection_profile_version (tenant_id, profile_id, profile_rev),
    constraint connection_profile_grant_uses_array
        check (jsonb_typeof(allowed_uses) = 'array'),
    constraint connection_profile_grant_grantee_format
        check (grantee ~ '^[a-z][a-z0-9._-]{2,63}$')
);

create unique index connection_profile_grant_active_uk
    on app.connection_profile_grant (tenant_id, profile_id, profile_rev, grantee)
    where revoked_at is null;

create index connection_profile_grant_lookup
    on app.connection_profile_grant (tenant_id, profile_id, profile_rev, granted_at desc);
