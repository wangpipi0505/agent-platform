-- A3-6 skill V322：MCP 连接档案与工具快照两表一次交付（库稿 §4.9；台账 §7.4 A3-6）。
-- 只增不改：全新两表随链尾追加，存量升级路径（V321→V322）自然有序，无需 outOfOrder；
-- 空库初始化随 V300..V322 全链建齐。手写回滚见文件尾注释。
--
-- 修订绑定（主方案 §6.3）：探针（test）与发现（discover）绑定 mcp_connection.revision；
-- 配置变更 revision+1，替换或停用连接后旧修订的探针/发现结果一律拦截丢弃，不得覆盖当前目录。
-- 目录发布规则（评审 12 复审）：目录版本=成功发布的完整快照——同一 batch_no 全部工具齐备
-- 才算完整；分页不完整不生效；批次单调：只接受 batch_no 大于当前已发布批次的完整快照；
-- 重发现失败保留上一代快照（目录可用性优先）。
--
-- 台账 A3-6 卡面在库稿 §4.9 两表之外明确"目录版本列 batch_no"：本迁移在 mcp_connection 上落
--   batch_no        = 最近一次成功发布的完整批次号（对外目录版本，GET /{id} 返回）
--   issued_batch_no = 最近一次签发的发现批次号（发现受理时 +1 分配，发布按批次号串行）。
-- 两列均为跨修订单调（修订绑定靠 revision 列表达，不重置批次），使"旧批次不覆盖新批次"
-- 在库层有单调护栏（见 mcp_connection_monotonic_guard 触发器）。

CREATE TABLE skill.mcp_connection (
    tenant_id varchar(128) NOT NULL,
    code varchar(64) NOT NULL,
    name varchar(128) NOT NULL,
    transport varchar(24) NOT NULL CHECK (transport IN ('STREAMABLE_HTTP','SSE_LEGACY')),
    endpoint_profile_ref varchar(160),
    base_endpoint text CHECK (base_endpoint ~ '^https?://'),
    secret_ref text,
    timeout_ms int NOT NULL CHECK (timeout_ms > 0),
    revision int NOT NULL DEFAULT 1 CHECK (revision >= 1),
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ACTIVE','DISABLED')),
    batch_no bigint NOT NULL DEFAULT 0 CHECK (batch_no >= 0),
    issued_batch_no bigint NOT NULL DEFAULT 0 CHECK (issued_batch_no >= 0),
    last_test_at timestamptz,
    last_test_result jsonb
        CHECK (last_test_result IS NULL OR jsonb_typeof(last_test_result) = 'object'),
    created_by varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, code),
    -- 端点二选一（库稿 §4.9）：引用 app.connection_profile 或直配 base_endpoint，恰好其一。
    -- secret_ref 是唯一凭据携带位且任何端点形态下都允许（平台纪律：仅引用不存值）。
    CONSTRAINT mcp_connection_endpoint_choice
        CHECK ((endpoint_profile_ref IS NOT NULL) <> (base_endpoint IS NOT NULL)),
    -- 停用/替换纪律：停用的连接不受理新发现（见服务层），未完成批次在发布端拦截。
    CONSTRAINT mcp_connection_batch_order CHECK (batch_no <= issued_batch_no)
);

-- 快照身份字段不可变（迁移纪律：不可变新表随建表附触发器）：发现落库的
-- 租户/连接/修订/批次/工具名/schema/摘要/发现时间冻结；治理状态
-- （status/reviewed_at/skill_draft_ref）随差异审核迁移。行不可删除（历史批次保留原始快照，
-- 工具移除走 status='REMOVED' 状态投影）。
CREATE FUNCTION skill.protect_mcp_tool_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'mcp tool snapshot cannot be deleted; mark REMOVED instead';
    END IF;
    IF (NEW.tenant_id, NEW.connection_code, NEW.connection_revision, NEW.batch_no, NEW.tool_name,
        NEW.schema, NEW.schema_digest, NEW.discovered_at)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.connection_code, OLD.connection_revision, OLD.batch_no, OLD.tool_name,
        OLD.schema, OLD.schema_digest, OLD.discovered_at)
    THEN RAISE EXCEPTION 'mcp tool snapshot is immutable'; END IF;
    RETURN NEW;
END $$;

