package cn.agentplatform.app.application;

import cn.agentplatform.app.domain.AppRules;
import cn.agentplatform.app.domain.CapabilityValues;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * 应用草稿生命周期：新建、复制、保存编排、改元数据、停启用/归档/回滚、删除与生命周期日志。
 *
 * <p>拆分动机来自结构卡 MNT-06：发布预检与发布提交（{@link AppService} 的 precheck/commit/pin）
 * 与草稿生命周期是两条不同的责任线，混在一个 2000 行的类里既难审也无法单独回归。此处只搬移
 * 既有实现，判据、错误码、缺省值、事务边界与写库顺序逐字保持；发布侧需要的读取能力通过
 * {@link Port} 反向注入，避免生命周期子步反过来依赖发布实现。
 *
 * <p>事务所有权：本类的每个写方法各自开启事务（与搬移前一致），{@code copy} 内部调用
 * {@code create} 时因传播级别为 REQUIRED 仍加入外层事务，因此复制要么整份成功要么整份回滚。
 */
public class AppLifecycleService {

    /**
     * 生命周期写路径只需要的读取/校验能力，由 {@link AppService} 实现并注入。
     *
     * <p>刻意保持窄口：发布快照、预检、pin 等发布责任不从这里暴露，防止把发布逻辑重新拖回生命周期。
     */
    public interface Port {

        /** 加锁读取应用与草稿（对应原 {@code get(code, true)}）。 */
        Map<String, Object> load(String code, boolean lock);

        /** 读取最新视图（对应原 {@code get(code)}）。 */
        Map<String, Object> reload(String code);

        /** 读取历史发布版本，回滚时用于确认目标版本存在。 */
        Map<String, Object> version(String code, int versionNo);

        /** 把用户提交的 config 归一为含模板摘要的编排输入。 */
        Map<String, Object> templateConfig(Map<String, Object> config);

        /** 能力包来源校验：带能力包的应用不能脱离其冻结来源。 */
        void assertCapabilitySource(Map<String, Object> app);
    }

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Port port;

