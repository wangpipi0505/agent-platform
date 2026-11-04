package cn.agentplatform.app.application;

import cn.agentplatform.app.domain.CapabilityBinding;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 会话导入的纯来源规则：只消费真实完整预检、冻结声明与已批准连接事实，不解析第二份OpenAPI。
 * 人工语义必须来自持久会话；本类既不补Schema，也不签票、取密钥或决定当前主体grant。
 */
public final class OnboardingImportRules {
    private static final Set<String> SOURCE_KEYS = Set.of("kind", "operationId", "method", "path", "documentSha256");
    private static final Set<String> REQUIRED_MANUAL = Set.of("name", "description", "execution", "guard", "returnSpec", "errorSemantics");
    private static final Set<String> MANUAL_KEYS = Set.of("name", "description", "execution", "guard", "returnSpec", "errorSemantics", "timeoutMs", "constraints", "authRef");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern RETURN_PATH = Pattern.compile("\\$(?:\\.[A-Za-z_][A-Za-z0-9_-]*|\\[[0-9]{1,6}])*");
    private static final Pattern SUMMARY_PATH = Pattern.compile("\\{\\{\\s*[A-Za-z_][A-Za-z0-9_.-]*\\s*}}");
    private OnboardingImportRules() {}

    /** 缺少人工合同的老签名明确拒绝，不能把空守卫或虚构呈现当成已经配置。 */
    public static Map<String,Object> prepare(String document, Map<String,Object> preview,
            Map<String,Object> declaration, Map<String,Object> routePlan,
            List<String> selectedOperationIds, Map<String,Object> connectionFacts) {
        return prepare(document, preview, declaration, routePlan, selectedOperationIds, connectionFacts, Map.of());
    }

