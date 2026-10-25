ALTER TABLE skill.execution_fact ADD COLUMN skill_contract_sha256 char(64);
UPDATE skill.execution_fact f SET skill_contract_sha256=v.sha256 FROM skill.skill_version v WHERE v.tenant_id=f.tenant_id AND v.id=f.skill_version_id;
ALTER TABLE skill.execution_fact ALTER COLUMN skill_contract_sha256 SET NOT NULL;
