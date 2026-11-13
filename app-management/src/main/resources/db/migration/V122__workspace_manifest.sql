-- app V122：工作区 manifest（workspace_manifest）+ integration_config_release 扩列——台账 A3-1。
--
-- 设计与边界（数据库设计 §4.6；主方案 §6.6；接口稿 §5.5/§3.5）：
-- 1) workspace_manifest 是应用装配清单的不可变修订链（工作区＝现有 App，不新建实体；资源名保持
--    app/{code}/workspace_manifest）：每次保存追加 revision，digest=内容寻址 sha256（规范化 JSON），
--    UNIQUE(tenant_id, app_code, digest) 保证同内容不重复出版本；manifest jsonb ≤1MB 且顶层 object
--    并含五个必键（metadata/model/skills/contextPolicy/permission，字段级定义见冻结契约
--    contracts/manifest.schema.json）。
-- 2) 不可变触发器：修订链禁 UPDATE/DELETE（与 app_version/prompt_template_version 同纪律）。
-- 3) integration_config_release 扩列 manifest_revision/manifest_digest：发布（配置发布快照）时
--    由服务端固化当前 manifest 头修订（不信任客户端自报，同 candidate_sha256 口径）；成对 CHECK
--    同空或同非空；对 workspace_manifest 是逻辑引用（app_code 同为"逻辑 FK"口径，不加物理外键，
--    跨对象归属由应用层核验——与库稿 §4.6 "逻辑 FK→application" 一致）。
-- 4) 发布预检的 manifest 引用核验（技能/插件/规则）在应用层执行并把结果写进既有
--    publish_report.checks（库稿 §4.6：现有表不扩列）；本迁移只交付存储。
--
-- 【编号顺序说明】本迁移编号 V122 < V123（A3-3 规则资产，与本书同目录先后合入）。全新验证库按
-- 版本号自然有序应用（…→V121→V122→V123），无碍；对已应用过 V123 的存量库，V122 属于"版本号
-- 低于已应用最大版本"的未应用迁移，Flyway 默认（outOfOrder=false）会忽略它，必须显式开启
-- outOfOrder=true 才会补应用。本卡验证采用全新专用库 agent_platform_v122_test（推荐路径），
-- 不依赖 outOfOrder。

CREATE TABLE app.workspace_manifest (
 tenant_id varchar(128) NOT NULL,
 app_code varchar(64) NOT NULL,
 revision integer NOT NULL CHECK(revision > 0),
 manifest jsonb NOT NULL CHECK(octet_length(manifest::text) <= 1048576
   AND jsonb_typeof(manifest) = 'object'
   AND manifest ? 'metadata' AND manifest ? 'model' AND manifest ? 'skills'
   AND manifest ? 'contextPolicy' AND manifest ? 'permission'),
 digest char(64) NOT NULL CHECK(digest ~ '^[0-9a-f]{64}$'),
 created_by varchar(128) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id, app_code, revision),
 CONSTRAINT workspace_manifest_digest_unique UNIQUE(tenant_id, app_code, digest)
);

-- 不可变触发器：追加式修订链，一律拒绝 UPDATE/DELETE。
CREATE FUNCTION app.protect_workspace_manifest() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'workspace_manifest %/% revision % is immutable (append-only)',
   OLD.app_code, OLD.tenant_id, OLD.revision;
END $$;
CREATE TRIGGER immutable_workspace_manifest BEFORE UPDATE OR DELETE ON app.workspace_manifest
 FOR EACH ROW EXECUTE FUNCTION app.protect_workspace_manifest();

-- 配置发布快照钉住 manifest 修订：两列成对（同空=无 manifest 的存量口径，同非空=已固化）。
ALTER TABLE app.integration_config_release
 ADD COLUMN manifest_revision integer,
 ADD COLUMN manifest_digest char(64) CHECK(manifest_digest ~ '^[0-9a-f]{64}$'),
 ADD CONSTRAINT integration_config_release_manifest_pair
   CHECK((manifest_revision IS NULL) = (manifest_digest IS NULL));

-- 回滚（与既有迁移一致：手写，保留说明）：
-- ALTER TABLE app.integration_config_release
--   DROP CONSTRAINT integration_config_release_manifest_pair;
-- ALTER TABLE app.integration_config_release
--   DROP COLUMN manifest_digest, DROP COLUMN manifest_revision;
-- DROP TRIGGER immutable_workspace_manifest ON app.workspace_manifest;
-- DROP FUNCTION app.protect_workspace_manifest();
-- DROP TABLE app.workspace_manifest;
