package cn.agentplatform.skill.application;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.infra.McpTransport;
import cn.agentplatform.skill.infra.McpTransport.Categories;
import cn.agentplatform.skill.infra.McpTransport.McpEndpointSpec;
import cn.agentplatform.skill.infra.McpTransport.McpHandshake;
import cn.agentplatform.skill.infra.McpTransport.McpToolDescriptor;
import cn.agentplatform.skill.infra.McpTransport.McpTransportFailure;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;

/**
 * A3-6 MCP 连接档案服务（接口稿 §5.6 MCP 表；库稿 §4.9 两表；主方案 §6.3；台账 §7.4 A3-6）。
 *
 * <p><b>修订绑定</b>：探针（{@link #test}）与发现（{@link #discover}）绑定连接修订号
 * {@code mcp_connection.revision}；配置变更（{@link #updateConfig}）使 revision+1，替换或
 * 停用连接后旧修订的探针/发现结果在写回/发布端拦截丢弃，不得覆盖当前目录。
 *
 * <p><b>批次串行发布（评审 12 复审）</b>：目录版本 = 成功发布的完整快照。发现受理时签发
 * {@code issued_batch_no+1} 并绑定当前修订；只有完整走完分页链的批次（{@code nextCursor}
 * 为空）才在单事务内发布目录——写快照行、把上一代目录中缺席的工具标记 REMOVED、推进
 * {@code batch_no}，发布守卫 {@code revision=绑定修订 AND batch_no<本批次 AND 非 DISABLED}。
 * 分页不完整/中途失败整批作废，上一代目录原样保留（目录可用性优先）；旧批次与旧修订的
 * 迟到发布一律 409 拦截。批次号跨修订单调（库层单调触发器兜底）。
 *
 * <p><b>首期按次建连</b>：test/discover 每次经 {@link McpTransport} SPI 新建会话、用完即弃；
 * 差异审核通过后经既有技能登记入口（{@link SkillCatalog#create}）生成技能草稿，编码沿用
 * {@code mcp__<连接>__<工具>} 前缀约定进工具目录，调用沿用现有 MCP 执行链路。
 */
@Service
public class McpConnectionRegistry {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final McpTransport transport;
    private final SkillCatalog catalog;

    private static final int MAX_PAGES = 16;
    private static final int MAX_CURSOR_LENGTH = 1024;
    private static final int MAX_SCHEMA_BYTES = 262144;

