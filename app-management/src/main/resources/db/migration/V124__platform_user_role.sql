-- R-39: platform-owned build grants, configurable page access and integration mappings.
-- Rollback (before data is in use): drop the role_mapping column, then drop
-- app.capability_page, app.role_capability and app.platform_user_role.

CREATE TABLE app.platform_user_role (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 tenant_id varchar(128) NOT NULL CHECK (btrim(tenant_id) <> ''),
 subject varchar(128) NOT NULL CHECK (btrim(subject) <> ''),
 platform_role varchar(32) NOT NULL CHECK (platform_role IN ('ADMIN','BUILDER','OPERATOR','USER')),
 -- Empty scope grants all applications in this tenant. Store stable app codes only.
 app_scope jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (
   CASE WHEN jsonb_typeof(app_scope) = 'array' THEN
     octet_length(app_scope::text) <= 65536
     AND NOT jsonb_path_exists(app_scope, 'strict $[*] ? (@.type() != "string")')
   ELSE false END
 ),
 status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
 granted_by varchar(128) NOT NULL CHECK (btrim(granted_by) <> ''),
 granted_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz CHECK (expires_at > granted_at),
 revoked_at timestamptz,
 updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE (tenant_id, subject, platform_role),
 CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);

CREATE TABLE app.role_capability (
 role varchar(32) NOT NULL CHECK (role IN ('ADMIN','BUILDER','OPERATOR','USER')),
 capability varchar(64) NOT NULL CHECK (capability ~ '^[a-z][a-z0-9]*([.][a-z][a-z0-9]*)+$'),
 enabled boolean NOT NULL DEFAULT true,
 updated_by varchar(128) NOT NULL CHECK (btrim(updated_by) <> ''),
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY (role, capability)
);

CREATE TABLE app.capability_page (
 capability varchar(64) NOT NULL CHECK (capability ~ '^[a-z][a-z0-9]*([.][a-z][a-z0-9]*)+$'),
 page_route varchar(256) NOT NULL CHECK (left(page_route, 1) = '/'),
 enabled boolean NOT NULL DEFAULT true,
 updated_by varchar(128) NOT NULL CHECK (btrim(updated_by) <> ''),
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY (capability, page_route)
);

ALTER TABLE app.integration_credential
 ADD COLUMN IF NOT EXISTS role_mapping jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE app.integration_credential
 ADD CONSTRAINT integration_credential_role_mapping_check CHECK (
   CASE WHEN jsonb_typeof(role_mapping) = 'object' THEN
     octet_length(role_mapping::text) <= 16384
     AND NOT jsonb_path_exists(role_mapping, 'strict $.* ? (
       @.type() != "string" ||
       (@ != "AGENT_VIEWER" && @ != "AGENT_OPERATOR" && @ != "AGENT_ADMIN")
     )')
   ELSE false END
 );