    /**
     * 返回defaults/overrides可直接交原OpenApiImports；operations保留原生五字段来源和预期合同。
     * provenance单独落会话来源库，不能追加到既有OPENAPI importSource。
     */
    public static Map<String,Object> prepare(String document, Map<String,Object> preview,
            Map<String,Object> declaration, Map<String,Object> routePlan,
            List<String> selectedOperationIds, Map<String,Object> connectionFacts,
            Map<String,Object> manualOverrides) {
        if (document == null || document.isBlank() || document.getBytes(StandardCharsets.UTF_8).length > 1_048_576)
            throw invalid("document", "需要真实且不超过1MiB的规格文本");
        String documentSha = Digest.sha256(document);
        equal(documentSha, object(preview, "preview").get("documentSha256"), "preview.documentSha256");
        var route = route(routePlan);
        var selection = CapabilityBinding.select(object(declaration, "declaration"), route);
        if (!"HTTP_SYNC".equals(selection.executionInterface().get("kind"))) throw unsupported("仅支持HTTP_SYNC来源");
        if (selection.identity().get("outboundIdentity") instanceof Map<?,?> identity && !identity.isEmpty())
            throw unsupported("原OpenAPI导入尚不承载出站身份声明");
        var facts = object(connectionFacts, "connectionFacts");
        equal("APPROVED", facts.get("status"), "connection.status");
        equal("HTTP_SYNC", facts.get("protocol"), "connection.protocol");
        String resolutionDigest = sha(facts.get("resolutionDigest"), "connection.resolutionDigest");
        var bindings = rows(facts.get("bindings"), "connection.bindings", 1);
        if (bindings.size() != 1) throw invalid("connection.bindings", "必须先按精确绑定收窄成唯一批准来源");
        var binding = bindings.getFirst();
        equal(route.get("environment"), binding.get("environment"), "connection.environment");
        String baseUrl = text(binding.get("baseEndpoint"), "connection.baseEndpoint", 2048);
        URI base = endpoint(baseUrl, false);
        // 现有受控执行按origin附加路由；首版不把带根路径的绑定错误地再附加一次。
        if (!(base.getRawPath().isEmpty() || "/".equals(base.getRawPath()))) throw unsupported("首版导入要求批准绑定为固定HTTP origin");
        equal(selection.environmentDeclaration(), baseUrl, "software.environments");
        String authKind = text(facts.get("authKind"), "connection.authKind", 32);
        if (!Set.of("NONE", "BEARER").contains(authKind)) throw unsupported("原OpenAPI导入仅支持NONE/BEARER，不能伪称其他鉴权");
        String secretRef = Objects.toString(binding.get("secretRef"), "");
        if ("NONE".equals(authKind)) {
            if (!secretRef.isBlank()) throw conflict("NONE绑定不得附带凭据引用");
        } else if (!(secretRef.matches("vault:[0-9a-fA-F-]{36}") || secretRef.matches("env:AGENT_SECRET_[A-Z0-9_]+")))
            throw invalid("connection.secretRef", "需要批准的secret_ref，不接受明文凭据");

        if (selectedOperationIds == null || selectedOperationIds.isEmpty() || selectedOperationIds.size() > 32
                || new HashSet<>(selectedOperationIds).size() != selectedOperationIds.size())
            throw invalid("selectedOperationIds", "需要非空、唯一的精确操作集合");
        var actual = index(rows(preview.get("operations"), "preview.operations", 100), "operationId");
        var tools = new LinkedHashMap<String,Map<String,Object>>();
        for (var tool : selection.tools()) {
            if (tool.containsKey("skillRef")) throw unsupported("固定skillRef工具应走原精确发布绑定，不能由会话新导入");
            String operation = text(tool.get("operation"), "tool.operation", 256);
            if (tools.putIfAbsent(operation, tool) != null) throw conflict("包路线包含歧义operationId");
        }
        var manual = object(manualOverrides, "manualOverrides");
        if (!new HashSet<>(selectedOperationIds).equals(manual.keySet()))
            throw missing("所选operationId必须逐项有且只有一份显式人工overrides");
        var overrides = new LinkedHashMap<String,Object>();
        var operations = new ArrayList<Map<String,Object>>();
        for (String id : selectedOperationIds) {
            var op = actual.get(id); var tool = tools.get(id);
            if (op == null || tool == null || !Boolean.TRUE.equals(op.get("supported"))) throw conflict("所选操作未在真实预检和冻结路线中共同开放: " + id);
            if (!"READ_ONLY".equals(tool.get("exposure"))) throw unsupported("首版只接受冻结包明确声明的READ_ONLY工具");
            if (tool.get("artifacts") instanceof Collection<?> artifacts && !artifacts.isEmpty()) throw unsupported("产物声明尚无新导入的精确运行绑定");
            if (tool.containsKey("riskLevel") && !"STANDARD".equals(tool.get("riskLevel"))) throw unsupported("首版只支持STANDARD只读风险");
            if (tool.containsKey("pagination") && !"none".equalsIgnoreCase(Objects.toString(tool.get("pagination")))) throw unsupported("首版不导入分页方言");
            String method = text(op.get("method"), "operation.method", 8);
            if (!Set.of("GET", "POST").contains(method)) throw unsupported("首版只接受已声明的同步GET/POST查询");
            String path = text(op.get("path"), "operation.path", 2048);
            if (!path.startsWith("/") || path.startsWith("//") || path.indexOf('?') >= 0 || path.indexOf('#') >= 0) throw invalid("operation.path", "路由必须为原生绝对路径模板");
            String target = baseUrl.replaceAll("/+$", "") + path;
            URI targetUri = endpoint(target, true);
            if (!Objects.equals(base.getRawAuthority(), targetUri.getRawAuthority()) || !base.getScheme().equals(targetUri.getScheme())) throw conflict("操作不能改变批准来源");
            endpointPolicy(facts, targetUri, method);
            if (!list(facts.get("operationAllowlist"), "operationAllowlist", 128).contains(id)) throw conflict("原生操作不在批准连接白名单: " + id);
            equal(selection.executionInterface().get("endpoint"), target, "interface.endpoint");
            if (!selection.executionInterface().containsKey("httpMethod")) throw missing("interface.httpMethod");
            equal(selection.executionInterface().get("httpMethod"), method, "interface.httpMethod");
            var input = schema(op.get("inputSchema"), "preview.inputSchema");
            var output = schema(op.get("outputSchema"), "preview.outputSchema");
            equal(schema(tool.get("inputSchema"), "tool.inputSchema"), input, "tool.inputSchema");
            equal(schema(tool.get("outputSchema"), "tool.outputSchema"), output, "tool.outputSchema");
            var httpMapping = object(op.get("httpMapping"), "preview.httpMapping");
            if (httpMapping.isEmpty()) throw invalid("preview.httpMapping", "完整预检缺少真实HTTP参数映射");
            if (Boolean.TRUE.equals(op.get("requiresAuth")) && !"BEARER".equals(authKind)) throw conflict("规格要求Bearer而批准连接未提供");
            var configured = manual(object(manual.get(id), "overrides." + id));
            var execution = object(configured.get("execution"), "execution");
            if (!Boolean.TRUE.equals(execution.get("readonly")) || !Boolean.FALSE.equals(execution.get("sideEffect"))
                    || !Boolean.FALSE.equals(execution.get("cancellable"))) throw unsupported("首版须显式只读、无副作用、不可取消");
            for (String flag : List.of("readonly", "sideEffect", "idempotent"))
                if (tool.containsKey(flag)) equal(tool.get(flag), execution.get(flag), "tool." + flag);
            if (selection.identity().containsKey("guard")) equal(selection.identity().get("guard"), configured.get("guard"), "identity.guard");
            if (tool.containsKey("errorSemantics")) equal(tool.get("errorSemantics"), configured.get("errorSemantics"), "tool.errorSemantics");
            if (tool.containsKey("description")) equal(tool.get("description"), configured.get("description"), "tool.description");
            long timeout = positive(configured.get("timeoutMs"), "overrides.timeoutMs");
            if (timeout < 100 || timeout > 600000) throw invalid("timeoutMs", "必须在100..600000");
            if (!selection.executionInterface().containsKey("timeoutMs")) throw missing("interface.timeoutMs");
            equal(selection.executionInterface().get("timeoutMs"), timeout, "interface.timeoutMs");
            if (configured.containsKey("authRef") && !Objects.toString(configured.get("authRef"), "").equals(secretRef)) throw conflict("人工配置不能替换批准secret_ref");
            configured.put("authRef", secretRef);
            var source = Map.<String,Object>of("kind", "OPENAPI", "operationId", id, "method", method, "path", path, "documentSha256", documentSha);
            var contract = new LinkedHashMap<String,Object>();
            for (String key : List.of("description", "execution", "guard", "returnSpec", "errorSemantics", "authRef")) contract.put(key, configured.get(key));
            contract.put("type", "HTTP"); contract.put("invocationKind", "SYNC"); contract.put("endpoint", target); contract.put("httpMethod", method);
            contract.put("inputSchema", input); contract.put("outputSchema", output); contract.put("httpMapping", copy(httpMapping));
            contract.put("timeoutMs", timeout); contract.put("authScheme", "BEARER"); contract.put("riskLevel", "READ_ONLY"); contract.put("importSource", source);
            if (configured.get("constraints") instanceof Map<?,?> limits && limits.containsKey("outputMaxBytes")) contract.put("outputMaxBytes", limits.get("outputMaxBytes"));
            var prepared = new LinkedHashMap<String,Object>();
            prepared.put("toolId", tool.get("id")); prepared.put("operationId", id); prepared.put("method", method); prepared.put("path", path);
            prepared.put("importSource", source); prepared.put("contract", contract); prepared.put("tool", copy(tool));
            prepared.put("contractSha256", Digest.sha256(Json.canonical(contract)));
            operations.add(prepared); overrides.put(id, configured);
        }
        String packageSha = Digest.sha256(Json.canonical(declaration));
        var connection = new LinkedHashMap<String,Object>();
        connection.put("profileId", text(facts.get("profileId"), "profileId", 48)); connection.put("profileRev", positive(facts.get("profileRev"), "profileRev"));
        connection.put("bindingId", text(binding.get("bindingId"), "bindingId", 48)); connection.put("bindingRev", positive(binding.get("bindingRev"), "bindingRev"));
        connection.put("environment", route.get("environment")); connection.put("resolutionDigest", resolutionDigest);
        var result = new LinkedHashMap<String,Object>();
        result.put("documentSha256", documentSha); result.put("packageDeclarationSha256", packageSha);
        result.put("routePlan", Map.of("environment", route.get("environment"), "routeProfileId", route.get("routeProfileId")));
        result.put("operations", operations); result.put("defaults", Map.of("baseUrl", baseUrl)); result.put("overrides", overrides);
        result.put("includeOps", new ArrayList<>(selectedOperationIds));
        result.put("provenance", Map.of("documentSha256", documentSha, "packageDeclarationSha256", packageSha,
                "connectionRef", connection, "manualOverridesSha256", Digest.sha256(Json.canonical(manual)),
                "operations", operations.stream().map(item -> Map.of("toolId", item.get("toolId"), "importSource", item.get("importSource"), "contractSha256", item.get("contractSha256"))).toList()));
        return result;
    }

