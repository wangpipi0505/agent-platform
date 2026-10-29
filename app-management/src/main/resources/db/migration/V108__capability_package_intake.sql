-- EX-10 Phase 1: immutable, tenant-local declaration intake only.
-- No application/skill binding or execution permission is created by this table.
CREATE TABLE app.capability_package_version (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id varchar(128) NOT NULL,
    package_id varchar(64) NOT NULL,
    package_version varchar(128) NOT NULL,
    package_sha256 char(64) NOT NULL CHECK (package_sha256 ~ '^[0-9a-f]{64}$'),
    declaration jsonb NOT NULL CHECK (jsonb_typeof(declaration) = 'object'),
    software_id text NOT NULL,
    software_version text,
    binding_status varchar(32) NOT NULL DEFAULT 'UNRESOLVED' CHECK (binding_status = 'UNRESOLVED'),
    created_by varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, package_id, package_version)
);
-- Future verified bindings require their own migration and service flow; do not overwrite
-- a registered package version in place or reinterpret UNRESOLVED as runtime readiness.
