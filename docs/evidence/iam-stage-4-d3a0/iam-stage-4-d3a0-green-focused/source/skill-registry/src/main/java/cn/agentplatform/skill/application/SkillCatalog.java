package cn.agentplatform.skill.application;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.domain.SkillRules;
import cn.agentplatform.skill.infra.McpAdapter;
import cn.agentplatform.skill.infra.OpenApiHttpMapping;
import cn.agentplatform.skill.infra.SkillAdapters;
import cn.agentplatform.skill.infra.SkillClients;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

    public SkillCatalog(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            SkillClients clients,
            @Value("${agent.services.app:http://127.0.0.1:18081}") String appUrl) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.clients = clients;
        this.appUrl = appUrl;
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
        var invocation = object(body.getOrDefault("invocation", Map.of()));
        String type =
                Objects.toString(
                        body.getOrDefault("type", invocation.getOrDefault("adapterType", "")), "");
        if (!Set.of("HTTP", "MCP", "ALGORITHM").contains(type))
            throw error(400, "INVALID_SKILL", "适配器类型无效");
        var result = new LinkedHashMap<String, Object>();
        result.put("type", type);
        result.put("description", text(body, "description"));
        String endpoint =
                Objects.toString(
                        body.getOrDefault("endpoint", invocation.getOrDefault("endpoint", "")), "");
        if (body.containsKey("httpMapping")) {
            var mapped = new LinkedHashMap<String, Object>(body);
            mapped.put("type", type);
            mapped.put("endpoint", endpoint);
            mapped.put(
                    "httpMethod",
                    body.getOrDefault("httpMethod", invocation.getOrDefault("httpMethod", "POST")));
            OpenApiHttpMapping.validate(mapped);
        } else if (!type.equals("ALGORITHM")) SkillRules.endpoint(endpoint);
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
        if (!(type.equals("HTTP") && Set.of("SYNC", "SUBMIT_QUERY", "CALLBACK").contains(kind)
                || type.equals("MCP") && kind.equals("SYNC")
                || type.equals("ALGORITHM") && kind.equals("SUBMIT_QUERY")))
            throw error(400, "UNSUPPORTED_INVOCATION_KIND", "该适配器与调用形态组合尚未支持");
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
        }
        result.put("invocationKind", kind);
        long timeout =
                ((Number)
                                body.getOrDefault(
                                        "timeoutMs", invocation.getOrDefault("timeoutMs", 30000)))
                        .longValue();
        if (timeout < 100 || timeout > 600000)
            throw error(400, "INVALID_SKILL", "timeoutMs必须为100至600000");
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

    public Map<String, Object> publish(String code, int version) {
        return tx.execute(
                status -> {
                    main(code, true);
                    var candidate = version(code, version);
                    long id = ((Number) candidate.get("skillVersionId")).longValue();
                    if (candidate.get("status").equals("PUBLISHED")) return candidate;
                    if (!candidate.get("status").equals("DRAFT"))
                        throw error(409, "SKILL_STATE", "只有草稿可发布");
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
