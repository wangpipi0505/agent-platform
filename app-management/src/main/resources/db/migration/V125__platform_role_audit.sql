-- R-39: store authorization changes with the grant transaction.
-- Rollback before use: drop app.platform_role_audit, then app.reject_platform_role_audit_mutation().

CREATE TABLE app.platform_role_audit (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 tenant_id varchar(128) NOT NULL CHECK (btrim(tenant_id) <> ''),
 subject varchar(128) NOT NULL CHECK (btrim(subject) <> ''),
 platform_role varchar(32) NOT NULL CHECK (platform_role IN ('ADMIN','BUILDER','OPERATOR','USER')),
 event_type varchar(48) NOT NULL CHECK (event_type ~ '^[A-Z][A-Z0-9_]+$'),
 actor_type varchar(16) NOT NULL CHECK (actor_type IN ('SYSTEM','USER')),
 actor_subject varchar(128),
 occurred_at timestamptz NOT NULL DEFAULT now(),
 CHECK ((actor_type = 'USER') = (actor_subject IS NOT NULL))
);

CREATE INDEX platform_role_audit_subject_idx
 ON app.platform_role_audit(tenant_id, subject, occurred_at DESC, id DESC);

CREATE FUNCTION app.reject_platform_role_audit_mutation() RETURNS trigger
 LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'platform_role_audit is append-only';
END;
$$;

CREATE TRIGGER platform_role_audit_append_only
 BEFORE UPDATE OR DELETE ON app.platform_role_audit
 FOR EACH ROW EXECUTE FUNCTION app.reject_platform_role_audit_mutation();
