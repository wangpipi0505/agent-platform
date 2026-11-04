-- CR05：用户专属接入会话、按计划冻结的真实步骤收据及请求幂等；不保存端点或凭据。
create table app.onboarding_session (
 tenant_id varchar(128) not null, session_id varchar(40) not null,
 principal_id varchar(128) not null, template_code varchar(64) not null, template_rev int not null,
 app_code varchar(64) not null, app_name varchar(128) not null,
 rev int not null default 1 check(rev>0), status varchar(24) not null default 'DRAFT',
 template_snapshot jsonb not null, plan jsonb not null, plan_sha256 char(64) not null,
 application jsonb, create_request_id varchar(128) not null, create_sha256 char(64) not null,
 created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
 primary key(tenant_id,session_id), unique(tenant_id,principal_id,create_request_id),
 check(status in ('DRAFT','ASSEMBLED','VERIFIED','PUBLISHED','CANCELLED'))
);
create table app.onboarding_session_step (
 tenant_id varchar(128) not null, session_id varchar(40) not null, plan_sha256 char(64) not null,
 step varchar(16) not null check(step in ('ASSEMBLE','VERIFY','PUBLISH')),
 status varchar(16) not null check(status in ('RUNNING','SUCCEEDED','FAILED')),
 request_id varchar(128) not null, request_sha256 char(64) not null,
 execution_token varchar(36) not null, lease_until timestamptz not null,
 receipt jsonb, error jsonb, created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
 primary key(tenant_id,session_id,plan_sha256,step),
 foreign key(tenant_id,session_id) references app.onboarding_session(tenant_id,session_id)
);
create table app.onboarding_session_request (
 tenant_id varchar(128) not null, session_id varchar(40) not null, request_id varchar(128) not null,
 request_sha256 char(64) not null, response jsonb not null, created_at timestamptz not null default now(),
 primary key(tenant_id,session_id,request_id),
 foreign key(tenant_id,session_id) references app.onboarding_session(tenant_id,session_id)
);
create index onboarding_session_owner on app.onboarding_session(tenant_id,principal_id,created_at desc);
