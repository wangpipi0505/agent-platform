-- 一个接入计划拥有独立、不可变的原始规格与解析来源；不覆盖包的全局解析和应用回执。
create table app.onboarding_import_source (
    tenant_id varchar(128) not null,
    source_id varchar(64) not null,
    session_id varchar(40) not null,
    principal_id varchar(128) not null,
    plan_sha256 char(64) not null,
    package_version_id bigint not null,
    package_sha256 char(64) not null,
    document_sha256 char(64) not null,
    frozen_plan jsonb not null,
    snapshot jsonb not null,
    snapshot_sha256 char(64) not null,
    status varchar(16) not null default 'FROZEN' check (status in ('FROZEN','RESOLVED')),
    resolved_source jsonb,
    created_at timestamptz not null default now(),
    resolved_at timestamptz,
    primary key (tenant_id, source_id),
    unique (tenant_id, session_id, plan_sha256),
    foreign key (tenant_id, session_id) references app.onboarding_session(tenant_id, session_id),
    check ((status='FROZEN' and resolved_source is null and resolved_at is null)
        or (status='RESOLVED' and resolved_source is not null and resolved_at is not null))
);

alter table app.capability_app_binding add column session_source_id varchar(64);
alter table app.capability_app_binding add constraint capability_app_session_source_fk
    foreign key (tenant_id, session_source_id)
    references app.onboarding_import_source(tenant_id, source_id);

create function app.protect_onboarding_import_source() returns trigger language plpgsql as $$
begin
    if TG_OP='DELETE' then
        raise exception 'onboarding import source is immutable';
    end if;
    if OLD.status='RESOLVED' or NEW.status<>'RESOLVED'
       or (to_jsonb(NEW)-array['status','resolved_source','resolved_at'])
          is distinct from (to_jsonb(OLD)-array['status','resolved_source','resolved_at']) then
        raise exception 'onboarding import source only allows one resolution';
    end if;
    return NEW;
end $$;
create trigger onboarding_import_source_immutable before update or delete
    on app.onboarding_import_source for each row
    execute function app.protect_onboarding_import_source();
