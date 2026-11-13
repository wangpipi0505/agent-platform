package cn.agentplatform.skill.application;

import cn.agentplatform.common.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/**
 * A3-2 插件治理数据面（接口稿 §5.6 插件/MCP 表；库稿 §4.8 五表；主方案 §6.2）。
 *
 * <p>本卡只交付数据面与 API 骨架：来源登记/读取、发现受理（202 QUEUED）、发现结果查询、
 * 版本启停与授权/撤权的落库与状态机。发现执行器（QUEUED/租约失效 RUNNING 的原子认领、
 * 接管后代次递增、按来源串行批次发布、仅完整批次更新目录）属 A3-5 消费卡——本类提供的
 * {@code recordDiscoveredVersion}/{@code markRunning}/{@code complete}/{@code fail} 是
 * 执行器将来使用的写入口，DB 约束（claim 成对、终态清空、UNIQUE(source_id,batch_no)、
 * changes 仅随 SUCCEEDED）已把迟到提交与半批次挡在库层。
 */
@Service
public class PluginGovernance {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    @Autowired
    public PluginGovernance(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
    }

    private static PlatformException error(int status, String code, String message) {
        return new PlatformException(status, code, message);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object raw) {
        if (!(raw instanceof Map)) throw error(400, "INVALID_PLUGIN_REQUEST", "字段必须为JSON对象");
        return (Map<String, Object>) raw;
    }

    private static String text(Map<String, Object> b, String key) {
        String value = Objects.toString(b.get(key), "");
        if (value.isBlank() || value.length() > 4096)
            throw error(400, "INVALID_PLUGIN_REQUEST", "缺少或无效字段: " + key);
        return value;
    }

    private String tenant() {
        return Actor.current().tenantId();
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

    // ---------------------------------------------------------------- 来源

    /** 来源登记（接口稿 §5.6：ADMIN 登记）。能力包来源（CAPABILITY_PACKAGE）对应既有 V108 导入适配。 */
    public Map<String, Object> createSource(Map<String, Object> body) {
        admin();
        String code = text(body, "code");
        if (!code.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))
            throw error(400, "INVALID_PLUGIN_SOURCE", "编码无效");
        String name = text(body, "name");
        if (name.length() > 128) throw error(400, "INVALID_PLUGIN_SOURCE", "名称过长");
        String sourceType = text(body, "sourceType");
        if (!Set.of("REGISTRY", "GIT", "DIRECTORY", "CAPABILITY_PACKAGE").contains(sourceType))
            throw error(400, "INVALID_PLUGIN_SOURCE", "sourceType 未登记: " + sourceType);
        String location = text(body, "location");
        if (location.length() > 4096) throw error(400, "INVALID_PLUGIN_SOURCE", "location 过长");
        try {
            long id =
                    tx.execute(
                            status ->
                                    db.queryForObject(
                                            "insert into"
                                                + " skill.plugin_source(tenant_id,code,name,source_type,location,created_by)"
                                                + " values(?,?,?,?,?,?) returning id",
                                            Long.class,
                                            tenant(),
                                            code,
                                            name,
                                            sourceType,
                                            location,
                                            Actor.current().principalId()));
            return source(id);
        } catch (DataIntegrityViolationException race) {
            if (uniqueViolation(race))
                throw error(409, "PLUGIN_SOURCE_CONFLICT", "同租户已存在同编码插件来源");
            throw race;
        }
    }

    public List<Map<String, Object>> listSources() {
        admin();
        return db.queryForList(
                "select id,code,name,source_type,location,status,last_discovered_at,created_by,created_at"
                        + " from skill.plugin_source where tenant_id=? order by created_at desc,id desc",
                tenant()).stream().map(this::sourceView).toList();
    }

