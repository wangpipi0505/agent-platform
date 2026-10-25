package cn.agentplatform.app.application;

import cn.agentplatform.app.domain.AppRules;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** 应用草稿与发布事务。业务定义进入候选摘要，画布布局只用于展示；运行时始终读取不可变版本。 */
@Service
public class AppService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final TransactionTemplate publishedReadTx;
    private final RestClient http = client();
    private final String skillUrl, modelUrl, monitorUrl, engineUrl;

    public AppService(
            JdbcTemplate db,
            org.springframework.transaction.PlatformTransactionManager manager,
            @Value("${agent.services.skill:http://127.0.0.1:18083}") String skillUrl,
            @Value("${agent.services.model:http://127.0.0.1:18084}") String modelUrl,
            @Value("${agent.services.monitor:http://127.0.0.1:18085}") String monitorUrl,
            @Value("${agent.services.engine:http://127.0.0.1:18082}") String engineUrl) {
        this.engineUrl = engineUrl;
        this.db = db;
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

    public static String text(Map<String, Object> body, String field) {
        String v = Objects.toString(body.get(field), "");
        if (v.isBlank() || v.length() > 128) throw error(400, "INVALID_INPUT", "缺少或无效字段: " + field);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object v) {
        if (!(v instanceof Map<?, ?>)) throw error(400, "INVALID_INPUT", "必须提供JSON对象");
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objects(Object v) {
        if (v == null) return List.of();
        if (!(v instanceof List<?> list) || list.stream().anyMatch(x -> !(x instanceof Map)))
            throw error(400, "INVALID_INPUT", "必须提供对象数组");
        return (List<Map<String, Object>>) v;
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
        v.put("candidateSha256", sha(v));
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
                        var input = new LinkedHashMap<String, Object>();
                        input.put("code", code);
                        input.put("name", name);
                        for (String field :
                                List.of("type", "description", "domain", "bizCategory", "tags"))
                            input.put(field, source.get(field));
                        create(input);
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
                            "insert into app.app_draft(tenant_id,app_id,app_code) select"
                                + " tenant_id,id,code from app.application where tenant_id=? and"
                                + " code=?",
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
        snapshot.put(
                "dependencyVersions",
                Map.of(
                        "skillRefs",
                        skillRefs(
                                object(snapshot.get("config")), object(snapshot.get("definition"))),
                        "modelRefs",
                        refs));
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

    public Map<String, Object> precheck(String code) {
        var app = get(code);
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
        var evalHolder = new HashMap<String, Object>();
        check(
                checks,
                "评测门禁",
                () -> {
                    var eval =
                            remote(
                                    monitorUrl,
                                    "/internal/evaluations/accepted?appCode="
                                            + code
                                            + "&draftRev="
                                            + rev
                                            + "&candidateSha256="
                                            + hash);
                    if (!Boolean.TRUE.equals(eval.get("accepted")))
                        throw new IllegalArgumentException("缺少满足安全100%、通过率及无新增回归的候选报告");
                    evalHolder.putAll(eval);
                });
        String id = "pc_" + UUID.randomUUID();
        boolean passed = checks.stream().allMatch(c -> c.get("result").equals("PASS"));
        String evalId = Objects.toString(evalHolder.get("evalReportId"), "");
        db.update(
                "insert into"
                    + " app.publish_report(tenant_id,id,app_draft_id,app_code,draft_rev,candidate_sha256,eval_report_id,checks,passed,expires_at,model_refs)"
                    + " values(?,?,?,?,?,?,?,?::jsonb,?,?,?::jsonb)",
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
                Json.write(models));
        return Map.of(
                "precheckId",
                id,
                "draftRev",
                rev,
                "candidateSha256",
                hash,
                "evalReportId",
                evalId,
                "passed",
                passed,
                "result",
                passed ? "PASS" : "BLOCKED",
                "report",
                Map.of("checks", checks, "warnings", List.of()));
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

    private void validateSchema(Object raw) {
        if (raw == null) return;
        var schema = object(raw);
        String type = Objects.toString(schema.get("type"), "");
        if (!Set.of("object", "array", "string", "integer", "number", "boolean", "null")
                .contains(type)) throw new IllegalArgumentException("Schema缺少有效type");
        if (schema.get("properties") instanceof Map<?, ?> properties)
            properties.values().forEach(this::validateSchema);
        if (schema.containsKey("items")) validateSchema(schema.get("items"));
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
        return tx.execute(
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
                        return publication(code, ((Number) old.get("version_no")).intValue(), hash);
                    }
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
                    } else version = ((Number) existing.getFirst().get("version_no")).intValue();
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
