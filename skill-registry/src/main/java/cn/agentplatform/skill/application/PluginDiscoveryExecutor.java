package cn.agentplatform.skill.application;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PreDestroy;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

/**
 * A3-5 插件发现执行器（库稿 §4.8 执行器段；接口稿 §5.6；主方案 §6.2；台账 §7.4 A3-5）。
 *
 * <p><b>认领与接管</b>：原子认领 QUEUED 或租约失效的 RUNNING（单条条件 UPDATE，照 T2-6 分派
 * 三要素），认领代次单调递增；未到期的 RUNNING 由原实例持有——同实例重复执行只续租不重扫，
 * 他实例持有效租约时跳过。租约失效后被接管，原持有者的 complete/fail 因持有者（owner+代次）
 * 不符被拒，无法覆盖接管者结果（迟到实例无法覆盖结果；拒绝留痕不覆盖）。
 *
 * <p><b>批次串行发布门</b>：发布前在同一事务内校验持有者、租约未过期、状态仍 RUNNING、
 * 来源未停用、批次号大于本来源上次成功发布的批次（发布按来源串行提交）；通过后目录合并
 * （{@link PluginGovernance} recordDiscoveredVersion 的包内入口）、changes 落库、SUCCEEDED
 * 终态与 last_discovered_at 回写一次提交（目录更新、差异和终态在同一事务）。批次过旧、
 * 来源停用与失败一律保留原目录，只写可查询的终态原因；终态写一次后状态机拒绝再次提交
 * （同一批次终态只提交一次）。
 *
 * <p><b>异步与调度</b>：trigger 受理（202 QUEUED）后由实例内独立工作线程异步执行，不依赖
 * Spring 调度；本服务再以 {@code @Scheduled} 调用 {@link #pulse()} 兜底（R-6 装配，照
 * SkillOutbox 先例）——接管崩溃实例遗弃行并为自有临期租约续租；
 * 测试用同步直调 {@link #execute(String)}。
 *
 * <p>扫描经 {@link PluginSourceScanner}（首版演示桩 {@link DemoPluginSourceScanner}，demo:true；
 * 真实扫描器随来源适配卡交付）。执行器线程不经 HTTP 面：租户取自 plugin_discovery 行，
 * 目录创建者为系统执行者 {@value #SYSTEM_PRINCIPAL}，符合"身份只来自已验签 JWT"纪律。
 */
@Service
public class PluginDiscoveryExecutor {
    private static final Logger log = LoggerFactory.getLogger(PluginDiscoveryExecutor.class);