    public Map<String, Object> source(long id) {
        var rows =
                db.queryForList(
                        "select id,code,name,source_type,location,status,last_discovered_at,created_by,created_at"
                                + " from skill.plugin_source where tenant_id=? and id=?",
                        tenant(),
                        id);
        if (rows.isEmpty()) throw error(404, "PLUGIN_SOURCE_NOT_FOUND", "插件来源不存在");
        return sourceView(rows.getFirst());
    }

    private Map<String, Object> sourceView(Map<String, Object> row) {
        var view = new LinkedHashMap<String, Object>();
        view.put("id", ((Number) row.get("id")).longValue());
        view.put("code", row.get("code"));
        view.put("name", row.get("name"));
        view.put("sourceType", row.get("source_type"));
        view.put("location", row.get("location"));
        view.put("status", row.get("status"));
        view.put("lastDiscoveredAt", toString(row.get("last_discovered_at")));
        view.put("createdBy", row.get("created_by"));
        view.put("createdAt", toString(row.get("created_at")));
        return view;
    }

    // ---------------------------------------------------------------- 发现

    /**
     * 触发来源发现（接口稿 §5.6：受理返回 202 {discoveryId,status:'QUEUED'}）。批次号同来源单调
     * 递增（UNIQUE(source_id,batch_no) 兜底并发），本卡不执行发现——执行器认领属 A3-5。
     */
    public Map<String, Object> triggerDiscovery(long sourceId) {
        admin();
        var sourceRow = sourceRow(sourceId);
        if (!"ACTIVE".equals(sourceRow.get("status")))
            throw error(409, "PLUGIN_SOURCE_DISABLED", "停用的来源不能触发发现");
        String discoveryId = "disc_" + UUID.randomUUID().toString().replace("-", "");
        return tx.execute(
                status -> {
                    long batch =
                            db.queryForObject(
                                    "select coalesce(max(batch_no),0)+1 from skill.plugin_discovery"
                                        + " where source_id=?",
                                    Long.class,
                                    sourceId);
                    db.update(
                            "insert into skill.plugin_discovery(discovery_id,tenant_id,source_id,batch_no,status)"
                                    + " values(?,?,?,?,'QUEUED')",
                            discoveryId,
                            tenant(),
                            sourceId,
                            batch);
                    var result = new LinkedHashMap<String, Object>();
                    result.put("discoveryId", discoveryId);
                    result.put("status", "QUEUED");
                    result.put("batchNo", batch);
                    return result;
                });
    }

    /** 发现结果查询（接口稿 §5.6）：未完成省略结果字段；失败给 error 投影；成功给 changes 差异。 */
    public Map<String, Object> discovery(long sourceId, String discoveryId) {
        admin();
        if (discoveryId == null || !discoveryId.matches("disc_[0-9a-f]{32}"))
            throw error(404, "DISCOVERY_NOT_FOUND", "发现记录不存在");
        sourceRow(sourceId);
        var rows =
                db.queryForList(
                        "select * from skill.plugin_discovery where tenant_id=? and source_id=?"
                                + " and discovery_id=?",
                        tenant(),
                        sourceId,
                        discoveryId);
        if (rows.isEmpty()) throw error(404, "DISCOVERY_NOT_FOUND", "发现记录不存在");
        var row = rows.getFirst();
        String status = row.get("status").toString();
        var view = new LinkedHashMap<String, Object>();
        view.put("discoveryId", row.get("discovery_id"));
        view.put("status", status);
        view.put("batchNo", ((Number) row.get("batch_no")).longValue());
        view.put("createdAt", toString(row.get("created_at")));
        view.put("startedAt", toString(row.get("started_at")));
        view.put("finishedAt", toString(row.get("finished_at")));
        if ("FAILED".equals(status)) {
            view.put(
                    "error",
                    Map.of(
                            "code", Objects.toString(row.get("error_code"), ""),
                            "message", Objects.toString(row.get("error_summary"), "")));
        }
        if ("SUCCEEDED".equals(status))
            view.put("changes", Json.read(Objects.toString(row.get("changes"), "{}"), Object.class));
        return view;
    }

