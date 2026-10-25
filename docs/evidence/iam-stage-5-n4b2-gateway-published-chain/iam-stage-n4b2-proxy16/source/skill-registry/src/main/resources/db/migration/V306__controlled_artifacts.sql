-- 业务归属与生命周期以PG为权威；物理存储定位仅供infra，不进入执行信封。
CREATE TABLE skill.artifact (
 artifact_id varchar(36) PRIMARY KEY CHECK (artifact_id ~ '^art_[a-f0-9]{32}$'),
 tenant_id varchar(128) NOT NULL, principal_id varchar(128) NOT NULL,
 run_id varchar(128) NOT NULL, call_id bigint NOT NULL CHECK (call_id>0),
 bucket varchar(128) NOT NULL, object_key text NOT NULL,
 state varchar(24) NOT NULL CHECK (state IN ('UPLOADING','READY','CLEANUP_PENDING','DELETED')),
 content_type varchar(128) NOT NULL, size_bytes bigint CHECK (size_bytes BETWEEN 0 AND 67108864),
 sha256 varchar(64) CHECK (sha256 ~ '^[a-f0-9]{64}$'),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz NOT NULL, cleanup_after timestamptz NOT NULL,
 cleanup_attempts integer NOT NULL DEFAULT 0, cleanup_owner varchar(64),cleanup_lease_until timestamptz,
 last_error_code varchar(64),deleted_at timestamptz,
 UNIQUE(tenant_id,artifact_id),UNIQUE(bucket,object_key),
 CHECK (state<>'READY' OR (sha256 IS NOT NULL AND size_bytes IS NOT NULL))
);
CREATE INDEX artifact_owner_run ON skill.artifact(tenant_id,principal_id,run_id,artifact_id);
CREATE INDEX artifact_cleanup_due ON skill.artifact(state,cleanup_after,expires_at) WHERE state<>'DELETED';
-- 清理任务只能删除上传前登记的固定键。禁止事后改归属/定位造成跨租户误删。
CREATE FUNCTION skill.protect_artifact_identity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF (NEW.artifact_id,NEW.tenant_id,NEW.principal_id,NEW.run_id,NEW.call_id,NEW.bucket,NEW.object_key)
 IS DISTINCT FROM (OLD.artifact_id,OLD.tenant_id,OLD.principal_id,OLD.run_id,OLD.call_id,OLD.bucket,OLD.object_key)
 THEN RAISE EXCEPTION 'artifact ownership and storage binding are immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_artifact_identity BEFORE UPDATE ON skill.artifact FOR EACH ROW EXECUTE FUNCTION skill.protect_artifact_identity();
