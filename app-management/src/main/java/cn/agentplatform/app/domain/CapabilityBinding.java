package cn.agentplatform.app.domain;

import cn.agentplatform.app.application.AppService;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import java.util.*;

/** 声明与已发布契约的比较规则；注册资格只由技能服务实际注册表决定。 */
public record CapabilityBinding(String route, Object environmentDeclaration,
        Map<String, Object> identity, Map<String, Object> executionInterface,
        List<Map<String, Object>> tools, Map<String, Map<String, Object>> supplied) {

    public static Map<String, Object> plan(Map<String, Object> body) {
        if (!Set.of("environment", "routeProfileId", "bindings").containsAll(body.keySet()))
            throw bad("解析计划仅接受 environment/routeProfileId/bindings");
        var result = new LinkedHashMap<String, Object>();
        result.put("environment", AppService.text(body, "environment"));
        result.put("routeProfileId", AppService.text(body, "routeProfileId"));
        result.put("bindings", AppService.objects(body.get("bindings")));
        return result;
    }

    public static CapabilityBinding select(Map<String, Object> pkg, Map<String, Object> plan) {
        var environments = AppService.object(AppService.object(pkg.get("software")).get("environments"));
        String environment = plan.get("environment").toString(), routeId = plan.get("routeProfileId").toString();
        if (!environments.containsKey(environment)) throw bad("environment 未在本包声明");
        var profiles = AppService.objects(pkg.get("routeProfiles"));
        var interfaces = AppService.objects(pkg.get("interfaces"));
        Map<String, Object> profile = profiles.isEmpty()
                ? Map.of("id", routeId, "execution", routeId)
                : profiles.stream().filter(p -> routeId.equals(p.get("id"))).findFirst()
                        .orElseThrow(() -> bad("routeProfileId 未在本包声明"));
        var endpoint = interfaces.stream().filter(p -> profile.get("execution").equals(p.get("id"))).findFirst()
                .orElseThrow(() -> bad("路线执行入口未声明"));
        var ontology = AppService.object(pkg.getOrDefault("ontology", Map.of()));
        Object ontologyRef = profile.getOrDefault("ontology", ontology.isEmpty() || "NONE".equals(ontology.get("mode"))
                ? "none" : ontology.get("space") + "@" + ontology.get("release"));
        if (!"none".equals(ontologyRef)) throw unresolved("本体绑定须在应用阶段完成精确发布核验");
        var identity = AppService.object(pkg.get("identity"));
        if (!AppService.objects(identity.get("secrets")).isEmpty() || endpoint.containsKey("authRef"))
            throw new PlatformException(409, "UNRESOLVED_SECRET_BINDING", "声明中的密钥引用尚无受控绑定");
        if (identity.get("scopes") instanceof Collection<?> scopes && !scopes.isEmpty())
            throw unresolved("声明 scopes 尚无受控映射");
        List<Map<String, Object>> selected = new ArrayList<>();
        var all = AppService.objects(pkg.get("tools"));
        if (profile.containsKey("tools")) {
            for (Object toolId : (List<?>) profile.get("tools"))
                selected.add(all.stream().filter(t -> toolId.equals(t.get("id"))).findFirst()
                        .orElseThrow(() -> bad("路线工具未声明")));
        } else {
            for (var tool : all)
                if (!Boolean.FALSE.equals(tool.get("enabled")) && endpoint.get("id").equals(tool.get("binding"))) selected.add(tool);
        }
        if (selected.isEmpty()) throw bad("路线没有启用工具");
        var ids = new HashSet<String>();
        for (var tool : selected) {
            ids.add(tool.get("id").toString());
            if (Boolean.FALSE.equals(tool.get("enabled")) || !endpoint.get("id").equals(tool.get("binding")))
                throw new PlatformException(409, "CAPABILITY_ROUTE_BINDING_CONFLICT", "选中路线工具必须启用并使用同一执行入口");
        }
        var supplied = new LinkedHashMap<String, Map<String, Object>>();
        for (var binding : AppService.objects(plan.get("bindings"))) {
            if (!binding.keySet().equals(Set.of("toolId", "skillRef"))) throw bad("binding 仅接受 toolId/skillRef");
            String id = AppService.text(binding, "toolId");
            if (!ids.contains(id) || supplied.putIfAbsent(id, referenceShape(binding.get("skillRef"))) != null)
                throw bad("额外或重复的工具 binding");
        }
        return new CapabilityBinding(routeId, environments.get(environment), identity, endpoint, List.copyOf(selected), supplied);
    }

    private static Map<String, Object> referenceShape(Object raw) {
        var ref = AppService.object(raw);
        if (!ref.keySet().containsAll(Set.of("code", "version"))
                || !Set.of("code", "version", "sha256").containsAll(ref.keySet())) throw bad("skillRef 需要精确 code/version");
        if (ref.containsKey("sha256") && (!(ref.get("sha256") instanceof String sha) || !sha.matches("[0-9a-f]{64}")))
            throw bad("skillRef.sha256 必须是小写 SHA-256");
        return ref;
    }

    public Map<String, Object> reference(Map<String, Object> tool) {
        var explicit = supplied.get(tool.get("id"));
        var declared = tool.containsKey("skillRef") ? referenceShape(tool.get("skillRef")) : null;
        if (explicit == null && declared == null)
            throw new PlatformException(409, "CAPABILITY_BINDING_REQUIRED", "工具缺少精确已发布技能引用");
        if (explicit == null) return declared;
        if (declared == null) return explicit;
        for (String key : List.of("code", "version", "sha256"))
            if (declared.containsKey(key) && explicit.containsKey(key)
                    && !Json.tree(declared.get(key)).equals(Json.tree(explicit.get(key)))) throw conflict("skillRef." + key);
        var merged = new LinkedHashMap<>(declared); merged.putAll(explicit); return merged;
    }

    public Map<String, Object> mapping(Map<String, Object> tool, Map<String, Object> ref, Map<String, Object> actual) {
        if (ref.containsKey("sha256") && !ref.get("sha256").equals(actual.get("sha256")))
            throw new PlatformException(409, "CAPABILITY_BINDING_DIGEST_MISMATCH", "声明技能摘要与精确发布版本不同");
        var definition = AppService.object(actual.get("definition"));
        String kind = executionInterface.get("kind").toString();
        String type = switch (kind) { case "HTTP_SYNC", "HTTP_ASYNC" -> "HTTP"; default -> kind; };
        equal(type, definition.get("type"), "interface.kind");
        if (Set.of("HTTP_SYNC", "MCP", "PROCESS").contains(kind)) equal("SYNC", definition.get("invocationKind"), "invocationKind");
        if (kind.equals("HTTP_ASYNC") && !Set.of("SUBMIT_QUERY", "CALLBACK").contains(definition.get("invocationKind"))) throw conflict("invocationKind");
        if (kind.startsWith("HTTP")) {
            equal(executionInterface.get("endpoint"), definition.get("endpoint"), "endpoint");
            declared(executionInterface, "httpMethod", definition, "httpMethod");
            declared(executionInterface, "timeoutMs", definition, "timeoutMs");
            if (kind.equals("HTTP_ASYNC")) {
                var reconciliation = AppService.object(executionInterface.get("reconciliation"));
                var actualPolicy = AppService.object(definition.getOrDefault("reconciliation", Map.of()));
                reconciliation.forEach((key, value) -> equal(value, actualPolicy.get(key), "reconciliation." + key));
            }
        } else if (kind.equals("MCP")) {
            equal(executionInterface.get("serverRef"), definition.get("endpoint"), "serverRef");
            if (tool.containsKey("operation")) equal(tool.get("operation"), definition.get("toolName"), "operation");
        } else if (kind.equals("PROCESS")) {
            var process = AppService.object(definition.get("process"));
            equal(executionInterface.get("command"), process.get("command"), "command");
            equal(executionInterface.get("protocol"), actual.get("resolvedProcessProtocol"), "protocol");
            declared(executionInterface, "workDir", process, "workDirRoot");
            declared(executionInterface, "timeoutMs", process, "timeoutMs");
        } else if (kind.equals("ALGORITHM")) equal(executionInterface.get("jobTemplate"), definition.get("jobTemplate"), "jobTemplate");
        if (tool.containsKey("operation") && !kind.equals("MCP")) {
            var source = AppService.object(definition.getOrDefault("importSource", Map.of()));
            equal(tool.get("operation"), source.get("toolCode"), "operation");
        }
        var execution = AppService.object(definition.get("execution"));
        for (String field : List.of("readonly", "sideEffect", "idempotent")) declared(tool, field, execution, field);
        if ("READ_ONLY".equals(tool.get("exposure"))) {
            equal(true, execution.get("readonly"), "readonly"); equal(false, execution.get("sideEffect"), "sideEffect");
            equal("READ_ONLY", definition.get("riskLevel"), "riskLevel");
        } else {
            equal(false, execution.get("readonly"), "readonly"); equal("CONFIRM_REQUIRED", definition.get("riskLevel"), "riskLevel");
            equal(new TreeSet<>((List<String>) tool.get("confirmRoles")),
                    new TreeSet<>((List<String>) execution.getOrDefault("confirmRoles", List.of())), "confirmRoles");
        }
        var approval = AppService.object(execution.getOrDefault("approval", Map.of()));
        equal(tool.getOrDefault("riskLevel", "STANDARD"), approval.getOrDefault("risk", "STANDARD"), "approval.risk");
        for (String field : List.of("inputSchema", "outputSchema", "errorSemantics"))
            if (tool.get(field) instanceof Map<?, ?> object && !object.isEmpty()) equal(object, definition.get(field), field);
        // 数组规则的先后次序也是声明的一部分；历史空声明仍不增加绑定条件。
        if (tool.get("errorSemantics") instanceof Collection<?> rules && !rules.isEmpty())
            equal(rules, definition.get("errorSemantics"), "errorSemantics");
        if (tool.containsKey("pagination")) {
            var pagination = AppService.object(definition.getOrDefault("pagination", Map.of()));
            String mode = tool.get("pagination").toString().toUpperCase(Locale.ROOT);
            equal(mode, pagination.getOrDefault("mode", "NONE"), "pagination");
        }
        if (tool.get("artifacts") instanceof Collection<?> artifacts && !artifacts.isEmpty()) throw unresolved("产物声明尚无精确运行绑定");
        declared(identity, "outboundIdentity", definition, "identity");
        if (identity.get("guard") instanceof Map<?, ?> guard && !guard.isEmpty()) equal(guard, definition.get("guard"), "identity.guard");
        var result = new LinkedHashMap<String, Object>();
        result.put("routeProfileId", route); result.put("toolId", tool.get("id"));
        result.put("interfaceId", executionInterface.get("id")); result.put("skillCode", actual.get("code"));
        result.put("skillVersionId", actual.get("skillVersionId")); result.put("version", actual.get("version"));
        result.put("sha256", actual.get("sha256"));
        return result;
    }

    private static void declared(Map<String, Object> from, String key, Map<String, Object> to, String target) {
        if (from.containsKey(key)) equal(from.get(key), to.get(target), key);
    }
    private static void equal(Object declared, Object actual, String field) {
        if (!Json.tree(declared).equals(Json.tree(actual))) throw conflict(field);
    }
    private static PlatformException conflict(String field) {
        return new PlatformException(409, "CAPABILITY_BINDING_CONFLICT", "声明与发布技能不一致: " + field);
    }
    private static PlatformException unresolved(String message) { return new PlatformException(409, "CAPABILITY_BINDING_REQUIRED", message); }
    private static PlatformException bad(String message) { return new PlatformException(400, "INVALID_INPUT", message); }
}
