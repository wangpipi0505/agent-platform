-- A3-2 skill V321：技能双轨与插件五表一次交付（库稿 §4.8/§3；台账 §7.4 A3-2）。
-- 只增不改：skill.kind 走 ADD COLUMN IF NOT EXISTS，DEFAULT 'TOOL' 保证存量技能语义零变化；
-- 存量升级路径（skill 链 V320→V321）自然有序，无需 outOfOrder；空库初始化随 V300..V321 全链建齐。
-- GUIDE 不建新表：SKILL.md 正文与 frontmatter 存 skill_version.contract jsonb
-- （约定形状 {name,description,whenToUse,modelInvocable,content}），版本/sha256/发布/统计链路全复用。
-- TOOL 契约 businessIntentInput 为 jsonb 约定字段，无迁移（发布预检在服务层执行）。
-- 插件数据面（来源/主表/版本/授权/发现）供 A3-5 发现执行器消费：认领（claim_owner+lease_until 成对、
-- 终态清空）、接管（claim_generation 递增）、批次串行发布（batch_no 同来源单调，UNIQUE(source_id,batch_no)，
-- 仅 batch_no 大于上次成功发布的完整批次可更新目录）等规则在服务层实现，本迁移只落库面与约束。

-- kind 扩列（库稿 §4.8）：varchar(16) NOT NULL DEFAULT 'TOOL'，CHECK IN('TOOL','GUIDE')。
ALTER TABLE skill.skill ADD COLUMN IF NOT EXISTS kind varchar(16) NOT NULL DEFAULT 'TOOL';
-- 双轨枚举具名约束：后续扩枚举一律走新迁移（§1.2 状态枚举纪律）。
ALTER TABLE skill.skill ADD CONSTRAINT skill_kind_check CHECK (kind IN ('TOOL','GUIDE'));

-- capabilities 数组元素形状（库稿 §4.8）：{type:'SKILL'|'MCP_CONNECTION'|'GUIDE_BUNDLE', ref, digest}。
-- CHECK 约束不能带子查询，元素级校验收敛为 IMMUTABLE 纯函数；授予能力子集（<=manifest.capabilities）
-- 由服务层核验，本函数只守元素形状。
CREATE FUNCTION skill.valid_plugin_capabilities(value jsonb) RETURNS boolean LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE element jsonb;
BEGIN
    IF value IS NULL OR jsonb_typeof(value) <> 'array' THEN RETURN FALSE; END IF;
    FOR element IN SELECT e FROM jsonb_array_elements(value) t(e) LOOP
        IF jsonb_typeof(element) <> 'object'
           OR element->>'type' NOT IN ('SKILL','MCP_CONNECTION','GUIDE_BUNDLE')
           OR coalesce(length(element->>'ref'), 0) NOT BETWEEN 1 AND 256
           OR element->>'digest' !~ '^[a-f0-9]{64}$'
        THEN RETURN FALSE; END IF;
    END LOOP;
    RETURN TRUE;
END $$;

CREATE TABLE skill.plugin_source (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id varchar(128) NOT NULL,
    code varchar(64) NOT NULL,
    name varchar(128) NOT NULL,
    source_type varchar(24) NOT NULL
        CHECK (source_type IN ('REGISTRY','GIT','DIRECTORY','CAPABILITY_PACKAGE')),
    location text NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    last_discovered_at timestamptz,
    created_by varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);

CREATE TABLE skill.plugin (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id varchar(128) NOT NULL,
    code varchar(64) NOT NULL,
    name varchar(128) NOT NULL,
    source_id bigint NOT NULL REFERENCES skill.plugin_source(id),
    latest_version_id bigint,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    created_by varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);

CREATE TABLE skill.plugin_version (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id varchar(128) NOT NULL,
    plugin_id bigint NOT NULL REFERENCES skill.plugin(id),
    source_id bigint NOT NULL REFERENCES skill.plugin_source(id),
    version varchar(64) NOT NULL,
    manifest jsonb NOT NULL
        CHECK (jsonb_typeof(manifest) = 'object' AND octet_length(manifest::text) <= 262144),
    manifest_digest char(64) NOT NULL CHECK (manifest_digest ~ '^[a-f0-9]{64}$'),
    capabilities jsonb NOT NULL
        CHECK (skill.valid_plugin_capabilities(capabilities) AND octet_length(capabilities::text) <= 262144),
    status varchar(16) NOT NULL DEFAULT 'DISCOVERED'
        CHECK (status IN ('DISCOVERED','ENABLED','DISABLED','REVOKED')),
    discovered_at timestamptz NOT NULL DEFAULT now(),
    enabled_at timestamptz,
    disabled_reason varchar(512),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, plugin_id, version)
);

CREATE TABLE skill.plugin_grant (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plugin_version_id bigint NOT NULL REFERENCES skill.plugin_version(id),
    grantee_type varchar(16) NOT NULL CHECK (grantee_type IN ('TENANT','WORKSPACE')),
    grantee_code varchar(64) NOT NULL,
    capabilities jsonb NOT NULL
        CHECK (skill.valid_plugin_capabilities(capabilities) AND octet_length(capabilities::text) <= 262144),
    status varchar(16) NOT NULL DEFAULT 'GRANTED' CHECK (status IN ('GRANTED','REVOKED')),
    granted_by varchar(128) NOT NULL,
    granted_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz,
    revoke_reason varchar(512),
    UNIQUE (plugin_version_id, grantee_type, grantee_code)
);