    /** 原生OPENAPI来源先逐字核验；随后仅在比较副本移除operation，复用既有传输/Schema/守卫比较。 */
    public static Map<String,Object> mapping(Map<String,Object> declaration, Map<String,Object> routePlan,
            Map<String,Object> preparedOperation, Map<String,Object> actualVersion) {
        var selection = CapabilityBinding.select(object(declaration, "declaration"), route(routePlan));
        var prepared = object(preparedOperation, "preparedOperation");
        var expected = object(prepared.get("contract"), "prepared.contract");
        equal(prepared.get("contractSha256"), Digest.sha256(Json.canonical(expected)), "prepared.contractSha256");
        var actual = object(actualVersion, "actualVersion");
        text(actual.get("code"), "actual.code", 64); positive(actual.get("version"), "actual.version");
        positive(actual.get("skillVersionId"), "actual.skillVersionId"); sha(actual.get("sha256"), "actual.sha256");
        var definition = object(actual.get("definition"), "actual.definition");
        var source = object(definition.get("importSource"), "actual.importSource");
        if (!source.keySet().equals(SOURCE_KEYS)) throw conflict("OPENAPI来源只能保留原五字段");
        var nativeSource = object(prepared.get("importSource"), "prepared.importSource");
        if (!nativeSource.keySet().equals(SOURCE_KEYS)) throw conflict("准备计划不是原生OPENAPI来源");
        equal("OPENAPI", source.get("kind"), "source.kind");
        for (String field : List.of("operationId", "method", "path")) equal(nativeSource.get(field), prepared.get(field), "prepared." + field);
        for (String field : SOURCE_KEYS) equal(nativeSource.get(field), source.get(field), "OPENAPI." + field);
        var tool = selection.tools().stream().filter(value -> Objects.equals(value.get("id"), prepared.get("toolId"))).findFirst()
                .orElseThrow(() -> conflict("来源工具不在冻结路线内"));
        if (tool.containsKey("skillRef")) throw unsupported("固定skillRef工具不能走新导入映射");
        equal(tool.get("operation"), nativeSource.get("operationId"), "tool.operationId");
        equal(copy(tool), prepared.get("tool"), "prepared.tool");
        for (var field : expected.entrySet()) equal(field.getValue(), definition.get(field.getKey()), "contract." + field.getKey());
        var comparison = new LinkedHashMap<>(tool);
        comparison.remove("operation"); // 仅替代旧ONTOLOGY toolCode比较；原tool/source从未被修改。
        return selection.mapping(comparison, Map.of("code", actual.get("code"), "version", actual.get("version")), actual);
    }

