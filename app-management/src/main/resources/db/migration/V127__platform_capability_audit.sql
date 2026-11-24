CREATE TABLE app.platform_capability_audit (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 target_table text NOT NULL CHECK (target_table IN ('role_capability','capability_page')),
 operation text NOT NULL CHECK (operation IN ('INSERT','UPDATE','DELETE')),
 capability varchar(64) NOT NULL,
 database_actor text NOT NULL,
 before_state jsonb,
 after_state jsonb,
 occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE TRIGGER platform_capability_audit_append_only
 BEFORE UPDATE OR DELETE ON app.platform_capability_audit
 FOR EACH ROW EXECUTE FUNCTION app.reject_platform_role_audit_mutation();

CREATE FUNCTION app.audit_platform_capability_change() RETURNS trigger
 LANGUAGE plpgsql AS $$
DECLARE
 previous jsonb;
 current_value jsonb;
BEGIN
 IF TG_OP <> 'INSERT' THEN previous := to_jsonb(OLD); END IF;
 IF TG_OP <> 'DELETE' THEN current_value := to_jsonb(NEW); END IF;
 IF previous IS NOT DISTINCT FROM current_value THEN RETURN NULL; END IF;
 INSERT INTO app.platform_capability_audit(target_table,operation,capability,database_actor,before_state,after_state)
 VALUES(TG_TABLE_NAME,TG_OP,coalesce(current_value,previous)->>'capability',current_user,previous,current_value);
 RETURN NULL;
END;
$$;

CREATE TRIGGER platform_role_capability_changes
 AFTER INSERT OR UPDATE OR DELETE ON app.role_capability
 FOR EACH ROW EXECUTE FUNCTION app.audit_platform_capability_change();

CREATE TRIGGER platform_capability_page_changes
 AFTER INSERT OR UPDATE OR DELETE ON app.capability_page
 FOR EACH ROW EXECUTE FUNCTION app.audit_platform_capability_change();