    /**
     * 发现结果写入口（A3-5 执行器消费）：插件主行按 (tenant,code) 归并、版本行按
     * (tenant,plugin,version) 幂等——同摘要重放返回既有版本（执行器重试安全），异摘要冲突。
     * 返回版本 id。manifest 上限 256KB 与能力元素形状在本入口前置校验，DB CHECK 兜底。
     */
    public long recordDiscoveredVersion(
            long sourceId,
            String pluginCode,
            String pluginName,
            String version,
            Map<String, Object> manifest,
            List<Map<String, Object>> capabilities) {
        admin();
        sourceRow(sourceId);
        if (pluginCode == null || !pluginCode.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))
            throw error(400, "INVALID_PLUGIN", "插件编码无效");
        if (pluginName == null || pluginName.isBlank() || pluginName.length() > 128)
            throw error(400, "INVALID_PLUGIN", "插件名称无效");
        if (version == null || version.isBlank() || version.length() > 64)
            throw error(400, "INVALID_PLUGIN_VERSION", "版本号无效");
        if (manifest == null || manifest.isEmpty())
            throw error(400, "INVALID_PLUGIN_MANIFEST", "manifest 必须为非空JSON对象");
        String manifestJson = Json.write(manifest);
        if (manifestJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 262144)
            throw error(400, "PLUGIN_MANIFEST_TOO_LARGE", "manifest 超过 256KB 上限");
        var capabilityList = validateCapabilities(capabilities);
        String manifestDigest = Digest.sha256(Json.canonical(manifest));
        return tx.execute(
                status -> {
                    Long pluginId;
                    var existing =
                            db.queryForList(
                                    "select id from skill.plugin where tenant_id=? and code=?",
                                    tenant(),
                                    pluginCode);
                    if (existing.isEmpty()) {
                        pluginId =
                                db.queryForObject(
                                        "insert into skill.plugin(tenant_id,code,name,source_id,created_by)"
                                                + " values(?,?,?,?,?) returning id",
                                        Long.class,
                                        tenant(),
                                        pluginCode,
                                        pluginName,
                                        sourceId,
                                        Actor.current().principalId());
                    } else {
                        pluginId = ((Number) existing.getFirst().get("id")).longValue();
                        // 换源语义（库稿 §4.8）：插件编码租户内唯一、与来源解耦；发现来源变化即更新
                        // 当前来源引用（换源审计随 A3-5 执行器落地，本卡保持引用正确）。
                        db.update(
                                "update skill.plugin set source_id=?,updated_at=now()"
                                        + " where tenant_id=? and id=? and source_id<>?",
                                sourceId,
                                tenant(),
                                pluginId,
                                sourceId);
                    }
                    var same =
                            db.queryForList(
                                    "select id from skill.plugin_version where tenant_id=? and"
                                        + " plugin_id=? and version=? and manifest_digest=?",
                                    tenant(),
                                    pluginId,
                                    version,
                                    manifestDigest);
                    long versionId;
                    if (!same.isEmpty()) {
                        // 同一 (plugin,version,manifest_digest) 重放：执行器重试/迟到提交安全。
                        versionId = ((Number) same.getFirst().get("id")).longValue();
                    } else {
                        var conflicting =
                                db.queryForList(
                                        "select id from skill.plugin_version where tenant_id=? and"
                                            + " plugin_id=? and version=?",
                                        tenant(),
                                        pluginId,
                                        version);
                        if (!conflicting.isEmpty())
                            throw error(
                                    409,
                                    "PLUGIN_VERSION_CONFLICT",
                                    "同版本号已存在且 manifest 摘要不同；换源或换内容请使用新版本号");
                        try {
                            versionId =
                                    db.queryForObject(
                                            "insert into skill.plugin_version(tenant_id,plugin_id,source_id,version,"
                                                    + "manifest,manifest_digest,capabilities)"
                                                    + " values(?,?,?,?,?::jsonb,?,?::jsonb) returning id",
                                            Long.class,
                                            tenant(),
                                            pluginId,
                                            sourceId,
                                            version,
                                            manifestJson,
                                            manifestDigest,
                                            Json.write(capabilityList));
                        } catch (DataIntegrityViolationException race) {
                            if (uniqueViolation(race))
                                throw error(409, "PLUGIN_VERSION_CONFLICT", "同租户同插件同版本并发冲突");
                            throw race;
                        }
                    }
                    db.update(
                            "update skill.plugin set latest_version_id=?,updated_at=now()"
                                    + " where tenant_id=? and id=?",
                            versionId,
                            tenant(),
                            pluginId);
                    return versionId;
                });
    }

    /** QUEUED→RUNNING 认领写入口（A3-5 执行器消费）：claim_owner+lease_until 成对，认领代次+1。 */
    public void markRunning(String discoveryId, String claimOwner, Instant leaseUntil) {
        admin();
        if (claimOwner == null || claimOwner.isBlank() || leaseUntil == null)
            throw error(400, "INVALID_DISCOVERY_CLAIM", "认领需要 owner 与租约截止时间成对出现");
        tx.executeWithoutResult(
                status -> {
                    var row = discoveryRow(discoveryId);
                    if (!"QUEUED".equals(row.get("status")))
                        throw error(409, "DISCOVERY_STATE", "仅 QUEUED 可被认领；租约失效接管的代次推进属 A3-5 执行器");
                    db.update(
                            "update skill.plugin_discovery set status='RUNNING',started_at=now(),"
                                    + "claim_owner=?,lease_until=?,claim_generation=claim_generation+1"
                                    + " where discovery_id=?",
                            claimOwner,
                            java.sql.Timestamp.from(leaseUntil),
                            discoveryId);
                });
    }

    /** RUNNING→SUCCEEDED 终态写入口：批次、代次由调用方核验；目录差异与终态同事务提交。 */
    public void complete(String discoveryId, long expectedGeneration, Map<String, Object> changes) {
        admin();
        String changesJson = Json.write(Objects.requireNonNullElse(changes, Map.of()));
        if (changesJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 262144)
            throw error(400, "DISCOVERY_CHANGES_TOO_LARGE", "changes 超过 256KB 上限");
        tx.executeWithoutResult(
                status -> {
                    var row = discoveryRow(discoveryId);
                    requireRunning(row);
                    requireGeneration(row, expectedGeneration);
                    db.update(
                            "update skill.plugin_discovery set status='SUCCEEDED',finished_at=now(),"
                                    + "claim_owner=null,lease_until=null,changes=?::jsonb"
                                    + " where discovery_id=?",
                            changesJson,
                            discoveryId);
                    db.update(
                            "update skill.plugin_source set last_discovered_at=now() where tenant_id=? and id=?",
                            tenant(),
                            ((Number) row.get("source_id")).longValue());
                });
    }

    /** 终态失败写入口：失败保留上一成功目录（本卡不触碰目录），只落可查询的失败投影。 */
    public void fail(String discoveryId, long expectedGeneration, String errorCode, String errorSummary) {
        admin();
        if (errorCode == null
                || !errorCode.matches("[A-Za-z0-9_]{1,64}")
                || errorSummary == null
                || errorSummary.isBlank()
                || errorSummary.length() > 512)
            throw error(400, "INVALID_DISCOVERY_FAILURE", "失败终态需要 error_code 与可展示摘要");
        tx.executeWithoutResult(
                status -> {
                    var row = discoveryRow(discoveryId);
                    requireGeneration(row, expectedGeneration);
                    String current = row.get("status").toString();
                    if (!"RUNNING".equals(current) && !"QUEUED".equals(current))
                        throw error(409, "DISCOVERY_STATE", "终态不可再次迁移");
                    db.update(
                            "update skill.plugin_discovery set status='FAILED',finished_at=now(),"
                                    + "claim_owner=null,lease_until=null,error_code=?,error_summary=?"
                                    + " where discovery_id=?",
                            errorCode,
                            errorSummary,
                            discoveryId);
                });
    }

    /** REVOKED 终态写入口（治理流，接口未单列端点）：停用拦截后续调用，历史运行保留原始版本。 */
    public void revokeVersion(long versionId, String reason) {
        admin();
        if (reason == null || reason.isBlank() || reason.length() > 512)
            throw error(400, "INVALID_PLUGIN_STATE", "吊销需要可审计原因");
        tx.executeWithoutResult(
                status -> {
                    var row = versionRow(versionId);
                    if ("REVOKED".equals(row.get("status"))) return;
                    db.update(
                            "update skill.plugin_version set status='REVOKED',disabled_reason=?,updated_at=now()"
                                    + " where tenant_id=? and id=?",
                            reason,
                            tenant(),
                            versionId);
                });
    }

    // ---------------------------------------------------------------- 版本与授权

    public List<Map<String, Object>> listPlugins(String status, Long sourceId) {
        admin();
        var args = new ArrayList<Object>(List.of(tenant()));
        String filter = " where p.tenant_id=?";
        if (status != null) {
            if (!Set.of("DISCOVERED", "ENABLED", "DISABLED", "REVOKED").contains(status))
                throw error(400, "INVALID_PLUGIN_STATE", "status 过滤只允许版本状态机枚举");
            filter += " and v.status=?";
            args.add(status);
        }
        if (sourceId != null) {
            filter += " and p.source_id=?";
            args.add(sourceId);
        }
        return db.queryForList(
                        "select v.id as version_id,v.plugin_id,p.code as plugin_code,p.name as plugin_name,"
                                + "v.version,v.status,v.manifest_digest,v.capabilities,v.source_id,"
                                + "v.discovered_at,v.enabled_at,v.disabled_reason"
                                + " from skill.plugin_version v join skill.plugin p on p.id=v.plugin_id"
                                + filter
                                + " order by p.code,v.version",
                        args.toArray())
                .stream()
                .map(this::versionView)
                .toList();
    }

    /** 启用（DISCOVERED/DISABLED→ENABLED）：停用拦截后续调用；REVOKED 为终态不可启用。 */
    public Map<String, Object> enableVersion(long versionId) {
        admin();
        return tx.execute(
                status -> {
                    var row = versionRow(versionId);
                    String current = row.get("status").toString();
                    switch (current) {
                        case "ENABLED" -> {}
                        case "DISCOVERED", "DISABLED" -> {
                            if (!"ACTIVE".equals(
                                    db.queryForObject(
                                            "select status from skill.plugin where tenant_id=? and id=?",
                                            String.class,
                                            tenant(),
                                            ((Number) row.get("plugin_id")).longValue())))
                                throw error(409, "PLUGIN_DISABLED", "插件主行已停用，不能启用版本");
                            db.update(
                                    "update skill.plugin_version set status='ENABLED',enabled_at=now(),"
                                            + "disabled_reason=null,updated_at=now() where tenant_id=? and id=?",
                                    tenant(),
                                    versionId);
                        }
                        default -> throw error(409, "PLUGIN_STATE", "REVOKED 为终态，不能启用");
                    }
                    return version(versionId);
                });
    }

    /** 停用（disable 填 reason，接口稿 §5.6）：停用拦截后续调用；原因随版本行可查询。 */
    public Map<String, Object> disableVersion(long versionId, Map<String, Object> body) {
        admin();
        String reason = text(Objects.requireNonNullElse(body, Map.of()), "reason");
        if (reason.length() > 512) throw error(400, "INVALID_PLUGIN_STATE", "停用原因过长");
        return tx.execute(
                status -> {
                    var row = versionRow(versionId);
                    String current = row.get("status").toString();
                    switch (current) {
                        case "DISABLED" -> {}
                        case "ENABLED", "DISCOVERED" -> db.update(
                                "update skill.plugin_version set status='DISABLED',disabled_reason=?,"
                                        + "updated_at=now() where tenant_id=? and id=?",
                                reason,
                                tenant(),
                                versionId);
                        default -> throw error(409, "PLUGIN_STATE", "REVOKED 为终态，不能停用");
                    }
                    return version(versionId);
                });
    }

    /** 授权（接口稿 §5.6：{granteeType,granteeCode,capabilities[]}）：能力必须为版本能力清单子集。 */
    public Map<String, Object> grant(long versionId, Map<String, Object> body) {
        admin();
        String granteeType = text(body, "granteeType");
        if (!Set.of("TENANT", "WORKSPACE").contains(granteeType))
            throw error(400, "INVALID_PLUGIN_GRANT", "granteeType 只允许 TENANT 或 WORKSPACE");
        String granteeCode = text(body, "granteeCode");
        if (granteeCode.length() > 64) throw error(400, "INVALID_PLUGIN_GRANT", "granteeCode 过长");
        var requested = validateCapabilities(body.get("capabilities") instanceof List<?> l
                ? l.stream()
                        .map(x -> object(x))
                        .toList()
                : null);
        if (requested.isEmpty()) throw error(400, "INVALID_PLUGIN_GRANT", "授权能力子集不能为空");
        return tx.execute(
                status -> {
                    var row = versionRow(versionId);
                    if (!"ENABLED".equals(row.get("status")))
                        throw error(409, "PLUGIN_STATE", "仅已启用版本可授权；治理流程为校验启用后授权");
                    var members = Json.read(row.get("capabilities").toString(), List.class);
                    for (var capability : requested)
                        if (!containsCapability(members, capability))
                            throw error(400, "PLUGIN_GRANT_NOT_SUBSET", "授权能力必须为版本能力清单的子集: " + capability);
                    var existing =
                            db.queryForList(
                                    "select id,status,capabilities from skill.plugin_grant"
                                        + " where plugin_version_id=? and grantee_type=? and grantee_code=?",
                                    versionId,
                                    granteeType,
                                    granteeCode);
                    if (!existing.isEmpty()) {
                        var grant = existing.getFirst();
                        String grantStatus = grant.get("status").toString();
                        if ("GRANTED".equals(grantStatus))
                            throw error(409, "PLUGIN_GRANT_EXISTS", "同授权对象已有有效授权；调整能力范围请先撤销");
                        // 撤销后的重新授权：同一行翻回 GRANTED（UNIQUE 不允许第二行）；能力范围以
                        // 原冻结行为准，防静默扩大授权（触发器保护不变式）。
                        var frozen = Json.read(grant.get("capabilities").toString(), Object.class);
                        if (!Json.tree(frozen).equals(Json.tree(requested)))
                            throw error(
                                    409,
                                    "PLUGIN_GRANT_EXISTS",
                                    "历史授权记录的能力范围已冻结且与本次不同；请保持原能力子集或更换授权对象");
                        db.update(
                                "update skill.plugin_grant set status='GRANTED',revoked_at=null,"
                                        + "revoke_reason=null where id=?",
                                grant.get("id"));
                        return grant(((Number) grant.get("id")).longValue());
                    }
                    long grantId =
                            db.queryForObject(
                                    "insert into skill.plugin_grant(plugin_version_id,grantee_type,grantee_code,"
                                            + "capabilities,granted_by)"
                                            + " values(?,?,?,?::jsonb,?) returning id",
                                    Long.class,
                                    versionId,
                                    granteeType,
                                    granteeCode,
                                    Json.write(requested),
                                    Actor.current().principalId());
                    return grant(grantId);
                });
    }

    /** 撤权（接口稿 §5.6：撤权下次解析生效）：状态迁移，不删除记录。 */
    public Map<String, Object> revoke(String grantId, Map<String, Object> body) {
        admin();
        String reason =
                body == null || body.get("reason") == null
                        ? ""
                        : body.get("reason").toString();
        if (reason.length() > 512) throw error(400, "INVALID_PLUGIN_GRANT", "撤权原因过长");
        return tx.execute(
                status -> {
                    long id;
                    try {
                        id = Long.parseLong(Objects.toString(grantId, ""));
                    } catch (NumberFormatException invalid) {
                        throw error(404, "PLUGIN_GRANT_NOT_FOUND", "授权记录不存在");
                    }
                    var rows =
                            db.queryForList(
                                    "select g.id from skill.plugin_grant g"
                                        + " join skill.plugin_version v on v.id=g.plugin_version_id"
                                        + " where g.id=? and v.tenant_id=?",
                                    id,
                                    tenant());
                    if (rows.isEmpty()) throw error(404, "PLUGIN_GRANT_NOT_FOUND", "授权记录不存在");
                    db.update(
                            "update skill.plugin_grant set status='REVOKED',revoked_at=now(),"
                                    + "revoke_reason=nullif(?,'') where id=? and status='GRANTED'",
                            reason,
                            id);
                    return grant(id);
                });
    }

    private Map<String, Object> grant(long grantId) {
        var rows =
                db.queryForList(
                        "select id,plugin_version_id,grantee_type,grantee_code,capabilities,status,"
                                + "granted_by,granted_at,revoked_at,revoke_reason"
                                + " from skill.plugin_grant where id=?",
                        grantId);
        var row = rows.getFirst();
        var view = new LinkedHashMap<String, Object>();
        view.put("grantId", ((Number) row.get("id")).longValue());
        view.put("pluginVersionId", ((Number) row.get("plugin_version_id")).longValue());
        view.put("granteeType", row.get("grantee_type"));
        view.put("granteeCode", row.get("grantee_code"));
        view.put("capabilities", Json.read(row.get("capabilities").toString(), Object.class));
        view.put("status", row.get("status"));
        view.put("grantedBy", row.get("granted_by"));
        view.put("grantedAt", toString(row.get("granted_at")));
        view.put("revokedAt", toString(row.get("revoked_at")));
        view.put("revokeReason", row.get("revoke_reason"));
        return view;
    }

    private Map<String, Object> version(long versionId) {
        var rows =
                db.queryForList(
                        "select v.id as version_id,v.plugin_id,p.code as plugin_code,p.name as plugin_name,"
                                + "v.version,v.status,v.manifest_digest,v.capabilities,v.source_id,"
                                + "v.discovered_at,v.enabled_at,v.disabled_reason"
                                + " from skill.plugin_version v join skill.plugin p on p.id=v.plugin_id"
                                + " where v.tenant_id=? and v.id=?",
                        tenant(),
                        versionId);
        if (rows.isEmpty()) throw error(404, "PLUGIN_VERSION_NOT_FOUND", "插件版本不存在");
        return versionView(rows.getFirst());
    }

    private Map<String, Object> versionView(Map<String, Object> row) {
        var view = new LinkedHashMap<String, Object>();
        view.put("versionId", ((Number) row.get("version_id")).longValue());
        view.put("pluginId", ((Number) row.get("plugin_id")).longValue());
        view.put("pluginCode", row.get("plugin_code"));
        view.put("pluginName", row.get("plugin_name"));
        view.put("version", row.get("version"));
        view.put("status", row.get("status"));
        view.put("manifestDigest", row.get("manifest_digest"));
        view.put("capabilities", Json.read(row.get("capabilities").toString(), Object.class));
        view.put("sourceId", ((Number) row.get("source_id")).longValue());
        view.put("discoveredAt", toString(row.get("discovered_at")));
        view.put("enabledAt", toString(row.get("enabled_at")));
        view.put("disabledReason", row.get("disabled_reason"));
        return view;
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> sourceRow(long sourceId) {
        var rows =
                db.queryForList(
                        "select id,status from skill.plugin_source where tenant_id=? and id=?",
                        tenant(),
                        sourceId);
        if (rows.isEmpty()) throw error(404, "PLUGIN_SOURCE_NOT_FOUND", "插件来源不存在");
        return rows.getFirst();
    }

    private Map<String, Object> discoveryRow(String discoveryId) {
        if (discoveryId == null || !discoveryId.matches("disc_[0-9a-f]{32}"))
            throw error(404, "DISCOVERY_NOT_FOUND", "发现记录不存在");
        var rows =
                db.queryForList(
                        "select discovery_id,source_id,status,claim_generation,batch_no from"
                                + " skill.plugin_discovery where tenant_id=? and discovery_id=? for update",
                        tenant(),
                        discoveryId);
        if (rows.isEmpty()) throw error(404, "DISCOVERY_NOT_FOUND", "发现记录不存在");
        return rows.getFirst();
    }

    private Map<String, Object> versionRow(long versionId) {
        var rows =
                db.queryForList(
                        "select id,plugin_id,status,capabilities from skill.plugin_version"
                                + " where tenant_id=? and id=? for update",
                        tenant(),
                        versionId);
        if (rows.isEmpty()) throw error(404, "PLUGIN_VERSION_NOT_FOUND", "插件版本不存在");
        return rows.getFirst();
    }

    private static void requireRunning(Map<String, Object> row) {
        if (!"RUNNING".equals(row.get("status")))
            throw error(409, "DISCOVERY_STATE", "仅 RUNNING 可提交终态；认领与接管规则属 A3-5 执行器");
    }

    private static void requireGeneration(Map<String, Object> row, long expectedGeneration) {
        if (((Number) row.get("claim_generation")).longValue() != expectedGeneration)
            throw error(409, "DISCOVERY_CLAIM_STALE", "认领代次不匹配：存在更新后的接管，本次提交被拒绝");
    }

    /** 能力元素形状校验（与库稿 §4.8 元素契约一致）；返回深拷贝列表供落库。 */
    private List<Map<String, Object>> validateCapabilities(List<Map<String, Object>> capabilities) {
        if (capabilities == null) throw error(400, "INVALID_PLUGIN_CAPABILITY", "capabilities 必须为非空数组");
        var result = new ArrayList<Map<String, Object>>();
        for (var raw : capabilities) {
            var element = object(raw);
            String type = Objects.toString(element.get("type"), "");
            Object ref = element.get("ref");
            Object digest = element.get("digest");
            if (!Set.of("SKILL", "MCP_CONNECTION", "GUIDE_BUNDLE").contains(type)
                    || !(ref instanceof String reference)
                    || reference.isBlank()
                    || reference.length() > 256
                    || !(digest instanceof String sha)
                    || !sha.matches("[a-f0-9]{64}"))
                throw error(
                        400,
                        "INVALID_PLUGIN_CAPABILITY",
                        "能力元素必须为 {type:'SKILL'|'MCP_CONNECTION'|'GUIDE_BUNDLE', ref, digest}: " + element);
            var normalized = new LinkedHashMap<String, Object>();
            normalized.put("type", type);
            normalized.put("ref", reference);
            normalized.put("digest", sha);
            result.add(normalized);
        }
        return result;
    }

    private static boolean containsCapability(Object members, Map<String, Object> capability) {
        if (!(members instanceof Collection<?> list)) return false;
        for (var member : list)
            if (member instanceof Map<?, ?> candidate
                    && Objects.toString(candidate.get("type"), "").equals(capability.get("type"))
                    && Objects.toString(candidate.get("ref"), "").equals(capability.get("ref"))
                    && Objects.toString(candidate.get("digest"), "").equals(capability.get("digest")))
                return true;
        return false;
    }

    private static String toString(Object value) {
        return value == null ? null : value.toString();
    }
}
