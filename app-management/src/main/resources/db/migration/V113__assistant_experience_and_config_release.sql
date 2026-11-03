-- INT-03 子步1：体验描述、接入档案与配置发布的最小存储。
--
-- 设计与边界（对齐 contracts/configuration-profile.md §2/§3/§4 与 assistant-integration.md §6/§8）：
-- 1) 三个对象都走"父表 + 不可变修订"：修订只追加，旧行永久保留；状态在父表上，不属于修订内容。
-- 2) 激活是 CAS + 行锁：同一 (tenant, app, scope) 同时只有一个 ACTIVE 指针，切换时旧指针置 SUPERSEDED。
-- 3) 摘要只覆盖声明与版本引用，不含任何 secret 值；凭据只出现 credential_policy_ref / secret_ref 这类引用。
-- 4) 本迁移不触碰 app.application / app.app_version / 发布方法（与 MNT-06 发布子步串行）。

CREATE TABLE app.assistant_experience (
 tenant_id varchar(128) NOT NULL,
 app_code varchar(64) NOT NULL,
 current_rev integer NOT NULL DEFAULT 0 CHECK(current_rev >= 0),
 status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','APPROVED','DISABLED')),
 created_by varchar(128) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id, app_code),
 FOREIGN KEY(tenant_id, app_code) REFERENCES app.application(tenant_id, code)
);

-- 体验修订：标题/帮助说明属于"可单独发体验修订"的范围，语义字段（能力、提示词引用）变更仍受发布门禁约束。
CREATE TABLE app.assistant_experience_revision (
 tenant_id varchar(128) NOT NULL,
 app_code varchar(64) NOT NULL,
 experience_rev integer NOT NULL CHECK(experience_rev > 0),
 title varchar(128) NOT NULL,
 welcome_text varchar(2048) NOT NULL DEFAULT '',
 capability_descriptions jsonb NOT NULL DEFAULT '[]' CHECK(jsonb_typeof(capability_descriptions) = 'array'),
 prompt_refs jsonb NOT NULL DEFAULT '[]' CHECK(jsonb_typeof(prompt_refs) = 'array'),
 input_presentation jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(input_presentation) = 'object'),
 result_presentation jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(result_presentation) = 'object'),
 help_refs jsonb NOT NULL DEFAULT '[]' CHECK(jsonb_typeof(help_refs) = 'array'),
 declaration_sha256 char(64) NOT NULL CHECK(declaration_sha256 ~ '^[0-9a-f]{64}$'),
 created_by varchar(128) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id, app_code, experience_rev),
 FOREIGN KEY(tenant_id, app_code) REFERENCES app.assistant_experience(tenant_id, app_code)
);

CREATE TABLE app.integration_profile (
 tenant_id varchar(128) NOT NULL,
 profile_id varchar(64) NOT NULL,
 app_code varchar(64) NOT NULL,
 current_rev integer NOT NULL DEFAULT 0 CHECK(current_rev >= 0),
 status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','APPROVED','DISABLED')),
 created_by varchar(128) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id, profile_id),
 FOREIGN KEY(tenant_id, app_code) REFERENCES app.application(tenant_id, code)
);

-- 接入档案修订：mode ∈ L0..L3；allowedOrigins 精确匹配；contextSchema 只含声明字段；
-- credentialPolicyRef 是引用而不是凭据本身，禁止任意回调 JS/HTML/URL 进入本表。
CREATE TABLE app.integration_profile_revision (
 tenant_id varchar(128) NOT NULL,
 profile_id varchar(64) NOT NULL,
 profile_rev integer NOT NULL CHECK(profile_rev > 0),
 biz_system_code varchar(64) NOT NULL,
 environment varchar(32) NOT NULL CHECK(environment ~ '^[a-z][a-z0-9-]{0,31}$'),
 mode varchar(2) NOT NULL CHECK(mode IN ('L0','L1','L2','L3')),
 allowed_origins jsonb NOT NULL DEFAULT '[]' CHECK(jsonb_typeof(allowed_origins) = 'array'),
 context_schema jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(context_schema) = 'object'),
 credential_policy_ref varchar(512) NOT NULL DEFAULT '',
 experience_ref jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(experience_ref) = 'object'),
 declaration_sha256 char(64) NOT NULL CHECK(declaration_sha256 ~ '^[0-9a-f]{64}$'),
 created_by varchar(128) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id, profile_id, profile_rev),
 FOREIGN KEY(tenant_id, profile_id) REFERENCES app.integration_profile(tenant_id, profile_id)
);

