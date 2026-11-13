-- app V123：规则资产（rule_asset + rule_asset_version）——台账 A3-3。
--
-- 设计与边界（数据库设计 §4.7；1109 §5.1 规则＝名称/适用范围/触发条件/处理要求/启停/版本）：
-- 1) rule_asset 是租户内规则主档：nature 区分指导性（GUIDANCE，进提示装配"规则段"）与
--    强制性（ENFORCED，进 Guard 结构化校验）；scope 是 {app?, stage?, toolClass?} 适用范围
--    （jsonb 对象，键集合与取值形状由应用层约束，库层只兜对象类型）；启停状态在主档，
--    版本链推进记录在 current_version_no。
-- 2) rule_asset_version 是不可变版本链：content={trigger, action, requirement}（触发条件/处理动作/
--    处理要求），sha256 覆盖 content 的规范化 JSON；DRAFT 版本可改（草稿保存），PUBLISHED/ARCHIVED
--    由触发器禁 UPDATE/DELETE（发布后禁改，与 app_version/prompt_template_version 同纪律）。
-- 3) 发布预检（同一 scope 的 ENFORCED 规则互斥）在应用层执行并阻断；运行命中记录规则版本进审计
--    （run_event payload）属运行端 Guard 挂接（台账 H1-6），本迁移不涉及。
-- 4) 只增不改：不触碰既有表；主档不外键到 app.application（scope.app 是可选逻辑引用，
--    跨对象归属由应用层核验，与"跨库只存稳定标识"纪律一致）。手写回滚注释保留。

CREATE TABLE app.rule_asset (
 tenant_id varchar(128) NOT NULL,
 code varchar(64) NOT NULL,
 name varchar(128) NOT NULL,
 nature varchar(16) NOT NULL CHECK(nature IN ('GUIDANCE','ENFORCED')),
 scope jsonb NOT NULL CHECK(jsonb_typeof(scope) = 'object'),
 status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ENABLED','DISABLED','ARCHIVED')),
 current_version_no integer NOT NULL DEFAULT 1 CHECK(current_version_no > 0),
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id, code)
);

CREATE TABLE app.rule_asset_version (
 tenant_id varchar(128) NOT NULL,
 rule_code varchar(64) NOT NULL,
 version_no integer NOT NULL CHECK(version_no > 0),
 content jsonb NOT NULL CHECK(jsonb_typeof(content) = 'object'
   AND content ? 'trigger' AND content ? 'action' AND content ? 'requirement'),
 sha256 char(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
 status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED','ARCHIVED')),
 published_by varchar(128),
 published_at timestamptz,
 PRIMARY KEY(tenant_id, rule_code, version_no),
 FOREIGN KEY(tenant_id, rule_code) REFERENCES app.rule_asset(tenant_id, code)
);

-- 应用列表页按 scope->>'app' 取规则；表达式索引与 GET /admin/apps/{code}/rules 的过滤一致。
CREATE INDEX rule_asset_scope_app ON app.rule_asset(tenant_id, (scope->>'app'));

-- 不可变触发器：仅 DRAFT 版本可改可删；PUBLISHED/ARCHIVED 一律拒绝（发布后禁改）。
CREATE FUNCTION app.protect_rule_asset_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status <> 'DRAFT' THEN
   RAISE EXCEPTION 'rule_asset_version %/% is immutable (status=%)', OLD.rule_code, OLD.version_no, OLD.status;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_rule_asset_version BEFORE UPDATE OR DELETE ON app.rule_asset_version
 FOR EACH ROW EXECUTE FUNCTION app.protect_rule_asset_version();

-- 回滚（与既有迁移一致：手写，保留说明）：
-- DROP TRIGGER immutable_rule_asset_version ON app.rule_asset_version;
-- DROP FUNCTION app.protect_rule_asset_version();
-- DROP INDEX app.rule_asset_scope_app;
-- DROP TABLE app.rule_asset_version;
-- DROP TABLE app.rule_asset;
