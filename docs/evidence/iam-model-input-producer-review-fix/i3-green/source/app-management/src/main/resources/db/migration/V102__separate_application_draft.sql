-- 保留已有新工程数据，把活动草稿从应用主实体移入独立行；之后所有编辑与发布竞争同一app_draft锁。
ALTER TABLE app.application ADD COLUMN id bigint GENERATED ALWAYS AS IDENTITY;
ALTER TABLE app.application ADD CONSTRAINT application_id_unique UNIQUE(id);
ALTER TABLE app.application ADD CONSTRAINT application_tenant_id_code_unique UNIQUE(tenant_id,id,code);
CREATE TABLE app.app_draft (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,tenant_id varchar(128) NOT NULL,app_id bigint NOT NULL,app_code varchar(64) NOT NULL,
 draft_no integer NOT NULL DEFAULT 1,draft_rev bigint NOT NULL DEFAULT 1,
 config jsonb NOT NULL DEFAULT '{}',definition jsonb NOT NULL DEFAULT '{}',layout jsonb NOT NULL DEFAULT '{}',
 updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,app_code),
 FOREIGN KEY(tenant_id,app_id,app_code) REFERENCES app.application(tenant_id,id,code)
);
INSERT INTO app.app_draft(tenant_id,app_id,app_code,draft_rev,config,definition,layout)
 SELECT tenant_id,id,code,draft_rev,config,definition,layout FROM app.application;
ALTER TABLE app.publish_report ADD COLUMN app_draft_id bigint REFERENCES app.app_draft(id);
UPDATE app.publish_report r SET app_draft_id=d.id FROM app.app_draft d WHERE d.tenant_id=r.tenant_id AND d.app_code=r.app_code;
ALTER TABLE app.publish_report ALTER COLUMN app_draft_id SET NOT NULL;
ALTER TABLE app.app_version ADD COLUMN app_id bigint REFERENCES app.application(id);
ALTER TABLE app.app_version ADD COLUMN app_draft_id bigint REFERENCES app.app_draft(id);
-- 仅迁移期回填新关系列，不修改已发布payload、摘要、评测编号或版本号；同一DDL事务内恢复不可变触发器。
ALTER TABLE app.app_version DISABLE TRIGGER immutable_app_version;
UPDATE app.app_version v SET app_id=a.id,app_draft_id=d.id FROM app.application a JOIN app.app_draft d ON d.tenant_id=a.tenant_id AND d.app_code=a.code WHERE v.tenant_id=a.tenant_id AND v.app_code=a.code;
ALTER TABLE app.app_version ENABLE TRIGGER immutable_app_version;
ALTER TABLE app.app_version ALTER COLUMN app_id SET NOT NULL;
ALTER TABLE app.app_version ALTER COLUMN app_draft_id SET NOT NULL;
ALTER TABLE app.publish_commit ADD COLUMN app_id bigint REFERENCES app.application(id);
ALTER TABLE app.publish_commit ADD COLUMN app_version_id bigint REFERENCES app.app_version(id);
UPDATE app.publish_commit c SET app_id=v.app_id,app_version_id=v.id FROM app.app_version v WHERE v.tenant_id=c.tenant_id AND v.app_code=c.app_code AND v.version_no=c.version_no;
ALTER TABLE app.publish_commit ALTER COLUMN app_id SET NOT NULL;
ALTER TABLE app.publish_commit ALTER COLUMN app_version_id SET NOT NULL;
ALTER TABLE app.skill_reference ADD COLUMN app_version_id bigint REFERENCES app.app_version(id);
UPDATE app.skill_reference r SET app_version_id=v.id FROM app.app_version v WHERE v.tenant_id=r.tenant_id AND v.app_code=r.app_code AND v.version_no=r.app_version_no;
ALTER TABLE app.skill_reference ALTER COLUMN app_version_id SET NOT NULL;
ALTER TABLE app.application DROP COLUMN draft_rev,DROP COLUMN config,DROP COLUMN definition,DROP COLUMN layout;
