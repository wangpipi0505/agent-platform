package cn.agentplatform.app.application;

import cn.agentplatform.app.domain.AppDigest;
import cn.agentplatform.app.domain.AppRules;
import cn.agentplatform.app.domain.PublishGate;
import cn.agentplatform.app.domain.PublishWaiver;
import cn.agentplatform.app.infra.AppPublicationStore;
import cn.agentplatform.app.infra.ReleasePins;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.schema.SchemaDialect;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import java.sql.ResultSet;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** 应用草稿与发布事务。业务定义进入候选摘要，画布布局只用于展示；运行时始终读取不可变版本。 */
@Service
public class AppService {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(AppService.class);

    private final JdbcTemplate db;
    /** 发布绑定观测的持久化协作对象：只做 {@code app.publish_pin} 的读写，事务仍归本类。 */
    private final AppPublicationStore publicationStore;
    private final PlatformAccessService platformAccess;
    /**
     * 草稿生命周期协作对象：新建/复制/保存/元数据/停启用归档回滚/删除与生命周期日志已搬入
     * {@link AppLifecycleService}，本类只保留兼容门面。发布预检与提交仍在本类，二者事务边界互不相同。
     */
    private final AppLifecycleService lifecycle;
    private final AppPublicationService publication;
    private final TransactionTemplate tx;
    private final TransactionTemplate publishedReadTx;
    private final RestClient http = client();
    private final String skillUrl, modelUrl, monitorUrl, engineUrl;
    private final TemplateService templates;
    private OnboardingApplicationSources onboardingSources;
    private cn.agentplatform.app.infra.AppAuthorityTransport authorityTransport;
    @Value("${agent.security.mode:dev-hmac}")
    private String serviceSecurityMode = "dev-hmac";

    @Autowired
    public void onboardingSources(OnboardingApplicationSources sources) {
        this.onboardingSources = sources;
    }

    @Autowired
    public void authorityTransport(cn.agentplatform.app.infra.AppAuthorityTransport transport) {
        this.authorityTransport = transport;
    }

    /**
     * 发布门禁模式与豁免回滚开关。用字段注入而不是构造参数，保持既有装配签名不变；手工构造（测试/诊断）时
     * 取初始值 BLOCKING + 开启，等价于生产默认的失败关闭。
     */
    @Value("${agent.publish.gate-mode:BLOCKING}")
    private String configuredGateMode = "BLOCKING";

    @Value("${agent.publish.waiver-enabled:true}")
    private boolean waiverEnabled = true;

    private String publicationTarget = "PRODUCTION";

    /** 发布目标由部署固定，不能由浏览器或本体 pin.environment 自报。 */
    @Value("${agent.publish.target-environment:PRODUCTION}")
    public void publicationTarget(String value) {
        String target = Objects.toString(value, "").trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PRODUCTION", "NON_PRODUCTION").contains(target))
            throw new IllegalStateException("PUBLICATION_TARGET_INVALID");
        publicationTarget = target;
    }

    /**
     * pin 结果落库开关与本体环境名。T18.3：绑定状态机结果在发布事务提交后落 app.publish_pin，
     * 反查不再依赖日志。落库失败不阻断已提交的发布（与 T-pin fail-open 同一取向）；
     * {@code agent.pin.record-enabled=false} 是回滚开关，只停写、不改发布响应。
     */
    @Value("${agent.pin.record-enabled:true}")
    private boolean pinRecordEnabled = true;

    @Value("${agent.pin.environment:production}")
    private String pinEnvironment = "production";

    /** 测试/诊断入口：覆盖 pin 落库开关，不改装配与依赖。 */
    void pinRecord(boolean enabled) {
        this.pinRecordEnabled = enabled;
    }

    /**
     * 本体发布绑定客户端。构造签名被大量手工构造（测试/诊断）固定，改用可选 setter 注入；未装配时发布
     * 照常成功并返回 pin=DISABLED，绝不因为缺少可选集成阻断既有发布路径。
     */
    private ReleasePins pins;

    @Autowired(required = false)
    public void pins(ReleasePins pins) {
        this.pins = pins;
    }

    /** 豁免查询集中在此：撤销/到期过滤与"最新窗口优先"只在这两处生效，避免多处各写一份条件。 */
    private static final String WAIVER_BY_ID =
            "select * from app.publish_waiver where tenant_id=? and app_code=? and id=?";

    private static final String WAIVER_ACTIVE =
            "select * from app.publish_waiver where tenant_id=? and app_code=? and draft_rev=?"
                    + " and candidate_sha256=? and revoked_at is null and expires_at>now()"
                    + " order by expires_at desc limit 1";

    public AppService(
            JdbcTemplate db,
            org.springframework.transaction.PlatformTransactionManager manager,
            @Value("${agent.services.skill:http://127.0.0.1:18083}") String skillUrl,
            @Value("${agent.services.model:http://127.0.0.1:18084}") String modelUrl,
            @Value("${agent.services.monitor:http://127.0.0.1:18085}") String monitorUrl,
            @Value("${agent.services.engine:http://127.0.0.1:18082}") String engineUrl) {
        this.engineUrl = engineUrl;
        this.db = db;
        this.publicationStore = new AppPublicationStore(db);
        this.platformAccess = new PlatformAccessService(db);
        // 生命周期子步只需读取与两条校验，用窄接口反向注入；发布预检/提交仍留在本类，
        // 因此不存在"生命周期依赖发布实现"的反向环。
        this.lifecycle =
                new AppLifecycleService(
                        db,
                        manager,
                        new AppLifecycleService.Port() {
                            @Override
                            public Map<String, Object> load(String code, boolean lock) {
                                return get(code, lock);
                            }

                            @Override
                            public Map<String, Object> reload(String code) {
                                return get(code);
                            }

                            @Override
                            public Map<String, Object> version(String code, int versionNo) {
                                return AppService.this.version(code, versionNo);
                            }

                            @Override
                            public Map<String, Object> templateConfig(Map<String, Object> config) {
                                return AppService.this.templateConfig(config);
                            }

                            @Override
                            public void assertCapabilitySource(Map<String, Object> app) {
                                AppService.this.assertCapabilitySource(app);
                            }
                        });
        this.templates = new TemplateService(db);
        this.tx = new TransactionTemplate(manager);
        this.publishedReadTx = new TransactionTemplate(manager);
        this.publishedReadTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.publishedReadTx.setTimeout(3);
        this.skillUrl = skillUrl;
        this.modelUrl = modelUrl;
        this.monitorUrl = monitorUrl;
        // 发布簇在最后装配：Port 取的是本类已建好的写事务与远端地址，避免读到构造未完成的状态。
        this.publication = new AppPublicationService(publicationPort());
    }

