-- INT-02 子步1（ADMIN 批准连接与环境绑定目录）。对象与字段按 contracts/configuration-profile.md §2，
-- 归属：INT-02 实现 ConnectionProfile/EnvironmentBinding，INT-03 只引用不重建。
--
-- 语义（定死）：
--  * 修订不可变：profile_rev / binding_rev 每次变更插入新行，旧行永久保留；服务层只追加，不做原地更新。
--  * connection_profile / environment_binding 只保存身份行与"当前修订指针 + 生命周期状态"；
--    status 不是修订内容（批准/停用不重写历史修订），业务字段全部在 *_version / *_revision 中。
--  * environment_binding_revision 以 (tenant_id,connection_profile_id,connection_profile_rev) 外键指向
--    具体连接修订：引用必须落在已存在的不可变修订上，天然阻断"引用未来修订/悬空修订/被就地改写的修订"。
--  * config_write_request 承载幂等 requestId：同 (tenant_id,request_id) 只产生一次对象/修订，重放返回首次
--    结果；载荷摘要不同则拒绝（防止同号换内容）。它只证明"这次写请求已处理"，不是业务事实库。
--  * 不保存任何明文凭据：secret_ref 仅引用（scheme:ref 形式，应用层校验），凭据值不进本迁移任何列。
--  * 迁移编号按 D2 批核发（AP app-management 现最大 V110 → 本批 V111）。
--
-- 回滚（down，需人工执行；先确认无在途写入窗口）：
-- DROP TABLE IF EXISTS app.config_write_request;
-- DROP TABLE IF EXISTS app.environment_binding_revision;
-- DROP TABLE IF EXISTS app.environment_binding;
-- DROP TABLE IF EXISTS app.connection_profile_version;
-- DROP TABLE IF EXISTS app.connection_profile;

CREATE TABLE app.connection_profile (
    tenant_id varchar(128) NOT NULL,
    profile_id varchar(64) NOT NULL,
    current_rev int NOT NULL CHECK (current_rev > 0),
    status varchar(16) NOT NULL CHECK (status IN ('DRAFT', 'APPROVED', 'DISABLED')),
    created_by varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, profile_id)
);

-- protocol 取值即 capability-package.schema.json v1 的执行方式枚举（本对象不新建第五种协议 kind）；
-- PROCESS 仍是冻结留白，登记合法但执行归 T22C，本表不做执行侧承诺。
CREATE TABLE app.connection_profile_version (
    tenant_id varchar(128) NOT NULL,
    profile_id varchar(64) NOT NULL,
    profile_rev int NOT NULL CHECK (profile_rev > 0),
    protocol varchar(24) NOT NULL CHECK (protocol IN ('HTTP_SYNC', 'HTTP_ASYNC', 'MCP', 'ALGORITHM', 'PROCESS')),
    operation_allowlist jsonb NOT NULL CHECK (jsonb_typeof(operation_allowlist) = 'array'),
    endpoint_policy jsonb NOT NULL CHECK (jsonb_typeof(endpoint_policy) = 'object'),
    auth_scheme jsonb NOT NULL CHECK (jsonb_typeof(auth_scheme) = 'object'),
    identity_policy jsonb NOT NULL CHECK (jsonb_typeof(identity_policy) = 'object'),
    input_contract_refs jsonb NOT NULL CHECK (jsonb_typeof(input_contract_refs) = 'array'),
    output_contract_refs jsonb NOT NULL CHECK (jsonb_typeof(output_contract_refs) = 'array'),
    allowed_uses jsonb NOT NULL CHECK (jsonb_typeof(allowed_uses) = 'array'),
    declaration_sha256 char(64) NOT NULL CHECK (declaration_sha256 ~ '^[0-9a-f]{64}$'),
    created_by varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, profile_id, profile_rev),
    FOREIGN KEY (tenant_id, profile_id) REFERENCES app.connection_profile (tenant_id, profile_id)
);

CREATE TABLE app.environment_binding (
    tenant_id varchar(128) NOT NULL,
    binding_id varchar(64) NOT NULL,
    current_rev int NOT NULL CHECK (current_rev > 0),
    status varchar(16) NOT NULL CHECK (status IN ('DRAFT', 'APPROVED', 'DISABLED')),
    created_by varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, binding_id)
);

-- base_endpoint 受 URI/来源策略限制（应用层按 https、本机开发回环 http 校验）；secret_ref 只存引用；
-- 健康探针只读（health_policy 只声明，不由此迁移触发任何外部调用）。
CREATE TABLE app.environment_binding_revision (
    tenant_id varchar(128) NOT NULL,
    binding_id varchar(64) NOT NULL,
    binding_rev int NOT NULL CHECK (binding_rev > 0),
    environment varchar(64) NOT NULL CHECK (environment ~ '^[a-z][a-z0-9-]{0,31}$'),
    connection_profile_id varchar(64) NOT NULL,
    connection_profile_rev int NOT NULL CHECK (connection_profile_rev > 0),
    base_endpoint varchar(2048) NOT NULL CHECK (base_endpoint ~ '^https?://'),
    secret_ref varchar(512) NOT NULL,
    runtime_ref jsonb NOT NULL CHECK (jsonb_typeof(runtime_ref) = 'object'),
    origin_policy jsonb NOT NULL CHECK (jsonb_typeof(origin_policy) = 'object'),
    health_policy jsonb NOT NULL CHECK (jsonb_typeof(health_policy) = 'object'),
    declaration_sha256 char(64) NOT NULL CHECK (declaration_sha256 ~ '^[0-9a-f]{64}$'),
    created_by varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, binding_id, binding_rev),
    FOREIGN KEY (tenant_id, binding_id) REFERENCES app.environment_binding (tenant_id, binding_id),
    FOREIGN KEY (tenant_id, connection_profile_id, connection_profile_rev)
        REFERENCES app.connection_profile_version (tenant_id, profile_id, profile_rev)
);

CREATE TABLE app.config_write_request (
    tenant_id varchar(128) NOT NULL,
    request_id varchar(64) NOT NULL,
    operation varchar(64) NOT NULL,
    target varchar(64) NOT NULL,
    request_sha256 char(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    response jsonb NOT NULL CHECK (jsonb_typeof(response) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, request_id)
);