CREATE TABLE skill.mcp_tool_snapshot (
    tenant_id varchar(128) NOT NULL,
    connection_code varchar(64) NOT NULL,
    connection_revision int NOT NULL,
    batch_no bigint NOT NULL CHECK (batch_no > 0),
    tool_name varchar(160) NOT NULL,
    schema jsonb NOT NULL
        CHECK (jsonb_typeof(schema) = 'object' AND octet_length(schema::text) <= 262144),
    schema_digest char(64) NOT NULL CHECK (schema_digest ~ '^[a-f0-9]{64}$'),
    -- 差异审核状态机（库稿 §4.9）：AVAILABLE=新增或与上一代一致；CHANGED=摘要与上一代不同
    -- （差异检出，进待审核队列）；PENDING_REVIEW=差异审核受理（SUBMIT）；AUTHORIZED=审核通过
    -- （APPROVE，携带 skill_draft_ref）；REMOVED=发布时上一代目录行在本批次缺席。
    status varchar(16) NOT NULL DEFAULT 'AVAILABLE'
        CHECK (status IN ('AVAILABLE','CHANGED','REMOVED','PENDING_REVIEW','AUTHORIZED')),
    discovered_at timestamptz NOT NULL DEFAULT now(),
    reviewed_at timestamptz,
    skill_draft_ref varchar(160),
    PRIMARY KEY (tenant_id, connection_code, connection_revision, batch_no, tool_name),
    -- 审核投影三约束：ref 与 reviewed_at 成对出现；AUTHORIZED 必须携带 ref；ref 只在
    -- AUTHORIZED（通过）或 REMOVED（工具移除后保留既有引用，对齐"已发布引用只解析到原版本"）出现。
    CONSTRAINT mcp_tool_snapshot_review_ref_pair
        CHECK ((skill_draft_ref IS NULL) = (reviewed_at IS NULL)),
    CONSTRAINT mcp_tool_snapshot_authorized_has_ref
        CHECK (status <> 'AUTHORIZED' OR skill_draft_ref IS NOT NULL),
    CONSTRAINT mcp_tool_snapshot_ref_state
        CHECK (skill_draft_ref IS NULL OR status IN ('AUTHORIZED','REMOVED'))
);

-- 快照不可变触发器（见上方函数注释）。
CREATE TRIGGER immutable_mcp_tool_snapshot
    BEFORE UPDATE OR DELETE ON skill.mcp_tool_snapshot
    FOR EACH ROW EXECUTE FUNCTION skill.protect_mcp_tool_snapshot();

-- 连接行护栏：revision/issued_batch_no/batch_no 只增不减（修订绑定与批次单调的库层兜底，
-- 服务层发布守卫为主）；连接行不可删除（停用走 DISABLED，历史快照保留逻辑引用）。
CREATE FUNCTION skill.protect_mcp_connection_lifecycle() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'mcp connection cannot be deleted; disable instead';
    END IF;
    IF NEW.revision < OLD.revision
       OR NEW.issued_batch_no < OLD.issued_batch_no
       OR NEW.batch_no < OLD.batch_no
    THEN RAISE EXCEPTION 'mcp connection revision/batch must be monotonic'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER mcp_connection_monotonic_guard
    BEFORE UPDATE OR DELETE ON skill.mcp_connection
    FOR EACH ROW EXECUTE FUNCTION skill.protect_mcp_connection_lifecycle();

-- 目录读取与批次串行发布的支撑索引：目录=当前修订的已发布批次；差异对比读取同修订
-- 相邻批次。唯一键即复合主键，覆盖 (租户,连接,修订,批次) 的目录扫描。
CREATE INDEX mcp_tool_snapshot_batch_scan
    ON skill.mcp_tool_snapshot(tenant_id, connection_code, connection_revision, batch_no);
CREATE INDEX mcp_tool_snapshot_status_scan
    ON skill.mcp_tool_snapshot(tenant_id, connection_code, status);

-- 手写回滚（生产禁用，仅灾难恢复参考；迁移纪律要求留注释）：
--   DROP TABLE IF EXISTS skill.mcp_tool_snapshot, skill.mcp_connection CASCADE;
--   DROP FUNCTION IF EXISTS skill.protect_mcp_tool_snapshot(),
--        skill.protect_mcp_connection_lifecycle();
