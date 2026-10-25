CREATE SCHEMA IF NOT EXISTS skill;
CREATE TABLE skill.skill (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,tenant_id varchar(128) NOT NULL,code varchar(64) NOT NULL,
 name varchar(128) NOT NULL,category varchar(64) NOT NULL DEFAULT '',tags jsonb NOT NULL DEFAULT '[]',
 status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED','DISABLED')),
 current_version_id bigint,updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,code)
);
CREATE TABLE skill.skill_version (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,tenant_id varchar(128) NOT NULL,skill_code varchar(64) NOT NULL,
 version_no integer NOT NULL,status varchar(32) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED','ARCHIVED')),
 contract jsonb NOT NULL,sha256 char(64) NOT NULL,published_by varchar(128),published_at timestamptz,
 UNIQUE(tenant_id,skill_code,version_no),FOREIGN KEY(tenant_id,skill_code) REFERENCES skill.skill(tenant_id,code)
);
CREATE TABLE skill.connection_test (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,tenant_id varchar(128) NOT NULL,skill_version_id bigint NOT NULL REFERENCES skill.skill_version(id),
 candidate_sha256 char(64) NOT NULL,passed boolean NOT NULL,report jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE skill.acceptance (
 id varchar(64) NOT NULL,tenant_id varchar(128) NOT NULL,skill_version_id bigint NOT NULL REFERENCES skill.skill_version(id),
 candidate_sha256 char(64) NOT NULL,passed boolean NOT NULL,hit_rate numeric NOT NULL,report jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(tenant_id,id)
);
CREATE TABLE skill.execution_fact (
 tenant_id varchar(128) NOT NULL,call_id bigint NOT NULL,skill_version_id bigint NOT NULL REFERENCES skill.skill_version(id),
 principal_id varchar(128) NOT NULL,run_id varchar(128) NOT NULL,attempt_id bigint NOT NULL,execution_owner varchar(128) NOT NULL,
 args_digest char(64) NOT NULL,request_snapshot jsonb NOT NULL,status varchar(16) NOT NULL CHECK(status IN ('SUCCEEDED','FAILED','UNKNOWN')),
 result jsonb NOT NULL,duration_ms bigint NOT NULL,is_mock boolean NOT NULL,call_kind varchar(16) NOT NULL CHECK(call_kind IN ('FORMAL','DEBUG','EVALUATION')),
 callback_state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(callback_state IN ('PENDING','DELIVERED','LATE')),
 callback_error text,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(tenant_id,call_id)
);
CREATE INDEX execution_fact_statistics ON skill.execution_fact(tenant_id,skill_version_id,call_kind,is_mock,created_at);
CREATE TABLE skill.external_job_binding (
 tenant_id varchar(128) NOT NULL,call_id bigint NOT NULL,external_job_id varchar(128) NOT NULL,
 status varchar(16) NOT NULL,metadata jsonb NOT NULL DEFAULT '{}',PRIMARY KEY(tenant_id,call_id)
);
CREATE FUNCTION skill.protect_published_contract() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF OLD.status IN ('PUBLISHED','ARCHIVED') THEN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'published skill version cannot be deleted'; END IF;
  IF NEW.contract IS DISTINCT FROM OLD.contract OR NEW.sha256 IS DISTINCT FROM OLD.sha256 OR NEW.version_no IS DISTINCT FROM OLD.version_no THEN RAISE EXCEPTION 'published skill contract is immutable'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_skill_contract BEFORE UPDATE OR DELETE ON skill.skill_version FOR EACH ROW EXECUTE FUNCTION skill.protect_published_contract();