    public AppLifecycleService(JdbcTemplate db, PlatformTransactionManager manager, Port port) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.port = port;
    }

    private String tenant() {
        return Actor.current().tenantId();
    }

    private static PlatformException err(int status, String code, String message) {
        return new PlatformException(status, code, message);
    }

    private static String text(Map<String, Object> body, String field) {
        return CapabilityValues.text(body, field);
    }

    private static Map<String, Object> object(Object v) {
        return CapabilityValues.object(v);
    }

    private static long number(Map<String, Object> body, String field) {
        Object v = body.get(field);
        if (!(v instanceof Number n) || n.longValue() < 1)
            throw err(400, "INVALID_INPUT", "缺少或无效数字: " + field);
        return n.longValue();
    }

    public Map<String, Object> create(Map<String, Object> body) {
        String code = text(body, "code"), name = text(body, "name"), type = text(body, "type");
        if (!code.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || !Set.of("CHAT", "FLOW").contains(type))
            throw err(400, "INVALID_INPUT", "应用编码或类型无效");
        Map<String, Object> initialConfig = Map.of();
        if (body.containsKey("promptTemplateRef")) {
            var reference = object(body.get("promptTemplateRef"));
            if (!Set.of("code", "version").equals(reference.keySet()))
                throw err(400, "INVALID_INPUT", "创建时仅提供模板编码与精确版本，摘要由服务端固定");
            initialConfig = port.templateConfig(Map.of("promptSource", "TEMPLATE", "promptRefs", List.of(reference)));
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
                    return port.reload(code);
                });
    }

    public Map<String, Object> copy(String sourceCode, Map<String, Object> body) {
        if (!(body.get("code") instanceof String) || !(body.get("name") instanceof String))
            throw err(400, "INVALID_INPUT", "新应用编码和名称必须为字符串");
        String code = text(body, "code"), name = text(body, "name");
        try {
            return tx.execute(
                    s -> {
                        port.load(sourceCode, true);
                        // READ COMMITTED 下等待 d 锁的 join 可能保留旧 a；持有既有草稿锁后，
                        // 用新语句快照读取完整来源，保证复制内容与来源修订/发布状态一致。
                        var source = port.reload(sourceCode);
                        port.assertCapabilitySource(source);
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
                        return port.reload(code);
                    });
        } catch (DuplicateKeyException ex) {
            throw err(409, "APP_CODE_CONFLICT", "应用编码已存在，请更换编码");
        }
    }

    public Map<String, Object> save(String code, Map<String, Object> body, boolean flow) {
        return tx.execute(
                s -> {
                    var old = port.load(code, true);
                    long rev = number(body, "draftRev");
                    if (((Number) old.get("draftRev")).longValue() != rev)
                        throw err(409, "DRAFT_CONFLICT", "草稿已被更新，请刷新");
                    if (Set.of("DISABLED", "ARCHIVED").contains(old.get("status")))
                        throw err(409, "APP_STATE", "请先重新启用应用");
                    if (flow && !"FLOW".equals(old.get("type")))
                        throw err(409, "APP_TYPE", "仅流程应用可保存流程图");
                    String field = flow ? "definition" : "config";
                    Map<String, Object> value = object(body.get(field));
                    if (!flow) value = port.templateConfig(value);
                    var checked = new LinkedHashMap<String, Object>(old);
                    checked.put(field, value);
                    if (object(old.get("config")).containsKey("capabilityPackages")
                            && !object(checked.get("config")).containsKey("capabilityPackages"))
                        throw err(409, "CAPABILITY_SOURCE_CONFLICT", "不能移除应用的能力包来源");
                    port.assertCapabilitySource(checked);
                    boolean changed =
                            !AppRules.canonical(value).equals(AppRules.canonical(old.get(field)));
                    Object layout =
                            flow
                                    ? body.getOrDefault("layout", old.get("layout"))
                                    : old.get("layout");
                    if (!(layout instanceof Map)) throw err(400, "INVALID_INPUT", "layout必须为对象");
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
                    return port.reload(code);
                });
    }

    public Map<String, Object> metadata(String code, Map<String, Object> body) {
        return tx.execute(
                status -> {
                    var old = port.load(code, true);
                    if (number(body, "draftRev") != ((Number) old.get("draftRev")).longValue())
                        throw err(409, "DRAFT_CONFLICT", "请刷新草稿");
                    if (Set.of("DISABLED", "ARCHIVED").contains(old.get("status")))
                        throw err(409, "APP_STATE", "请先启用应用");
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
                    return port.reload(code);
                });
    }

    /** 生命周期日志：发布路径也复用同一写入点，避免两处各写一份 from/to 语义。 */
    public void log(String code, String action, String from, String to, Object detail) {
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
        port.reload(code);
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
                    var old = port.load(code, true);
                    String from = old.get("status").toString();
                    String to =
                            switch (action) {
                                case "disable" -> {
                                    if (!Set.of("PUBLISHED", "DRAFT").contains(from)
                                            || old.get("currentVersionNo") == null)
                                        throw err(409, "APP_STATE", "仅已发布应用可停用");
                                    yield "DISABLED";
                                }
                                case "enable" -> {
                                    if (!from.equals("DISABLED")
                                            || old.get("currentVersionNo") == null)
                                        throw err(409, "APP_STATE", "仅停用应用可启用");
                                    yield "PUBLISHED";
                                }
                                case "archive" -> {
                                    if (!from.equals("DISABLED"))
                                        throw err(409, "APP_STATE", "仅停用应用可归档");
                                    yield "ARCHIVED";
                                }
                                case "rollback" -> {
                                    int version = (int) number(body, "versionNo");
                                    port.version(code, version);
                                    if (!Set.of("PUBLISHED", "DRAFT").contains(from))
                                        throw err(409, "APP_STATE", "当前状态不能回滚");
                                    db.update(
                                            "update app.application set current_version_no=? where"
                                                    + " tenant_id=? and code=?",
                                            version,
                                            tenant(),
                                            code);
                                    yield "PUBLISHED";
                                }
                                default -> throw err(400, "INVALID_ACTION", "未知生命周期操作");
                            };
                    db.update(
                            "update app.application set status=?,updated_at=now() where tenant_id=?"
                                    + " and code=?",
                            to,
                            tenant(),
                            code);
                    log(code, action.toUpperCase(Locale.ROOT), from, to, body);
                    return port.reload(code);
                });
    }

    public void delete(String code) {
        tx.executeWithoutResult(
                s -> {
                    var old = port.load(code, true);
                    if (!old.get("status").equals("DRAFT") || old.get("currentVersionNo") != null)
                        throw err(409, "APP_STATE", "只能删除从未发布的草稿");
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
}
