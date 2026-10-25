-- 发布契约不可逆地冻结，草稿删除必须RETURN OLD，不能被BEFORE DELETE触发器静默取消。
CREATE OR REPLACE FUNCTION skill.protect_published_contract() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF OLD.status IN ('PUBLISHED','ARCHIVED') THEN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'published skill version cannot be deleted'; END IF;
  IF NEW.status NOT IN ('PUBLISHED','ARCHIVED') OR NEW.contract IS DISTINCT FROM OLD.contract OR NEW.sha256 IS DISTINCT FROM OLD.sha256
   OR NEW.version_no IS DISTINCT FROM OLD.version_no OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.skill_code IS DISTINCT FROM OLD.skill_code
   OR NEW.id IS DISTINCT FROM OLD.id OR NEW.published_at IS DISTINCT FROM OLD.published_at OR NEW.published_by IS DISTINCT FROM OLD.published_by
   THEN RAISE EXCEPTION 'published skill contract is immutable'; END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
ALTER TABLE skill.skill_version ADD CONSTRAINT skill_version_tenant_id_unique UNIQUE(tenant_id,id);
ALTER TABLE skill.skill ADD CONSTRAINT current_version_same_tenant FOREIGN KEY(tenant_id,current_version_id) REFERENCES skill.skill_version(tenant_id,id);
ALTER TABLE skill.execution_fact ADD CONSTRAINT fact_version_same_tenant FOREIGN KEY(tenant_id,skill_version_id) REFERENCES skill.skill_version(tenant_id,id);
ALTER TABLE skill.connection_test ADD CONSTRAINT connection_version_same_tenant FOREIGN KEY(tenant_id,skill_version_id) REFERENCES skill.skill_version(tenant_id,id);
ALTER TABLE skill.acceptance ADD CONSTRAINT acceptance_version_same_tenant FOREIGN KEY(tenant_id,skill_version_id) REFERENCES skill.skill_version(tenant_id,id);