CREATE TABLE skill.plugin_discovery (
    discovery_id varchar(64) PRIMARY KEY,
    tenant_id varchar(128) NOT NULL,
    source_id bigint NOT NULL REFERENCES skill.plugin_source(id),
    batch_no bigint NOT NULL CHECK (batch_no > 0),
    status varchar(16) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz,
    claim_owner varchar(128),
    lease_until timestamptz,
    claim_generation bigint NOT NULL DEFAULT 0 CHECK (claim_generation >= 0),
    error_code varchar(64),
    error_summary varchar(512),
    changes jsonb,
    UNIQUE (source_id, batch_no),
    -- 认领与租约成对写入（库稿 §4.8）；终态一律清空认领对，迟到实例无法覆盖结果。
    -- 多列 CHECK 显式具名：便于验证与后续迁移引用，不依赖 PG 自动命名回退（{table}_checkN）。
    CONSTRAINT plugin_discovery_claim_pair CHECK ((claim_owner IS NULL) = (lease_until IS NULL)),
    CONSTRAINT plugin_discovery_terminal_claim_clear
        CHECK (status IN ('QUEUED','RUNNING') OR (claim_owner IS NULL AND lease_until IS NULL)),
    -- 失败投影只在 FAILED 终态出现；changes 只随成功终态同事务提交。
    CONSTRAINT plugin_discovery_error_projection CHECK (error_code IS NULL OR status = 'FAILED'),
    CONSTRAINT plugin_discovery_error_summary_projection
        CHECK (error_summary IS NULL OR status = 'FAILED'),
    CONSTRAINT plugin_discovery_changes_on_success CHECK (changes IS NULL OR status = 'SUCCEEDED'),
    CONSTRAINT plugin_discovery_changes_shape
        CHECK (changes IS NULL OR (jsonb_typeof(changes) = 'object' AND octet_length(changes::text) <= 262144))
);

-- plugin.latest_version_id 的 FK 在 plugin_version 建表后补约束（库稿 §4.8：可空 FK 后补）。
ALTER TABLE skill.plugin
    ADD CONSTRAINT plugin_latest_version_fk FOREIGN KEY (latest_version_id) REFERENCES skill.plugin_version(id);

-- 发现认领扫描与批次回放的支撑索引；唯一键已覆盖 (source_id,batch_no) 单调读取。
CREATE INDEX plugin_discovery_claim_scan ON skill.plugin_discovery(status, lease_until);
CREATE INDEX plugin_discovery_source_history ON skill.plugin_discovery(source_id, created_at);

-- 版本快照不可变（迁移纪律：不可变新表随建表附触发器）：发现落库的来源/清单/能力/发现时间冻结，
-- 治理状态（status/enabled_at/disabled_reason/updated_at）随启停迁移；行不可删除（历史运行保留原始版本，
-- 终态走 REVOKED）。tenant_id/plugin_id/source_id/version/manifest/manifest_digest/capabilities/discovered_at 整组保护。
CREATE FUNCTION skill.protect_plugin_version_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'plugin version cannot be deleted; revoke instead';
    END IF;
    IF (NEW.tenant_id, NEW.plugin_id, NEW.source_id, NEW.version, NEW.manifest,
        NEW.manifest_digest, NEW.capabilities, NEW.discovered_at)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.plugin_id, OLD.source_id, OLD.version, OLD.manifest,
        OLD.manifest_digest, OLD.capabilities, OLD.discovered_at)
    THEN RAISE EXCEPTION 'plugin version snapshot is immutable'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER immutable_plugin_version_snapshot
    BEFORE UPDATE OR DELETE ON skill.plugin_version
    FOR EACH ROW EXECUTE FUNCTION skill.protect_plugin_version_snapshot();

-- 授权记录身份不可变：授权对象与能力范围冻结（撤权=状态迁移，不删除）；重新授权（撤销后再次授予）
-- 允许把同一行翻回 GRANTED 并清空撤权投影，但能力范围仍以原行为准，防止静默扩大授权。
CREATE FUNCTION skill.protect_plugin_grant_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'plugin grant cannot be deleted; revoke instead';
    END IF;
    IF (NEW.plugin_version_id, NEW.grantee_type, NEW.grantee_code, NEW.capabilities, NEW.granted_by, NEW.granted_at)
       IS DISTINCT FROM
       (OLD.plugin_version_id, OLD.grantee_type, OLD.grantee_code, OLD.capabilities, OLD.granted_by, OLD.granted_at)
    THEN RAISE EXCEPTION 'plugin grant identity is immutable'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER immutable_plugin_grant_identity
    BEFORE UPDATE OR DELETE ON skill.plugin_grant
    FOR EACH ROW EXECUTE FUNCTION skill.protect_plugin_grant_identity();

-- 手写回滚（生产禁用，仅灾难恢复参考；迁移纪律要求留注释）：
--   DROP TABLE IF EXISTS skill.plugin_discovery, skill.plugin_grant, skill.plugin_version,
--        skill.plugin, skill.plugin_source CASCADE;
--   DROP FUNCTION IF EXISTS skill.protect_plugin_version_snapshot(),
--        skill.protect_plugin_grant_identity(), skill.valid_plugin_capabilities(jsonb);
--   ALTER TABLE skill.skill DROP CONSTRAINT IF EXISTS skill_kind_check;
--   ALTER TABLE skill.skill DROP COLUMN IF EXISTS kind;  -- 历史行已按 DEFAULT 'TOOL' 回填，删列即回到 V320 形状
