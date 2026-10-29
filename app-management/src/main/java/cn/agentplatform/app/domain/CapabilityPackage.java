package cn.agentplatform.app.domain;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.*;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** Immutable declaration identity, not a resolved skill contract or an executable app. */
public record CapabilityPackage(String packageId, String version, String sha256,
        String softwareId, String softwareVersion, String declarationJson) {
    private static final Pattern CREDENTIAL_KEY = Pattern.compile("pass(word)?|secret|token|api_?key|credential", Pattern.CASE_INSENSITIVE);
    private static final List<String> GATES = List.of("connection", "contract", "model", "evaluation", "safety", "concurrency");
    private static final JsonSchema SCHEMA = loadSchema();

    private static JsonSchema loadSchema() {
        try (var input = CapabilityPackage.class.getResourceAsStream("/capability-package.schema.json")) {
            if (input == null) throw new IllegalStateException("Capability schema resource missing");
            byte[] bytes = input.readAllBytes();
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!sha.equals("0456799d04deb57dfb114d169d8a37c7b57a49cad3996fee917a93efa704a634"))
                throw new IllegalStateException("Frozen capability schema changed");
            var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
                    builder -> builder.schemaLoaders(loaders -> loaders.add(iri -> {
                        throw new IllegalArgumentException("Capability schema forbids external loading");
                    })));
            var config = new SchemaValidatorsConfig();
            config.setFailFast(false);
            return factory.getSchema(Json.read(new String(bytes, StandardCharsets.UTF_8)), config);
        } catch (Exception ex) {
            throw new IllegalStateException("Capability schema unavailable", ex);
        }
    }

    public static CapabilityPackage parse(Object raw) {
        if (!(raw instanceof Map<?, ?>)) throw invalid("package 必须为对象");
        var pkg = Json.tree(raw);
        String canonical = Json.canonical(pkg);
        if (canonical.getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024)
            throw invalid("能力包声明超过 2 MiB");
        checkIdentityCredentials(pkg.path("identity"));
        if (!SCHEMA.validate(pkg).isEmpty()) throw invalid("声明不符合冻结的 CapabilityPackage v1 schema");
        String name = pkg.path("metadata").path("name").asText();
        String version = pkg.path("metadata").path("version").asText();
        if (!name.matches("[a-z][a-z0-9-]{2,63}") || version.length() > 128
                || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))
            throw invalid("metadata.name/version 身份无效或过长");
        checkReferences(pkg);
        pkg.path("software").path("environments").forEach(value -> checkUrl(value.asText()));
        for (JsonNode entry : pkg.path("interfaces")) {
            for (String field : List.of("endpoint", "serverRef")) checkUrl(entry.path(field).asText());
            for (String field : List.of("endpoint", "resultEndpoint", "lookupEndpoint"))
                checkUrl(entry.path("reconciliation").path(field).asText());
        }
        return new CapabilityPackage(name, version, Digest.sha256(canonical),
                pkg.path("software").path("system").asText(),
                pkg.path("software").has("version") ? pkg.path("software").path("version").asText() : null,
                canonical);
    }

    private static Map<String, JsonNode> index(JsonNode values, String field, String label) {
        var result = new LinkedHashMap<String, JsonNode>();
        for (JsonNode value : values) {
            if (result.putIfAbsent(value.path(field).asText(), value) != null)
                throw invalid(label + " 标识重复");
        }
        return result;
    }

    private static void checkReferences(JsonNode pkg) {
        var interfaces = index(pkg.path("interfaces"), "id", "interfaces");
        var tools = index(pkg.path("tools"), "id", "tools");
        index(pkg.path("routeProfiles"), "id", "routeProfiles");
        index(pkg.path("agents"), "code", "agents");
        for (JsonNode tool : tools.values()) {
            if (!interfaces.containsKey(tool.path("binding").asText()))
                throw invalid("tools.binding 未声明");
            if (tool.path("readonly").asBoolean() && (tool.path("sideEffect").asBoolean()
                    || "WRITE_WITH_APPROVAL".equals(tool.path("exposure").asText())))
                throw invalid("只读工具不得声明写入或副作用");
            if ("READ_ONLY".equals(tool.path("exposure").asText()) && tool.path("sideEffect").asBoolean())
                throw invalid("READ_ONLY 不得声明副作用");
        }
        var ontology = pkg.path("ontology");
        if ("NONE".equals(ontology.path("mode").asText()) && ontology.has("contextPack"))
            throw invalid("ontology NONE 不得声明 contextPack");
        for (JsonNode route : pkg.path("routeProfiles")) {
            if (!interfaces.containsKey(route.path("execution").asText()))
                throw invalid("routeProfiles.execution 未声明");
            for (JsonNode tool : route.path("tools")) requireEnabled(tools, tool.asText());
            if (route.has("ontology") && !"none".equals(route.path("ontology").asText())) {
                String ref = ontology.path("space").asText() + "@" + ontology.path("release").asText();
                if (ontology.isMissingNode() || "NONE".equals(ontology.path("mode").asText())
                        || !ref.equals(route.path("ontology").asText()))
                    throw invalid("routeProfiles.ontology 与本包声明不符");
            }
        }
        for (JsonNode agent : pkg.path("agents"))
            for (JsonNode ref : agent.path("skillRefs")) requireEnabled(tools, ref.path("tool").asText());
        var verification = pkg.path("verification");
        var closed = GATES.stream().filter(gate -> !verification.path(gate).asBoolean()).toList();
        if (closed.size() == GATES.size()) throw invalid("verification 不得全部关闭");
        var exempted = new HashSet<String>();
        for (JsonNode exemption : verification.path("exemptions")) exempted.add(exemption.path("gate").asText());
        if (!exempted.containsAll(closed)) throw invalid("关闭 verification 门必须声明豁免原因");
    }

    private static void requireEnabled(Map<String, JsonNode> tools, String id) {
        var tool = tools.get(id);
        if (tool == null || (tool.has("enabled") && !tool.path("enabled").asBoolean()))
            throw invalid("路线或应用引用了未知/停用工具");
    }

    private static void checkIdentityCredentials(JsonNode identity) {
        identity.fields().forEachRemaining(entry -> {
            // secrets 是冻结的 {ref,purpose} 列表，purpose 是用途说明而非秘密值。
            // 这里只保留该已知结构的说明字段；结构本身仍须通过完整 schema 校验。
            if (entry.getKey().equals("secrets") && entry.getValue().isArray()) {
                entry.getValue().forEach(secret -> checkCredentials(secret.path("ref"), true));
            } else {
                checkCredentials(entry.getValue(), CREDENTIAL_KEY.matcher(entry.getKey()).find());
            }
        });
    }

    private static void checkCredentials(JsonNode node, boolean credentialValue) {
        if (node.isTextual() && credentialValue && !node.asText().matches("(?s)(secret|cred)://.+"))
            throw invalid("identity 中的凭据只能使用 secret:// 或 cred:// 引用");
        if (node.isArray()) node.forEach(child -> checkCredentials(child, credentialValue));
        // 已进入凭据字段后，包装对象或数组不能解除检查；只接受引用，不读取引用值。
        if (node.isObject()) node.fields().forEachRemaining(entry ->
                checkCredentials(entry.getValue(), credentialValue || CREDENTIAL_KEY.matcher(entry.getKey()).find()));
    }

    private static void checkUrl(String value) {
        if (!value.regionMatches(true, 0, "http://", 0, 7)
                && !value.regionMatches(true, 0, "https://", 0, 8)) return;
        try {
            // 仅解析 authority，保留对账路径中的 {jobId} 等声明占位符；绝不连接 URL。
            int start = value.indexOf("://") + 3;
            int end = value.length();
            for (char delimiter : new char[] {'/', '?', '#'}) {
                int position = value.indexOf(delimiter, start);
                if (position >= 0) end = Math.min(end, position);
            }
            var uri = URI.create(value.substring(0, end));
            String authority = uri.getRawAuthority();
            if (authority == null || uri.getRawUserInfo() != null
                    || URLDecoder.decode(authority, StandardCharsets.UTF_8).contains("@"))
                throw invalid("URL 不得含 userinfo 凭据");
            int query = value.indexOf('?'), fragment = value.indexOf('#');
            if (query >= 0 && (fragment < 0 || query < fragment)) {
                String queryText = value.substring(query + 1, fragment < 0 ? value.length() : fragment);
                for (String pair : queryText.split("&")) {
                    String key = URLDecoder.decode(pair.split("=", 2)[0], StandardCharsets.UTF_8);
                    if (key.matches("(?i)(password|passwd|secret|token|access_token|api_?key|credential)"))
                        throw invalid("URL 不得携带凭据查询参数，请使用 authRef");
                }
            }
        } catch (IllegalArgumentException ex) {
            throw invalid("URL 声明或编码无效");
        }
    }

    private static PlatformException invalid(String message) {
        return new PlatformException(400, "CAPABILITY_DECLARATION_INVALID", message);
    }
}
