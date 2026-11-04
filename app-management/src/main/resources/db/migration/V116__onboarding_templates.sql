-- ONB-01（服务端）：业务接入模板目录。模板只包装既有能力包与 INT-01/03 契约引用，
-- 不建第二套连接或模板引擎；发布后修订不可原地修改，只能追加新版本。

create table if not exists app.onboarding_template (
    tenant_id     varchar(64)  not null,
    code          varchar(64)  not null,
    current_rev   integer      not null default 1,
    status        varchar(16)  not null default 'DRAFT'
                  check (status in ('DRAFT', 'PUBLISHED', 'DISABLED')),
    created_by    varchar(128) not null,
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now(),
    primary key (tenant_id, code),
    check (code ~ '^[a-z][a-z0-9_]{1,63}$')
);

create table if not exists app.onboarding_template_version (
    tenant_id                varchar(64)   not null,
    code                     varchar(64)   not null,
    template_rev             integer       not null,
    kind                     varchar(32)   not null
                             check (kind in ('QUERY', 'FILE_ANALYSIS', 'CONTROLLED_WRITE')),
    name                     varchar(128)  not null,
    description              varchar(2048) not null default '',
    source_package_version_id bigint       not null,
    source_package_sha256    char(64)      not null,
    app_code                 varchar(64)   not null,
    experience_ref           jsonb         not null,
    integration_ref          jsonb         not null,
    declaration_sha256       char(64)      not null,
    created_by               varchar(128)  not null,
    created_at               timestamptz   not null default now(),
    primary key (tenant_id, code, template_rev),
    foreign key (tenant_id, code) references app.onboarding_template (tenant_id, code),
    check (source_package_version_id >= 1),
    check (source_package_sha256 ~ '^[0-9a-f]{64}$'),
    check (declaration_sha256 ~ '^[0-9a-f]{64}$'),
    check (jsonb_typeof(experience_ref) = 'object'),
    check (jsonb_typeof(integration_ref) = 'object')
);

create index if not exists onboarding_template_app_idx
    on app.onboarding_template (tenant_id, code);
create index if not exists onboarding_template_version_kind_idx
    on app.onboarding_template_version (tenant_id, kind);

-- 回滚（按需手工执行，保留既有审计事实）：
-- drop table if exists app.onboarding_template_version;
-- drop table if exists app.onboarding_template;