    /** 目录创建者使用的系统执行者标识（plugin.created_by 非空；执行器无用户身份）。 */
    static final String SYSTEM_PRINCIPAL = "plugin-discovery-executor";
    /** 租约时长（秒）：认领与续租统一；pulse 续租窗口取一半。 */
    static final int LEASE_SECONDS = 120;
    private static final int PULSE_BATCH = 8;

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final PluginGovernance governance;
    private final PluginSourceScanner scanner;
    /** 实例认领者标识：同实例重复执行识别续租、跨实例识别接管的依据（SkillOutbox owner 先例）。 */
    private final String owner =
            "discovery-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final ExecutorService workers =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        var thread = new Thread(runnable, "plugin-discovery-worker");
                        thread.setDaemon(true);
                        return thread;
                    });

    @Autowired
    public PluginDiscoveryExecutor(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            PluginGovernance governance,
            PluginSourceScanner scanner) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.governance = governance;
        this.scanner = scanner;
    }

    @PreDestroy
    void shutdown() {
        workers.shutdown();
    }

    /** 实例认领者标识（监控/测试用：markRunning 模拟本实例认领、pulse 续租窗口验证）。 */
    public String claimOwner() {
        return owner;
    }

    // ---------------------------------------------------------------- Actor 面

    /**
     * 触发来源发现（接口稿 §5.6：202 {discoveryId,status:'QUEUED'}）：受理复用数据面
     * （ADMIN 校验、停用来源 409 PLUGIN_SOURCE_DISABLED 拦截、批次号同来源单调+UNIQUE 兜底），
     * 随后派发实例内工作线程异步执行；派发失败（关停竞态）行留 QUEUED 由 pulse() 接管，
     * 受理不回滚。
     */
    public Map<String, Object> trigger(long sourceId) {
        var accepted = governance.triggerDiscovery(sourceId);
        String discoveryId = accepted.get("discoveryId").toString();
        try {
            workers.execute(
                    () -> {
                        try {
                            execute(discoveryId);
                        } catch (RuntimeException failure) {
                            // 工作线程兜底：可预期失败已在 execute 内写成 FAILED 终态，这里只防未预期异常吞线程。
                            log.error("发现执行未预期失败 discoveryId={}", discoveryId, failure);
                        }
                    });
        } catch (RejectedExecutionException shutdownRace) {
            log.warn("执行器关停中，发现 {} 留待 pulse 接管", discoveryId);
        }
        return accepted;
    }

    // ---------------------------------------------------------------- 执行

    /**
     * 执行入口（测试同步直调；trigger 派发与 pulse 兜底共用）：原子认领 → 扫描来源 → 发布门提交。
     * 返回 {discoveryId,status,batchNo,claimOwner,claimGeneration,startedAt,finishedAt,error?,changes?}
     * 投影，另按结果附加 renewed/skipped 标记。
     */
    public Map<String, Object> execute(String discoveryId) {
        var row = discoveryRow(discoveryId);
        if (row == null) throw new PlatformException(404, "DISCOVERY_NOT_FOUND", "发现记录不存在");
        String current = row.get("status").toString();
        if ("SUCCEEDED".equals(current) || "FAILED".equals(current))
            return view(discoveryId); // 终态只提交一次：重复执行直接回投影

        String tenantId = row.get("tenant_id").toString();
        long sourceId = ((Number) row.get("source_id")).longValue();
        var source = sourceRow(tenantId, sourceId);
        if (source.isEmpty())
            throw new PlatformException(404, "PLUGIN_SOURCE_NOT_FOUND", "插件来源不存在");
        // 来源级停用拦截（库稿 §4.8：来源停用保留原目录，写入可查询的终态原因）：未认领行直接
        // 失败终态；已被认领的行由其持有者的发布门处置（外部不替他人判死）。
        if ("DISABLED".equals(source.getFirst().get("status"))) {
            if ("QUEUED".equals(current)) {
                fail(
                        discoveryId,
                        ((Number) row.get("claim_generation")).longValue(),
                        "PLUGIN_SOURCE_DISABLED",
                        "来源已停用，发现不再执行，保留原目录");
                return view(discoveryId);
            }
            var running = view(discoveryId);
            running.put("skipped", true);
            return running;
        }

        var claim = claim(discoveryId);
        if (claim == null) {
            // 未取得认领：自有未到期租约→续租（不重扫）；他人持有或终态→跳过
            var after = discoveryRow(discoveryId);
            if (after != null
                    && "RUNNING".equals(after.get("status"))
                    && owner.equals(Objects.toString(after.get("claim_owner"), null))) {
                renew(discoveryId);
                var renewed = view(discoveryId);
                renewed.put("renewed", true);
                return renewed;
            }
            var skipped = view(discoveryId);
            skipped.put("skipped", true);
            return skipped;
        }

        long generation = ((Number) claim.get("claim_generation")).longValue();
        long batchNo = ((Number) claim.get("batch_no")).longValue();
        List<DiscoveredPlugin> discovered;
        try {
            discovered =
                    scanner.scan(
                            tenantId,
                            sourceId,
                            Objects.toString(source.getFirst().get("source_type"), ""),
                            Objects.toString(source.getFirst().get("location"), ""),
                            batchNo);
        } catch (Exception failure) {
            // 扫描失败：失败保留原目录，只落可查询原因。
            fail(discoveryId, generation, "DISCOVERY_SCAN_FAILED", shorten(failure.toString()));
            return view(discoveryId);
        }
        try {
            complete(discoveryId, generation, discovered == null ? List.of() : discovered);
        } catch (PlatformException failure) {
            // 发布门内异常（如目录合并冲突）：发布事务已整体回滚，失败保留原目录并落原因；
            // 若此时行已被接管，失败声明同样拒绝——行留待接管者处置。
            try {
                fail(discoveryId, generation, failure.code(), shorten(failure.getMessage()));
            } catch (PlatformException stale) {
                log.info("发现 {} 的失败终态被拒绝（{}）：行由接管者处置", discoveryId, stale.code());
            }
        }
        return view(discoveryId);
    }

    /**
     * 发布门提交（库稿 §4.8 执行器段；公开给测试直接驱动以模拟迟到实例）。同一事务内依序：
     * 状态 RUNNING（同一批次终态只提交一次）→持有者（owner+认领代次，迟到实例拒绝留痕不覆盖）
     * →租约未过期→来源未停用→批次号大于上次成功批次→目录合并（recordDiscoveredVersion 消费）
     * →changes+终态+last_discovered_at。门内拦截（来源停用/批次过旧）保留原目录、写 FAILED
     * 终态原因后返回 FAILED 投影；持有者不符抛 DISCOVERY_CLAIM_STALE，租约失效抛
     * DISCOVERY_LEASE_EXPIRED（行留待接管）。
     */
    public Map<String, Object> complete(
            String discoveryId, long expectedGeneration, List<DiscoveredPlugin> plugins) {
        Objects.requireNonNull(plugins, "完整批次必须给出扫描结果（空目录传空列表）");
        return tx.execute(
                status -> {
                    var rows =
                            db.queryForList(
                                    "select tenant_id,source_id,batch_no,status,claim_owner,"
                                        + "lease_until,claim_generation from skill.plugin_discovery"
                                        + " where discovery_id=? for update",
                                    discoveryId);
                    if (rows.isEmpty())
                        throw new PlatformException(404, "DISCOVERY_NOT_FOUND", "发现记录不存在");
                    var row = rows.getFirst();
                    String tenantId = row.get("tenant_id").toString();
                    long sourceId = ((Number) row.get("source_id")).longValue();
                    long batchNo = ((Number) row.get("batch_no")).longValue();
                    long generation = ((Number) row.get("claim_generation")).longValue();
                    // 状态校验在前：终态只提交一次，重放提交按状态机诊断（而非持有者）。
                    if (!"RUNNING".equals(row.get("status")))
                        throw new PlatformException(
                                409, "DISCOVERY_STATE", "仅 RUNNING 可提交终态；同一批次终态只提交一次");
                    // 持有者校验：迟到实例（被接管）一律拒绝留痕不覆盖（库稿 §4.8）。
                    if (!owner.equals(Objects.toString(row.get("claim_owner"), null))
                            || generation != expectedGeneration)
                        throw new PlatformException(
                                409,
                                "DISCOVERY_CLAIM_STALE",
                                "认领不匹配当前持有者或代次：存在接管，本次提交被拒绝");
                    // 租约校验：提交前校验租约，失效持有者不再可信，行留待接管（不写终态）。
                    var leaseUntil = (Timestamp) row.get("lease_until");
                    if (leaseUntil == null || !leaseUntil.toInstant().isAfter(Instant.now()))
                        throw new PlatformException(409, "DISCOVERY_LEASE_EXPIRED", "租约已过期：续租或等接管后再提交");

                    var source =
                            db.queryForList(
                                    "select status from skill.plugin_source"
                                        + " where id=? and tenant_id=? for update",
                                    sourceId,
                                    tenantId);
                    if (source.isEmpty())
                        throw new PlatformException(404, "PLUGIN_SOURCE_NOT_FOUND", "插件来源不存在");
                    // 来源停用门：保留原目录，写入可查询的终态原因。
                    if ("DISABLED".equals(source.getFirst().get("status"))) {
                        failTerminal(
                                discoveryId,
                                "PLUGIN_SOURCE_DISABLED",
                                "来源已停用，发现结果不发布，保留原目录");
                        return view(discoveryId);
                    }
                    // 批次串行门：批次号须大于上次成功发布的批次；旧批次保留原目录+终态原因。
                    long lastSucceeded =
                            db.queryForObject(
                                    "select coalesce(max(batch_no),0) from skill.plugin_discovery"
                                        + " where tenant_id=? and source_id=? and status='SUCCEEDED'",
                                    Long.class,
                                    tenantId,
                                    sourceId);
                    if (batchNo <= lastSucceeded) {
                        failTerminal(
                                discoveryId,
                                "DISCOVERY_BATCH_STALE",
                                "批次 " + batchNo + " 不大于上次成功发布批次 " + lastSucceeded + "，拒绝发布并保留原目录");
                        return view(discoveryId);
                    }

                    // 完整批次发布：差异分类→目录合并（recordDiscoveredVersion 消费）→changes+终态同事务。
                    var changes = publish(tenantId, sourceId, plugins);
                    String changesJson = Json.write(changes);
                    if (changesJson.getBytes(StandardCharsets.UTF_8).length > 262144)
                        throw new PlatformException(
                                400, "DISCOVERY_CHANGES_TOO_LARGE", "changes 超过 256KB 上限");
                    db.update(
                            "update skill.plugin_discovery set status='SUCCEEDED',finished_at=now(),"
                                + "claim_owner=null,lease_until=null,changes=?::jsonb where discovery_id=?",
                            changesJson,
                            discoveryId);
                    db.update(
                            "update skill.plugin_source set last_discovered_at=now()"
                                + " where id=? and tenant_id=?",
                            sourceId,
                            tenantId);
                    var done = view(discoveryId);
                    done.put("changes", changes);
                    return done;
                });
    }

    /**
     * 失败终态（库稿 §4.8：失败保留原目录，写入可查询的终态原因；公开给测试直接驱动）。
     * 认领代次校验贯穿：被接管后原持有者的失败声明同样拒绝（留痕不覆盖）。QUEUED 行
     * （未认领）可直接失败（来源停用等受理后拦截路径），以行上当前代次核验。
     */
    public void fail(String discoveryId, long expectedGeneration, String errorCode, String message) {
        if (errorCode == null
                || !errorCode.matches("[A-Za-z0-9_]{1,64}")
                || message == null
                || message.isBlank()
                || message.length() > 512)
            throw new PlatformException(400, "INVALID_DISCOVERY_FAILURE", "失败终态需要 error_code 与可展示摘要");
        tx.executeWithoutResult(
                status -> {
                    var rows =
                            db.queryForList(
                                    "select status,claim_generation from skill.plugin_discovery"
                                        + " where discovery_id=? for update",
                                    discoveryId);
                    if (rows.isEmpty())
                        throw new PlatformException(404, "DISCOVERY_NOT_FOUND", "发现记录不存在");
                    var row = rows.getFirst();
                    if (((Number) row.get("claim_generation")).longValue() != expectedGeneration)
                        throw new PlatformException(
                                409, "DISCOVERY_CLAIM_STALE", "认领代次不匹配：存在更新后的接管，失败声明被拒绝");
                    String current = row.get("status").toString();
                    if (!"RUNNING".equals(current) && !"QUEUED".equals(current))
                        throw new PlatformException(409, "DISCOVERY_STATE", "终态不可再次迁移");
                    failTerminal(discoveryId, errorCode, message);
                });
    }

    /**
     * 生产兜底入口（照 SkillOutbox 先例，R-6 起随本服务装配 @Scheduled）：
     * 先为本实例持有且临期的 RUNNING 续租，再认领并执行可执行行（QUEUED 或租约失效 RUNNING，
     * 含实例崩溃遗弃行的接管）。跨租户扫描只取 discovery_id（系统调度器例外），逐行
     * execute 按行租户隔离。返回认领执行的行数。
     */
    @Scheduled(fixedDelayString = "${agent.discovery.pulse-ms:5000}")
    public int pulse() {
        renewOwnExpiringLeases();
        var claimable =
                db.queryForList(
                        "select discovery_id from skill.plugin_discovery where status='QUEUED'"
                            + " or (status='RUNNING' and (lease_until is null or lease_until<=now()))"
                            + " order by created_at limit "
                            + PULSE_BATCH);
        int processed = 0;
        for (var row : claimable) {
            execute(row.get("discovery_id").toString());
            processed++;
        }
        return processed;
    }

    // ---------------------------------------------------------------- 发布门内部

    /**
     * 完整批次与当前目录的差异（库稿 §4.8 changes：added/changed/removed 各含插件、版本与
     * 能力变化）：added=目录无该 (plugin,version)；changed=已知插件出现新版本号（升级，
     * 附 previousVersion）；removed=本来源目录下有而本次扫描缺失的插件（快照不可变不删行，
     * 差异供管理员复核停用；快照行保留期间后续批次持续提示缺失，直至治理停用/吊销）；
     * 同版本同摘要=不变（幂等重放），同版本异摘要由合并入口按
     * PLUGIN_VERSION_CONFLICT 冲突回滚整笔发布。差异分类先于目录合并读库，条目 versionId
     * 取合并返回值——三份投影与目录更新同事务落库。
     */
    private Map<String, Object> publish(
            String tenantId, long sourceId, List<DiscoveredPlugin> plugins) {
        // 1) 合并前分类：known 取自当前目录（避免读到本事务刚合并的版本而漏记 changed）。
        var addedCodes = new LinkedHashMap<String, Boolean>(); // code -> 是否升级（changed）
        var previous = new LinkedHashMap<String, String>();
        for (var plugin : plugins) {
            var versions =
                    db.queryForList(
                            "select v.version from skill.plugin_version v"
                                + " join skill.plugin p on p.id=v.plugin_id"
                                + " where v.tenant_id=? and p.code=?",
                            tenantId,
                            plugin.code());
            var known =
                    versions.stream()
                            .map(v -> v.get("version").toString())
                            .collect(Collectors.toSet());
            if (!known.contains(plugin.version())) {
                addedCodes.put(plugin.code(), !known.isEmpty());
                if (!known.isEmpty()) previous.put(plugin.code(), latestOf(tenantId, plugin.code()));
            }
        }
        // 2) removed：本来源目录下有而本次扫描缺失的插件（取 latest 版本投影；行保留不删）。
        var scannedCodes = plugins.stream().map(DiscoveredPlugin::code).collect(Collectors.toSet());
        var removed = new ArrayList<Map<String, Object>>();
        for (var row :
                db.queryForList(
                        "select p.code,v.id as version_id,v.version,v.capabilities from skill.plugin p"
                            + " join skill.plugin_version v on v.id=p.latest_version_id"
                            + " where p.tenant_id=? and p.source_id=?",
                        tenantId,
                        sourceId)) {
            if (!scannedCodes.contains(row.get("code").toString())) {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("plugin", row.get("code"));
                entry.put("version", row.get("version"));
                entry.put("versionId", ((Number) row.get("version_id")).longValue());
                entry.put(
                        "capabilities",
                        Json.read(Objects.toString(row.get("capabilities"), "[]"), Object.class));
                removed.add(entry);
            }
        }
        // 3) 目录合并（recordDiscoveredVersion 消费）：冲突（同版本异摘要）整体回滚发布事务。
        var merged = new LinkedHashMap<String, Long>();
        for (var plugin : plugins)
            merged.put(
                    plugin.code(),
                    governance.mergeDiscoveredVersion(
                            tenantId,
                            SYSTEM_PRINCIPAL,
                            sourceId,
                            plugin.code(),
                            plugin.name(),
                            plugin.version(),
                            plugin.manifest(),
                            plugin.capabilities()));
        // 4) 组装 added/changed 条目（versionId 取合并返回值）。
        var added = new ArrayList<Map<String, Object>>();
        var changed = new ArrayList<Map<String, Object>>();
        for (var plugin : plugins) {
            Boolean upgrade = addedCodes.get(plugin.code());
            if (upgrade == null) continue; // 同版本（同摘要幂等/异摘要冲突）不进差异
            var entry = new LinkedHashMap<String, Object>();
            entry.put("plugin", plugin.code());
            entry.put("version", plugin.version());
            entry.put("versionId", merged.get(plugin.code()));
            entry.put("capabilities", plugin.capabilities());
            if (upgrade) entry.put("previousVersion", previous.get(plugin.code()));
            if (upgrade) changed.add(entry);
            else added.add(entry);
        }
        var changes = new LinkedHashMap<String, Object>();
        changes.put("added", added);
        changes.put("changed", changed);
        changes.put("removed", removed);
        return changes;
    }

    private String latestOf(String tenantId, String pluginCode) {
        var rows =
                db.queryForList(
                        "select v.version from skill.plugin p"
                            + " join skill.plugin_version v on v.id=p.latest_version_id"
                            + " where p.tenant_id=? and p.code=?",
                        tenantId,
                        pluginCode);
        return rows.isEmpty() ? null : rows.getFirst().get("version").toString();
    }

    /** 门内失败终态：保留原目录，只写可查询原因（调用方持有事务与行锁；按主键定位）。 */
    private void failTerminal(String discoveryId, String errorCode, String summary) {
        db.update(
                "update skill.plugin_discovery set status='FAILED',finished_at=now(),"
                    + "claim_owner=null,lease_until=null,error_code=?,error_summary=?"
                    + " where discovery_id=?",
                errorCode,
                summary,
                discoveryId);
    }

    // ---------------------------------------------------------------- 认领与租约

    /**
     * 原子认领（库稿 §4.8）：QUEUED 或租约失效 RUNNING → RUNNING+owner+租约+代次+1。
     * 条件 UPDATE 是唯一写入口，并发认领恰一人成功；返回 RETURNING 列
     * {claim_generation,source_id,batch_no} 或 null。
     */
    private Map<String, Object> claim(String discoveryId) {
        var claimed =
                db.queryForList(
                        "update skill.plugin_discovery set status='RUNNING',"
                            + "started_at=coalesce(started_at,now()),claim_owner=?,"
                            + "lease_until=now()+make_interval(secs=>(?::double precision)),"
                            + "claim_generation=claim_generation+1"
                            + " where discovery_id=? and (status='QUEUED' or (status='RUNNING'"
                            + " and (lease_until is null or lease_until<=now())))"
                            + " returning claim_generation,source_id,batch_no",
                        owner,
                        LEASE_SECONDS,
                        discoveryId);
        return claimed.isEmpty() ? null : claimed.getFirst();
    }

    /** 未到期 RUNNING 由原实例续租（库稿 §4.8）：只延租约，不改代次不重扫。 */
    private void renew(String discoveryId) {
        db.update(
                "update skill.plugin_discovery set"
                    + " lease_until=now()+make_interval(secs=>(?::double precision))"
                    + " where discovery_id=? and claim_owner=? and status='RUNNING'"
                    + " and lease_until>now()",
                LEASE_SECONDS,
                discoveryId,
                owner);
    }

    private void renewOwnExpiringLeases() {
        db.update(
                "update skill.plugin_discovery set"
                    + " lease_until=now()+make_interval(secs=>(?::double precision))"
                    + " where status='RUNNING' and claim_owner=?"
                    + " and lease_until<=now()+make_interval(secs=>(?::double precision))",
                LEASE_SECONDS,
                owner,
                LEASE_SECONDS / 2);
    }

    // ---------------------------------------------------------------- 投影

    private Map<String, Object> discoveryRow(String discoveryId) {
        var rows =
                db.queryForList(
                        "select discovery_id,tenant_id,source_id,batch_no,status,claim_owner,"
                            + "claim_generation from skill.plugin_discovery where discovery_id=?",
                        discoveryId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<Map<String, Object>> sourceRow(String tenantId, long sourceId) {
        return db.queryForList(
                "select source_type,location,status from skill.plugin_source"
                    + " where id=? and tenant_id=?",
                sourceId,
                tenantId);
    }

    /** 终态/状态投影（无 Actor 面；与 PluginGovernance.discovery 同形状，租户取自行）。 */
    private Map<String, Object> view(String discoveryId) {
        var rows = db.queryForList("select * from skill.plugin_discovery where discovery_id=?", discoveryId);
        if (rows.isEmpty()) throw new PlatformException(404, "DISCOVERY_NOT_FOUND", "发现记录不存在");
        var row = rows.getFirst();
        String status = row.get("status").toString();
        var out = new LinkedHashMap<String, Object>();
        out.put("discoveryId", row.get("discovery_id"));
        out.put("status", status);
        out.put("batchNo", ((Number) row.get("batch_no")).longValue());
        out.put("claimOwner", row.get("claim_owner"));
        out.put("claimGeneration", ((Number) row.get("claim_generation")).longValue());
        out.put("startedAt", stringOf(row.get("started_at")));
        out.put("finishedAt", stringOf(row.get("finished_at")));
        if ("FAILED".equals(status))
            out.put(
                    "error",
                    Map.of(
                            "code",
                            Objects.toString(row.get("error_code"), ""),
                            "message",
                            Objects.toString(row.get("error_summary"), "")));
        if ("SUCCEEDED".equals(status) && row.get("changes") != null)
            out.put("changes", Json.read(row.get("changes").toString(), Object.class));
        return out;
    }

    private static String stringOf(Object value) {
        return value == null ? null : value.toString();
    }

    /** 失败摘要截断到可存储上限（error_summary 512）。 */
    private static String shorten(String message) {
        if (message == null || message.isBlank()) return "执行失败";
        return message.length() > 512 ? message.substring(0, 512) : message;
    }
}
