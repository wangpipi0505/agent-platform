CREATE SCHEMA IF NOT EXISTS app;
CREATE TABLE app.application (
 tenant_id varchar(128) NOT NULL, code varchar(64) NOT NULL, name varchar(128) NOT NULL,
 type varchar(16) NOT NULL CHECK(type IN ('CHAT','FLOW')), description text NOT NULL DEFAULT '',
 domain varchar(64) NOT NULL DEFAULT '', biz_category varchar(64) NOT NULL DEFAULT '', tags jsonb NOT NULL DEFAULT '[]',
 status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED','DISABLED','ARCHIVED')),
 draft_rev bigint NOT NULL DEFAULT 1, config jsonb NOT NULL DEFAULT '{}', definition jsonb NOT NULL DEFAULT '{}', layout jsonb NOT NULL DEFAULT '{}',
 current_version_no integer, created_by varchar(128) NOT NULL, updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,code)
);
CREATE TABLE app.app_version (
 id bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
 tenant_id varchar(128) NOT NULL, app_code varchar(64) NOT NULL, version_no integer NOT NULL,
 draft_rev bigint NOT NULL, candidate_sha256 char(64) NOT NULL, snapshot jsonb NOT NULL,
 eval_report_id varchar(128) NOT NULL, published_by varchar(128) NOT NULL, published_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,app_code,version_no), UNIQUE(tenant_id,app_code,draft_rev,candidate_sha256),
 FOREIGN KEY(tenant_id,app_code) REFERENCES app.application(tenant_id,code)
);
CREATE TABLE app.publish_report (
 tenant_id varchar(128) NOT NULL, id varchar(64) NOT NULL, app_code varchar(64) NOT NULL,
 draft_rev bigint NOT NULL, candidate_sha256 char(64) NOT NULL, eval_report_id varchar(128),
 checks jsonb NOT NULL, passed boolean NOT NULL, expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,id)
);
CREATE TABLE app.publish_commit (
 tenant_id varchar(128) NOT NULL, app_code varchar(64) NOT NULL, request_no varchar(128) NOT NULL,
 precheck_id varchar(64) NOT NULL, version_no integer NOT NULL, draft_rev bigint NOT NULL, candidate_sha256 char(64) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,app_code,request_no),
 FOREIGN KEY(tenant_id,app_code,version_no) REFERENCES app.app_version(tenant_id,app_code,version_no)
);
CREATE TABLE app.skill_reference (
 tenant_id varchar(128) NOT NULL, app_code varchar(64) NOT NULL, app_version_no integer NOT NULL,
 skill_code varchar(64) NOT NULL, skill_version integer NOT NULL,
 PRIMARY KEY(tenant_id,app_code,app_version_no,skill_code),
 FOREIGN KEY(tenant_id,app_code,app_version_no) REFERENCES app.app_version(tenant_id,app_code,version_no)
);
CREATE TABLE app.lifecycle_log (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, tenant_id varchar(128) NOT NULL, app_code varchar(64) NOT NULL,
 actor varchar(128) NOT NULL, action varchar(32) NOT NULL, from_state varchar(16) NOT NULL,to_state varchar(16) NOT NULL,
 detail jsonb NOT NULL DEFAULT '{}', created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE app.classification (
 tenant_id varchar(128) NOT NULL, kind varchar(32) NOT NULL CHECK(kind IN ('DOMAIN','BIZ_CATEGORY','TAG')),
 code varchar(64) NOT NULL, name varchar(128) NOT NULL, PRIMARY KEY(tenant_id,kind,code)
);
CREATE TABLE app.prompt_template (
 tenant_id varchar(128) NOT NULL, code varchar(64) NOT NULL, name varchar(128) NOT NULL, description text NOT NULL DEFAULT '',
 current_version integer NOT NULL DEFAULT 1, PRIMARY KEY(tenant_id,code)
);
CREATE TABLE app.prompt_template_version (
 tenant_id varchar(128) NOT NULL, template_code varchar(64) NOT NULL, version_no integer NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED','ARCHIVED')),
 content text NOT NULL, variables jsonb NOT NULL DEFAULT '[]', output_contract jsonb NOT NULL DEFAULT '{}',sha256 char(64) NOT NULL,
 published_by varchar(128),published_at timestamptz, PRIMARY KEY(tenant_id,template_code,version_no),
 FOREIGN KEY(tenant_id,template_code) REFERENCES app.prompt_template(tenant_id,code)
);
-- Immutable published payloads are protected against accidental updates in addition to application-level append-only writes.
CREATE FUNCTION app.protect_published() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_TABLE_NAME = 'app_version' OR OLD.status = 'PUBLISHED' THEN RAISE EXCEPTION 'published version is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_app_version BEFORE UPDATE OR DELETE ON app.app_version FOR EACH ROW EXECUTE FUNCTION app.protect_published();
CREATE TRIGGER immutable_template_version BEFORE UPDATE OR DELETE ON app.prompt_template_version FOR EACH ROW EXECUTE FUNCTION app.protect_published();

