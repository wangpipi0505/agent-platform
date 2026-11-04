-- CR05 R2：session 导入与精确技能草稿在同一 skill 事务提交。
-- 来源只是 app 已冻结的业务输入；实际授权根另存审计，不接受请求自报 root。
create table skill.onboarding_skill_import_receipt (
    id bigint generated always as identity primary key,
    tenant_id varchar(128) not null,
    principal_id varchar(128) not null,
    session_id varchar(40) not null,
    plan_sha256 char(64) not null check (plan_sha256 ~ '^[0-9a-f]{64}$'),
    operation_id varchar(256) not null,
    source jsonb not null check (jsonb_typeof(source) = 'object'),
    source_sha256 char(64) not null check (source_sha256 ~ '^[0-9a-f]{64}$'),
    request_sha256 char(64) not null check (request_sha256 ~ '^[0-9a-f]{64}$'),
    binding_refs jsonb not null check (jsonb_typeof(binding_refs) = 'array'),
    skill_code varchar(64) not null,
    skill_version_no integer not null check (skill_version_no > 0),
    skill_version_id bigint not null,
    skill_sha256 char(64) not null check (skill_sha256 ~ '^[0-9a-f]{64}$'),
    authority jsonb not null check (jsonb_typeof(authority) = 'object'),
    created_at timestamptz not null default now(),
    unique (tenant_id, session_id, plan_sha256, operation_id),
    unique (tenant_id, skill_version_id),
    foreign key (tenant_id, skill_version_id)
        references skill.skill_version(tenant_id, id)
);

create index onboarding_skill_import_owner
    on skill.onboarding_skill_import_receipt(tenant_id, principal_id, session_id, plan_sha256);

-- 不允许把人工改过的草稿重新盖章为原导入事实；更改计划必须使用新的 planSha256。
create function skill.protect_onboarding_import_receipt() returns trigger language plpgsql as $$
begin
    raise exception 'onboarding skill import receipt is immutable';
end
$$;
create trigger immutable_onboarding_import_receipt
    before update or delete on skill.onboarding_skill_import_receipt
    for each row execute function skill.protect_onboarding_import_receipt();