    private static Map<String,Object> manual(Map<String,Object> raw) {
        if (!MANUAL_KEYS.containsAll(raw.keySet())) throw invalid("overrides", "不能覆盖code/endpoint/Schema/source或携带未登记字段");
        if (!raw.keySet().containsAll(REQUIRED_MANUAL)) {
            var absent = new TreeSet<>(REQUIRED_MANUAL); absent.removeAll(raw.keySet()); throw missing(String.join(",", absent));
        }
        var value = copy(raw);
        text(value.get("name"), "name", 128); text(value.get("description"), "description", 2000);
        var execution = object(value.get("execution"), "execution");
        if (!execution.keySet().equals(Set.of("readonly", "sideEffect", "idempotent", "cancellable"))
                || execution.values().stream().anyMatch(flag -> !(flag instanceof Boolean))) throw invalid("execution", "必须显式提供四个布尔执行属性");
        var guard = object(value.get("guard"), "guard");
        if (!guard.keySet().equals(Set.of("roles", "attributes"))) throw missing("guard.roles/attributes");
        strings(guard.get("roles"), "guard.roles", 32);
        for (var entry : object(guard.get("attributes"), "guard.attributes").entrySet()) strings(entry.getValue(), "guard.attributes." + entry.getKey(), 128);
        var errors = list(value.get("errorSemantics"), "errorSemantics", 64);
        for (Object error : errors) {
            var rule = object(error, "errorSemantics");
            if (!rule.keySet().equals(Set.of("match", "semantic"))) throw invalid("errorSemantics", "须为原错误语义数组");
            text(rule.get("match"), "errorSemantics.match", 256); text(rule.get("semantic"), "errorSemantics.semantic", 64);
        }
        returns(object(value.get("returnSpec"), "returnSpec"));
        if (!value.containsKey("timeoutMs")) throw missing("timeoutMs");
        if (value.containsKey("constraints")) {
            var constraints = object(value.get("constraints"), "constraints");
            if (!Set.of("outputMaxBytes").containsAll(constraints.keySet())) throw unsupported("首版只支持显式outputMaxBytes约束");
            if (constraints.containsKey("outputMaxBytes")) {
                long limit = positive(constraints.get("outputMaxBytes"), "outputMaxBytes");
                if (limit < 1024 || limit > 10_485_760) throw invalid("outputMaxBytes", "超出已有平台范围");
            }
        }
        return value;
    }