    private static RestClient client() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(60000);
        return RestClient.builder().requestFactory(factory).build();
    }

    private String tenant() {
        return Actor.current().tenantId();
    }

    public static PlatformException error(int status, String code, String message) {
        return new PlatformException(status, code, message);
    }

    /** 兼容入口：取值与校验规则已迁至 domain.CapabilityValues，错误码与缺省值不变。 */
    public static String text(Map<String, Object> body, String field) {
        return cn.agentplatform.app.domain.CapabilityValues.text(body, field);
    }

    public static Map<String, Object> object(Object v) {
        return cn.agentplatform.app.domain.CapabilityValues.object(v);
    }

    public static List<Map<String, Object>> objects(Object v) {
        return cn.agentplatform.app.domain.CapabilityValues.objects(v);
    }

    public static long number(Map<String, Object> body, String field) {
        Object v = body.get(field);
        if (!(v instanceof Number n) || n.longValue() < 1)
            throw error(400, "INVALID_INPUT", "缺少或无效数字: " + field);
        return n.longValue();
    }

    private Map<String, Object> row(ResultSet r, int n) throws SQLException {
        var v = new LinkedHashMap<String, Object>();
        for (String k : List.of("code", "name", "type", "description", "domain", "status"))
            v.put(k, r.getString(k));
        v.put("id", r.getLong("id"));
        v.put("draftId", r.getLong("draft_id"));
        v.put("draftNo", r.getInt("draft_no"));
        v.put("bizCategory", r.getString("biz_category"));
        v.put("draftRev", r.getLong("draft_rev"));
        v.put("currentVersionNo", r.getObject("current_version_no"));
        v.put("updatedAt", r.getTimestamp("updated_at").toInstant().toString());
        for (String k : List.of("tags", "config", "definition", "layout"))
            v.put(k, Json.read(r.getString(k), Object.class));
        // 先算哈希再写派生字段：candidateSha256 覆盖快照内容，semanticDigest 是更窄的身份。
        String candidateSha = sha(v);
        String semanticDigest = AppDigest.semantic(candidate(v));
        v.put("candidateSha256", candidateSha);
        v.put("semanticDigest", semanticDigest);
        return v;
    }

    public static Map<String, Object> candidate(Map<String, Object> v) {
        var c = new LinkedHashMap<String, Object>();
        c.put("appCode", v.get("code"));
        for (String k :
                List.of(
                        "type",
                        "name",
                        "description",
                        "domain",
                        "bizCategory",
                        "tags",
                        "config",
                        "definition")) c.put(k, v.get(k));
        return c;
    }

    public static String sha(Map<String, Object> v) {
        return Digest.sha256(AppRules.canonical(candidate(v)));
    }

    public Map<String, Object> get(String code) {
        return get(code, false);
    }

    private Map<String, Object> get(String code, boolean lock) {
        var rows =
                db.query(
                        "select a.*,d.id as"
                            + " draft_id,d.draft_no,d.draft_rev,d.config,d.definition,d.layout from"
                            + " app.application a join app.app_draft d on d.tenant_id=a.tenant_id"
                            + " and d.app_code=a.code where a.tenant_id=? and a.code=?"
                                + (lock ? " for update of d" : ""),
                        this::row,
                        tenant(),
                        code);
        if (rows.isEmpty()) throw error(404, "APP_NOT_FOUND", "应用不存在");
        return rows.getFirst();
    }

    public List<Map<String, Object>> list() {
        return list(Map.of());
    }

    public List<Map<String, Object>> list(Map<String, String> filters) {
        return list(filters, new PlatformAccessService.ApplicationScope(true, Set.of()));
    }

    public List<Map<String, Object>> list(Map<String, String> filters, PlatformAccessService.ApplicationScope scope) {
        if (!scope.all() && scope.codes().isEmpty()) return List.of();
        var sql =
                new StringBuilder(
                        "select a.*,d.id as"
                            + " draft_id,d.draft_no,d.draft_rev,d.config,d.definition,d.layout from"
                            + " app.application a join app.app_draft d on d.tenant_id=a.tenant_id"
                            + " and d.app_code=a.code where a.tenant_id=?");
        var args = new ArrayList<Object>();
        args.add(tenant());
        if (!scope.all()) {
            sql.append(" and a.code in (").append(String.join(",", Collections.nCopies(scope.codes().size(), "?")))
                    .append(")");
            args.addAll(scope.codes());
        }
        String status = filters.getOrDefault("status", "").trim();
        if (status.isEmpty()) sql.append(" and a.status<>'ARCHIVED'");
        else {
            if (!Set.of("DRAFT", "PUBLISHED", "DISABLED", "ARCHIVED").contains(status))
                throw error(400, "INVALID_INPUT", "应用状态无效");
            sql.append(" and a.status=?");
            args.add(status);
        }
        for (String key : List.of("q", "code", "domain", "bizCategory", "tag")) {
            String value = filters.getOrDefault(key, "").trim();
            if (value.length() > 128) throw error(400, "INVALID_INPUT", "检索条件过长: " + key);
            if (value.isEmpty()) continue;
            switch (key) {
                case "q" -> {
                    // position treats %, _ and backslashes as literal search text, not SQL
                    // wildcards.
                    sql.append(
                            " and (position(lower(?) in lower(a.name))>0"
                                    + " or position(lower(?) in lower(a.code))>0)");
                    args.add(value);
                    args.add(value);
                }
                case "tag" -> {
                    sql.append(" and jsonb_exists(a.tags,?)");
                    args.add(value);
                }
                default -> {
                    sql.append(" and a.")
                            .append(key.equals("bizCategory") ? "biz_category" : key)
                            .append("=?");
                    args.add(value);
                }
            }
        }
        sql.append(" order by a.updated_at desc,a.code");
        return db.query(sql.toString(), this::row, args.toArray());
    }









    public Map<String, Object> create(Map<String, Object> body) {
        return lifecycle.create(body);
    }

    public Map<String, Object> copy(String sourceCode, Map<String, Object> body) {
        return lifecycle.copy(sourceCode, body);
    }

    public Map<String, Object> save(String code, Map<String, Object> body, boolean flow) {
        return lifecycle.save(code, body, flow);
    }

    public Map<String, Object> metadata(String code, Map<String, Object> body) {
        return lifecycle.metadata(code, body);
    }

    private void log(String code, String action, String from, String to, Object detail) {
        lifecycle.log(code, action, from, to, detail);
    }

    public List<Map<String, Object>> lifecycleLogs(String code) {
        return lifecycle.lifecycleLogs(code);
    }

    public Map<String, Object> lifecycle(String code, String action, Map<String, Object> body) {
        return lifecycle.lifecycle(code, action, body);
    }

    public void delete(String code) {
        lifecycle.delete(code);
    }
    public List<Map<String, Object>> references(String code) {
        return db.queryForList(
                "select app_code,app_version_no,skill_code,skill_version from app.skill_reference"
                        + " where tenant_id=? and skill_code=? order by app_code,app_version_no",
                tenant(),
                code);
    }

    public List<Map<String, Object>> versions(String code) {
        get(code);
        return db.query(
                "select version_no,draft_rev,candidate_sha256,published_at,eval_report_id from"
                    + " app.app_version where tenant_id=? and app_code=? order by version_no desc",
                (r, n) ->
                        Map.<String, Object>of(
                                "versionNo",
                                r.getInt(1),
                                "draftRev",
                                r.getLong(2),
                                "candidateSha256",
                                r.getString(3),
                                "publishedAt",
                                r.getTimestamp(4).toInstant().toString(),
                                "evalReportId",
                                r.getString(5)),
                tenant(),
                code);
    }

    public Map<String, Object> version(String code, int version) {
        var rows =
                db.query(
                        "select id,snapshot::text from app.app_version where tenant_id=? and"
                                + " app_code=? and version_no=?",
                        (r, n) -> {
                            var snapshot =
                                    new LinkedHashMap<String, Object>(
                                            Json.read(r.getString(2), Map.class));
                            snapshot.put("appVersionKey", snapshot.get("appVersionId"));
                            snapshot.put("appVersionId", r.getLong(1));
                            snapshot.put("versionId", r.getLong(1));
                            return snapshot;
                        },
                        tenant(),
                        code,
                        version);
        if (rows.isEmpty()) throw error(404, "VERSION_NOT_FOUND", "版本不存在");
        return object(rows.getFirst());
    }

    public Map<String, Object> versionDiff(String code, int fromVersion, int toVersion) {
        if (fromVersion < 1 || toVersion < 1) throw error(400, "INVALID_INPUT", "版本号必须为正整数");
        get(code);
        var from = version(code, fromVersion);
        var to = version(code, toVersion);
        var changes = new ArrayList<Map<String, Object>>();
        // Compare business configuration only; generated publication identities and evidence are
        // deliberately excluded, and neither immutable snapshot nor the current draft is changed.
        diff("", candidate(from), candidate(to), changes);
        return Map.of(
                "appCode",
                code,
                "fromVersion",
                fromVersion,
                "toVersion",
                toVersion,
                "fromSha256",
                from.get("candidateSha256"),
                "toSha256",
                to.get("candidateSha256"),
                "changes",
                changes);
    }

    private static void diff(
            String path, Object before, Object after, List<Map<String, Object>> changes) {
        if (Objects.equals(before, after)) return;
        if (before instanceof Map<?, ?> left && after instanceof Map<?, ?> right) {
            var keys = new TreeSet<String>();
            left.keySet().forEach(k -> keys.add(k.toString()));
            right.keySet().forEach(k -> keys.add(k.toString()));
            for (String key : keys) {
                String child = path + "/" + key.replace("~", "~0").replace("/", "~1");
                if (left.containsKey(key) && right.containsKey(key))
                    diff(child, left.get(key), right.get(key), changes);
                else {
                    var change = new LinkedHashMap<String, Object>();
                    change.put("path", child);
                    change.put("operation", left.containsKey(key) ? "REMOVE" : "ADD");
                    if (left.containsKey(key)) change.put("before", left.get(key));
                    if (right.containsKey(key)) change.put("after", right.get(key));
                    changes.add(change);
                }
            }
        } else {
            var change = new LinkedHashMap<String, Object>();
            change.put("path", path);
            change.put("operation", "REPLACE");
            change.put("before", before);
            change.put("after", after);
            changes.add(change);
        }
    }

    public Map<String, Object> published(String code) {
        var app = get(code);
        if (Set.of("DISABLED", "ARCHIVED").contains(app.get("status"))
                || app.get("currentVersionNo") == null)
            throw error(409, "APP_UNAVAILABLE", "应用未发布或已停用");
        return version(code, ((Number) app.get("currentVersionNo")).intValue());
    }

    /** Current published payload only. Other draft/history capabilities need their own migration. */
    public Map<String, Object> publishedAuthorized(String code) {
        // Never perform online current inside a caller's transaction/locks, including REQUIRED
        // participation. Spring 6.2.7 DataSourceTransactionManager binds one connection per DS;
        // explicit RC below therefore has meaning only after rejecting an ambient transaction.
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw error(403, "AUTHORITY_TRANSACTION_FORBIDDEN", "授权刷新不能位于业务事务内");
        var current = AuthorityContext.current();
        var actor = Actor.current();
        if (!"engine".equals(current.caller())
                || !"app".equals(current.call().target())
                || !"app.published.read".equals(current.call().action())
                || current.operation() == null
                || !("PORTAL".equals(current.operation().originKind())
                        && Set.of("PORTAL", "DELEGATED").contains(current.operation().sourceKind())
                    || "INTEGRATION".equals(current.operation().originKind())
                        && "INTEGRATION".equals(current.operation().sourceKind())
                        && IntegrationTaskRoutes.versionRoot(current.call().resource())))
            throw error(403, "AUTHORITY_RESOURCE_MISMATCH", "发布读取能力不匹配");
        MetadataSelectors.code(code);
        var received = current.call().resource();
        boolean applicationRead = "app-runtime".equals(received.kind());
        boolean runtimeRead = applicationRead
                || cn.agentplatform.common.authority.PortalRuntimeRoutes.sessionForApp(received, code)
                || cn.agentplatform.common.authority.PortalRuntimeRoutes.memoryForApp(received, code);
        if (applicationRead && !cn.agentplatform.common.authority.PortalRuntimeRoutes.appResource(code).equals(received))
            throw error(403, "AUTHORITY_RESOURCE_MISMATCH", "发布资源与授权不匹配");
        var observed = publishedMetadata(actor.tenantId(), code);
        if (observed == null) throw error(404, "APP_NOT_FOUND", "应用不存在");
        requirePublishedAvailable(observed);
        // Runtime collections ask for the active publication. Run admission keeps its exact
        // immutable version binding and the conflict response when the active pointer moves.
        var root = runtimeRead
                ? received
                : new ResourceBinding("app-version", code + "@" + observed.versionNo(), observed.sha());
        if (!root.equals(received)) {
            // An old positive version for this code is a publication conflict; do not silently
            // exchange/retarget to the newer pointer. Wrong code/kind/current SHA is forbidden.
            String prefix = code + "@";
            if ("app-version".equals(received.kind()) && received.id().startsWith(prefix)
                    && !root.id().equals(received.id())
                    && received.id().substring(prefix.length()).matches("[1-9][0-9]*"))
                throw error(409, "APP_PUBLICATION_CHANGED", "应用发布版本已变化，请重新发起请求");
            throw error(403, "AUTHORITY_RESOURCE_MISMATCH", "发布资源与授权不匹配");
        }
        // The only explicit refresh is outside the transaction. Preserve this exact refreshed
        // retained object; every lock/payload/commit boundary below is local-only and cannot renew it.
        var retained = AuthorityContext.requireInitialFor(actor, root);
        String tenantId = retained.current().actor().tenantId();
        Map<String, Object> snapshot = publishedReadTx.execute(status -> {
            // Spring's transaction deadline is applied to JDBC statements by DataSourceUtils.
            // PG also bounds server-side statement/lock waits. FOR SHARE requires a writable tx.
            db.execute("set local statement_timeout='2000ms'");
            db.execute("set local lock_timeout='1500ms'");
            // Delegated portal access is scoped by the platform grant. Integration identities
            // already carry the credential's app-version binding and its verified role mapping.
            if ("PORTAL".equals(current.operation().originKind())) {
                platformAccess.require(actor, PlatformAccessService.VIEW, code);
            }
            var locked = db.query(
                    "select a.id,a.code,a.status,a.current_version_no from app.application a"
                            + " where a.tenant_id=? and a.code=? for share of a",
                    (r, n) -> r.getLong("id"), tenantId, code);
            AuthorityContext.requireBound(retained);
            if (locked.isEmpty())
                throw error(409, "APP_PUBLICATION_CHANGED", "应用发布状态已变化");
            // Separate READ_COMMITTED statement after the lock wait: a LEFT JOIN in the waiting
            // lock statement cannot establish a fresh snapshot of its joined version columns.
            var latest = publishedMetadata(tenantId, code);
            if (!observed.equals(latest))
                throw error(409, "APP_PUBLICATION_CHANGED", "应用发布状态已变化");
            requirePublishedAvailable(latest);
            AuthorityContext.requireBound(retained);
            var rows = db.query(
                    "select snapshot::text from app.app_version where tenant_id=? and app_id=?"
                            + " and app_code=? and id=? and version_no=? and candidate_sha256=?",
                    (r, n) -> authorizedSnapshot(r.getString(1), latest),
                    tenantId, latest.appId(), code, latest.versionId(), latest.versionNo(), latest.sha());
            if (rows.size() != 1)
                throw error(409, "APP_PUBLICATION_CHANGED", "发布快照不存在或身份不一致");
            AuthorityContext.requireBound(retained);
            return rows.getFirst();
        });
        // TransactionTemplate returns only after actual commit/cleanup. A materialized Map must
        // still pass this local check; no stale partial result escapes an expired request frame.
        // Linearization is the locked payload read, not Servlet serialization or the last byte.
        AuthorityContext.requireBound(retained);
        return snapshot;
    }

    private record PublishedReadMetadata(
            long appId, String code, String status, Integer pointer,
            Long versionId, Integer versionNo, String sha) {}

    private PublishedReadMetadata publishedMetadata(String tenantId, String code) {
        var rows = db.query(
                "select a.id,a.code,a.status,a.current_version_no,v.id as version_id,v.version_no,v.candidate_sha256"
                        + " from app.application a left join app.app_version v on v.tenant_id=a.tenant_id"
                        + " and v.app_code=a.code and v.app_id=a.id and v.version_no=a.current_version_no"
                        + " where a.tenant_id=? and a.code=?",
                (r, n) -> new PublishedReadMetadata(r.getLong("id"), r.getString("code"), r.getString("status"),
                        (Integer) r.getObject("current_version_no"), (Long) r.getObject("version_id"),
                        (Integer) r.getObject("version_no"), r.getString("candidate_sha256")),
                tenantId, code);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static void requirePublishedAvailable(PublishedReadMetadata metadata) {
        if (Set.of("DISABLED", "ARCHIVED").contains(metadata.status())
                || metadata.pointer() == null || metadata.versionId() == null || metadata.versionNo() == null)
            throw error(409, "APP_UNAVAILABLE", "应用未发布或已停用");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> authorizedSnapshot(String json, PublishedReadMetadata metadata) {
        var node = Json.read(json);
        String key = metadata.code() + "@" + metadata.versionNo();
        // candidate_sha256 is the persisted candidate digest, not a hash of this returned JSON.
        // config.guard remains embedded payload; this check does not claim a pre-read guard policy.
        if (!node.isObject() || !node.path("appCode").isTextual()
                || !metadata.code().equals(node.path("appCode").textValue())
                || !node.path("versionNo").isIntegralNumber() || !node.path("versionNo").canConvertToInt()
                || node.path("versionNo").intValue() != metadata.versionNo()
                || !node.path("candidateSha256").isTextual()
                || !metadata.sha().equals(node.path("candidateSha256").textValue())
                || !node.path("appVersionId").isTextual()
                || !key.equals(node.path("appVersionId").textValue()))
            throw error(409, "APP_SNAPSHOT_IDENTITY_MISMATCH", "发布快照身份不一致");
        var snapshot = new LinkedHashMap<String, Object>(Json.read(json, Map.class));
        snapshot.put("appVersionKey", key);
        snapshot.put("appVersionId", metadata.versionId());
        snapshot.put("versionId", metadata.versionId());
        return snapshot;
    }

    public Map<String, Object> publishedInputs(String code, Integer requestedVersion) {
        var app = get(code);
        if (Set.of("DISABLED", "ARCHIVED").contains(app.get("status"))
                || app.get("currentVersionNo") == null)
            throw error(409, "APP_UNAVAILABLE", "应用未发布或已停用");
        int version =
                requestedVersion == null
                        ? ((Number) app.get("currentVersionNo")).intValue()
                        : requestedVersion;
        if (version < 1) throw error(400, "INVALID_INPUT", "发布版本号无效");
        var snapshot = version(code, version);
        var config = object(snapshot.get("config"));
        var definition = object(snapshot.get("definition"));
        var result = new LinkedHashMap<String, Object>();
        result.put("appCode", code);
        result.put("versionNo", version);
        result.put("type", snapshot.get("type"));
        result.put(
                "inputs",
                definition.getOrDefault("inputs", config.getOrDefault("inputs", List.of())));
        if (config.get("inputSchema") instanceof Map<?, ?>)
            result.put("inputSchema", config.get("inputSchema"));
        return result;
    }

    public Map<String, Object> frozenCandidate(String code, long rev, String hash) {
        var app = get(code);
        if (rev != ((Number) app.get("draftRev")).longValue() || !sha(app).equals(hash))
            throw error(412, "CANDIDATE_CHANGED", "草稿已变更，请重新评测");
        var result = candidate(app);
        // 语义摘要必须在加 draftRev/versionNo 这些调用上下文之前算：它只描述候选内容本身。
        result.put("semanticDigest", AppDigest.semantic(result));
        result.put("draftRev", rev);
        result.put("candidateSha256", hash);
        result.put("appVersionId", code + "@draft:" + rev + ":" + hash);
        result.put("versionNo", 0);
        attachModels(result, frozenModels(app, rev, hash));
        return result;
    }

    /** 外部冻结不持应用锁；唯一键使同一候选的并发评测/发布选择同一组依赖。 */
    private List<Map<String, Object>> frozenModels(Map<String, Object> app, long rev, String hash) {
        String code = app.get("code").toString();
        var rows =
                db.queryForList(
                        "select model_refs::text from app.candidate_model_snapshot where"
                            + " tenant_id=? and app_code=? and draft_rev=? and candidate_sha256=?",
                        tenant(),
                        code,
                        rev,
                        hash);
        if (!rows.isEmpty())
            return objects(Json.read(rows.getFirst().get("model_refs").toString(), Object.class));
        var refs = freezeConfiguredModels(app);
        String stored =
                db.queryForObject(
                        "insert into"
                            + " app.candidate_model_snapshot(tenant_id,app_code,draft_rev,candidate_sha256,model_refs)"
                            + " values(?,?,?,?,?::jsonb) on"
                            + " conflict(tenant_id,app_code,draft_rev,candidate_sha256) do update"
                            + " set model_refs=app.candidate_model_snapshot.model_refs returning"
                            + " model_refs::text",
                        String.class,
                        tenant(),
                        code,
                        rev,
                        hash,
                        Json.write(refs));
        return objects(Json.read(stored, Object.class));
    }

    private List<Map<String, Object>> freezeConfiguredModels(Map<String, Object> app) {
        var ids = new LinkedHashSet<Long>();
        collectModelIds(object(app.get("config")), ids);
        collectModelIds(object(app.get("definition")), ids);
        var refs = new ArrayList<Map<String, Object>>();
        for (long id : ids) {
            var frozen =
                    remotePost(
                            modelUrl,
                            "/internal/model-bindings/freeze",
                            Map.of("modelEntryId", id));
            refs.add(
                    Map.of(
                            "modelEntryId",
                            id,
                            "modelBindingRef",
                            Map.of(
                                    "bindingId",
                                    frozen.get("bindingId"),
                                    "sha256",
                                    frozen.get("sha256")),
                            "entry",
                            frozen.get("entry")));
        }
        return refs;
    }

    /** Freeze remotely before locking. Previously published versions and admitted runs retain their refs. */
    public Map<String, Object> refreeze(String code, Map<String, Object> request) {
        String requestId = text(request, "requestId");
        long expected = number(request, "draftRev");
        if (expected < 1 || !requestId.matches("[A-Za-z0-9_-]{1,64}"))
            throw error(400, "INVALID_INPUT", "需要有效的请求号和草稿修订");
        var prior = refreezeReceipt(code, requestId, expected);
        if (prior != null) return prior;
        var app = get(code);
        if (((Number) app.get("draftRev")).longValue() != expected)
            throw error(412, "CANDIDATE_CHANGED", "草稿已更新");
        if (Set.of("DISABLED", "ARCHIVED").contains(app.get("status")))
            throw error(409, "APP_STATE", "请先重新启用应用");
        String hash = sha(app);
        var refs = freezeConfiguredModels(app);
        if (refs.isEmpty()) throw error(409, "BINDING_NOT_FROZEN", "请先在草稿设置有效模型条目");
        for (var item : refs) {
            var ref = Json.tree(item.get("modelBindingRef"));
            if (ref.path("bindingId").asLong() < 1
                    || !Digest.sha256(Json.canonical(item.get("entry"))).equals(ref.path("sha256").asText()))
                throw error(409, "MATERIAL_DIGEST_MISMATCH", "模型服务返回的冻结引用与快照不符");
        }
        return tx.execute(status -> {
            var locked = get(code, true);
            var replay = refreezeReceipt(code, requestId, expected);
            if (replay != null) return replay;
            if (((Number) locked.get("draftRev")).longValue() != expected || !hash.equals(sha(locked)))
                throw error(412, "CANDIDATE_CHANGED", "冻结期间草稿已更新");
            if (Set.of("DISABLED", "ARCHIVED").contains(locked.get("status")))
                throw error(409, "APP_STATE", "请先重新启用应用");
            var existing = db.queryForList("select model_refs::text from app.candidate_model_snapshot"
                    + " where tenant_id=? and app_code=? and draft_rev=? and candidate_sha256=?",
                    String.class, tenant(), code, expected, hash);
            boolean changed = existing.isEmpty() || !Json.canonical(Json.read(existing.getFirst())).equals(Json.canonical(refs));
            long revision = expected;
            if (changed) {
                revision++;
                int nextDraft = locked.get("currentVersionNo") instanceof Number version ? version.intValue() + 1 : 1;
                db.update("update app.app_draft set draft_rev=?,draft_no=greatest(draft_no,?),updated_at=now()"
                                + " where tenant_id=? and app_code=?",
                        revision, nextDraft, tenant(), code);
                db.update("insert into app.candidate_model_snapshot(tenant_id,app_code,draft_rev,candidate_sha256,model_refs)"
                        + " values(?,?,?,?,?::jsonb)", tenant(), code, revision, hash, Json.write(refs));
            }
            var bindings = refs.stream().map(item -> item.get("modelBindingRef")).toList();
            Map<String, Object> receipt = Map.of("requestId", requestId, "requestedRev", expected,
                    "draftRev", revision, "candidateSha256", hash, "modelBindingRefs", bindings, "changed", changed);
            String state = locked.get("status").toString();
            log(code, "MODEL_REFREEZE", state, state, receipt);
            return receipt;
        });
    }

    private Map<String, Object> refreezeReceipt(String code, String requestId, long expected) {
        var rows = db.queryForList("select detail::text from app.lifecycle_log where tenant_id=? and app_code=?"
                + " and action='MODEL_REFREEZE' and detail->>'requestId'=?", String.class, tenant(), code, requestId);
        if (rows.isEmpty()) return null;
        var receipt = object(Json.read(rows.getFirst(), Map.class));
        if (((Number) receipt.get("requestedRev")).longValue() != expected)
            throw error(409, "REQUEST_CONFLICT", "重冻结请求号已用于其他草稿修订");
        return receipt;
    }

    private void collectModelIds(Object value, Set<Long> ids) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("modelEntryId") instanceof Number n && n.longValue() > 0)
                ids.add(n.longValue());
            for (String binding : List.of("modelBinding", "modelOverride"))
                collectModelIds(map.get(binding), ids);
            if (map.get("nodeOverrides") instanceof Map<?, ?> overrides)
                overrides.values().forEach(v -> collectModelIds(v, ids));
            collectModelIds(map.get("nodes"), ids);
        } else if (value instanceof Collection<?> list) list.forEach(v -> collectModelIds(v, ids));
    }

    private void attachModels(Map<String, Object> snapshot, List<Map<String, Object>> refs) {
        var dependencies = new LinkedHashMap<String, Object>(Map.of(
                        "skillRefs",
                        publication.skillRefs(
                                object(snapshot.get("config")), object(snapshot.get("definition"))),
                        "modelRefs",
                        refs));
        var config = object(snapshot.get("config"));
        if ("TEMPLATE".equals(config.get("promptSource"))) {
            var references = objects(templateConfig(config).get("promptRefs"));
            if (!Json.tree(config.get("promptRefs")).equals(Json.tree(references)))
                throw error(409, "PROMPT_TEMPLATE_SNAPSHOT_MISMATCH", "候选模板引用缺少服务端固定摘要");
            // 正文不重复进入候选摘要；其字节由已入摘要的精确 promptRefs.sha256 约束。
            dependencies.put("frozenPrompts", references.stream().map(templates::frozenReference).toList());
        }
        snapshot.put("dependencyVersions", dependencies);
    }

    private Map<String, Object> templateConfig(Map<String, Object> config) {
        if (!config.containsKey("promptSource")) return config;
        // R-25：promptSource 枚举与 engine ContextAssembler 对齐（TEMPLATE/DIRECT）——DIRECT=内联
        // systemPrompt 显式声明，无模板引用处理，原样保存；未知值仍拒绝。
        if ("DIRECT".equals(config.get("promptSource"))) return config;
        if (!"TEMPLATE".equals(config.get("promptSource")) || config.containsKey("frozenPrompts")
                || config.containsKey("dependencyVersions"))
            throw error(400, "INVALID_INPUT", "模板来源必须显式选择，冻结正文由服务端提供");
        var references = objects(config.get("promptRefs"));
        if (references.size() != 1)
            throw error(400, "PROMPT_VERSION_REQUIRED", "模板主来源必须选择一个精确版本");
        var frozen = templates.frozenReference(references.getFirst());
        var result = new LinkedHashMap<>(config);
        result.put("promptRefs", List.of(Map.of("code", frozen.get("code"),
                "version", frozen.get("version"), "sha256", frozen.get("sha256"))));
        return result;
    }

    public Map<String, Object> remote(String base, String path) {
        if ("iam".equals(serviceSecurityMode)) {
            if (authorityTransport == null)
                throw error(503, "AUTHORITY_CONTEXT_MISSING", "应用当前授权出站未装配");
            return authorityTransport.call(base, path, null);
        }
        try {
            var result =
                    http.get()
                            .uri(base + path)
                            .header(
                                    "Authorization",
                                    "Bearer " + ServiceTokens.issue(Actor.current()))
                            .retrieve()
                            .body(Map.class);
            if (result == null) throw new IllegalStateException("空响应");
            return object(result.containsKey("data") ? result.get("data") : result);
        } catch (Exception ex) {
            throw error(502, "DEPENDENCY_UNAVAILABLE", "依赖服务校验失败: " + path);
        }
    }

    public Map<String, Object> remotePost(String base, String path, Map<String, Object> body) {
        if ("iam".equals(serviceSecurityMode)) {
            if (authorityTransport == null)
                throw error(503, "AUTHORITY_CONTEXT_MISSING", "应用当前授权出站未装配");
            return authorityTransport.call(base, path, body);
        }
        try {
            var result =
                    http.post()
                            .uri(base + path)
                            .header(
                                    "Authorization",
                                    "Bearer " + ServiceTokens.issue(Actor.current()))
                            .body(body)
                            .retrieve()
                            .body(Map.class);
            if (result == null) throw new IllegalStateException("空响应");
            return object(result.containsKey("data") ? result.get("data") : result);
        } catch (Exception ex) {
            throw error(502, "DEPENDENCY_UNAVAILABLE", "依赖服务校验失败: " + path);
        }
    }

    /** 只读组装校验，不创建评测/模型冻结/发布报告，也不持有草稿或包行锁。 */
    public void validateCapabilityDraft(Map<String, Object> app) {
        var config = object(app.get("config"));
        var definition = object(app.get("definition"));
        try {
            if ("FLOW".equals(app.get("type"))) {
                var errors = AppRules.graphErrors(definition);
                if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
                var checked = remotePost(engineUrl, "/internal/graphs/validate", Map.of("definition", definition));
                if (!Boolean.TRUE.equals(checked.get("valid"))) throw new IllegalArgumentException("流程图未通过真实结构校验");
            }
            publication.parameterChecks(app, config, definition);
            publication.typeChecks(config, definition);
            publication.referenceChecks(config, definition, false);
            publication.referenceChecks(config, definition, true);
            if (config.get("modelBinding") instanceof Map<?, ?> binding) {
                long id = number(object(binding), "modelEntryId");
                var model = remote(modelUrl, "/internal/model-entries/" + id);
                if (!publication.skillRefs(config, definition).isEmpty()
                        && !((Collection<?>) model.getOrDefault("capabilities", List.of())).contains("tools"))
                    throw new IllegalArgumentException("模型不支持工具调用");
            }
        } catch (IllegalArgumentException ex) {
            throw error(400, "INVALID_INPUT", Objects.toString(ex.getMessage(), "应用配置无效"));
        }
    }

    /** 普通保存不能伪造来源；包来源关系和 config 必须在同一租户、同一应用身份下逐字一致。 */
    private void assertCapabilitySource(Map<String, Object> app) {
        if (onboardingSources != null && onboardingSources.assertIfPresent(app)) return;
        var config = object(app.get("config"));
        if (onboardingSources == null && objects(config.get("capabilityPackages")).stream()
                .anyMatch(source -> source.containsKey("sessionSourceId")))
            throw error(503, "ONBOARDING_SOURCE_UNAVAILABLE", "会话来源校验未装配");
        if (!config.containsKey("capabilityPackages")) return;
        var rows = db.queryForList("select b.source::text,p.package_sha256,p.binding_status,p.resolved_mapping::text"
                        + " from app.capability_app_binding b join app.capability_package_version p"
                        + " on p.tenant_id=b.tenant_id and p.id=b.package_version_id"
                        + " where b.tenant_id=? and b.app_id=? and b.app_code=? order by b.package_version_id",
                tenant(), app.get("id"), app.get("code"));
        var expected = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            var source = Json.read(row.get("source").toString(), Map.class);
            if (!"RESOLVED".equals(row.get("binding_status")) || !source.get("sha256").equals(row.get("package_sha256"))
                    || !source.get("mappingSha256").equals(Digest.sha256(Json.canonical(Json.read(row.get("resolved_mapping").toString())))))
                throw error(409, "CAPABILITY_SOURCE_CONFLICT", "能力包解析来源不一致");
            expected.add(source);
        }
        if (expected.isEmpty() || !Json.canonical(expected).equals(Json.canonical(config.get("capabilityPackages"))))
            throw error(409, "CAPABILITY_SOURCE_CONFLICT", "能力包来源必须来自本应用的真实解析关系");
        checkCapabilityReferences(config, object(app.get("definition")));
    }

    /** 工具策略、版本和来源作为一个整体保留；首版不隐式重绑、停用或更改工具档案。 */
    public static void checkCapabilityReferences(Map<String, Object> config, Map<String, Object> definition) {
        var refs = new LinkedHashMap<String, Map<String, Object>>();
        for (var source : objects(config.get("capabilityPackages")))
            for (var binding : objects(source.get("bindings"))) {
                String code = text(binding, "skillCode");
                var ref = Map.<String, Object>of("code", code, "version", binding.get("version"));
                var prior = refs.putIfAbsent(code, ref);
                if (prior != null && !Json.canonical(prior).equals(Json.canonical(ref)))
                    throw error(409, "CAPABILITY_SOURCE_CONFLICT", "相同技能存在不同来源版本");
            }
        if (!Json.canonical(new ArrayList<>(refs.values())).equals(Json.canonical(config.get("skillRefs")))
                || !Collections.disjoint(config.keySet(), Set.of("tools", "frozenPrompts", "capabilityBindings", "toolProfile")))
            throw error(409, "CAPABILITY_SOURCE_CONFLICT", "不能改写已绑定工具或来源策略");
        for (var node : objects(definition.get("nodes")))
            if (node.get("skillRef") instanceof Map<?, ?> raw) {
                var ref = object(raw);
                var expected = refs.get(text(ref, "code"));
                if (expected == null || !Json.canonical(expected).equals(Json.canonical(ref)))
                    throw error(409, "CAPABILITY_SOURCE_CONFLICT", "流程技能必须使用已绑定的精确版本");
            }
    }

    // ---------------------------------------------------------------- 发布（MNT-06 发布子步）
    // 预检/提交/豁免/pin 观测的实现已迁至 AppPublicationService：本类保留同名方法作为兼容门面，
    // 请求形状、错误码、事务边界与返回值不变；发布簇经 Port 反向取用本类的读取、远端调用与写事务。

    public Map<String, Object> precheck(String code) {
        return publication.precheck(code);
    }

    public Map<String, Object> commit(String code, Map<String, Object> body) {
        return publication.commit(code, body);
    }

    public Map<String, Object> createWaiver(String code, Map<String, Object> body) {
        return publication.createWaiver(code, body);
    }

    public Map<String, Object> revokeWaiver(String code, String waiverId) {
        return publication.revokeWaiver(code, waiverId);
    }

    /** 兼容入口：Schema 预检规则随发布簇迁至 AppPublicationService。 */
    void validateSchema(Object raw) {
        publication.validateSchema(raw);
    }

    public Map<String, Object> pinStatus(String code) {
        return publication.pinStatus(code);
    }

    /** 测试/诊断入口：覆盖门禁模式与豁免开关，不改动事务与 HTTP 依赖。 */
    void configurePublishGate(String mode, boolean enabled) {
        this.configuredGateMode = mode;
        this.waiverEnabled = enabled;
    }

    private AppPublicationService.Port publicationPort() {
        return new AppPublicationService.Port() {
            @Override
            public JdbcTemplate db() {
                return db;
            }

            @Override
            public TransactionTemplate tx() {
                return tx;
            }

            @Override
            public String tenantId() {
                return tenant();
            }

            @Override
            public String engineUrl() {
                return engineUrl;
            }

            @Override
            public String modelUrl() {
                return modelUrl;
            }

            @Override
            public String monitorUrl() {
                return monitorUrl;
            }

            @Override
            public String skillUrl() {
                return skillUrl;
            }

            @Override
            public String gateMode() {
                return configuredGateMode;
            }

            @Override
            public boolean waiverEnabled() {
                return waiverEnabled;
            }

            @Override
            public String publicationTarget() {
                return publicationTarget;
            }

            @Override
            public boolean pinRecordEnabled() {
                return pinRecordEnabled;
            }

            @Override
            public String pinEnvironment() {
                return pinEnvironment;
            }

            @Override
            public ReleasePins pins() {
                return pins;
            }

            @Override
            public AppPublicationStore store() {
                return publicationStore;
            }

            @Override
            public Map<String, Object> load(String code) {
                return AppService.this.get(code);
            }

            @Override
            public Map<String, Object> loadForUpdate(String code) {
                return AppService.this.get(code, true);
            }

            @Override
            public Map<String, Object> templateConfig(Map<String, Object> config) {
                return AppService.this.templateConfig(config);
            }

            @Override
            public List<Map<String, Object>> frozenModels(Map<String, Object> app, long rev, String hash) {
                return AppService.this.frozenModels(app, rev, hash);
            }

            @Override
            public void attachModels(Map<String, Object> snapshot, List<Map<String, Object>> refs) {
                AppService.this.attachModels(snapshot, refs);
            }

            @Override
            public void collectModelIds(Object value, Set<Long> ids) {
                AppService.this.collectModelIds(value, ids);
            }

            @Override
            public void assertCapabilitySource(Map<String, Object> app) {
                AppService.this.assertCapabilitySource(app);
            }

            @Override
            public Map<String, Object> remote(String base, String path) {
                return AppService.this.remote(base, path);
            }

            @Override
            public Map<String, Object> remotePost(String base, String path, Map<String, Object> body) {
                return AppService.this.remotePost(base, path, body);
            }

            @Override
            public void log(String code, String action, String from, String to, Object detail) {
                AppService.this.log(code, action, from, to, detail);
            }
        };
    }
}
