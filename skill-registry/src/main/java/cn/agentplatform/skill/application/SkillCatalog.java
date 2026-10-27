package cn.agentplatform.skill.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.auth.AuthScheme;
import cn.agentplatform.skill.domain.ProcessContract;
import cn.agentplatform.skill.domain.SkillRules;
import cn.agentplatform.skill.infra.McpAdapter;
import cn.agentplatform.skill.infra.OpenApiHttpMapping;
import cn.agentplatform.skill.infra.SkillAdapters;
import cn.agentplatform.skill.infra.SkillClients;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** 技能版本只保存契约，发布指针与历史引用分离，已经发布的契约不被后续草稿修改。 */
@Service
public class SkillCatalog {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final SkillClients clients;
    private final String appUrl;
    private final boolean legacyBearer;

    /** 单测直连四参构造：等价于默认 legacy-bearer=true，既有测试无需感知本卡开关。 */
    public SkillCatalog(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            SkillClients clients,
            String appUrl) {
        this(db, manager, clients, appUrl, true);
    }

    /**
     * 容器注入的正式构造。legacy-bearer 只影响注册期归一化（未声明 authScheme 时补 BEARER 还是
     * 拒绝），见 {@link cn.agentplatform.common.auth.AuthScheme} 的兼容窗口说明；默认 true 保证
     * 升级瞬间存量技能的对外凭据形态不变。
     */
    @Autowired
    public SkillCatalog(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            SkillClients clients,
            @Value("${agent.services.app:http://127.0.0.1:18081}") String appUrl,
            @Value("${agent.skill.auth.legacy-bearer:true}") boolean legacyBearer) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.clients = clients;
        this.appUrl = appUrl;
        this.legacyBearer = legacyBearer;
    }

    private String tenant() {
        return Actor.current().tenantId();
    }

    public static PlatformException error(int status, String code, String message) {
        return new PlatformException(status, code, message);
    }

    public static String text(Map<String, Object> b, String key) {
        String value = Objects.toString(b.get(key), "");
        if (value.isBlank() || value.length() > 4096)
            throw error(400, "INVALID_SKILL", "缺少或无效字段: " + key);
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object raw) {
        if (!(raw instanceof Map)) throw error(400, "INVALID_SKILL", "字段必须为JSON对象");
        return (Map<String, Object>) raw;
    }

    public static long number(Map<String, Object> b, String key) {
        if (!(b.get(key) instanceof Number n) || n.longValue() < 1)
            throw error(400, "INVALID_SKILL", "缺少正整数: " + key);
        return n.longValue();
    }

    private Map<String, Object> main(String code, boolean lock) {
        var rows =
                db.queryForList(
                        "select * from skill.skill where tenant_id=? and code=?"
                                + (lock ? " for update" : ""),
                        tenant(),
                        code);
        if (rows.isEmpty()) throw error(404, "SKILL_NOT_FOUND", "技能不存在");
        return rows.getFirst();
    }

    private Map<String, Object> versionRow(ResultSet row, int ignored) throws SQLException {
        var result =
                new LinkedHashMap<String, Object>(Json.read(row.getString("contract"), Map.class));
        result.put("skillVersionId", row.getLong("id"));
        result.put("id", row.getLong("id"));
        result.put("versionNo", row.getInt("version_no"));
        result.put("version", row.getInt("version_no"));
        result.put("status", row.getString("status"));
        result.put("sha256", row.getString("sha256"));
        result.put("digest", row.getString("sha256"));
        result.put("definition", Json.read(row.getString("contract"), Object.class));
        result.put("code", row.getString("skill_code"));
        return result;
    }

    public List<Map<String, Object>> versions(String code) {
        main(code, false);
        return db.query(
                "select * from skill.skill_version where tenant_id=? and skill_code=? order by"
                        + " version_no desc",
                this::versionRow,
                tenant(),
                code);
    }

    public Map<String, Object> version(String code, int version) {
        var rows =
                db.query(
                        "select v.* from skill.skill_version v join skill.skill s on"
                            + " s.tenant_id=v.tenant_id and s.code=v.skill_code where v.tenant_id=?"
                            + " and v.skill_code=? and v.version_no=?",
                        this::versionRow,
                        tenant(),
                        code,
                        version);
        if (rows.isEmpty()) throw error(404, "SKILL_VERSION_NOT_FOUND", "技能版本不存在");
        var result = rows.getFirst();
        result.put("skillStatus", main(code, false).get("status"));
        return result;
    }

    public Map<String, Object> versionId(long id) {
        var rows =
                db.query(
                        "select * from skill.skill_version where tenant_id=? and id=?",
                        this::versionRow,
                        tenant(),
                        id);
        if (rows.isEmpty()) throw error(404, "SKILL_VERSION_NOT_FOUND", "技能版本不存在");
        var result = rows.getFirst();
        result.put("skillStatus", main(result.get("code").toString(), false).get("status"));
        return result;
    }

    /** Only the tenant's saved draft chooses endpoint/credential; discovery never calls tools. */
    public Map<String, Object> mcpTools(String code, int version) {
        Actor.current().requireRole("EDITOR");
        var candidate = version(code, version);
        if (!"DRAFT".equals(candidate.get("status")))
            throw error(409, "SKILL_STATE", "仅草稿可列取MCP工具");
        if (!"MCP".equals(candidate.get("type"))) throw error(400, "INVALID_SKILL", "该草稿不是MCP技能");
        if (!Objects.toString(candidate.get("authRef"), "").isBlank())
            Actor.current().requireRole("ADMIN");
        try {
            return new McpAdapter().discover(candidate);
        } catch (SkillAdapters.Failure failure) {
            int status =
                    switch (failure.semantic) {
                        case "NO_PERMISSION" -> 403;
                        case "QUOTA" -> 429;
                        case "PARAM_ERROR" -> 400;
                        default -> 502;
                    };
            throw error(status, "MCP_" + failure.semantic, failure.getMessage());
        }
    }

    public Map<String, Object> get(String code, Integer requested) {
        var main = main(code, false);
        if (requested != null) return version(code, requested);
        var versions = versions(code);
        Map<String, Object> selected =
                main.get("current_version_id") == null
                        ? (versions.isEmpty() ? new LinkedHashMap<>() : versions.getFirst())
                        : versionId(((Number) main.get("current_version_id")).longValue());
        var result = new LinkedHashMap<>(selected);
        result.put("id", main.get("id"));
        result.put("code", code);
        result.put("name", main.get("name"));
        result.put("status", main.get("status"));
        result.put(
                "currentVersion",
                main.get("current_version_id") == null ? null : selected.get("versionNo"));
        result.put("currentVersionId", main.get("current_version_id"));
        result.put("versions", versions);
        result.put("category", main.get("category"));
        result.put("tags", Json.read(main.get("tags").toString(), Object.class));
        return result;
    }

    public List<Map<String, Object>> list() {
        return db
                .queryForList(
                        "select code from skill.skill where tenant_id=? order by updated_at desc",
                        tenant())
                .stream()
                .map(r -> get(r.get("code").toString(), null))
                .toList();
    }

    public Map<String, Object> create(Map<String, Object> body) {
        String code = text(body, "code"), name = text(body, "name");
        if (!code.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || name.length() > 128)
            throw error(400, "INVALID_SKILL", "编码或名称无效");
        return tx.execute(
                status -> {
                    db.update(
                            "insert into skill.skill(tenant_id,code,name,category,tags)"
                                    + " values(?,?,?,?,?::jsonb)",
                            tenant(),
                            code,
                            name,
                            Objects.toString(body.get("category"), ""),
                            Json.write(body.getOrDefault("tags", List.of())));
                    var contract =
                            body.containsKey("type") || body.containsKey("invocation")
                                    ? normalize(body)
                                    : Map.<String, Object>of();
                    db.update(
                            "insert into"
                                + " skill.skill_version(tenant_id,skill_code,version_no,contract,sha256)"
                                + " values(?,?,1,?::jsonb,?)",
                            tenant(),
                            code,
                            Json.write(contract),
                            Digest.sha256(Json.canonical(contract)));
                    return get(code, null);
                });
    }

    public Map<String, Object> save(String code, Map<String, Object> body) {
        return tx.execute(
                status -> {
                    main(code, true);
                    var all = versions(code);
                    int version = ((Number) all.getFirst().get("versionNo")).intValue();
                    var effective = new LinkedHashMap<String, Object>(body);
                    var previous = object(all.getFirst().get("definition"));
                    // 旧编辑器省略新增字段不等于删除来源/执行映射；在行锁内补回，避免并发草稿覆盖。
                    if (previous.containsKey("importSource")) {
                        if (effective.containsKey("importSource")
                                && !Json.tree(previous.get("importSource"))
                                        .equals(Json.tree(effective.get("importSource"))))
                            throw error(400, "IMPORT_SOURCE_IMMUTABLE", "导入来源不能通过编辑替换；请重新导入为独立草稿");
                        effective.putIfAbsent("importSource", previous.get("importSource"));
                    }
                    if (previous.containsKey("httpMapping")
                            && !effective.containsKey("httpMapping"))
                        effective.put("httpMapping", previous.get("httpMapping"));
                    // authScheme 同属“旧编辑器省略不等于删除”的字段：局部保存若把 API_KEY 静默降级成
                    // 默认 BEARER，出站凭据形态会在一次无关编辑后改变且不留痕迹；显式声明过的契约
                    // 以旧值为准，本次提交若带新值仍以新值为准（putIfAbsent 语义）。
                    if (previous.containsKey(AuthScheme.FIELD)
                            && !effective.containsKey(AuthScheme.FIELD))
                        effective.put(AuthScheme.FIELD, previous.get(AuthScheme.FIELD));
                    var contract = normalize(effective);
                    if (all.getFirst().get("status").equals("DRAFT")) {
                        db.update(
                                "update skill.skill_version set contract=?::jsonb,sha256=? where"
                                        + " tenant_id=? and skill_code=? and version_no=? and"
                                        + " status='DRAFT'",
                                Json.write(contract),
                                Digest.sha256(Json.canonical(contract)),
                                tenant(),
                                code,
                                version);
                    } else {
                        version++;
                        db.update(
                                "insert into"
                                    + " skill.skill_version(tenant_id,skill_code,version_no,contract,sha256)"
                                    + " values(?,?,?,?::jsonb,?)",
                                tenant(),
                                code,
                                version,
                                Json.write(contract),
                                Digest.sha256(Json.canonical(contract)));
                    }
                    db.update(
                            "update skill.skill set updated_at=now() where tenant_id=? and code=?",
                            tenant(),
                            code);
                    return version(code, version);
                });
    }

    public Map<String, Object> normalize(Map<String, Object> body) {
        // 编辑既有SYNC技能时，清空httpMapping即表示改为无映射的异步提交；空映射按缺省处理，
        // 否则OpenAPI映射校验会把“已清空”误判为形状不一致，导入来源的技能就无法收敛为SUBMIT_QUERY。
        if (body.containsKey("httpMapping") && object(body.get("httpMapping")).isEmpty()) {
            body = new LinkedHashMap<>(body);
            body.remove("httpMapping");
        }
        var invocation = object(body.getOrDefault("invocation", Map.of()));
        String type =
                Objects.toString(
                        body.getOrDefault("type", invocation.getOrDefault("adapterType", "")), "");
        if (!Set.of("HTTP", "MCP", "ALGORITHM", "PROCESS").contains(type))
            throw error(400, "INVALID_SKILL", "适配器类型无效");
        var result = new LinkedHashMap<String, Object>();
        result.put("type", type);
        result.put("description", text(body, "description"));
        boolean process = type.equals("PROCESS");
        String endpoint =
                Objects.toString(
                        body.getOrDefault("endpoint", invocation.getOrDefault("endpoint", "")), "");
        // PROCESS在本机执行、不出站：endpoint是HTTP寻址，httpMapping是OpenAPI参数映射，二者与process
        // 子契约互斥。这里点名冲突字段而不是笼统报“契约无效”，编辑器才能把错误定位到具体输入项。
        if (process) {
            if (!endpoint.isBlank())
                throw error(
                        400,
                        "INVALID_SKILL",
                        "PROCESS 技能不能携带 endpoint：本地进程不发起 HTTP 请求，请移除端点");
            if (body.containsKey("httpMapping"))
                throw error(
                        400,
                        "INVALID_SKILL",
                        "PROCESS 技能不能携带 httpMapping：本地进程不走 OpenAPI 参数映射");
        }
        if (body.containsKey("httpMapping")) {
            var mapped = new LinkedHashMap<String, Object>(body);
            mapped.put("type", type);
            mapped.put("endpoint", endpoint);
            mapped.put(
                    "httpMethod",
                    body.getOrDefault("httpMethod", invocation.getOrDefault("httpMethod", "POST")));
            OpenApiHttpMapping.validate(mapped);
        } else if (!type.equals("ALGORITHM") && !process) SkillRules.endpoint(endpoint);
        result.put("endpoint", endpoint);
        String auth =
                Objects.toString(
                        body.getOrDefault("authRef", invocation.getOrDefault("authRef", "")), "");
        if (!auth.isBlank()
                && !auth.startsWith("vault:")
                && !auth.matches("env:AGENT_SECRET_[A-Z0-9_]+"))
            throw error(400, "INVALID_SECRET_REF", "认证只能使用已配置secret_ref");
        if (!auth.isBlank()) Actor.current().requireRole("ADMIN");
        result.put("authRef", auth);
        // authScheme 放在 authRef 校验之后归一化：组合规则（NONE 不得带凭据、API_KEY 必须带凭据）
        // 只有在 authRef 已是合法 secret_ref 之后才有意义，否则两类错误会互相掩盖。
        // 归一化结果一律显式落库，执行侧不再回读 legacy 开关，避免同一契约因部署配置不同而被解释成
        // 不同鉴权方式。缺省补 BEARER 的兼容窗口由 SkillRules/AuthScheme 统一裁决。
        AuthScheme scheme =
                SkillRules.authScheme(
                        body.getOrDefault(
                                AuthScheme.FIELD, invocation.getOrDefault(AuthScheme.FIELD, "")),
                        auth,
                        type,
                        legacyBearer);
        result.put(AuthScheme.FIELD, scheme.name());
        result.put(
                "httpMethod",
                body.getOrDefault("httpMethod", invocation.getOrDefault("httpMethod", "POST")));
        String kind =
                Objects.toString(
                        invocation.getOrDefault(
                                "kind",
                                body.getOrDefault(
                                        "invocationKind",
                                        type.equals("ALGORITHM") ? "SUBMIT_QUERY" : "SYNC")));
        if (process && !kind.equals("SYNC"))
            throw error(
                    400,
                    "UNSUPPORTED_INVOCATION_KIND",
                    "PROCESS 仅支持 SYNC 调用形态（本地进程没有异步提交或回调通道），收到: " + kind);
        if (!(type.equals("HTTP") && Set.of("SYNC", "SUBMIT_QUERY", "CALLBACK").contains(kind)
                || type.equals("MCP") && kind.equals("SYNC")
                || type.equals("ALGORITHM") && kind.equals("SUBMIT_QUERY")
                || process && kind.equals("SYNC")))
            throw error(400, "UNSUPPORTED_INVOCATION_KIND", "该适配器与调用形态组合尚未支持");
        // PROCESS子契约只由ProcessContract.parse解释（绝对路径可执行文件、参数个数、超时、stdin/env、
        // 输出上限），目录层负责把规范化结果写回持久化契约：缺省值在注册期落库，执行期不再猜默认值，
        // 草稿读回的取值就是执行时的取值（与HTTP契约用OpenApiHttpMapping归一化同一做法）。
        Map<String, Object> processContract = null;
        if (process) {
            processContract = normalizedProcess(body);
            result.put("process", processContract);
        }
        if (type.equals("HTTP") && kind.equals("CALLBACK")) {
            if (!"POST".equals(result.get("httpMethod")) || body.containsKey("httpMapping"))
                throw error(400, "INVALID_ASYNC_PROTOCOL", "回调提交仅支持POST JSON参数");
            var callback =
                    object(
                            body.getOrDefault(
                                    "callback", invocation.getOrDefault("callback", Map.of())));
            String reference = text(callback, "secretRef");
            Actor.current().requireRole("ADMIN");
            // Resolve now to prove tenant ownership; only the opaque reference is frozen.
            SecretVault.resolve(reference);
            result.put("callback", Map.of("secretRef", reference));
            result.put(
                    "jobIdPath",
                    invocation.getOrDefault(
                            "jobIdPath", body.getOrDefault("jobIdPath", "$.jobId")));
            cn.agentplatform.skill.infra.HttpAsyncAdapter.path(
                    Json.tree(Map.of()), result.get("jobIdPath").toString());
        }
        if (type.equals("HTTP")
                && (kind.equals("SUBMIT_QUERY")
                        || kind.equals("CALLBACK")
                                && !object(body.getOrDefault("reconciliation", Map.of()))
                                        .isEmpty())) {
            if (!"POST".equals(result.get("httpMethod")) || body.containsKey("httpMapping"))
                throw error(
                        400, "INVALID_ASYNC_PROTOCOL", "异步HTTP提交当前仅支持POST JSON参数，不能混用OpenAPI映射");
            var policy = object(body.getOrDefault("reconciliation", Map.of()));
            Object echo = policy.get("idempotencyKeyEcho");
            if (echo != null && !(echo instanceof Boolean))
                throw error(400, "INVALID_ASYNC_PROTOCOL", "idempotencyKeyEcho必须为布尔值");
            String query = text(policy, "endpoint");
            SkillRules.endpoint(query.replace("{jobId}", "job").replace("{idempotencyKey}", "key"));
            if (!Objects.toString(policy.getOrDefault("method", "GET")).equals("GET")
                    || !query.contains("{jobId}") && !query.contains("{idempotencyKey}")
                    || !policy.containsKey("doneValue"))
                throw error(400, "INVALID_ASYNC_PROTOCOL", "异步查询需要GET、原jobId或幂等键占位符及完成标记");
            cn.agentplatform.skill.infra.HttpAsyncAdapter.path(
                    Json.tree(Map.of()), text(policy, "donePath"));
            result.put(
                    "jobIdPath",
                    invocation.getOrDefault(
                            "jobIdPath", body.getOrDefault("jobIdPath", "$.jobId")));
            for (String path :
                    List.of(
                            result.get("jobIdPath").toString(),
                            Objects.toString(
                                    policy.getOrDefault("idempotencyKeyPath", "$.idempotencyKey")),
                            Objects.toString(policy.getOrDefault("resultPath", "$.result"))))
                cn.agentplatform.skill.infra.HttpAsyncAdapter.path(Json.tree(Map.of()), path);
            if (policy.containsKey("lookupEndpoint")) {
                String lookup = text(policy, "lookupEndpoint");
                if (!lookup.contains("{idempotencyKey}") || lookup.contains("{jobId}"))
                    throw error(400, "INVALID_ASYNC_PROTOCOL", "丢失提交响应后的查询必须仅使用原幂等键");
                SkillRules.endpoint(lookup.replace("{idempotencyKey}", "key"));
            }
            if (policy.containsKey("failedValue") || policy.containsKey("failedValues")) {
                Object raw =
                        policy.containsKey("failedValues")
                                ? policy.get("failedValues")
                                : policy.get("failedValue");
                var failed = Json.tree(raw);
                if (!(failed.isArray() || failed.isValueNode()))
                    throw error(400, "INVALID_ASYNC_PROTOCOL", "失败终态必须为标量或标量数组");
                if (failed.isArray() && failed.isEmpty())
                    throw error(400, "INVALID_ASYNC_PROTOCOL", "失败终态数组不能为空");
                var values = failed.isArray() ? failed : Json.mapper().createArrayNode().add(failed);
                for (var value : values)
                    if (!value.isValueNode())
                        throw error(400, "INVALID_ASYNC_PROTOCOL", "失败终态元素必须为标量");
                if (Json.tree(policy.get("doneValue")).equals(failed))
                    throw error(400, "INVALID_ASYNC_PROTOCOL", "失败终态不能与完成终态相同");
            }
            if (policy.containsKey("failureMessagePath"))
                cn.agentplatform.skill.infra.HttpAsyncAdapter.path(
                        Json.tree(Map.of()), text(policy, "failureMessagePath"));
            if (policy.containsKey("resultEndpoint")) {
                String resultEndpoint = text(policy, "resultEndpoint");
                if (!resultEndpoint.contains("{jobId}")
                        && !resultEndpoint.contains("{idempotencyKey}"))
                    throw error(400, "INVALID_ASYNC_PROTOCOL", "结果端点需要原jobId或幂等键占位符");
                SkillRules.endpoint(
                        resultEndpoint.replace("{jobId}", "job").replace("{idempotencyKey}", "key"));
            }
            if (policy.containsKey("resultPath"))
                cn.agentplatform.skill.infra.HttpAsyncAdapter.path(
                        Json.tree(Map.of()), text(policy, "resultPath"));
            if (policy.containsKey("resultEndpointPath"))
                cn.agentplatform.skill.infra.HttpAsyncAdapter.path(
                        Json.tree(Map.of()), text(policy, "resultEndpointPath"));
        }
        result.put("invocationKind", kind);
        long timeout;
        if (process) {
            // PROCESS只有一个真实超时：process.timeoutMs（缺省60s、上限30min）。顶层timeoutMs是所有
            // 适配器共用的形状字段，这里派生而不是再独立声明——否则编辑页显示30s、执行器按60s跑，
            // 同一契约里两个互相矛盾的数。通用100..600000是HTTP调用时长的上界，不适用于本地进程。
            timeout = ((Number) processContract.get("timeoutMs")).longValue();
        } else {
            timeout =
                    ((Number)
                                    body.getOrDefault(
                                            "timeoutMs", invocation.getOrDefault("timeoutMs", 30000)))
                            .longValue();
            if (timeout < 100 || timeout > 600000)
                throw error(400, "INVALID_SKILL", "timeoutMs必须为100至600000");
        }
        result.put("timeoutMs", timeout);
        var input = object(body.get("inputSchema"));
        var output = object(body.getOrDefault("outputSchema", Map.of("type", "object")));
        if (type.equals("MCP")) SkillRules.mcpSchema(input);
        else SkillRules.schema(input);
        SkillRules.schema(output);
        result.put("inputSchema", input);
        result.put("outputSchema", output);
        var execution = object(body.get("execution"));
        for (String flag : List.of("readonly", "sideEffect", "idempotent", "cancellable"))
            if (!(execution.get(flag) instanceof Boolean))
                throw error(400, "INVALID_SKILL", "execution需显式布尔字段: " + flag);
        if (Boolean.TRUE.equals(execution.get("readonly"))
                && Boolean.TRUE.equals(execution.get("sideEffect")))
            throw error(400, "INVALID_SKILL", "只读与副作用互斥");
        String risk =
                Boolean.TRUE.equals(execution.get("readonly"))
                                && !"CONFIRM_REQUIRED".equals(body.get("riskLevel"))
                        ? "READ_ONLY"
                        : "CONFIRM_REQUIRED";
        result.put("riskLevel", risk);
        result.put("execution", execution);
        var guard =
                new LinkedHashMap<String, Object>(
                        object(body.getOrDefault("guard", body.getOrDefault("access", Map.of()))));
        var attributes =
                new LinkedHashMap<String, Object>(
                        object(guard.getOrDefault("attributes", Map.of())));
        if (guard.get("guardAttrs") instanceof Collection<?> names)
            for (Object name : names) attributes.putIfAbsent(name.toString(), List.of());
        guard.remove("guardAttrs");
        guard.put("attributes", attributes);
        guard.putIfAbsent("roles", List.of());
        if (!(guard.get("roles") instanceof Collection<?> roles)
                || roles.stream().anyMatch(role -> !(role instanceof String r) || r.isBlank()))
            throw error(400, "INVALID_GUARD", "守卫角色必须为明确字符串数组");
        for (var entry : attributes.entrySet())
            if (!(entry.getValue() instanceof Collection<?> values)
                    || values.stream().anyMatch(value -> !(value instanceof String)))
                throw error(400, "INVALID_GUARD", "守卫属性值必须为字符串数组");
        result.put("guard", guard);
        result.put("errorSemantics", body.getOrDefault("errorSemantics", List.of()));
        result.put("returnSpec", body.getOrDefault("returnSpec", Map.of()));
        var constraints = object(body.getOrDefault("constraints", Map.of()));
        int max = ((Number) constraints.getOrDefault("outputMaxBytes", 1048576)).intValue();
        if (max < 1024 || max > 10485760)
            throw error(400, "INVALID_SKILL", "outputMaxBytes范围为1024至10485760");
        result.put("outputMaxBytes", max);
        result.put("reconciliation", body.getOrDefault("reconciliation", Map.of()));
        if (type.equals("MCP")) {
            Object raw = body.getOrDefault("toolName", invocation.get("toolName"));
            if (!(raw instanceof String name) || !name.matches("[A-Za-z0-9_.-]{1,128}"))
                throw error(400, "INVALID_SKILL", "MCP需要明确的toolName（1至128字符）");
            result.put("toolName", name);
        }
        if (body.containsKey("jobTemplate")) result.put("jobTemplate", body.get("jobTemplate"));
        // 来源与映射属于被摘要的不可变契约；草稿修改不丢来源，也不能携带额外执行字段。
        if (body.containsKey("httpMapping")) result.put("httpMapping", body.get("httpMapping"));
        if (body.containsKey("importSource")) {
            var source = object(body.get("importSource"));
            if (!source.keySet()
                            .equals(
                                    Set.of(
                                            "kind",
                                            "operationId",
                                            "method",
                                            "path",
                                            "documentSha256"))
                    || !"OPENAPI".equals(source.get("kind"))
                    || !Objects.toString(source.get("documentSha256"), "").matches("[a-f0-9]{64}"))
                throw error(400, "INVALID_IMPORT_SOURCE", "导入来源结构无效");
            for (String field : List.of("operationId", "method", "path")) text(source, field);
            result.put("importSource", source);
        }
        return result;
    }

    /**
     * PROCESS 子契约的唯一解释器是 {@link ProcessContract#parse}。目录层不复写它的边界（命令首元素绝对路径、
     * 参数个数上限、超时范围、stdin/env/输出上限），只把规范化结果整理成可持久化的 JSON 形状：缺省值在注册期
     * 落库而不是执行期再补，可选字段未声明时不写空值，避免草稿里出现一串无意义的 null。
     */
    private static Map<String, Object> normalizedProcess(Map<String, Object> body) {
        ProcessContract contract = ProcessContract.parse(Json.tree(body.get("process")));
        var normalized = new LinkedHashMap<String, Object>();
        normalized.put("command", contract.command());
        normalized.put("timeoutMs", contract.timeout().toMillis());
        if (contract.workDirRoot() != null) normalized.put("workDirRoot", contract.workDirRoot());
        if (contract.stdinJson() != null)
            normalized.put("stdin", Json.mapper().convertValue(contract.stdinJson(), Object.class));
        if (!contract.allowedEnv().isEmpty()) normalized.put("env", contract.allowedEnv());
        normalized.put("maxOutputBytes", contract.maxOutputBytes());
        return normalized;
    }

    public Map<String, Object> publish(String code, int version) {
        return tx.execute(
                status -> {
                    main(code, true);
                    var candidate = version(code, version);
                    long id = ((Number) candidate.get("skillVersionId")).longValue();
                    if (candidate.get("status").equals("PUBLISHED")) return candidate;
                    if (!candidate.get("status").equals("DRAFT"))
                        throw error(409, "SKILL_STATE", "只有草稿可发布");
                    // PROCESS 的自检放在发布口：command 指向不存在文件、workDirRoot 不可达是配置错误，
                    // 放它进 PUBLISHED 只会把失败推迟到第一次真实调用（而且那次可能已经产生外部影响）。
                    processPrecheck(candidate);
                    var execution = object(candidate.get("execution"));
                    var guard = object(candidate.get("guard"));
                    if ("CONFIRM_REQUIRED".equals(candidate.get("riskLevel"))
                            && (!(execution.get("confirmRoles") instanceof Collection<?> roles)
                                    || roles.isEmpty()
                                    || roles.stream()
                                            .anyMatch(
                                                    r ->
                                                            !(r instanceof String role)
                                                                    || role.isBlank())))
                        throw error(409, "CONFIRM_ROLES_REQUIRED", "需确认的技能必须声明明确confirmRoles");
                    if (Boolean.TRUE.equals(execution.get("readonly"))
                            && object(guard.getOrDefault("attributes", Map.of())).isEmpty())
                        throw error(409, "QUERY_GUARD_REQUIRED", "查询技能必须声明守卫属性");
                    if (db.queryForObject(
                                    "select count(*) from skill.connection_test where tenant_id=?"
                                            + " and skill_version_id=? and candidate_sha256=? and"
                                            + " passed",
                                    Integer.class,
                                    tenant(),
                                    id,
                                    candidate.get("sha256"))
                            == 0)
                        throw error(409, "CONNECTION_TEST_REQUIRED", "当前草稿尚无通过的真实连接/受控调试报告");
                    if (db.queryForObject(
                                    "select count(*) from skill.acceptance where tenant_id=? and"
                                        + " skill_version_id=? and candidate_sha256=? and passed"
                                        + " and hit_rate>=0.8",
                                    Integer.class,
                                    tenant(),
                                    id,
                                    candidate.get("sha256"))
                            == 0) throw error(409, "ACCEPTANCE_REQUIRED", "当前草稿选型验收尚未通过");
                    db.update(
                            "update skill.skill_version set status='ARCHIVED' where tenant_id=? and"
                                    + " skill_code=? and status='PUBLISHED'",
                            tenant(),
                            code);
                    db.update(
                            "update skill.skill_version set"
                                    + " status='PUBLISHED',published_by=?,published_at=now() where"
                                    + " tenant_id=? and id=?",
                            Actor.current().principalId(),
                            tenant(),
                            id);
                    db.update(
                            "update skill.skill set"
                                + " status='PUBLISHED',current_version_id=?,updated_at=now() where"
                                + " tenant_id=? and code=?",
                            id,
                            tenant(),
                            code);
                    return version(code, version);
                });
    }

    /**
     * PLAT-21c 发布预检（方案 §4.4–§4.6）。只检查“静态可判定”的事项：command[0] 是绝对路径且
     * 指向存在的普通文件、workDirRoot 是绝对路径且可创建、timeoutMs 落在 1..1800000（由
     * {@link ProcessContract#parse} 强制）、outputSchema 能被 {@link SkillRules#schema} 加载。
     *
     * <p>这里不做 true 值校验（跑一次进程），因为发布动作不应产生外部副作用；真实可用性由连接/受控
     * 调试报告与选型验收负责。失败统一 400：调用方需要改契约，重试发布没有意义。
     */
    void processPrecheck(Map<String, Object> candidate) {
        if (!"PROCESS".equals(candidate.get("type"))) return;
        var raw = Json.tree(candidate.get("process"));
        // 先按原始 JSON 检查路径字段，再交给 ProcessContract.parse：parse 的 400 语义是
        // “契约字段非法”，而发布口需要明确区分“契约语法没问题但指向的文件不可用”。
        precheckCommand(raw.get("command"));
        var workDirRoot = raw.get("workDirRoot");
        if (workDirRoot != null && workDirRoot.isTextual())
            precheckWorkDirRoot(workDirRoot.asText());
        ProcessContract contract = ProcessContract.parse(raw);
        if (contract.hasOutputSchema()) {
            try {
                SkillRules.schema(
                        Json.mapper().convertValue(contract.outputSchema(), Object.class));
            } catch (IllegalArgumentException ex) {
                throw precheckError("outputSchema 无效: " + ex.getMessage());
            }
        }
    }

    private void precheckCommand(JsonNode command) {
        if (command == null || !command.isArray() || command.isEmpty()) return;
        JsonNode first = command.get(0);
        if (!first.isTextual()) return;
        Path executable;
        try {
            executable = Path.of(first.asText());
        } catch (InvalidPathException ex) {
            throw precheckError("command[0] 不是合法的路径: " + first.asText());
        }
        if (!executable.isAbsolute())
            throw precheckError("command[0] 必须是绝对路径，不依赖 PATH 解析: " + executable);
        if (!Files.exists(executable))
            throw precheckError("command[0] 指向的文件不存在: " + executable);
        if (!Files.isRegularFile(executable))
            throw precheckError("command[0] 必须是普通文件（可执行文件或脚本）: " + executable);
    }

    private void precheckWorkDirRoot(String workDirRoot) {
        if (workDirRoot == null) return;
        Path dir;
        try {
            dir = Path.of(workDirRoot);
        } catch (InvalidPathException ex) {
            throw precheckError("workDirRoot 不是合法的路径: " + workDirRoot);
        }
        if (!dir.isAbsolute()) throw precheckError("workDirRoot 必须是绝对路径: " + dir);
        if (Files.exists(dir)) {
            if (!Files.isDirectory(dir)) throw precheckError("workDirRoot 存在但不是目录: " + dir);
            return;
        }
        // 不在这里真的建目录：发布事务回滚会留下空目录；只验证最近的已存在祖先可写即可。
        Path ancestor = dir.getParent();
        while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.getParent();
        if (ancestor == null || !Files.isDirectory(ancestor) || !Files.isWritable(ancestor))
            throw precheckError("workDirRoot 及其祖先都不可创建: " + dir);
        try {
            Path probe = Files.createTempDirectory(ancestor, ".precheck-");
            Files.deleteIfExists(probe);
        } catch (IOException ex) {
            throw precheckError("workDirRoot 不可创建: " + dir + " (" + ex.getMessage() + ")");
        }
    }

    private static PlatformException precheckError(String message) {
        return error(400, "PROCESS_PRECHECK_FAILED", message);
    }

    public void delete(String code) {
        tx.executeWithoutResult(
                status -> {
                    var entity = main(code, true);
                    if (!entity.get("status").equals("DRAFT")
                            || versions(code).stream()
                                    .anyMatch(v -> !v.get("status").equals("DRAFT")))
                        throw error(409, "SKILL_STATE", "只能删除从未发布的技能草稿");
                    db.update(
                            "delete from skill.connection_test t using skill.skill_version v where"
                                    + " t.tenant_id=? and v.tenant_id=t.tenant_id and"
                                    + " v.id=t.skill_version_id and v.skill_code=?",
                            tenant(),
                            code);
                    db.update(
                            "delete from skill.acceptance t using skill.skill_version v where"
                                    + " t.tenant_id=? and v.tenant_id=t.tenant_id and"
                                    + " v.id=t.skill_version_id and v.skill_code=?",
                            tenant(),
                            code);
                    db.update(
                            "delete from skill.skill_version where tenant_id=? and skill_code=? and"
                                    + " status='DRAFT'",
                            tenant(),
                            code);
                    db.update(
                            "delete from skill.skill where tenant_id=? and code=?", tenant(), code);
                });
    }

    public Object references(String code) {
        main(code, false);
        return clients.get(appUrl + "/internal/skills/" + code + "/references");
    }

    public Map<String, Object> disable(String code) {
        if (!((com.fasterxml.jackson.databind.JsonNode) references(code)).isEmpty())
            throw error(409, "SKILL_REFERENCED", "技能存在应用版本引用，不能停用");
        db.update(
                "update skill.skill set status='DISABLED',updated_at=now() where tenant_id=? and"
                        + " code=?",
                tenant(),
                code);
        return get(code, null);
    }
}
