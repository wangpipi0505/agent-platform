-- 单包版本首次应用计划不可覆写；复制应用仅复制来源关联，不复制创建收据或发布许可。
ALTER TABLE app.capability_package_version
    ADD COLUMN application_plan_sha256 char(64),
    ADD COLUMN application_receipt jsonb,
    ADD CONSTRAINT capability_application_plan_complete CHECK (
        (application_plan_sha256 IS NULL AND application_receipt IS NULL)
        OR (application_plan_sha256 IS NOT NULL AND application_receipt IS NOT NULL
            AND application_plan_sha256 ~ '^[0-9a-f]{64}$' AND jsonb_typeof(application_receipt)='array')),
    ADD CONSTRAINT capability_package_tenant_id_unique UNIQUE(tenant_id,id);
CREATE TABLE app.capability_app_binding (
    tenant_id varchar(128) NOT NULL,
    app_id bigint NOT NULL,
    app_code varchar(64) NOT NULL,
    package_version_id bigint NOT NULL,
    source jsonb NOT NULL CHECK(jsonb_typeof(source)='object'),
    PRIMARY KEY(tenant_id,app_id,package_version_id),
    FOREIGN KEY(tenant_id,app_id,app_code) REFERENCES app.application(tenant_id,id,code) ON DELETE CASCADE,
    FOREIGN KEY(tenant_id,package_version_id) REFERENCES app.capability_package_version(tenant_id,id)
);