    private static void returns(Map<String,Object> value) {
        if (!Set.of("structuredPath", "modelSummary", "fileRefPaths", "cardTemplate").containsAll(value.keySet())) throw unsupported("不能通过新导入声明动态文件获取或新语义投影");
        returnPath(value.get("structuredPath"));
        var summary = object(value.get("modelSummary"), "modelSummary");
        // 与既有SkillRules/表单的tpl方言一致；省略mode即TEMPLATE，显式其它模式仍拒绝。
        if (!Set.of("mode", "tpl").containsAll(summary.keySet()) || !summary.containsKey("tpl")
                || summary.containsKey("mode") && !"TEMPLATE".equals(summary.get("mode")))
            throw missing("modelSummary.tpl（mode仅支持TEMPLATE）");
        template(text(summary.get("tpl"), "modelSummary.tpl", 4096));
        for (Object path : list(value.get("fileRefPaths"), "fileRefPaths", 16)) returnPath(path);
        if (value.containsKey("cardTemplate")) {
            Object card = value.get("cardTemplate");
            if (!(card instanceof Map<?,?> || card instanceof List<?>)) throw invalid("cardTemplate", "必须为真实JSON容器");
            card(card, 0);
        }
    }
    private static void card(Object value,int depth) {
        if (depth > 16) throw invalid("cardTemplate", "嵌套过深");
        if (value instanceof Map<?,?> map) for (Object child : map.values()) card(child, depth + 1);
        else if (value instanceof List<?> list) for (Object child : list) card(child, depth + 1);
        else if (value instanceof String text) template(text);
    }
    private static void template(String value) {
        if (value.contains("${") || value.toLowerCase(Locale.ROOT).contains("<script")) throw invalid("returnSpec", "禁止执行表达式");
        String remainder = SUMMARY_PATH.matcher(value).replaceAll("");
        if (remainder.contains("{{") || remainder.contains("}}")) throw invalid("returnSpec", "仅允许受限字段占位符");
    }
    private static void returnPath(Object raw) { if (!RETURN_PATH.matcher(text(raw, "returnPath", 256)).matches()) throw invalid("returnPath", "只支持固定字段/数字下标路径"); }
    private static void endpointPolicy(Map<String,Object> facts,URI target,String method) {
        var policy = object(facts.get("endpointPolicy"), "endpointPolicy");
        String origin = target.getScheme() + "://" + target.getRawAuthority();
        if (policy.containsKey("hosts") && !list(policy.get("hosts"), "hosts", 32).contains(origin)) throw conflict("操作来源不在批准主机集合");
        if (policy.containsKey("httpMethods") && !list(policy.get("httpMethods"), "httpMethods", 8).contains(method)) throw conflict("操作方法不在批准集合");
        if (policy.containsKey("pathPrefixes") && list(policy.get("pathPrefixes"), "pathPrefixes", 32).stream().noneMatch(raw -> within(target.getPath(), raw.toString())))
            throw conflict("操作路径不在批准前缀内");
    }
    private static boolean within(String path,String prefix) { String root = prefix.replaceAll("/+$", ""); return root.isEmpty() || path.equals(root) || path.startsWith(root + "/"); }
    private static URI endpoint(String value,boolean template) {
        try {
            String candidate = template ? value.replaceAll("\\{[A-Za-z_][A-Za-z0-9_-]{0,63}}", "x") : value;
            URI uri = URI.create(candidate);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || !uri.getRawPath().equals(uri.getPath())
                    || uri.getPath().contains("\\") || uri.getPath().contains("//") || uri.getPath().contains(";")) throw new IllegalArgumentException();
            for (String part : uri.getPath().split("/")) if (Set.of(".", "..").contains(part)) throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException error) { throw invalid("endpoint", "需要固定、规范且无URL身份的HTTP来源"); }
    }
    private static Map<String,Object> route(Map<String,Object> input) {
        var value = object(input, "routePlan");
        if (!value.keySet().equals(Set.of("environment", "routeProfileId"))) throw invalid("routePlan", "仅接受environment/routeProfileId");
        return CapabilityBinding.plan(Map.of("environment", value.get("environment"), "routeProfileId", value.get("routeProfileId"), "bindings", List.of()));
    }
    private static Map<String,Map<String,Object>> index(List<Map<String,Object>> rows,String field) {
        var result = new LinkedHashMap<String,Map<String,Object>>();
        for (var row : rows) { String id = text(row.get(field), field, 256); if (result.putIfAbsent(id, row) != null) throw conflict("预检操作ID重复"); } return result;
    }
    private static Map<String,Object> schema(Object raw,String name) { var value = object(raw, name); if (value.isEmpty()) throw missing(name); return copy(value); }
    @SuppressWarnings("unchecked") private static Map<String,Object> copy(Map<String,Object> value) { return Json.read(Json.write(value), Map.class); }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value,String name) {
        if (!(value instanceof Map<?,?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) throw invalid(name, "必须为实际对象"); return (Map<String,Object>) value;
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object value,String name,int max) {
        var result = new ArrayList<Map<String,Object>>(); for (Object item : list(value, name, max)) result.add(object(item, name)); return result;
    }
    private static List<?> list(Object value,String name,int max) { if (!(value instanceof List<?> list) || list.size() > max) throw invalid(name, "需要有界数组"); return list; }
    private static void strings(Object value,String name,int max) { for (Object entry : list(value, name, max)) text(entry, name, 128); }
    private static String text(Object raw,String name,int max) { if (!(raw instanceof String value) || value.isBlank() || value.length() > max) throw missing(name); return value; }
    private static String sha(Object raw,String name) { String value = text(raw, name, 64); if (!DIGEST.matcher(value).matches()) throw invalid(name, "摘要无效"); return value; }
    private static long positive(Object raw,String name) {
        if (!(raw instanceof Number number)) throw invalid(name, "必须为精确正整数");
        try { long result = new BigDecimal(number.toString()).longValueExact(); if (result < 1) throw new ArithmeticException(); return result; }
        catch (ArithmeticException | NumberFormatException error) { throw invalid(name, "必须为精确正整数"); }
    }
    private static void equal(Object expected,Object actual,String field) { if (!Json.canonical(expected).equals(Json.canonical(actual))) throw conflict("来源不一致: " + field); }
    private static PlatformException invalid(String field,String message) { return new PlatformException(400, "ONBOARDING_IMPORT_INVALID", field + ": " + message); }
    private static PlatformException missing(String field) { return new PlatformException(409, "ONBOARDING_IMPORT_MANUAL_REQUIRED", "缺少真实人工/合同字段: " + field); }
    private static PlatformException unsupported(String reason) { return new PlatformException(409, "ONBOARDING_IMPORT_UNSUPPORTED", reason); }
    private static PlatformException conflict(String reason) { return new PlatformException(409, "CAPABILITY_BINDING_CONFLICT", reason); }
}
