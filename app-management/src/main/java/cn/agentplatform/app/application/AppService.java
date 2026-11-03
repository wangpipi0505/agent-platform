package cn.agentplatform.app.application;

import cn.agentplatform.app.domain.AppDigest;
import cn.agentplatform.app.domain.AppRules;
import cn.agentplatform.app.domain.PublishGate;
import cn.agentplatform.app.domain.PublishWaiver;
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

    /**
     * pin 结果落库 UPSERT：每应用一行最近一次尝试结果；last_pinned_* 只在 PINNED 时推进，
     * 失败/跳过不抹掉"远端当前仍指向哪个绑定"的已知事实。与 V106 迁移头注释的语义一一对应。
     */
    private static final String PIN_UPSERT =
            "insert into app.publish_pin(tenant_id,app_code,app_version_no,state,release_id,"
                    + "binding_id,reason,environment,last_pinned_release_id,last_pinned_binding_id,last_pinned_at)"
                    + " values(?,?,?,?,?,?,?,?,?,?,?)"
                    + " on conflict(tenant_id,app_code) do update set"
                    + " app_version_no=excluded.app_version_no,state=excluded.state,"
                    + " release_id=excluded.release_id,binding_id=excluded.binding_id,"
                    + " reason=excluded.reason,environment=excluded.environment,updated_at=now(),"
                    + " last_pinned_release_id=coalesce(case when excluded.state='PINNED' then"
                    + " excluded.release_id end, app.publish_pin.last_pinned_release_id),"
                    + " last_pinned_binding_id=coalesce(case when excluded.state='PINNED' then"
                    + " excluded.binding_id end, app.publish_pin.last_pinned_binding_id),"
                    + " last_pinned_at=coalesce(case when excluded.state='PINNED' then now() end,"
                    + " app.publish_pin.last_pinned_at)";

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final TransactionTemplate publishedReadTx;
    private final RestClient http = client();
    private final String skillUrl, modelUrl, monitorUrl, engineUrl;
    private final TemplateService templates;

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
        this.templates = new TemplateService(db);
        this.tx = new TransactionTemplate(manager);
        this.publishedReadTx = new TransactionTemplate(manager);
        this.publishedReadTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.publishedReadTx.setTimeout(3);
        this.skillUrl = skillUrl;
        this.modelUrl = modelUrl;
        this.monitorUrl = monitorUrl;
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
        var sql =
                new StringBuilder(
                        "select a.*,d.id as"
                            + " draft_id,d.draft_no,d.draft_rev,d.config,d.definition,d.layout from"
                            + " app.application a join app.app_draft d on d.tenant_id=a.tenant_id"
                            + " and d.app_code=a.code where a.tenant_id=?");
        var args = new ArrayList<Object>();
        args.add(tenant());
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

    public Map<String, Object> copy(String sourceCode, Map<String, Object> body) {
        if (!(body.get("code") instanceof String) || !(body.get("name") instanceof String))
            throw error(400, "INVALID_INPUT", "新应用编码和名称必须为字符串");
        String code = text(body, "code"), name = text(body, "name");
        try {
            return tx.execute(
                    s -> {
                        get(sourceCode, true);
                        // READ COMMITTED 下等待 d 锁的 join 可能保留旧 a；持有既有草稿锁后，
                        // 用新语句快照读取完整来源，保证复制内容与来源修订/发布状态一致。
                        var source = get(sourceCode);
                        assertCapabilitySource(source);
                        var input = new LinkedHashMap<String, Object>();
                        input.put("code", code);
                        input.put("name", name);
                        for (String field :
                                List.of("type", "description", "domain", "bizCategory", "tags"))
                            input.put(field, source.get(field));
                        var target = create(input);
                        if (object(source.get("config")).containsKey("capabilityPackages"))
                            db.update("insert into app.capability_app_binding(tenant_id,app_id,app_code,package_version_id,source)"
                                            + " select tenant_id,?,?,package_version_id,source from app.capability_app_binding where tenant_id=? and app_id=?",
                                    target.get("id"), code, tenant(), source.get("id"));
                        // 只复制编排输入并保留用户模型/技能引用；冻结模型、评测/预检/发布证据及集成凭据
                        // 绑定源应用身份，新草稿必须重新生成，不能据此继承发布许可。
                        db.update(
                                "update app.app_draft set"
                                    + " config=?::jsonb,definition=?::jsonb,layout=?::jsonb where"
                                    + " tenant_id=? and app_code=?",
                                Json.write(source.get("config")),
                                Json.write(source.get("definition")),
                                Json.write(source.get("layout")),
                                tenant(),
                                code);
                        var detail = new LinkedHashMap<String, Object>();
                        detail.put("sourceAppCode", sourceCode);
                        detail.put("sourceDraftRev", source.get("draftRev"));
                        detail.put("sourceCurrentVersionNo", source.get("currentVersionNo"));
                        log(code, "COPY", source.get("status").toString(), "DRAFT", detail);
                        return get(code);
                    });
        } catch (DuplicateKeyException ex) {
            throw error(409, "APP_CODE_CONFLICT", "应用编码已存在，请更换编码");
        }
    }

    public Map<String, Object> create(Map<String, Object> body) {
        String code = text(body, "code"), name = text(body, "name"), type = text(body, "type");
        if (!code.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || !Set.of("CHAT", "FLOW").contains(type))
            throw error(400, "INVALID_INPUT", "应用编码或类型无效");
        Map<String, Object> initialConfig = Map.of();
        if (body.containsKey("promptTemplateRef")) {
            var reference = object(body.get("promptTemplateRef"));
            if (!Set.of("code", "version").equals(reference.keySet()))
                throw error(400, "INVALID_INPUT", "创建时仅提供模板编码与精确版本，摘要由服务端固定");
            initialConfig = templateConfig(Map.of("promptSource", "TEMPLATE", "promptRefs", List.of(reference)));
        }
        final var config = initialConfig;
        return tx.execute(
                status -> {
                    db.update(
                            "insert into"
                                + " app.application(tenant_id,code,name,type,description,domain,biz_category,tags,created_by)"
                                + " values(?,?,?,?,?,?,?,?::jsonb,?)",
                            tenant(),
                            code,
                            name,
                            type,
                            Objects.toString(body.get("description"), ""),
                            Objects.toString(body.get("domain"), ""),
                            Objects.toString(body.get("bizCategory"), ""),
                            Json.write(body.getOrDefault("tags", List.of())),
                            Actor.current().principalId());
                    db.update(
                            "insert into app.app_draft(tenant_id,app_id,app_code,config) select"
                                + " tenant_id,id,code,?::jsonb from app.application where tenant_id=? and"
                                + " code=?",
                            Json.write(config),
                            tenant(),
                            code);
                    log(code, "CREATE", "DRAFT", "DRAFT", Map.of());
                    return get(code);
                });
    }

    public Map<String, Object> save(String code, Map<String, Object> body, boolean flow) {
        return tx.execute(
                s -> {
                    var old = get(code, true);
                    long rev = number(body, "draftRev");
                    if (((Number) old.get("draftRev")).longValue() != rev)
                        throw error(409, "DRAFT_CONFLICT", "草稿已被更新，请刷新");
                    if (Set.of("DISABLED", "ARCHIVED").contains(old.get("status")))
                        throw error(409, "APP_STATE", "请先重新启用应用");
                    if (flow && !"FLOW".equals(old.get("type")))
                        throw error(409, "APP_TYPE", "仅流程应用可保存流程图");
                    String field = flow ? "definition" : "config";
                    Map<String, Object> value = object(body.get(field));
                    if (!flow) value = templateConfig(value);
                    var checked = new LinkedHashMap<String, Object>(old);
                    checked.put(field, value);
                    if (object(old.get("config")).containsKey("capabilityPackages")
                            && !object(checked.get("config")).containsKey("capabilityPackages"))
                        throw error(409, "CAPABILITY_SOURCE_CONFLICT", "不能移除应用的能力包来源");
                    assertCapabilitySource(checked);
                    boolean changed =
                            !AppRules.canonical(value).equals(AppRules.canonical(old.get(field)));
                    Object layout =
                            flow
                                    ? body.getOrDefault("layout", old.get("layout"))
                                    : old.get("layout");
                    if (!(layout instanceof Map)) throw error(400, "INVALID_INPUT", "layout必须为对象");
                    db.update(
                            "update app.app_draft set "
                                    + field
                                    + "=?::jsonb,layout=?::jsonb,draft_rev=draft_rev+?,draft_no=draft_no+?,updated_at=now()"
                                    + " where tenant_id=? and app_code=?",
                            Json.write(value),
                            Json.write(layout),
                            changed ? 1 : 0,
                            changed && old.get("status").equals("PUBLISHED") ? 1 : 0,
                            tenant(),
                            code);
                    db.update(
                            "update app.application set status=case when ? then 'DRAFT' else status"
                                    + " end,updated_at=now() where tenant_id=? and code=?",
                            changed,
                            tenant(),
                            code);
                    if (changed)
                        log(
                                code,
                                "MODIFY",
                                old.get("status").toString(),
                                "DRAFT",
                                Map.of("previousDraftRev", rev));
                    return get(code);
                });
    }

    public Map<String, Object> metadata(String code, Map<String, Object> body) {
        return tx.execute(
                status -> {
                    var old = get(code, true);
                    if (number(body, "draftRev") != ((Number) old.get("draftRev")).longValue())
                        throw error(409, "DRAFT_CONFLICT", "请刷新草稿");
                    if (Set.of("DISABLED", "ARCHIVED").contains(old.get("status")))
                        throw error(409, "APP_STATE", "请先启用应用");
                    db.update(
                            "update app.application set"
                                + " name=?,description=?,domain=?,biz_category=?,tags=?::jsonb,status='DRAFT',updated_at=now()"
                                + " where tenant_id=? and code=?",
                            text(body, "name"),
                            Objects.toString(body.get("description"), ""),
                            Objects.toString(body.get("domain"), ""),
                            Objects.toString(body.get("bizCategory"), ""),
                            Json.write(body.getOrDefault("tags", List.of())),
                            tenant(),
                            code);
                    db.update(
                            "update app.app_draft set"
                                    + " draft_rev=draft_rev+1,draft_no=draft_no+?,updated_at=now()"
                                    + " where tenant_id=? and app_code=?",
                            old.get("status").equals("PUBLISHED") ? 1 : 0,
                            tenant(),
                            code);
                    log(
                            code,
                            "MODIFY",
                            old.get("status").toString(),
                            "DRAFT",
                            Map.of("previousDraftRev", old.get("draftRev"), "scope", "METADATA"));
                    return get(code);
                });
    }

    private void log(String code, String action, String from, String to, Object detail) {
        db.update(
                "insert into"
                    + " app.lifecycle_log(tenant_id,app_code,actor,action,from_state,to_state,detail)"
                    + " values(?,?,?,?,?,?,?::jsonb)",
                tenant(),
                code,
                Actor.current().principalId(),
                action,
                from,
                to,
                Json.write(detail));
    }

    public List<Map<String, Object>> lifecycleLogs(String code) {
        get(code);
        return db.query(
                "select id,actor,action,from_state,to_state,detail::text,created_at from"
                        + " app.lifecycle_log where tenant_id=? and app_code=? order by id desc",
                (r, n) ->
                        Map.<String, Object>of(
                                "id",
                                r.getLong("id"),
                                "actor",
                                r.getString("actor"),
                                "action",
                                r.getString("action"),
                                "fromState",
                                r.getString("from_state"),
                                "toState",
                                r.getString("to_state"),
                                "detail",
                                Json.read(r.getString("detail"), Map.class),
                                "createdAt",
                                r.getTimestamp("created_at").toInstant().toString()),
                tenant(),
                code);
    }

    public Map<String, Object> lifecycle(String code, String action, Map<String, Object> body) {
        return tx.execute(
                s -> {
                    var old = get(code, true);
                    String from = old.get("status").toString();
                    String to =
                            switch (action) {
                                case "disable" -> {
                                    if (!Set.of("PUBLISHED", "DRAFT").contains(from)
                                            || old.get("currentVersionNo") == null)
                                        throw error(409, "APP_STATE", "仅已发布应用可停用");
                                    yield "DISABLED";
                                }
                                case "enable" -> {
                                    if (!from.equals("DISABLED")
                                            || old.get("currentVersionNo") == null)
                                        throw error(409, "APP_STATE", "仅停用应用可启用");
                                    yield "PUBLISHED";
                                }
                                case "archive" -> {
                                    if (!from.equals("DISABLED"))
                                        throw error(409, "APP_STATE", "仅停用应用可归档");
                                    yield "ARCHIVED";
                                }
                                case "rollback" -> {
                                    int version = (int) number(body, "versionNo");
                                    version(code, version);
                                    if (!Set.of("PUBLISHED", "DRAFT").contains(from))
                                        throw error(409, "APP_STATE", "当前状态不能回滚");
                                    db.update(
                                            "update app.application set current_version_no=? where"
                                                    + " tenant_id=? and code=?",
                                            version,
                                            tenant(),
                                            code);
                                    yield "PUBLISHED";
                                }
                                default -> throw error(400, "INVALID_ACTION", "未知生命周期操作");
                            };
                    db.update(
                            "update app.application set status=?,updated_at=now() where tenant_id=?"
                                    + " and code=?",
                            to,
                            tenant(),
                            code);
                    log(code, action.toUpperCase(Locale.ROOT), from, to, body);
                    return get(code);
                });
    }

    public void delete(String code) {
        tx.executeWithoutResult(
                s -> {
                    var old = get(code, true);
                    if (!old.get("status").equals("DRAFT") || old.get("currentVersionNo") != null)
                        throw error(409, "APP_STATE", "只能删除从未发布的草稿");
                    db.update(
                            "delete from app.publish_report where tenant_id=? and app_code=?",
                            tenant(),
                            code);
                    db.update(
                            "delete from app.app_draft where tenant_id=? and app_code=?",
                            tenant(),
                            code);
                    db.update(
                            "delete from app.application where tenant_id=? and code=?",
                            tenant(),
                            code);
                    log(code, "DELETE", "DRAFT", "DRAFT", Map.of());
                });
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
                || !"app-version".equals(current.call().resource().kind())
                || current.operation() == null
                || !"PORTAL".equals(current.operation().originKind())
                || !Set.of("PORTAL", "DELEGATED").contains(current.operation().sourceKind()))
            throw error(403, "AUTHORITY_RESOURCE_MISMATCH", "发布读取能力不匹配");
        MetadataSelectors.code(code);
        var observed = publishedMetadata(actor.tenantId(), code);
        if (observed == null) throw error(404, "APP_NOT_FOUND", "应用不存在");
        requirePublishedAvailable(observed);
        var root = new ResourceBinding("app-version", code + "@" + observed.versionNo(), observed.sha());
        var received = current.call().resource();
        if (!root.equals(received)) {
            // An old positive version for this code is a publication conflict; do not silently
            // exchange/retarget to the newer pointer. Wrong code/kind/current SHA is forbidden.
            String prefix = code + "@";
            if (received.id().startsWith(prefix)
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
                        skillRefs(
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
            parameterChecks(app, config, definition);
            typeChecks(config, definition);
            referenceChecks(config, definition, false);
            referenceChecks(config, definition, true);
            if (config.get("modelBinding") instanceof Map<?, ?> binding) {
                long id = number(object(binding), "modelEntryId");
                var model = remote(modelUrl, "/internal/model-entries/" + id);
                if (!skillRefs(config, definition).isEmpty()
                        && !((Collection<?>) model.getOrDefault("capabilities", List.of())).contains("tools"))
                    throw new IllegalArgumentException("模型不支持工具调用");
            }
        } catch (IllegalArgumentException ex) {
            throw error(400, "INVALID_INPUT", Objects.toString(ex.getMessage(), "应用配置无效"));
        }
    }

    /** 普通保存不能伪造来源；包来源关系和 config 必须在同一租户、同一应用身份下逐字一致。 */
    private void assertCapabilitySource(Map<String, Object> app) {
        var config = object(app.get("config"));
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

    public Map<String, Object> precheck(String code) {
        var app = get(code);
        assertCapabilitySource(app);
        var config = object(app.get("config"));
        var definition = object(app.get("definition"));
        String hash = sha(app);
        long rev = ((Number) app.get("draftRev")).longValue();
        var checks = new ArrayList<Map<String, Object>>();
        var models = new ArrayList<Map<String, Object>>();
        check(
                checks,
                "结构",
                () -> {
                    if (app.get("type").equals("FLOW")) {
                        var errors = AppRules.graphErrors(definition);
                        if (!errors.isEmpty())
                            throw new IllegalArgumentException(String.join("; ", errors));
                        var engineCheck =
                                remotePost(
                                        engineUrl,
                                        "/internal/graphs/validate",
                                        Map.of("definition", definition));
                        if (!Boolean.TRUE.equals(engineCheck.get("valid")))
                            throw new IllegalArgumentException(
                                    Json.write(engineCheck.get("errors")));
                    }
                });
        check(checks, "参数", () -> parameterChecks(app, config, definition));
        check(checks, "类型与无效引用", () -> typeChecks(config, definition));
        check(
                checks,
                "引用有效性",
                () -> {
                    models.addAll(frozenModels(app, rev, hash));
                    for (var ref : models) {
                        remotePost(
                                modelUrl,
                                "/internal/model-bindings/validate",
                                object(ref.get("modelBindingRef")));
                        var entry = object(ref.get("entry"));
                        if (!skillRefs(config, definition).isEmpty()
                                && !((Collection<?>) entry.getOrDefault("capabilities", List.of()))
                                        .contains("tools"))
                            throw new IllegalArgumentException("模型不支持工具调用");
                    }
                    referenceChecks(config, definition, false);
                });
        check(checks, "守卫声明", () -> referenceChecks(config, definition, true));
        // 门禁在其余检查之后评估：即使阻断，也先落一份完整检查快照，供 ADMIN 判断是否值得豁免。
        PublishGate gate = publishGate();
        Map<String, Object> eval = null;
        var evalFailure = new String[1];
        // OFF 仅关闭质量阈值；所有目标仍需同报告的可信环境证据，故始终读取 monitor。
        {
            try {
                eval =
                        remote(
                                monitorUrl,
                                "/internal/evaluations/accepted?appCode="
                                        + code
                                        + "&draftRev="
                                        + rev
                                        + "&candidateSha256="
                                        + hash
                                        + "&semanticDigest="
                                        + URLEncoder.encode(AppDigest.semantic(candidate(app)), StandardCharsets.UTF_8));
            } catch (PlatformException ex) {
                evalFailure[0] = Objects.toString(ex.getMessage(), "评测依赖不可用");
            }
        }
        String semanticDigest = AppDigest.semantic(candidate(app));
        var sourceEvidence = eval != null && eval.get("publicationEvidence") instanceof Map<?, ?>
                ? object(eval.get("publicationEvidence")) : Map.<String, Object>of();
        boolean evidenceValid = validPublicationEvidence(sourceEvidence, code, rev, hash, semanticDigest);
        String environment = evidenceValid ? Objects.toString(sourceEvidence.get("environment"), "UNKNOWN") : "UNKNOWN";
        boolean environmentAllowed = evidenceValid && ("REAL".equals(environment)
                || "NON_PRODUCTION".equals(publicationTarget) && "DEMO".equals(environment));
        if (eval != null) {
            eval = new LinkedHashMap<>(eval);
            eval.put("accepted", evidenceValid && Boolean.TRUE.equals(sourceEvidence.get("qualityAccepted")));
            eval.put("evalReportId", evidenceValid ? sourceEvidence.get("evalReportId") : "");
            eval.put("matchedBy", hash.equals(sourceEvidence.get("candidateSha256")) ? "SHA256" : "SEMANTIC_DIGEST");
        }
        boolean accepted = eval != null && Boolean.TRUE.equals(eval.get("accepted"));
        // 语义摘要命中说明候选结构没变、只是描述类字段变了：证据可复用，但必须在检查快照里留下痕迹，
        // 否则事后无法分辨"这次是重新评测通过"还是"复用了旧报告"。
        String matchedBy = eval == null ? null : Objects.toString(eval.get("matchedBy"), null);
        // 依赖故障不允许用豁免绕过：拿不到候选状态时无法确认风险范围，只能阻断或告警。
        PublishWaiver waiver = null;
        if (waiverEnabled && gate != PublishGate.OFF && !accepted && evalFailure[0] == null)
            waiver = activeWaiver(code, rev, hash);
        String waiverId = waiver == null ? null : waiver.id();
        var gateCheck = new LinkedHashMap<String, Object>();
        gateCheck.put("group", "评测门禁");
        String gateResult;
        String gateDetail = null;
        if (evalFailure[0] != null) {
            gateResult = "ERROR";
            gateDetail = evalFailure[0];
        } else if (accepted) {
            gateResult = "PASS";
            if ("SEMANTIC_DIGEST".equals(matchedBy)) {
                gateDetail =
                        "语义摘要一致：候选仅展示性字段变化，复用评测报告 "
                                + Objects.toString(eval.get("evalReportId"), "")
                                + "（matchedBy=SEMANTIC_DIGEST）";
                gateCheck.put("evidence", "SEMANTIC_DIGEST");
            }
        } else if (gate == PublishGate.OFF) {
            gateResult = "SKIPPED";
            gateDetail = "门禁已关闭（gate-mode=OFF），不评估评测结论";
        } else if (waiver != null) {
            gateResult = "WAIVED";
            gateDetail = "ADMIN 显式豁免：" + waiver.reason();
            gateCheck.put("waiverId", waiver.id());
            gateCheck.put("expiresAt", waiver.expiresAt().toString());
        } else if (gate == PublishGate.ADVISORY) {
            gateResult = "ADVISORY";
            gateDetail = "缺少满足安全100%、通过率及无新增回归的候选报告";
        } else {
            gateResult = "BLOCKED";
            gateDetail = "缺少满足安全100%、通过率及无新增回归的候选报告";
        }
        gateCheck.put("result", gateResult);
        if (gateDetail != null) gateCheck.put("detail", gateDetail);
        checks.add(gateCheck);
        var environmentCheck = new LinkedHashMap<String, Object>();
        environmentCheck.put("group", "评测环境");
        environmentCheck.put("result", environmentAllowed ? "PASS" : "BLOCKED");
        environmentCheck.put("schemaVersion", 1);
        environmentCheck.put("publicationTarget", publicationTarget);
        environmentCheck.put("candidateSha256", hash);
        environmentCheck.put("semanticDigest", semanticDigest);
        environmentCheck.put("publicationEvidence", sourceEvidence);
        environmentCheck.put("detail", environmentAllowed ? "平台参与者自身模式及数据集身份已冻结；外部语义与业务效果不在本门范围"
                : "演示或未知来源评测不能用于生产发布；请在目标环境新建报告重新评测，普通豁免无效");
        checks.add(environmentCheck);
        var warnings = new ArrayList<String>();
        if (environmentAllowed && "DEMO".equals(environment))
            warnings.add("NON_PRODUCTION：本次仅允许演示发布，提交必须显式确认本预检与报告；不能作为生产来源证明");
        if ("ADVISORY".equals(gateResult))
            warnings.add("评测门禁：" + gateDetail + "（ADVISORY 仅告警，发布风险由发布人承担）");
        if ("WAIVED".equals(gateResult))
            warnings.add("评测门禁：本次发布使用 ADMIN 豁免 " + waiverId + "，到期 " + waiver.expiresAt());
        if ("SKIPPED".equals(gateResult)) warnings.add("评测门禁：已由 gate-mode=OFF 关闭，发布不带评测结论");
        if ("PASS".equals(gateResult) && "SEMANTIC_DIGEST".equals(matchedBy))
            warnings.add("评测门禁：本次复用旧评测报告（仅描述字段变化），报告编号 " + Objects.toString(eval.get("evalReportId"), ""));
        String id = "pc_" + UUID.randomUUID();
        // 被阻断的预检同样落审计行：事后可还原是谁、在什么模式、凭哪次豁免尝试过发布。
        boolean passed =
                checks.stream()
                        .allMatch(
                                c ->
                                        Set.of("PASS", "WAIVED", "ADVISORY", "SKIPPED")
                                                .contains(c.get("result")));
        String evalId = eval == null ? "" : Objects.toString(eval.get("evalReportId"), "");
        db.update(
                "insert into"
                    + " app.publish_report(tenant_id,id,app_draft_id,app_code,draft_rev,candidate_sha256,eval_report_id,checks,passed,expires_at,model_refs,gate_mode,waiver_id)"
                    + " values(?,?,?,?,?,?,?,?::jsonb,?,?,?::jsonb,?,?)",
                tenant(),
                id,
                app.get("draftId"),
                code,
                rev,
                hash,
                evalId,
                Json.write(checks),
                passed,
                java.sql.Timestamp.from(Instant.now().plusSeconds(900)),
                Json.write(models),
                gate.name(),
                waiverId);
        // 依赖故障在任何模式都拦截：不确定候选状态时的"放宽"等于没有门禁，且豁免不得代替可用性判断。
        if (!environmentAllowed)
            throw error(409, "EVALUATION_ENVIRONMENT_BLOCKED", "演示或未知来源评测不能用于当前发布目标；请重新评测，普通豁免无效");
        if ("ERROR".equals(gateResult) || (gate.blocking() && "BLOCKED".equals(gateResult)))
            throw error(409, "EVAL_GATE_BLOCKED", gateDetail);
        var result = new LinkedHashMap<String, Object>();
        result.put("precheckId", id);
        result.put("draftRev", rev);
        result.put("candidateSha256", hash);
        result.put("evalReportId", evalId);
        // 本次预检的选择上下文单独返回；不把 matchedBy 回写到历史评测报告。
        if (accepted && eval != null && matchedBy != null && Set.of("SHA256", "SEMANTIC_DIGEST").contains(matchedBy)) {
            var binding = new LinkedHashMap<String, Object>();
            binding.put("matchedBy", matchedBy);
            binding.put("evalReportId", evalId);
            binding.put("draftRev", rev);
            binding.put("candidateSha256", hash);
            binding.put("evaluatedCandidateSha256", eval.get("candidateSha256"));
            result.put("evaluationBinding", binding);
        }
        result.put("passed", passed);
        result.put("result", passed ? "PASS" : "BLOCKED");
        result.put("gateMode", gate.name());
        result.put("waiverId", waiverId);
        result.put("publicationTarget", publicationTarget);
        result.put("evaluationEnvironment", environment);
        result.put("environmentConfirmationRequired", "DEMO".equals(environment));
        result.put("environmentEvidenceSha256", sourceEvidence.get("evidenceSha256"));
        result.put("report", Map.of("checks", checks, "warnings", warnings));
        return result;
    }

    private boolean validPublicationEvidence(Map<String, Object> evidence, String code, long rev, String hash, String semantic) {
        var node = Json.tree(evidence);
        if (!node.path("schemaVersion").isIntegralNumber() || node.path("schemaVersion").asInt() != 2
                || !"PLATFORM_PARTICIPANT_MODES_AND_DATASET_IDENTITY".equals(node.path("scope").asText())
                || !"NOT_ASSESSED".equals(node.path("externalTruth").asText())
                || !node.path("caseSourcesSha256").asText().matches("[0-9a-f]{64}")
                || !tenant().equals(node.path("tenantId").asText()) || !code.equals(node.path("appCode").asText())
                || !node.path("draftRev").isIntegralNumber() || node.path("draftRev").asLong() != rev
                || node.path("evalReportId").asText().isBlank() || !node.path("qualityAccepted").isBoolean()
                || !Set.of("ACCEPTED", "REJECTED").contains(node.path("status").asText())
                || !node.path("datasetSha256").asText().matches("[0-9a-f]{64}")
                || node.path("datasetVersion").asText().isBlank()
                || !Set.of("REAL", "DEMO", "UNKNOWN").contains(node.path("environment").asText())
                || !(hash.equals(node.path("candidateSha256").asText())
                    || semantic != null && semantic.equals(node.path("candidateSemanticDigest").asText()))) return false;
        var unsigned = new LinkedHashMap<>(evidence);
        Object digest = unsigned.remove("evidenceSha256");
        return Digest.sha256(Json.canonical(unsigned)).equals(digest)
                && (Boolean.TRUE.equals(evidence.get("qualityAccepted")) == "ACCEPTED".equals(evidence.get("status")));
    }

    /** 仅消费本预检固化证据；不持发布行锁发 HTTP，也不把当前配置贴到旧报告上。 */
    private Map<String, Object> requirePublicationEnvironment(Map<String, Object> report, Map<String, Object> app,
            String code, long rev, String hash, String precheck, Map<String, Object> body) {
        var checks = Json.read(report.get("checks").toString());
        var matches = java.util.stream.StreamSupport.stream(checks.spliterator(), false)
                .filter(c -> "评测环境".equals(c.path("group").asText())).toList();
        if (matches.size() != 1) throw error(409, "EVALUATION_ENVIRONMENT_REQUIRED", "旧预检缺少冻结环境证据，请重新评测和预检");
        var check = matches.getFirst();
        var evidence = check.path("publicationEvidence").isObject()
                ? object(Json.mapper().convertValue(check.path("publicationEvidence"), Map.class)) : Map.<String, Object>of();
        String semantic = AppDigest.semantic(candidate(app));
        if (!check.path("schemaVersion").isIntegralNumber() || check.path("schemaVersion").asInt() != 1
                || !"PASS".equals(check.path("result").asText()) || !hash.equals(check.path("candidateSha256").asText())
                || !semantic.equals(check.path("semanticDigest").asText())
                || !publicationTarget.equals(check.path("publicationTarget").asText())
                || !validPublicationEvidence(evidence, code, rev, hash, semantic)
                || !Objects.equals(report.get("eval_report_id"), evidence.get("evalReportId")))
            throw error(409, "EVALUATION_ENVIRONMENT_BLOCKED", "环境证据与当前候选或部署目标不一致，请重新预检");
        String environment = Objects.toString(evidence.get("environment"), "UNKNOWN");
        if (!"REAL".equals(environment)) {
            if (!"NON_PRODUCTION".equals(publicationTarget) || !"DEMO".equals(environment))
                throw error(409, "EVALUATION_ENVIRONMENT_BLOCKED", "演示或未知评测不能用于生产发布");
            var confirmation = Json.tree(body.get("evaluationEnvironmentConfirmation"));
            if (!confirmation.path("confirmed").isBoolean() || !confirmation.path("confirmed").asBoolean()
                    || !precheck.equals(confirmation.path("precheckId").asText())
                    || !Objects.equals(evidence.get("evalReportId"), confirmation.path("evalReportId").asText())
                    || !Objects.equals(evidence.get("evidenceSha256"), confirmation.path("evidenceSha256").asText()))
                throw error(409, "EVALUATION_ENVIRONMENT_CONFIRMATION_REQUIRED", "非生产演示发布需显式确认当前预检、报告与来源摘要");
        }
        return evidence;
    }

    /** 测试/诊断入口：覆盖门禁模式与豁免开关，不改动事务与 HTTP 依赖。 */
    void configurePublishGate(String mode, boolean waiverEnabled) {
        this.configuredGateMode = mode;
        this.waiverEnabled = waiverEnabled;
    }

    /** 回滚开关关闭时放宽模式一律降级为 BLOCKING：保留历史数据，但不再接受新的放行。 */
    private PublishGate publishGate() {
        return waiverEnabled ? PublishGate.parse(configuredGateMode) : PublishGate.BLOCKING;
    }

    /**
     * 授予一次显式豁免。豁免不是"跳过检查"，而是把"缺评测报告"这一条从阻断改记为 WAIVED 并绑定候选指纹：
     * 草稿再保存一次（draft_rev 或 candidate_sha256 变化）豁免即自动失效，避免旧豁免被挪用到新候选上。
     */
    public Map<String, Object> createWaiver(String code, Map<String, Object> body) {
        requireWaiverEnabled();
        var app = get(code);
        var waiver =
                PublishWaiver.grant(
                        "pw_" + UUID.randomUUID(),
                        tenant(),
                        code,
                        object(body),
                        Actor.current().principalId(),
                        Instant.now());
        db.update(
                "insert into"
                    + " app.publish_waiver(id,tenant_id,app_code,draft_rev,candidate_sha256,reason,approved_by,approved_at,expires_at)"
                    + " values(?,?,?,?,?,?,?,?,?)",
                waiver.id(),
                waiver.tenantId(),
                waiver.appCode(),
                waiver.draftRev(),
                waiver.candidateSha256(),
                waiver.reason(),
                waiver.approvedBy(),
                java.sql.Timestamp.from(waiver.approvedAt()),
                java.sql.Timestamp.from(waiver.expiresAt()));
        // 豁免是高风险放行：授予人、理由、候选与窗口同时写生命周期日志，审计不必回查业务表。
        log(
                code,
                "PUBLISH_WAIVER_GRANT",
                Objects.toString(app.get("status"), ""),
                Objects.toString(app.get("status"), ""),
                Map.of(
                        "waiverId", waiver.id(),
                        "draftRev", waiver.draftRev(),
                        "candidateSha256", waiver.candidateSha256(),
                        "reason", waiver.reason(),
                        "expiresAt", waiver.expiresAt().toString()));
        return waiver.view(Instant.now());
    }

    /** 撤销幂等：重复撤销保留首次 revoked_at/revoked_by，不改写既有审计事实。 */
    public Map<String, Object> revokeWaiver(String code, String waiverId) {
        requireWaiverEnabled();
        var app = get(code);
        if (waiverId == null || !waiverId.matches("[A-Za-z0-9_-]{1,64}"))
            throw error(400, "PUBLISH_WAIVER_INVALID", "豁免标识无效");
        if (waivers(WAIVER_BY_ID, tenant(), code, waiverId).isEmpty())
            throw error(404, "PUBLISH_WAIVER_NOT_FOUND", "发布豁免不存在");
        db.update(
                "update app.publish_waiver set revoked_at=coalesce(revoked_at,now()),"
                        + " revoked_by=coalesce(revoked_by,?) where tenant_id=? and app_code=? and id=?",
                Actor.current().principalId(),
                tenant(),
                code,
                waiverId);
        var revoked = waivers(WAIVER_BY_ID, tenant(), code, waiverId).getFirst();
        log(
                code,
                "PUBLISH_WAIVER_REVOKE",
                Objects.toString(app.get("status"), ""),
                Objects.toString(app.get("status"), ""),
                Map.of("waiverId", waiverId, "reason", revoked.reason()));
        return revoked.view(Instant.now());
    }

    private void requireWaiverEnabled() {
        if (!waiverEnabled)
            throw error(
                    403,
                    "PUBLISH_WAIVER_DISABLED",
                    "发布豁免已被回滚开关 agent.publish.waiver-enabled=false 关闭");
    }

    /** 只认同时命中租户、应用、草稿版本与候选指纹，且未撤销、未过期的豁免。 */
    private PublishWaiver activeWaiver(String code, long rev, String hash) {
        var rows = waivers(WAIVER_ACTIVE, tenant(), code, rev, hash);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 提交时复核：报告引用的豁免必须仍然有效，防止 15 分钟报告窗口掩盖更短的豁免窗口。 */
    private boolean waiverActive(String waiverId) {
        return !db.queryForList(
                        "select 1 from app.publish_waiver where tenant_id=? and id=?"
                                + " and revoked_at is null and expires_at>now()",
                        tenant(),
                        waiverId)
                .isEmpty();
    }

    private List<PublishWaiver> waivers(String sql, Object... args) {
        return db.query(
                sql,
                (r, n) ->
                        new PublishWaiver(
                                r.getString("id"),
                                r.getString("tenant_id"),
                                r.getString("app_code"),
                                r.getLong("draft_rev"),
                                // char(64) 会右补空格，比较前必须裁剪，否则指纹永远匹配不上。
                                r.getString("candidate_sha256").trim(),
                                r.getString("reason"),
                                r.getString("approved_by"),
                                r.getTimestamp("approved_at").toInstant(),
                                r.getTimestamp("expires_at").toInstant(),
                                r.getTimestamp("revoked_at") == null
                                        ? null
                                        : r.getTimestamp("revoked_at").toInstant()),
                args);
    }

    private void check(List<Map<String, Object>> checks, String group, Runnable check) {
        try {
            check.run();
            checks.add(Map.of("group", group, "result", "PASS"));
        } catch (Exception ex) {
            checks.add(
                    Map.of(
                            "group",
                            group,
                            "result",
                            "ERROR",
                            "detail",
                            Objects.toString(ex.getMessage(), "校验失败")));
        }
    }

    private void parameterChecks(
            Map<String, Object> app, Map<String, Object> config, Map<String, Object> definition) {
        if (app.get("type").equals("CHAT") && !config.containsKey("modelBinding"))
            throw new IllegalArgumentException("对话应用缺少modelBinding");
        for (var node : objects(definition.get("nodes"))) {
            String type = Objects.toString(node.get("type"), "");
            if (type.isBlank()) throw new IllegalArgumentException("节点缺少type");
            if (Set.of("SKILL", "TOOL").contains(type)) {
                if (!node.containsKey("skillRef"))
                    throw new IllegalArgumentException("技能节点缺少skillRef");
                var skill = resolveSkill(object(node.get("skillRef")));
                AppRules.validateValue(
                        object(skill.get("inputSchema")),
                        node.getOrDefault("args", Map.of()),
                        Objects.toString(node.get("id")) + ".args",
                        true);
            }
        }
    }

    private void typeChecks(Map<String, Object> config, Map<String, Object> definition) {
        Set<String> allowed =
                Set.of(
                        "START",
                        "END",
                        "LLM",
                        "SKILL",
                        "CONDITION",
                        "CODE",
                        "RETRIEVE",
                        "CONFIRM",
                        "AGENT",
                        "ONTOLOGY");
        var ids = new HashSet<String>();
        for (var n : objects(definition.get("nodes"))) ids.add(Objects.toString(n.get("id"), ""));
        for (var n : objects(definition.get("nodes"))) {
            if (!allowed.contains(Objects.toString(n.get("type"), "")))
                throw new IllegalArgumentException("未知节点类型: " + n.get("type"));
            for (var mapping : objects(n.get("inputMappings"))) {
                String source = Objects.toString(mapping.get("sourceNodeId"), "");
                if (!source.isEmpty() && !ids.contains(source))
                    throw new IllegalArgumentException("参数引用不存在节点: " + source);
                if (mapping.containsKey("sourceType")
                        && mapping.containsKey("targetType")
                        && !mapping.get("sourceType").equals(mapping.get("targetType")))
                    throw new IllegalArgumentException("参数映射类型不兼容");
            }
        }
        var referencePattern = java.util.regex.Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\.");
        for (var node : objects(definition.get("nodes"))) {
            var matcher = referencePattern.matcher(Json.write(node));
            while (matcher.find()) {
                String scope = matcher.group(1);
                if (!Set.of("input", "context", "error").contains(scope) && !ids.contains(scope))
                    throw new IllegalArgumentException("变量引用不存在节点或作用域: " + scope);
            }
        }
        validateSchema(config.get("inputSchema"));
        validateSchema(config.get("outputSchema"));
    }

    /**
     * 应用预检的 Schema 方言：先归一再统一判定（{@link SchemaDialect}），聚合全部错误而不是只报首个。
     * 错误保留旧前缀 {@code Schema缺少有效type} 并追加 JSON Pointer，既有调用方/前端按前缀识别，不需要新协议。
     *
     * <p>有意保留的旧约束：根 Schema 仍必须声明 type，且根必须是对象（旧实现要求每个 properties/items
     * 子 Schema 都显式声明 type，本实现只要求根，嵌套缺 type 是合法 JSON Schema）。放宽原因与兼容性证据见
     * docs/evidence/2026-10-27-PLAT-08a-dialect/README.md。
     *
     * <p>包级可见：仅供同包单测直接覆盖预检规则，业务调用点（typeChecks/referenceChecks）不变。
     */
    void validateSchema(Object raw) {
        if (raw == null) return;
        JsonNode schema;
        try {
            schema = Json.tree(raw);
        } catch (RuntimeException ex) {
            // 保留序列化失败的原因而不是静默丢弃；数组/标量根能转换，会在下方 validate 中带 pointer 拒绝。
            throw new IllegalArgumentException("Schema缺少有效type: /: Schema必须为JSON对象", ex);
        }
        var normalized = SchemaDialect.normalize(schema);
        var errors = new ArrayList<>(SchemaDialect.validate(normalized));
        if (!normalized.isObject() || !normalized.has("type"))
            errors.add(
                    "/type: 缺少有效type（object/array/string/integer/number/boolean/null 或 [T,\"null\"]）");
        if (!errors.isEmpty())
            throw new IllegalArgumentException("Schema缺少有效type: " + String.join("; ", errors));
    }

    public List<Map<String, Object>> skillRefs(
            Map<String, Object> config, Map<String, Object> definition) {
        var refs = new ArrayList<Map<String, Object>>(objects(config.get("skillRefs")));
        for (var node : objects(definition.get("nodes")))
            if (node.get("skillRef") instanceof Map) refs.add(object(node.get("skillRef")));
        return refs;
    }

    private Map<String, Object> resolveSkill(Map<String, Object> ref) {
        String code = text(ref, "code");
        long version = number(ref, "version");
        var skill = remote(skillUrl, "/internal/skills/" + code);
        if ("DISABLED".equals(skill.get("status")))
            throw new IllegalArgumentException("技能已停用: " + code);
        return objects(skill.get("versions")).stream()
                .filter(
                        v ->
                                v.getOrDefault("versionNo", v.getOrDefault("version", 0))
                                                instanceof Number n
                                        && n.longValue() == version
                                        && Set.of("PUBLISHED", "ARCHIVED")
                                                .contains(v.get("status")))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("技能版本未发布: " + code + "@" + version));
    }

    private void referenceChecks(
            Map<String, Object> config, Map<String, Object> definition, boolean guards) {
        for (var ref : skillRefs(config, definition)) {
            String skillCode = text(ref, "code");
            long version = number(ref, "version");
            var skill = resolveSkill(ref);
            if (guards) {
                var guard = object(skill.getOrDefault("guard", Map.of()));
                var declared = object(config.getOrDefault("guard", Map.of()));
                var needRoles =
                        new HashSet<>((Collection<?>) guard.getOrDefault("roles", List.of()));
                var haveRoles =
                        new HashSet<>((Collection<?>) declared.getOrDefault("roles", List.of()));
                if (!haveRoles.containsAll(needRoles))
                    throw new IllegalArgumentException("应用未声明技能所需角色: " + skillCode);
                var attributes = object(guard.getOrDefault("attributes", Map.of()));
                var have = object(declared.getOrDefault("attributes", Map.of()));
                for (var e : attributes.entrySet()) {
                    if (!(e.getValue() instanceof Collection<?> required)
                            || !(have.get(e.getKey()) instanceof Collection<?> actual)
                            || !actual.containsAll(required))
                        throw new IllegalArgumentException("应用未声明技能所需属性: " + e.getKey());
                }
            } else {
                validateSchema(skill.get("inputSchema"));
                validateSchema(skill.get("outputSchema"));
            }
        }
        if (guards) return;
        if (config.get("modelBinding") instanceof Map<?, ?> binding) {
            Object id = binding.get("modelEntryId");
            if (id == null) throw new IllegalArgumentException("模型绑定需显式modelEntryId");
            var model = remote(modelUrl, "/internal/model-entries/" + id);
            if (!"ENABLED".equals(object(model.get("provider")).get("status")))
                throw new IllegalArgumentException("模型供应商不可用");
        }
        var promptRefs = new ArrayList<>(objects(config.get("promptRefs")));
        for (var node : objects(definition.get("nodes")))
            if (node.get("promptRef") instanceof Map) {
                var prompt = object(node.get("promptRef"));
                promptRefs.add(
                        Map.of(
                                "code",
                                prompt.getOrDefault(
                                        "templateCode", prompt.getOrDefault("code", "")),
                                "version",
                                prompt.getOrDefault("version", 0)));
            }
        for (var ref : promptRefs) {
            var rows =
                    db.queryForList(
                            "select status from app.prompt_template_version where tenant_id=? and"
                                    + " template_code=? and version_no=?",
                            tenant(),
                            text(ref, "code"),
                            number(ref, "version"));
            if (rows.isEmpty() || !"PUBLISHED".equals(rows.getFirst().get("status")))
                throw new IllegalArgumentException("模板引用未发布");
        }
    }

    public Map<String, Object> commit(String code, Map<String, Object> body) {
        String request = text(body, "idempotencyRequestNo"),
                precheck = text(body, "precheckId"),
                hash = text(body, "candidateSha256");
        long rev = number(body, "draftRev");
        // 当前禁用策略在事务外复核；事务内只读已持久快照，绝不持应用锁等待HTTP。
        var frozenReports =
                db.queryForList(
                        "select model_refs::text from app.publish_report where tenant_id=? and id=?"
                                + " and app_code=? and draft_rev=? and candidate_sha256=? and"
                                + " passed=true and expires_at>now()",
                        tenant(),
                        precheck,
                        code,
                        rev,
                        hash);
        boolean replay =
                !db.queryForList(
                                "select 1 from app.publish_commit where tenant_id=? and app_code=?"
                                        + " and request_no=?",
                                tenant(),
                                code,
                                request)
                        .isEmpty();
        if (!replay && !frozenReports.isEmpty())
            for (var ref :
                    objects(
                            Json.read(
                                    frozenReports.getFirst().get("model_refs").toString(),
                                    Object.class)))
                remotePost(
                        modelUrl,
                        "/internal/model-bindings/validate",
                        object(ref.get("modelBindingRef")));
        // 发布事务仅使用预检已固定的报告编号，不在持锁期间进行HTTP；15分钟有效期限制依赖校验结果的使用窗口。
        // 本体绑定是事务提交后的外部副作用（事务内禁止 HTTP）：lambda 只把候选带出事务，
        // 重放分支读已固化快照，普通分支用刚校验过的候选。
        var released = new AtomicReference<Map<String, Object>>();
        var committed = tx.execute(
                s -> {
                    // 所有改稿与发布都锁同一草稿行：复核与固化之间不能插入未经检查的修改。
                    var app = get(code, true);
                    var prior =
                            db.queryForList(
                                    "select version_no,draft_rev,candidate_sha256 from"
                                        + " app.publish_commit where tenant_id=? and app_code=? and"
                                        + " request_no=?",
                                    tenant(),
                                    code,
                                    request);
                    if (!prior.isEmpty()) {
                        var old = prior.getFirst();
                        if (((Number) old.get("draft_rev")).longValue() != rev
                                || !hash.equals(old.get("candidate_sha256")))
                            throw error(409, "IDEMPOTENCY_CONFLICT", "请求号已用于其他候选");
                        released.set(releasedCandidate(code, ((Number) old.get("version_no")).intValue()));
                        return publication(code, ((Number) old.get("version_no")).intValue(), hash);
                    }
                    assertCapabilitySource(app);
                    if (((Number) app.get("draftRev")).longValue() != rev || !sha(app).equals(hash))
                        throw error(412, "CANDIDATE_CHANGED", "草稿已变更，请重新预检");
                    var reports =
                            db.queryForList(
                                    "select * from app.publish_report where tenant_id=? and id=?"
                                        + " and app_code=? and draft_rev=? and candidate_sha256=?"
                                        + " and passed=true and expires_at>now()",
                                    tenant(),
                                    precheck,
                                    code,
                                    rev,
                                    hash);
                    if (reports.isEmpty())
                        throw error(412, "PRECHECK_INVALID", "预检无效、已过期或被阻断，请重新预检");
                    var publicationEvidence = requirePublicationEnvironment(reports.getFirst(), app, code, rev, hash, precheck, body);
                    // 报告有效期 15 分钟，豁免窗口可能更短或已被撤销：提交时重查一次，
                    // 否则撤销/到期后仍能用旧报告发布，撤销语义会被报告窗口吞掉。
                    var waiverRef = reports.getFirst().get("waiver_id");
                    if (waiverRef != null && !waiverActive(waiverRef.toString()))
                        throw error(409, "EVAL_GATE_BLOCKED", "发布豁免已撤销或过期，请重新预检或重新申请豁免");
                    var ids = new LinkedHashSet<Long>();
                    collectModelIds(app.get("config"), ids);
                    collectModelIds(app.get("definition"), ids);
                    if (!ids.isEmpty()) {
                        var frozen =
                                db.queryForList(
                                        "select model_refs::text from app.candidate_model_snapshot"
                                            + " where tenant_id=? and app_code=? and draft_rev=?"
                                            + " and candidate_sha256=?",
                                        tenant(),
                                        code,
                                        rev,
                                        hash);
                        if (frozen.isEmpty()
                                || !Json.canonical(
                                                Json.read(
                                                        frozen.getFirst()
                                                                .get("model_refs")
                                                                .toString()))
                                        .equals(
                                                Json.canonical(
                                                        Json.read(
                                                                reports.getFirst()
                                                                        .get("model_refs")
                                                                        .toString()))))
                            throw error(412, "MODEL_SNAPSHOT_MISMATCH", "候选与预检模型快照不一致，请重新评测预检");
                    }
                    if (!Set.of("DRAFT", "PUBLISHED").contains(app.get("status")))
                        throw error(409, "APP_STATE", "应用不可发布");
                    released.set(candidate(app));
                    var existing =
                            db.queryForList(
                                    "select version_no from app.app_version where tenant_id=? and"
                                            + " app_code=? and draft_rev=? and candidate_sha256=?",
                                    tenant(),
                                    code,
                                    rev,
                                    hash);
                    int version;
                    // 内容身份是(应用,修订,摘要)。不同请求号也复用已固化版本，不为并发重复发号。
                    if (existing.isEmpty()) {
                        version =
                                db.queryForObject(
                                        "select coalesce(max(version_no),0)+1 from app.app_version"
                                                + " where tenant_id=? and app_code=?",
                                        Integer.class,
                                        tenant(),
                                        code);
                        var snapshot = candidate(app);
                        snapshot.put("versionNo", version);
                        snapshot.put("draftRev", rev);
                        snapshot.put("candidateSha256", hash);
                        snapshot.put("appVersionId", code + "@" + version);
                        snapshot.put("interpreterVersion", "1.0");
                        snapshot.put("publicationTarget", publicationTarget);
                        snapshot.put("publicationEvidence", publicationEvidence);
                        if ("DEMO".equals(publicationEvidence.get("environment")))
                            snapshot.put("evaluationEnvironmentConfirmation", Map.of("confirmed", true,
                                    "precheckId", precheck, "evalReportId", publicationEvidence.get("evalReportId"),
                                    "evidenceSha256", publicationEvidence.get("evidenceSha256")));
                        attachModels(
                                snapshot,
                                objects(
                                        Json.read(
                                                reports.getFirst().get("model_refs").toString(),
                                                Object.class)));
                        db.update(
                                "insert into"
                                    + " app.app_version(tenant_id,app_id,app_draft_id,app_code,version_no,draft_rev,candidate_sha256,snapshot,eval_report_id,published_by)"
                                    + " values(?,?,?,?,?,?,?,?::jsonb,?,?)",
                                tenant(),
                                app.get("id"),
                                app.get("draftId"),
                                code,
                                version,
                                rev,
                                hash,
                                Json.write(snapshot),
                                reports.getFirst().get("eval_report_id"),
                                Actor.current().principalId());
                        for (var ref :
                                skillRefs(object(app.get("config")), object(app.get("definition"))))
                            db.update(
                                    "insert into"
                                        + " app.skill_reference(tenant_id,app_code,app_version_no,app_version_id,skill_code,skill_version)"
                                        + " values(?,?,?,(select id from app.app_version where"
                                        + " tenant_id=? and app_code=? and version_no=?),?,?) on"
                                        + " conflict do nothing",
                                    tenant(),
                                    code,
                                    version,
                                    tenant(),
                                    code,
                                    version,
                                    text(ref, "code"),
                                    number(ref, "version"));
                    } else {
                        version = ((Number) existing.getFirst().get("version_no")).intValue();
                        var oldSnapshot = Json.read(db.queryForObject("select snapshot::text from app.app_version where tenant_id=? and app_code=? and version_no=?",
                                String.class, tenant(), code, version));
                        var oldEvidence = oldSnapshot.path("publicationEvidence").isObject()
                                ? object(Json.mapper().convertValue(oldSnapshot.path("publicationEvidence"), Map.class)) : Map.<String, Object>of();
                        if (!publicationTarget.equals(oldSnapshot.path("publicationTarget").asText())
                                || !validPublicationEvidence(oldEvidence, code, rev, hash, AppDigest.semantic(candidate(app))))
                            throw error(409, "EVALUATION_ENVIRONMENT_REQUIRED", "既有版本缺少当前目标的可信来源，不能借新请求追认；请新建修订重新评测");
                    }
                    // 版本、当前指针、引用与幂等凭据处于同一事务，任一写失败全部回滚。
                    db.update(
                            "update app.application set"
                                + " current_version_no=?,status='PUBLISHED',updated_at=now() where"
                                + " tenant_id=? and code=?",
                            version,
                            tenant(),
                            code);
                    db.update(
                            "insert into"
                                + " app.publish_commit(tenant_id,app_id,app_version_id,app_code,request_no,precheck_id,version_no,draft_rev,candidate_sha256)"
                                + " values(?,?,(select id from app.app_version where tenant_id=?"
                                + " and app_code=? and version_no=?),?,?,?,?,?,?)",
                            tenant(),
                            app.get("id"),
                            tenant(),
                            code,
                            version,
                            code,
                            request,
                            precheck,
                            version,
                            rev,
                            hash);
                    log(
                            code,
                            "PUBLISH",
                            app.get("status").toString(),
                            "PUBLISHED",
                            Map.of("versionNo", version, "candidateSha256", hash));
                    return publication(code, version, hash);
                });
        // 绑定失败不回滚已提交版本（fail-open 默认）；结果只并入返回副本，不改事务产出的不可变发布凭据。
        var result = new LinkedHashMap<String, Object>(committed);
        var pin = pin(released.get(), code);
        // T18.3：落库发生在发布事务提交之后、与返回值组装同一处——发布事务内禁止 HTTP，pin 结果
        // 只在提交后才存在；落库自身是单条 UPSERT 自动提交，失败只告警，绝不影响已提交的发布与响应。
        recordPin(code, released.get(), pin, committed.get("versionNo"));
        result.put("pin", pin);
        return result;
    }

    /**
     * 事务提交后的观测写入（T18.3）：复用 {@link ReleasePins} 状态机结果，只读其返回 Map 与候选里的
     * config.ontology 声明，不改客户端本体。fail-closed 时 {@link #pin} 已抛 502，本方法不会被调用，
     * 与"拒绝本次响应"语义一致（版本已落库，重放同一请求号会再次尝试绑定并写观测行）。
     */
    private void recordPin(
            String code, Map<String, Object> candidate, Map<String, Object> pin, Object versionNo) {
        if (!pinRecordEnabled) return;
        try {
            String state = Objects.toString(pin.get("state"), "");
            String releaseId = ontologyReleaseId(candidate);
            String bindingId = Objects.toString(pin.get("bindingId"), null);
            boolean pinned = "PINNED".equals(state);
            db.update(
                    PIN_UPSERT,
                    tenant(),
                    code,
                    ((Number) Objects.requireNonNull(versionNo, "发布版本号缺失")).intValue(),
                    state,
                    releaseId,
                    bindingId,
                    Objects.toString(pin.get("reason"), null),
                    pinEnvironment,
                    pinned ? releaseId : null,
                    pinned ? bindingId : null,
                    pinned ? new Timestamp(System.currentTimeMillis()) : null);
        } catch (RuntimeException failure) {
            // 观测不得破坏发布：已提交的版本与响应照常返回；traceId 走 MDC（TraceContextFilter），
            // 日志模式自动带上，这里补记应用与状态供事后补偿。
            log.warn(
                    "pin 结果落库失败（不影响已提交发布）app={} state={} cause={}",
                    code,
                    pin.get("state"),
                    failure.getClass().getSimpleName());
        }
    }

    /** 候选里声明的目标 release（尽力记录，非远端回执）；未声明或为空返回 null。 */
    private static String ontologyReleaseId(Map<String, Object> candidate) {
        if (candidate == null || !(candidate.get("config") instanceof Map<?, ?> config)) return null;
        if (!(config.get("ontology") instanceof Map<?, ?> ontology)) return null;
        Object releaseId = ontology.get("releaseId");
        if (releaseId == null) return null;
        String value = releaseId.toString().trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * 按应用反查当前 pin 状态（管理面/运维用，T18.3）。应用不存在显式 404；
     * V106 之前的旧发布记录没有 pin 行，返回空 Map 而不是报错或静默造数据。
     */
    public Map<String, Object> pinStatus(String code) {
        get(code);
        var rows =
                db.queryForList(
                        "select * from app.publish_pin where tenant_id=? and app_code=?",
                        tenant(),
                        code);
        if (rows.isEmpty()) return new LinkedHashMap<>();
        var r = rows.getFirst();
        var view = new LinkedHashMap<String, Object>();
        view.put("appCode", code);
        view.put("versionNo", r.get("app_version_no"));
        view.put("state", r.get("state"));
        view.put("releaseId", r.get("release_id"));
        view.put("bindingId", r.get("binding_id"));
        view.put("reason", r.get("reason"));
        view.put("environment", r.get("environment"));
        view.put("lastPinnedReleaseId", r.get("last_pinned_release_id"));
        view.put("lastPinnedBindingId", r.get("last_pinned_binding_id"));
        view.put(
                "lastPinnedAt",
                r.get("last_pinned_at") == null
                        ? null
                        : ((Timestamp) r.get("last_pinned_at")).toInstant().toString());
        view.put(
                "updatedAt",
                r.get("updated_at") == null
                        ? null
                        : ((Timestamp) r.get("updated_at")).toInstant().toString());
        return view;
    }

    /**
     * 事务提交后调用一次绑定；结果只在这里并入返回值。fail-open 时 FAILED 供调用方观测，
     * fail-closed 由客户端抛 502，语义是拒绝本次响应，而事务内的发布已经不可回滚。
     */
    private Map<String, Object> pin(Map<String, Object> candidate, String code) {
        var client = pins;
        if (client == null) return Map.of("state", "DISABLED", "reason", "未装配发布绑定客户端");
        return client.pin(Actor.current(), code, candidate == null ? Map.of() : candidate);
    }

    /**
     * 重放时按版本号读回不可变快照：同一请求号可能在草稿被继续修改后重放，绑定必须描述当次发布的
     * 版本内容，而不是重放时刻的草稿。
     */
    private Map<String, Object> releasedCandidate(String code, int version) {
        var rows =
                db.query(
                        "select snapshot::text from app.app_version where tenant_id=? and app_code=?"
                                + " and version_no=?",
                        (r, n) -> {
                            var snapshot =
                                    new LinkedHashMap<String, Object>(
                                            Json.read(r.getString(1), Map.class));
                            snapshot.put("code", code);
                            return candidate(snapshot);
                        },
                        tenant(),
                        code,
                        version);
        if (rows.isEmpty()) throw error(404, "VERSION_NOT_FOUND", "版本不存在");
        return rows.getFirst();
    }

    private Map<String, Object> publication(String code, int version, String hash) {
        String base =
                System.getenv()
                        .getOrDefault("AGENT_WEBAPP_URL", "http://127.0.0.1:15174")
                        .replaceAll("/+$", "");
        String gateway =
                System.getenv()
                        .getOrDefault("AGENT_PUBLIC_GATEWAY_URL", "http://127.0.0.1:18080")
                        .replaceAll("/+$", "");
        String snippet =
                "<script src="
                        + Json.write(base + "/assistant.js")
                        + "></script>\n<script>\nAIAssistant.init({endpoint:"
                        + Json.write(gateway)
                        + ",appCode:"
                        + Json.write(code)
                        + ",ticket:async()=>{const response=await"
                        + " fetch('/your-backend/ticket',{method:'POST'});if(!response.ok)throw new"
                        + " Error('ticket exchange failed');const body=await response.json();return"
                        + " body.data.ticket;}});\n"
                        + "</script>";
        return Map.of(
                "versionNo",
                version,
                "sha256",
                hash,
                "appCode",
                code,
                "artifacts",
                Map.of(
                        "webAppUrl",
                        base + "/w/" + code,
                        "apiDocUrl",
                        "/admin/apps/" + code + "/integration",
                        "embedSnippet",
                        snippet,
                        "ticketUrl",
                        "/your-backend/ticket",
                        "ticketInstructions",
                        "宿主后端持有 ApiKey 并向网关换取短期应用票据；浏览器仅接收 ticket，禁止嵌入长期 ApiKey。"));
    }
}