    public McpConnectionRegistry(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            McpTransport transport,
            SkillCatalog catalog) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.transport = transport;
        this.catalog = catalog;
    }

    private static PlatformException error(int status, String code, String message) {
        return new PlatformException(status, code, message);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object raw) {
        if (!(raw instanceof Map)) throw error(400, "INVALID_MCP_CONNECTION", "字段必须为JSON对象");
        return (Map<String, Object>) raw;
    }

    private static String text(Map<String, Object> b, String key) {
        String value = Objects.toString(b.get(key), "");
        if (value.isBlank() || value.length() > 4096)
            throw error(400, "INVALID_MCP_CONNECTION", "缺少或无效字段: " + key);
        return value;
    }

    private String tenant() {
        return Actor.current().tenantId();
    }

    private static String toString(Object value) {
        return value == null ? null : value.toString();
    }

    private void admin() {
        Actor.current().requireRole("ADMIN");
    }

    private static boolean uniqueViolation(Throwable failure) {
        int depth = 0;
        for (Throwable cause = failure; cause != null && depth < 8; cause = cause.getCause(), depth++)
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) return true;
        return false;
    }

    // ------------------------------------------------------------------ 登记

    /** 连接登记（接口稿 §5.6 POST /admin/mcp-connections）：revision=1，status=DRAFT。 */
    public Map<String, Object> register(Map<String, Object> body) {
        admin();
        var input = object(body);
        String code = text(input, "code");
        if (!code.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))
            throw error(400, "INVALID_MCP_CONNECTION", "编码无效");
        String name = text(input, "name");
        if (name.length() > 128) throw error(400, "INVALID_MCP_CONNECTION", "名称过长");
        String transportType = text(input, "transport");
        if (!Set.of("STREAMABLE_HTTP", "SSE_LEGACY").contains(transportType))
            throw error(400, "INVALID_MCP_CONNECTION", "transport 只允许 STREAMABLE_HTTP 或 SSE_LEGACY");
        var endpoint = endpointPair(input, null, null);
        String secretRef = secretRef(input.get("secretRef"));
        int timeoutMs = timeoutMs(input.get("timeoutMs"));
        try {
            tx.executeWithoutResult(
                    status ->
                            db.update(
                                    "insert into skill.mcp_connection(tenant_id,code,name,transport,"
                                        + "endpoint_profile_ref,base_endpoint,secret_ref,timeout_ms,created_by)"
                                        + " values(?,?,?,?,?,?,?,?,?)",
                                    tenant(),
                                    code,
                                    name,
                                    transportType,
                                    endpoint.profileRef(),
                                    endpoint.baseEndpoint(),
                                    secretRef,
                                    timeoutMs,
                                    Actor.current().principalId()));
        } catch (DataIntegrityViolationException race) {
            if (uniqueViolation(race))
                throw error(409, "MCP_CONNECTION_CONFLICT", "同租户已存在同编码 MCP 连接");
            throw race;
        }
        return connection(code);
    }

    /** 端点二选一（库稿 §4.9）：引用 app.connection_profile 或直配 base_endpoint，恰好其一。 */
    private record EndpointPair(String profileRef, String baseEndpoint) {}

    private EndpointPair endpointPair(Map<String, Object> input, String oldProfile, String oldBase) {
        Object profile = input.get("endpointProfileRef");
        Object base = input.get("baseEndpoint");
        if (profile == null && base == null) {
            if (oldProfile == null && oldBase == null)
                throw error(
                        400,
                        "INVALID_MCP_CONNECTION",
                        "endpointProfileRef 与 baseEndpoint 必须二选一（库稿 §4.9 成对互斥）");
            return new EndpointPair(oldProfile, oldBase);
        }
        if (profile != null && base != null)
            throw error(
                    400,
                    "INVALID_MCP_CONNECTION",
                    "endpointProfileRef 与 baseEndpoint 只能二选一，不能同时提供");
        if (profile != null) {
            String ref = Objects.toString(profile, "").trim();
            if (ref.isBlank() || ref.length() > 160)
                throw error(400, "INVALID_MCP_CONNECTION", "endpointProfileRef 无效（<=160 字符）");
            return new EndpointPair(ref, null);
        }
        String endpoint = Objects.toString(base, "").trim();
        if (!endpoint.matches("^https?://.*") || endpoint.length() > 2048)
            throw error(400, "INVALID_MCP_CONNECTION", "baseEndpoint 必须为 http(s) URL（<=2048 字符）");
        return new EndpointPair(null, endpoint);
    }

    /**
     * 凭据仅引用不存值（平台纪律）。连接档案的 secret_ref 与技能契约 authRef 同一形状
     * （vault:/env:AGENT_SECRET_*），差异审核生成技能草稿时原样冻结，避免审核时才发现形状不符。
     */
    private String secretRef(Object raw) {
        String ref = raw == null ? "" : Objects.toString(raw, "").trim();
        if (ref.isEmpty()) return null;
        if (ref.length() > 512
                || !(ref.startsWith("vault:") || ref.matches("env:AGENT_SECRET_[A-Z0-9_]+")))
            throw error(400, "INVALID_SECRET_REF", "secret_ref 只能是 vault:/env:AGENT_SECRET_* 引用");
        return ref;
    }

    /**
     * 超时（库稿 §4.9 CHECK >0；服务层收紧到技能契约同界 100..600000，保证差异审核生成的
     * 技能草稿可直接通过既有登记校验，闭环不留暗坑）。
     */
    private int timeoutMs(Object raw) {
        if (!(raw instanceof Number number))
            throw error(400, "INVALID_MCP_CONNECTION", "timeoutMs 必须为正整数");
        long value = number.longValue();
        if (value < 100 || value > 600000)
            throw error(400, "INVALID_MCP_CONNECTION", "timeoutMs 必须为 100 至 600000");
        return (int) value;
    }

    // ------------------------------------------------------------------ 配置变更

    /**
     * 配置变更（主方案 §6.3：配置变更 revision+1）：端点/凭据/传输任一变更递增修订号；
     * 仅名称或启停状态变更不动修订号。停用（DISABLED）不拦截改配置，但发布端会拦截停用
     * 连接的未完成批次（主方案 §6.3 dsh 语义：停用使未完成批次失效）。
     */
    public Map<String, Object> updateConfig(String code, Map<String, Object> body) {
        admin();
        var input = object(body);
        return tx.execute(
                status -> {
                    var row = connectionRow(code);
                    String oldProfile = (String) row.get("endpoint_profile_ref");
                    String oldBase = (String) row.get("base_endpoint");
                    var endpoint = endpointPair(input, oldProfile, oldBase);
                    String transportType =
                            input.containsKey("transport")
                                    ? text(input, "transport")
                                    : row.get("transport").toString();
                    if (!Set.of("STREAMABLE_HTTP", "SSE_LEGACY").contains(transportType))
                        throw error(
                                400, "INVALID_MCP_CONNECTION", "transport 只允许 STREAMABLE_HTTP 或 SSE_LEGACY");
                    String name =
                            input.containsKey("name")
                                    ? text(input, "name")
                                    : row.get("name").toString();
                    if (name.length() > 128) throw error(400, "INVALID_MCP_CONNECTION", "名称过长");
                    String secretRef =
                            input.containsKey("secretRef")
                                    ? secretRef(input.get("secretRef"))
                                    : (String) row.get("secret_ref");
                    int timeoutMs =
                            input.containsKey("timeoutMs")
                                    ? timeoutMs(input.get("timeoutMs"))
                                    : ((Number) row.get("timeout_ms")).intValue();
                    String targetStatus =
                            input.containsKey("status")
                                    ? text(input, "status")
                                    : row.get("status").toString();
                    if (!Set.of("DRAFT", "ACTIVE", "DISABLED").contains(targetStatus))
                        throw error(400, "INVALID_MCP_CONNECTION", "status 只允许 DRAFT/ACTIVE/DISABLED");
                    int oldRevision = ((Number) row.get("revision")).intValue();
                    boolean configChanged =
                            !transportType.equals(row.get("transport").toString())
                                    || !Objects.equals(endpoint.profileRef(), oldProfile)
                                    || !Objects.equals(endpoint.baseEndpoint(), oldBase)
                                    || !Objects.equals(secretRef, row.get("secret_ref"))
                                    || timeoutMs != ((Number) row.get("timeout_ms")).intValue();
                    int newRevision = configChanged ? oldRevision + 1 : oldRevision;
                    int updated =
                            db.update(
                                    "update skill.mcp_connection set name=?,transport=?,endpoint_profile_ref=?,"
                                        + "base_endpoint=?,secret_ref=?,timeout_ms=?,revision=?,status=?,updated_at=now()"
                                        + " where tenant_id=? and code=? and revision=?",
                                    name,
                                    transportType,
                                    endpoint.profileRef(),
                                    endpoint.baseEndpoint(),
                                    secretRef,
                                    timeoutMs,
                                    newRevision,
                                    targetStatus,
                                    tenant(),
                                    code,
                                    oldRevision);
                    if (updated == 0)
                        throw error(409, "MCP_CONNECTION_CONFLICT", "连接配置已被并发修改，请重试");
                    return connectionView(connectionRow(code));
                });
    }

    // ------------------------------------------------------------------ 查询

    /** 管理查询（接口稿 §5.6 GET /{id}）：修订号、目录版本（最近完整发布批次）、最近测试投影。 */
    public Map<String, Object> connection(String code) {
        admin();
        return connectionView(connectionRow(code));
    }

    private Map<String, Object> connectionRow(String code) {
        var rows =
                db.queryForList(
                        "select tenant_id,code,name,transport,endpoint_profile_ref,base_endpoint,"
                            + "secret_ref,timeout_ms,revision,status,batch_no,issued_batch_no,"
                            + "last_test_at,last_test_result,created_by,created_at,updated_at"
                            + " from skill.mcp_connection where tenant_id=? and code=?",
                        tenant(),
                        code);
        if (rows.isEmpty()) throw error(404, "MCP_CONNECTION_NOT_FOUND", "MCP 连接不存在");
        return rows.getFirst();
    }

    private Map<String, Object> connectionView(Map<String, Object> row) {
        var view = new LinkedHashMap<String, Object>();
        view.put("code", row.get("code"));
        view.put("name", row.get("name"));
        view.put("transport", row.get("transport"));
        view.put("status", row.get("status"));
        view.put("revision", ((Number) row.get("revision")).intValue());
        view.put("batchNo", ((Number) row.get("batch_no")).longValue());
        view.put("issuedBatchNo", ((Number) row.get("issued_batch_no")).longValue());
        view.put("endpointProfileRef", row.get("endpoint_profile_ref"));
        view.put("baseEndpoint", row.get("base_endpoint"));
        // 凭据只回"已配置"标记，opaque secret_ref 不成为可转接的能力票据（对齐技能管理 publicView）。
        view.put("secretConfigured", row.get("secret_ref") != null);
        view.put("timeoutMs", ((Number) row.get("timeout_ms")).intValue());
        view.put("lastTestAt", toString(row.get("last_test_at")));
        view.put(
                "lastTestResult",
                row.get("last_test_result") == null
                        ? null
                        : Json.read(row.get("last_test_result").toString(), Object.class));
        view.put("createdBy", row.get("created_by"));
        view.put("createdAt", toString(row.get("created_at")));
        view.put("updatedAt", toString(row.get("updated_at")));
        return view;
    }

    // ------------------------------------------------------------------ 连接测试（探针）

    /**
     * 连接测试（接口稿 §5.6 POST /{id}/test）：按次建连——握手+鉴权，再完整走一遍工具列举
     * 证明链路可用；失败返回具体失败类别（AUTH_FAILED/TIMEOUT/UNREACHABLE/PROTOCOL_ERROR）。
     * 探针绑定当前修订号：写回 last_test_* 时守卫 revision 未变，修订已递增（连接已替换）的
     * 迟到探针结果拦截丢弃（响应 discarded=true，不改当前档案）。
     */
    public Map<String, Object> test(String code) {
        admin();
        var row = connectionRow(code);
        requireOperable(row);
        int boundRevision = ((Number) row.get("revision")).intValue();
        var spec = spec(row, boundRevision);
        long started = System.nanoTime();
        var outcome = probe(spec);
        var result = outcome.handshake();
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        var projection = new LinkedHashMap<String, Object>();
        projection.put("result", result.ok() ? "OK" : "FAILED");
        projection.put("category", result.ok() ? Categories.OK : result.category());
        if (result.message() != null) projection.put("message", result.message());
        if (result.protocolVersion() != null) projection.put("protocolVersion", result.protocolVersion());
        if (result.serverInfo() != null) projection.put("serverInfo", result.serverInfo());
        projection.put("toolCount", outcome.toolCount());
        projection.put("latencyMs", latencyMs);
        // 修订守卫写回：revision 已变（配置已变更）的迟到探针结果不落档案。
        int updated =
                db.update(
                        "update skill.mcp_connection set last_test_at=now(),last_test_result=?::jsonb,"
                            + "updated_at=now() where tenant_id=? and code=? and revision=?"
                            + " and status<>'DISABLED'",
                        Json.write(projection),
                        tenant(),
                        code,
                        boundRevision);
        var view = new LinkedHashMap<String, Object>(projection);
        view.put("code", code);
        view.put("revision", boundRevision);
        view.put("discarded", updated == 0);
        if (updated == 0) view.put("discardReason", "REVISION_CHANGED");
        return view;
    }

    /** 握手+完整列举（两段都过才算连通）；列举失败折算为探针失败类别。 */
    private ProbeOutcome probe(McpEndpointSpec spec) {
        var handshake = transport.handshake(spec);
        if (!handshake.ok()) return new ProbeOutcome(handshake, 0);
        try {
            return new ProbeOutcome(handshake, collectAllTools(spec).size());
        } catch (McpTransportFailure failure) {
            return new ProbeOutcome(McpHandshake.fail(failure.category(), failure.getMessage()), 0);
        }
    }

    private record ProbeOutcome(McpHandshake handshake, int toolCount) {}

    private void requireOperable(Map<String, Object> row) {
        if ("DISABLED".equals(row.get("status")))
            throw error(409, "MCP_CONNECTION_DISABLED", "停用的连接不受理测试或发现");
        if (row.get("endpoint_profile_ref") != null)
            throw error(
                    409,
                    "MCP_CONNECTION_PROFILE_UNRESOLVED",
                    "端点引用（app.connection_profile 复用）解析随接入卡交付；首期按次建连支持直配 base_endpoint");
    }

    private McpEndpointSpec spec(Map<String, Object> row, int boundRevision) {
        return new McpEndpointSpec(
                tenant(),
                row.get("code").toString(),
                boundRevision,
                row.get("transport").toString(),
                Objects.toString(row.get("base_endpoint"), ""),
                Objects.toString(row.get("secret_ref"), ""),
                ((Number) row.get("timeout_ms")).intValue());
    }

    /** 分页全量列举；页数/游标上限与既有 McpAdapter 同界（16 页、游标 1024、循环拒绝）。 */
    private List<McpToolDescriptor> collectAllTools(McpEndpointSpec spec) {
        var tools = new ArrayList<McpToolDescriptor>();
        String cursor = null;
        var seen = new HashSet<String>();
        for (int page = 0; ; page++) {
            if (page >= MAX_PAGES)
                throw new McpTransportFailure(Categories.PROTOCOL_ERROR, "MCP工具分页超过16页");
            var list = transport.listTools(spec, cursor);
            tools.addAll(list.tools());
            String next = list.nextCursor();
            if (next == null || next.isBlank()) return tools;
            if (next.length() > MAX_CURSOR_LENGTH || !seen.add(next))
                throw new McpTransportFailure(Categories.PROTOCOL_ERROR, "MCP工具分页游标无效或循环");
            cursor = next;
        }
    }

    // ------------------------------------------------------------------ 发现与批次串行发布

    /**
     * 发现（接口稿 §5.6 POST /{id}/discover）：受理即签发批次号并绑定当前修订；按次建连
     * 完整走完分页链后单事务发布目录。分页不完整/传输失败整批作废、保留上一代目录
     * （响应 status=FAILED 携带具体类别与 catalogPreserved 标记）；旧修订/旧批次/停用连接的
     * 发布在 {@link #publishBatch} 拦截（409）。失败发现不落任何快照行，批次号随
     * issued_batch_no 单调消耗，下一次发现必然大于本次。
     */
    public Map<String, Object> discover(String code) {
        admin();
        requireOperable(connectionRow(code));
        var bound = allocateBatch(code);
        int boundRevision = ((Number) bound.get("revision")).intValue();
        long batch = ((Number) bound.get("issued_batch_no")).longValue();
        var spec = spec(bound, boundRevision);
        try {
            var handshake = transport.handshake(spec);
            if (!handshake.ok())
                return failedDiscovery(code, boundRevision, batch, handshake.category(), handshake.message());
            var tools = collectAllTools(spec);
            var published = publishBatch(code, boundRevision, batch, tools);
            var view = new LinkedHashMap<String, Object>();
            view.put("code", code);
            view.put("status", "SUCCEEDED");
            view.put("revision", boundRevision);
            view.put("batchNo", batch);
            view.put("toolCount", published.toolCount());
            view.put("added", published.added());
            view.put("changed", published.changed());
            view.put("removed", published.removed());
            view.put("catalogPublished", true);
            return view;
        } catch (McpTransportFailure failure) {
            // 分页不完整不生效：整批作废，上一代目录原样保留（库稿 §4.9 目录发布规则）。
            return failedDiscovery(code, boundRevision, batch, failure.category(), failure.getMessage());
        }
    }

    private Map<String, Object> failedDiscovery(
            String code, int revision, long batch, String category, String message) {
        var view = new LinkedHashMap<String, Object>();
        view.put("code", code);
        view.put("status", "FAILED");
        view.put("revision", revision);
        view.put("batchNo", batch);
        view.put("category", category);
        view.put("message", message);
        view.put("catalogPreserved", true);
        return view;
    }

    /**
     * 批次签发（发现受理）：issued_batch_no 原子 +1 并锁定行读取当前修订与端点配置——
     * 同一连接并行发现按签发顺序获得严格递增的批次号，发布按批次号串行（台账 A3-6：目录
     * 发布=完整批次串行）。停用连接不签发（未完成批次随停用失效）。
     */
    Map<String, Object> allocateBatch(String code) {
        return tx.execute(
                status -> {
                    var rows =
                            db.queryForList(
                                    "update skill.mcp_connection set issued_batch_no=issued_batch_no+1,"
                                        + "updated_at=now() where tenant_id=? and code=? and status<>'DISABLED'"
                                        + " returning code,revision,transport,base_endpoint,endpoint_profile_ref,"
                                        + "secret_ref,timeout_ms,issued_batch_no",
                                    tenant(),
                                    code);
                    if (rows.isEmpty()) {
                        if (db.queryForList(
                                        "select 1 from skill.mcp_connection where tenant_id=? and code=?",
                                        tenant(),
                                        code)
                                .isEmpty())
                            throw error(404, "MCP_CONNECTION_NOT_FOUND", "MCP 连接不存在");
                        throw error(409, "MCP_CONNECTION_DISABLED", "停用的连接不受理发现");
                    }
                    return rows.getFirst();
                });
    }

    /** 完整批次发布结果：目录条数与相对上一代目录的差异（ADDED/CHANGED/REMOVED 工具名）。 */
    record BatchPublication(long batchNo, int toolCount, List<String> added, List<String> changed,
            List<String> removed) {}

    /**
     * 发布完整批次（discover 内部缝，测试用于确定性构造迟到/旧批次发布）：单事务内
     * 写本批次快照行（与上一代目录比摘要：一致 AVAILABLE、不同 CHANGED 进待审核）、
     * 上一代目录缺席工具标记 REMOVED（既有 skill_draft_ref 保留，对齐"已发布引用只解析到
     * 原版本"）、推进 batch_no。发布守卫（修订绑定 + 批次单调 + 非停用）失败即 409，
     * 事务回滚不留半批。
     */
    BatchPublication publishBatch(
            String code, int boundRevision, long boundBatch, List<McpToolDescriptor> tools) {
        Objects.requireNonNull(tools, "tools");
        return tx.execute(
                status -> {
                    var rows =
                            db.queryForList(
                                    "select code,revision,status,batch_no from skill.mcp_connection"
                                        + " where tenant_id=? and code=? for update",
                                    tenant(),
                                    code);
                    if (rows.isEmpty())
                        throw error(404, "MCP_CONNECTION_NOT_FOUND", "MCP 连接不存在");
                    var row = rows.getFirst();
                    if (((Number) row.get("revision")).intValue() != boundRevision)
                        throw error(
                                409,
                                "MCP_CONNECTION_REVISION_STALE",
                                "发现绑定的修订已过期（连接配置已变更），旧修订发现结果拦截丢弃");
                    if ("DISABLED".equals(row.get("status")))
                        throw error(409, "MCP_CONNECTION_DISABLED", "停用连接的未完成批次失效");
                    long publishedBatch = ((Number) row.get("batch_no")).longValue();
                    if (publishedBatch >= boundBatch)
                        throw error(
                                409,
                                "MCP_DISCOVERY_BATCH_STALE",
                                "发现批次 " + boundBatch + " 不大于已发布批次 " + publishedBatch
                                        + "，旧批次结果不覆盖目录");
                    // 上一代目录（同修订、当前已发布批次）；修订变更后第一代目录为空 → 全部视为新增。
                    var previous = new LinkedHashMap<String, String>();
                    if (publishedBatch > 0)
                        db.queryForList(
                                        "select tool_name,schema_digest from skill.mcp_tool_snapshot"
                                            + " where tenant_id=? and connection_code=? and connection_revision=?"
                                            + " and batch_no=?",
                                        tenant(),
                                        code,
                                        boundRevision,
                                        publishedBatch)
                                .forEach(
                                        r ->
                                                previous.put(
                                                        r.get("tool_name").toString(),
                                                        r.get("schema_digest").toString()));
                    var digests = new LinkedHashMap<String, String>();
                    var added = new ArrayList<String>();
                    var changed = new ArrayList<String>();
                    for (var tool : tools) {
                        String name = validatedToolName(tool);
                        String schemaJson = Json.write(object(tool.inputSchema()));
                        if (schemaJson.getBytes(StandardCharsets.UTF_8).length > MAX_SCHEMA_BYTES)
                            throw error(400, "INVALID_MCP_TOOL_SCHEMA", "工具 " + name + " 的 Schema 超过 256KB 上限");
                        String digest = Digest.sha256(Json.canonical(object(tool.inputSchema())));
                        if (digests.containsKey(name))
                            throw error(409, "MCP_TOOL_DUPLICATED", "本批次工具名重复: " + name);
                        digests.put(name, digest);
                        String nextStatus;
                        String previousDigest = previous.get(name);
                        if (previousDigest == null) {
                            nextStatus = "AVAILABLE";
                            added.add(name);
                        } else if (previousDigest.equals(digest)) {
                            nextStatus = "AVAILABLE";
                        } else {
                            nextStatus = "CHANGED";
                            changed.add(name);
                        }
                        db.update(
                                "insert into skill.mcp_tool_snapshot(tenant_id,connection_code,"
                                    + "connection_revision,batch_no,tool_name,schema,schema_digest,status)"
                                    + " values(?,?,?,?,?,?::jsonb,?,?)",
                                tenant(),
                                code,
                                boundRevision,
                                boundBatch,
                                name,
                                schemaJson,
                                digest,
                                nextStatus);
                    }
                    var removed = new ArrayList<String>();
                    for (var name : previous.keySet())
                        if (!digests.containsKey(name)) {
                            db.update(
                                    "update skill.mcp_tool_snapshot set status='REMOVED'"
                                        + " where tenant_id=? and connection_code=? and connection_revision=?"
                                        + " and batch_no=? and tool_name=?",
                                    tenant(),
                                    code,
                                    boundRevision,
                                    publishedBatch,
                                    name);
                            removed.add(name);
                        }
                    int updated =
                            db.update(
                                    "update skill.mcp_connection set batch_no=?,updated_at=now()"
                                        + " where tenant_id=? and code=? and revision=? and batch_no<?"
                                        + " and status<>'DISABLED'",
                                    boundBatch,
                                    tenant(),
                                    code,
                                    boundRevision,
                                    boundBatch);
                    if (updated == 0)
                        throw error(
                                409,
                                "MCP_DISCOVERY_BATCH_STALE",
                                "目录已推进，本批次发布被拦截（并发发布或修订变更）");
                    Collections.sort(added);
                    Collections.sort(changed);
                    Collections.sort(removed);
                    return new BatchPublication(boundBatch, digests.size(), added, changed, removed);
                });
    }

    private String validatedToolName(McpToolDescriptor tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank() || tool.name().length() > 160)
            throw error(400, "INVALID_MCP_TOOL", "远端工具名无效（<=160 字符）");
        if (!(tool.inputSchema() instanceof Map))
            throw error(400, "INVALID_MCP_TOOL_SCHEMA", "工具 " + tool.name() + " 的 Schema 必须为JSON对象");
        return tool.name();
    }

    // ------------------------------------------------------------------ 目录与差异

    /**
     * 目录查询（接口稿 §5.6 GET /{id}/tools?diff=true）：目录 = 当前修订的已发布批次快照行
     * （修订变更后尚未重发现时目录为空，catalogBatchNo 为 null）。diff=true 时与同修订
     * 相邻已发布批次比摘要，给出 ADDED/CHANGED/REMOVED 工具名清单。
     */
    public Map<String, Object> tools(String code, boolean diff) {
        admin();
        var row = connectionRow(code);
        int revision = ((Number) row.get("revision")).intValue();
        long catalogBatch = ((Number) row.get("batch_no")).longValue();
        var catalog =
                db.queryForList(
                        "select tool_name,schema,schema_digest,status,discovered_at,reviewed_at,"
                            + "skill_draft_ref from skill.mcp_tool_snapshot"
                            + " where tenant_id=? and connection_code=? and connection_revision=?"
                            + " and batch_no=? order by tool_name",
                        tenant(),
                        code,
                        revision,
                        catalogBatch);
        var view = new LinkedHashMap<String, Object>();
        view.put("code", code);
        view.put("revision", revision);
        view.put("batchNo", catalogBatch);
        view.put("catalogBatchNo", catalog.isEmpty() ? null : catalogBatch);
        var toolViews = new ArrayList<Map<String, Object>>();
        var currentDigests = new LinkedHashMap<String, String>();
        for (var r : catalog) {
            String name = r.get("tool_name").toString();
            currentDigests.put(name, r.get("schema_digest").toString());
            var item = new LinkedHashMap<String, Object>();
            item.put("toolName", name);
            item.put("schemaDigest", r.get("schema_digest"));
            item.put("status", r.get("status"));
            item.put("discoveredAt", toString(r.get("discovered_at")));
            item.put("reviewedAt", toString(r.get("reviewed_at")));
            item.put("skillDraftRef", r.get("skill_draft_ref"));
            toolViews.add(item);
        }
        view.put("tools", toolViews);
        if (diff) {
            var previous = new LinkedHashMap<String, String>();
            if (!catalog.isEmpty()) {
                Long previousBatch =
                        db.queryForObject(
                                "select coalesce(max(batch_no),0) from skill.mcp_tool_snapshot"
                                    + " where tenant_id=? and connection_code=? and connection_revision=?"
                                    + " and batch_no<?",
                                Long.class,
                                tenant(),
                                code,
                                revision,
                                catalogBatch);
                if (previousBatch != null && previousBatch > 0)
                    db.queryForList(
                                    "select tool_name,schema_digest from skill.mcp_tool_snapshot"
                                        + " where tenant_id=? and connection_code=? and connection_revision=?"
                                        + " and batch_no=?",
                                    tenant(),
                                    code,
                                    revision,
                                    previousBatch)
                            .forEach(
                                    r ->
                                            previous.put(
                                                    r.get("tool_name").toString(),
                                                    r.get("schema_digest").toString()));
            }
            var added = new ArrayList<String>();
            var changed = new ArrayList<String>();
            for (var entry : currentDigests.entrySet()) {
                String before = previous.get(entry.getKey());
                if (before == null) added.add(entry.getKey());
                else if (!before.equals(entry.getValue())) changed.add(entry.getKey());
            }
            var removed = new ArrayList<String>();
            for (var name : previous.keySet())
                if (!currentDigests.containsKey(name)) removed.add(name);
            Collections.sort(added);
            Collections.sort(changed);
            Collections.sort(removed);
            var diffView = new LinkedHashMap<String, Object>();
            diffView.put("added", added);
            diffView.put("changed", changed);
            diffView.put("removed", removed);
            view.put("diff", diffView);
        }
        return view;
    }

    // ------------------------------------------------------------------ 差异审核

    /**
     * 差异审核（接口稿 §5.6 POST .../tools/review）：对象为当前目录中的工具。
     * decision=APPROVE（缺省）经既有技能登记入口生成技能草稿（编码 mcp__&lt;连接&gt;__&lt;工具&gt;，
     * 主方案 §6.3 名称前缀约定），快照行迁移 AUTHORIZED 并冻结 skill_draft_ref；重复审核
     * 幂等返回既有引用。decision=SUBMIT 将 CHANGED 受理为 PENDING_REVIEW（人工审核队列）；
     * decision=REJECT 把 PENDING_REVIEW 驳回 CHANGED。
     */
    public Map<String, Object> review(String code, Map<String, Object> body) {
        admin();
        var input = object(Objects.requireNonNullElse(body, Map.of()));
        String toolName = text(input, "toolName");
        if (toolName.length() > 160) throw error(400, "INVALID_MCP_REVIEW", "toolName 无效");
        String decision =
                input.containsKey("decision") ? text(input, "decision") : "APPROVE";
        if (!Set.of("APPROVE", "SUBMIT", "REJECT").contains(decision))
            throw error(400, "INVALID_MCP_REVIEW", "decision 只允许 APPROVE/SUBMIT/REJECT");
        var row = connectionRow(code);
        int revision = ((Number) row.get("revision")).intValue();
        long catalogBatch = ((Number) row.get("batch_no")).longValue();
        if (catalogBatch == 0)
            throw error(409, "MCP_CONNECTION_STATE", "尚无已发布目录，先完成一次发现");
        return tx.execute(
                status -> {
                    var targets =
                            db.queryForList(
                                    "select tool_name,status,skill_draft_ref from skill.mcp_tool_snapshot"
                                        + " where tenant_id=? and connection_code=? and connection_revision=?"
                                        + " and batch_no=? and tool_name=? for update",
                                    tenant(),
                                    code,
                                    revision,
                                    catalogBatch,
                                    toolName);
                    if (targets.isEmpty())
                        throw error(404, "MCP_TOOL_NOT_FOUND", "当前目录中没有该工具: " + toolName);
                    var target = targets.getFirst();
                    String current = target.get("status").toString();
                    if ("REMOVED".equals(current))
                        throw error(409, "MCP_CONNECTION_STATE", "工具已移除，不能审核: " + toolName);
                    switch (decision) {
                        case "SUBMIT" -> {
                            if (!"CHANGED".equals(current))
                                throw error(
                                        409,
                                        "MCP_CONNECTION_STATE",
                                        "只有差异变更（CHANGED）工具可受理审核: " + toolName);
                            db.update(
                                    "update skill.mcp_tool_snapshot set status='PENDING_REVIEW'"
                                        + " where tenant_id=? and connection_code=? and connection_revision=?"
                                        + " and batch_no=? and tool_name=? and status='CHANGED'",
                                    tenant(),
                                    code,
                                    revision,
                                    catalogBatch,
                                    toolName);
                        }
                        case "REJECT" -> {
                            if (!"PENDING_REVIEW".equals(current))
                                throw error(
                                        409,
                                        "MCP_CONNECTION_STATE",
                                        "只有已受理（PENDING_REVIEW）工具可驳回: " + toolName);
                            db.update(
                                    "update skill.mcp_tool_snapshot set status='CHANGED'"
                                        + " where tenant_id=? and connection_code=? and connection_revision=?"
                                        + " and batch_no=? and tool_name=? and status='PENDING_REVIEW'",
                                    tenant(),
                                    code,
                                    revision,
                                    catalogBatch,
                                    toolName);
                        }
                        default -> {
                            if ("AUTHORIZED".equals(current))
                                return reviewView(code, revision, catalogBatch, toolName, "AUTHORIZED",
                                        target.get("skill_draft_ref").toString());
                            String draftRef = createSkillDraft(code, row, toolName, catalogBatch);
                            int updated =
                                    db.update(
                                            "update skill.mcp_tool_snapshot set status='AUTHORIZED',"
                                                + "reviewed_at=now(),skill_draft_ref=?"
                                                + " where tenant_id=? and connection_code=? and connection_revision=?"
                                                + " and batch_no=? and tool_name=?"
                                                + " and status in ('AVAILABLE','CHANGED','PENDING_REVIEW')",
                                            draftRef,
                                            tenant(),
                                            code,
                                            revision,
                                            catalogBatch,
                                            toolName);
                            if (updated == 0)
                                throw error(409, "MCP_CONNECTION_STATE", "工具状态已变化，审核未生效: " + toolName);
                        }
                    }
                    return reviewView(code, revision, catalogBatch, toolName, null, null);
                });
    }

    private Map<String, Object> reviewView(
            String code, int revision, long batch, String toolName, String status, String draftRef) {
        var fresh =
                db.queryForList(
                        "select status,reviewed_at,skill_draft_ref from skill.mcp_tool_snapshot"
                            + " where tenant_id=? and connection_code=? and connection_revision=?"
                            + " and batch_no=? and tool_name=?",
                        tenant(),
                        code,
                        revision,
                        batch,
                        toolName);
        var r = fresh.getFirst();
        var view = new LinkedHashMap<String, Object>();
        view.put("code", code);
        view.put("revision", revision);
        view.put("batchNo", batch);
        view.put("toolName", toolName);
        view.put("status", r.get("status"));
        view.put("reviewedAt", toString(r.get("reviewed_at")));
        view.put("skillDraftRef", r.get("skill_draft_ref"));
        if (status != null) view.put("previousStatus", status);
        if (draftRef != null) view.put("skillDraftRef", draftRef);
        return view;
    }

    /**
     * 生成技能草稿（现有入口 {@link SkillCatalog#create}）：MCP 执行契约直接冻结连接端点/凭据
     * 引用/超时与工具快照 Schema——首期按次建连下调用沿用现有 MCP 执行链路
     * （skill type=MCP → McpAdapter）。只读为缺省安全面（草稿可编辑后发布）。
     */
    private String createSkillDraft(
            String connectionCode, Map<String, Object> connection, String toolName, long batch) {
        String baseEndpoint = (String) connection.get("base_endpoint");
        if (baseEndpoint == null)
            throw error(
                    409,
                    "MCP_CONNECTION_PROFILE_UNRESOLVED",
                    "端点引用（app.connection_profile 复用）解析随接入卡交付；首期技能草稿需要直配 base_endpoint");
        var schemaRow =
                db.queryForList(
                        "select schema from skill.mcp_tool_snapshot where tenant_id=? and"
                            + " connection_code=? and tool_name=? order by batch_no desc limit 1",
                        tenant(),
                        connectionCode,
                        toolName);
        if (schemaRow.isEmpty())
            throw error(404, "MCP_TOOL_NOT_FOUND", "工具快照不存在: " + toolName);
        var schema = Json.read(schemaRow.getFirst().get("schema").toString(), Object.class);
        String draftCode = draftCode(connectionCode, toolName);
        var body = new LinkedHashMap<String, Object>();
        body.put("code", draftCode);
        body.put("name", "MCP " + toolName);
        body.put("type", "MCP");
        body.put(
                "description",
                "MCP 连接 " + connectionCode + " 工具 " + toolName + "（批次 " + batch + " 差异审核生成）");
        body.put("endpoint", baseEndpoint);
        body.put("toolName", toolName);
        body.put("timeoutMs", ((Number) connection.get("timeout_ms")).intValue());
        String secretRef = (String) connection.get("secret_ref");
        if (secretRef != null && !secretRef.isBlank()) body.put("authRef", secretRef);
        body.put("inputSchema", schema);
        body.put("outputSchema", Map.of("type", "object"));
        body.put(
                "execution",
                Map.of("readonly", true, "sideEffect", false, "idempotent", true, "cancellable", false));
        body.put("guard", Map.of("attributes", Map.of(), "roles", List.of()));
        try {
            catalog.create(body);
        } catch (PlatformException existing) {
            if ("SKILL_CODE_CONFLICT".equals(existing.code()))
                throw error(
                        409,
                        "MCP_REVIEW_CONFLICT",
                        "该工具已生成技能草稿（" + draftCode + "）；调整后请删除旧草稿或更换工具");
            throw existing;
        }
        return draftCode;
    }

    /**
     * mcp__&lt;连接&gt;__&lt;工具&gt; 编码约定（主方案 §6.3）：非法字符折叠为 _；连接段截到
     * 40 字符、工具段用剩余预算，总长不超过技能编码上界 64。长名截断可能撞编码——由
     * SKILL_CODE_CONFLICT 映射为 409 MCP_REVIEW_CONFLICT 明确暴露。
     */
    static String draftCode(String connectionCode, String toolName) {
        String codePart =
                connectionCode.length() <= 40 ? connectionCode : connectionCode.substring(0, 40);
        int toolRoom = 64 - ("mcp__".length() + codePart.length() + "__".length());
        if (toolRoom <= 0)
            throw error(400, "INVALID_MCP_REVIEW", "连接编码过长，无法生成 mcp__ 前缀技能编码");
        String toolPart = toolName.replaceAll("[^A-Za-z0-9_-]", "_").replaceAll("_+", "_");
        if (toolPart.length() > toolRoom) toolPart = toolPart.substring(0, toolRoom);
        return "mcp__" + codePart + "__" + toolPart;
    }
}
