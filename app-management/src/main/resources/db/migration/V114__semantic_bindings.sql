-- SEM-02.2 阶段1：应用语义绑定目录（ADMIN 编辑面）。
--
-- 边界（对齐 contracts/semantic-runtime.md §2 bindingRef 与 SEM-02 卡）：
-- 1) 语义绑定只保存"应用 -> 某个 READY release 上已批准语义扩展 -> 技能精确版本"的引用，
--    不复制本体内容、不复制技能定义、不保存连接信息；与 ConfigRelease 的组合引用由阶段2接线。
-- 2) 父表 + 不可变修订：修订只追加，旧行永久保留；引用摘要（release/extension/skill）变化
--    必须产生新修订，禁止原地改写既有声明。
-- 3) 四元组中的 contentDigest 与扩展摘要、技能摘要都按小写 SHA-256 存储；缺省引用不写空串，
--    而是 NULL（"未交付"与"空值"是两回事）。
-- 4) 本迁移不触碰 app.application / app.app_version / 发布方法（与 A 席子步2串行）。

CREATE TABLE app.semantic_binding (
 tenant_id varchar(128) NOT NULL,
 app_code varchar(64) NOT NULL,
 binding_id varchar(64) NOT NULL,
 current_rev integer NOT NULL DEFAULT 0 CHECK(current_rev >= 0),
 status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
 created_by varchar(128) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id, binding_id),
 FOREIGN KEY(tenant_id, app_code) REFERENCES app.application(tenant_id, code)
);

CREATE INDEX semantic_binding_app_idx ON app.semantic_binding(tenant_id, app_code);

-- 语义绑定修订：releaseRef 四元组 + 扩展引用 + 技能精确版本 + 只读能力集合。
CREATE TABLE app.semantic_binding_revision (
 tenant_id varchar(128) NOT NULL,
 binding_id varchar(64) NOT NULL,
 binding_rev integer NOT NULL CHECK(binding_rev > 0),
 release_space_code varchar(64) NOT NULL,
 release_id uuid NOT NULL,
 release_version varchar(64) NOT NULL,
 release_content_digest char(64) NOT NULL CHECK(release_content_digest ~ '^[0-9a-f]{64}$'),
 extension_id varchar(64),
 extension_digest char(64) NOT NULL CHECK(extension_digest ~ '^[0-9a-f]{64}$'),
 op_code varchar(80) NOT NULL,
 skill_code varchar(64) NOT NULL,
 skill_version integer NOT NULL CHECK(skill_version >= 1),
 skill_sha256 char(64) CHECK(skill_sha256 IS NULL OR skill_sha256 ~ '^[0-9a-f]{64}$'),
 profiles jsonb NOT NULL CHECK(jsonb_typeof(profiles) = 'array' AND jsonb_array_length(profiles) > 0),
 declaration_sha256 char(64) NOT NULL CHECK(declaration_sha256 ~ '^[0-9a-f]{64}$'),
 created_by varchar(128) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id, binding_id, binding_rev),
 FOREIGN KEY(tenant_id, binding_id) REFERENCES app.semantic_binding(tenant_id, binding_id)
);

-- 手写回滚（Flyway 社区版无 undo）：
-- DROP TABLE app.semantic_binding_revision;
-- DROP INDEX app.semantic_binding_app_idx;
-- DROP TABLE app.semantic_binding;