-- 配置发布：不可变组合快照（应用版本 + 体验修订 + 接入档案修订 + 连接引用）。
-- candidate_sha256 取自 app.app_version（不信任客户端自报）；digest 只覆盖声明与版本引用。
CREATE TABLE app.integration_config_release (
 tenant_id varchar(128) NOT NULL,
 release_id varchar(64) NOT NULL,
 app_code varchar(64) NOT NULL,
 app_version integer NOT NULL CHECK(app_version > 0),
 candidate_sha256 char(64) NOT NULL CHECK(candidate_sha256 ~ '^[0-9a-f]{64}$'),
 experience_rev integer NOT NULL CHECK(experience_rev > 0),
 integration_profile_id varchar(64) NOT NULL,
 integration_profile_rev integer NOT NULL CHECK(integration_profile_rev > 0),
 connection_profile_refs jsonb NOT NULL DEFAULT '[]' CHECK(jsonb_typeof(connection_profile_refs) = 'array'),
 activation_scope varchar(64) NOT NULL CHECK(activation_scope ~ '^[a-z][a-z0-9-]{0,31}$'),
 status varchar(16) NOT NULL DEFAULT 'DRAFT'
   CHECK(status IN ('DRAFT','VALIDATED','APPROVED','ACTIVE','SUPERSEDED','DISABLED')),
 digest char(64) CHECK(digest ~ '^[0-9a-f]{64}$'),
 created_by varchar(128) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id, release_id),
 FOREIGN KEY(tenant_id, app_code, app_version) REFERENCES app.app_version(tenant_id, app_code, version_no),
 FOREIGN KEY(tenant_id, app_code, experience_rev) REFERENCES app.assistant_experience_revision(tenant_id, app_code, experience_rev),
 FOREIGN KEY(tenant_id, integration_profile_id, integration_profile_rev) REFERENCES app.integration_profile_revision(tenant_id, profile_id, profile_rev)
);

-- 激活指针：同一 (tenant, app, scope) 只有一行，CAS 更新；切换时被替换的发布置 SUPERSEDED。
CREATE TABLE app.integration_config_release_activation (
 tenant_id varchar(128) NOT NULL,
 app_code varchar(64) NOT NULL,
 scope varchar(64) NOT NULL,
 release_id varchar(64) NOT NULL,
 activated_by varchar(128) NOT NULL,
 activated_at timestamptz NOT NULL DEFAULT now(),
 activation_rev integer NOT NULL DEFAULT 1 CHECK(activation_rev > 0),
 PRIMARY KEY(tenant_id, app_code, scope),
 FOREIGN KEY(tenant_id, release_id) REFERENCES app.integration_config_release(tenant_id, release_id)
);

CREATE INDEX integration_config_release_status ON app.integration_config_release(tenant_id, app_code, status);
CREATE INDEX integration_profile_revision_env ON app.integration_profile_revision(tenant_id, environment);

-- 回滚（与既有迁移一致：手写，保留说明）：
-- DROP TABLE app.integration_config_release_activation;
-- DROP TABLE app.integration_config_release;
-- DROP TABLE app.integration_profile_revision;
-- DROP TABLE app.integration_profile;
-- DROP TABLE app.assistant_experience_revision;
-- DROP TABLE app.assistant_experience;
