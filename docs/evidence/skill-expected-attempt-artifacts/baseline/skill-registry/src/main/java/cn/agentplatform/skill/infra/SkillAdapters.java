package cn.agentplatform.skill.infra;

import static cn.agentplatform.skill.application.SkillCatalog.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.domain.SkillRules;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.*;
import java.time.Duration;
import java.util.*;

@Component
public class SkillAdapters {
    public record Raw(
            JsonNode structured,
            List<Map<String, Object>> fileRefs,
            String summary,
            JsonNode ontologyMeta,
            String cardPayloadJson) {
        public Raw(
                JsonNode structured,
                List<Map<String, Object>> refs,
                String summary,
                JsonNode ontology) {
            this(structured, refs, summary, ontology, "");
        }
    }

    public static class Failure extends RuntimeException {
        public final String semantic;
        public final boolean unknown;

        public Failure(String semantic, boolean unknown, String message) {
            super(message);
            this.semantic = semantic;
            this.unknown = unknown;
        }
    }

    private final ArtifactStore artifacts;
    private final AlgorithmAdapter algorithm;
    private final HttpAsyncAdapter asyncHttp;

    public SkillAdapters(ArtifactStore artifacts, AlgorithmAdapter algorithm) {
        this(artifacts, algorithm, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SkillAdapters(
            ArtifactStore artifacts, AlgorithmAdapter algorithm, HttpAsyncAdapter asyncHttp) {
        this.artifacts = artifacts;
        this.algorithm = algorithm;
        this.asyncHttp = asyncHttp;
    }

    public boolean asynchronous(Map<String, Object> contract) {
        return "HTTP".equals(contract.get("type"))
                && Set.of("SUBMIT_QUERY", "CALLBACK")
                        .contains(Objects.toString(contract.get("invocationKind"), ""));
    }

    public void bind(Map<String, Object> contract, Map<String, Object> request) {
        if (asynchronous(contract)) asyncHttp.bind(contract, request);
    }

    /** LAST_REAL只由已核实事实调用；附件复制为当前run的独立归属，不能公开原run链接。 */
    public Raw recorded(
            JsonNode source,
            Map<String, Object> contract,
            long callId,
            String runId,
            String sourceRunId) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (JsonNode ref : source.path("fileRefs"))
            refs.add(
                    artifacts.copyToRun(
                            ref.path("artifactId").asText(),
                            sourceRunId,
                            runId,
                            callId,
                            contract.get("outputSchema")));
        return new Raw(
                source.path("structured"),
                refs,
                source.path("modelSummary").asText(),
                source.path("ontologyMeta"),
                source.path("cardPayloadJson").asText(""));
    }

    public Raw invoke(
            Map<String, Object> contract,
            Map<String, Object> arguments,
            String key,
            long callId,
            boolean queryOnly) {
        return invoke(contract, arguments, key, callId, queryOnly, null);
    }

    public Raw invoke(
            Map<String, Object> contract,
            Map<String, Object> arguments,
            String key,
            long callId,
            boolean queryOnly,
            String runId) {
        return switch (contract.get("type").toString()) {
            case "HTTP" ->
                    asynchronous(contract)
                            ? asyncHttp.invoke(
                                    contract, arguments, key, callId, queryOnly, runId, this)
                            : http(contract, arguments, key, callId, runId);
            case "MCP" -> {
                if (queryOnly)
                    throw new Failure("RESULT_UNKNOWN", true, "MCP SSE不提供原调用结果查询，禁止以对账名义重新调用工具");
                yield mcp(contract, arguments, callId, runId);
            }
            case "ALGORITHM" ->
                    algorithm.invoke(contract, arguments, key, callId, queryOnly, this, runId);
            default -> throw new Failure("PARAM_ERROR", false, "未支持的适配器类型");
        };
    }

    private Raw http(
            Map<String, Object> contract,
            Map<String, Object> arguments,
            String key,
            long callId,
            String runId) {
        String endpoint = contract.get("endpoint").toString();
        Object requestBody = arguments;
        boolean mapped = contract.containsKey("httpMapping");
        if (mapped) {
            var requestMapping = OpenApiHttpMapping.request(contract, arguments);
            endpoint = requestMapping.endpoint();
            requestBody = requestMapping.body();
        }
        SkillRules.endpoint(endpoint);
        String method = Objects.toString(contract.get("httpMethod"), "POST");
        if (!mapped && method.equals("GET")) {
            var uri = UriComponentsBuilder.fromUriString(endpoint);
            arguments.forEach(
                    (name, value) ->
                            uri.queryParam(
                                    name, value instanceof String ? value : Json.write(value)));
            endpoint = uri.build().encode().toUriString();
        }
        String auth = Objects.toString(contract.get("authRef"), "");
        String authorization = auth.isBlank() ? null : "Bearer " + SecretVault.resolve(auth);
        HttpMethod httpMethod = HttpMethod.valueOf(method);
        boolean sideEffect =
                Boolean.TRUE.equals(object(contract.get("execution")).get("sideEffect"));
        // HttpURLConnection不支持PATCH。仅该方法使用JDK21 transport，保持相同的超时/硬字节闸并在单次调用后关闭。
        java.net.http.HttpClient patchClient =
                method.equals("PATCH")
                        ? java.net.http.HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(3))
                                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                                .build()
                        : null;
        try {
            org.springframework.http.client.ClientHttpRequestFactory factory;
            if (patchClient != null) {
                var jdk =
                        new org.springframework.http.client.JdkClientHttpRequestFactory(
                                patchClient);
                jdk.setReadTimeout(
                        Duration.ofMillis(((Number) contract.get("timeoutMs")).longValue()));
                factory = jdk;
            } else {
                var simple = new SimpleClientHttpRequestFactory();
                simple.setConnectTimeout(3000);
                simple.setReadTimeout(((Number) contract.get("timeoutMs")).intValue());
                factory = simple;
            }
            var client = RestClient.builder().requestFactory(factory).build();
            var request =
                    mapped
                            ? client.method(httpMethod).uri(java.net.URI.create(endpoint))
                            : client.method(httpMethod).uri(endpoint);
            request.header("Idempotency-Key", key).header("Accept", "application/json");
            if (authorization != null) request.header("Authorization", authorization);
            // JsonNode强制Jackson编码；Java String直接交给默认转换器会变为裸text/plain，破坏OpenAPI JSON标量契约。
            if (!method.equals("GET") && requestBody != null)
                request.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body(Json.tree(requestBody));
            return request.exchange(
                    (sent, response) -> {
                        int code = response.getStatusCode().value();
                        if (code >= 400)
                            throw new Failure(
                                    code == 403
                                            ? "NO_PERMISSION"
                                            : code == 429
                                                    ? "QUOTA"
                                                    : code < 500
                                                            ? "PARAM_ERROR"
                                                            : sideEffect
                                                                    ? "RESULT_UNKNOWN"
                                                                    : "RETRYABLE_FAILURE",
                                    sideEffect && code >= 500,
                                    "外部端点HTTP " + code);
                        return read(response.getBody(), contract, callId, runId);
                    });
        } catch (Failure ex) {
            throw ex;
        } catch (Exception ex) {
            throw new Failure(
                    sideEffect ? "RESULT_UNKNOWN" : "RETRYABLE_FAILURE",
                    sideEffect,
                    "外部端点连接、超时或结果接收失败");
        } finally {
            if (patchClient != null) patchClient.close();
        }
    }

    /** 按字节读取max+1后立即转MinIO，普通响应仅在已知上限内构造JSON。 */
    public Raw read(InputStream input, Map<String, Object> contract, long callId)
            throws IOException {
        return read(input, contract, callId, null);
    }

    public Raw read(InputStream input, Map<String, Object> contract, long callId, String runId)
            throws IOException {
        int limit = ((Number) contract.get("outputMaxBytes")).intValue();
        byte[] prefix = input.readNBytes(limit + 1);
        if (prefix.length > limit) {
            var ref = artifacts.offload(prefix, input, contract.get("outputSchema"), callId, runId);
            return new Raw(Json.tree(null), List.of(ref), "结果已流式转存，请使用受控文件引用查看", Json.tree(null));
        }
        JsonNode output = ArtifactStore.readJson(new ByteArrayInputStream(prefix));
        if (output == null) throw new Failure("OUTPUT_INVALID", false, "外部响应为空");
        try {
            SkillRules.validate(
                    contract.get("outputSchema"), Json.read(output.toString(), Object.class));
        } catch (IllegalArgumentException ex) {
            throw new Failure("OUTPUT_INVALID", false, "外部响应不符合输出Schema");
        }
        JsonNode ontology = output.path("ontologyMeta");
        String summary = output.isTextual() ? output.asText() : Json.write(output);
        if (summary.length() > 2000) summary = summary.substring(0, 2000) + "…";
        var spec = object(contract.getOrDefault("returnSpec", Map.of()));
        try {
            JsonNode structured =
                    select(output, Objects.toString(spec.getOrDefault("structuredPath", "$")));
            var summarySpec = object(spec.getOrDefault("modelSummary", Map.of()));
            if ("PATH".equals(summarySpec.get("mode")))
                summary = valueText(select(output, Objects.toString(summarySpec.get("path"))));
            else if ("TEMPLATE".equals(summarySpec.get("mode")))
                summary = render(text(summarySpec, "tpl"), output);
            if (summary.length() > 2000) summary = summary.substring(0, 2000) + "…";
            var refs = new ArrayList<Map<String, Object>>();
            if (spec.get("fileRefPaths") instanceof Collection<?> paths)
                for (Object path : paths) addFiles(select(output, path.toString()), refs, runId);
            String card = "";
            if (spec.containsKey("cardTemplate")) {
                Object template = spec.get("cardTemplate");
                JsonNode json =
                        template instanceof String str ? Json.read(str) : Json.tree(template);
                if (!json.isContainerNode()) throw new IllegalArgumentException("卡片模板必须为JSON对象或数组");
                card = Json.write(renderCard(json, output));
            }
            return new Raw(structured, refs, summary, ontology, card);
        } catch (Failure ex) {
            throw ex;
        } catch (Exception ex) {
            throw new Failure("OUTPUT_INVALID", false, "结果四份内容映射不符合returnSpec");
        }
    }

    private static JsonNode select(JsonNode root, String path) {
        if (path.equals("$")) return root;
        int wildcard = path.indexOf("[*]");
        if (wildcard >= 0) {
            var array = select(root, path.substring(0, wildcard));
            if (!array.isArray()) throw new IllegalArgumentException("通配路径源非数组");
            var result = Json.mapper().createArrayNode();
            for (JsonNode item : array)
                result.add(select(item, "$" + path.substring(wildcard + 3)));
            return result;
        }
        if (!path.matches("\\$(?:\\.[A-Za-z_][A-Za-z0-9_-]*|\\[[0-9]+\\])+"))
            throw new IllegalArgumentException("结果路径仅支持点路径/数组下标/[*]");
        String normalized = path.substring(1).replaceAll("\\[([0-9]+)]", ".$1");
        if (normalized.startsWith(".")) normalized = normalized.substring(1);
        JsonNode value = root;
        for (String key : normalized.split("\\.")) {
            value = value.isArray() ? value.path(Integer.parseInt(key)) : value.path(key);
            if (value.isMissingNode()) throw new IllegalArgumentException("结果路径不存在: " + path);
        }
        return value;
    }

    private static String valueText(JsonNode value) {
        return value.isValueNode() ? value.asText() : Json.write(value);
    }

    private static String render(String template, JsonNode output) {
        var pattern = java.util.regex.Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.-]*)\\s*}}");
        var matcher = pattern.matcher(template);
        var result = new StringBuffer();
        while (matcher.find())
            matcher.appendReplacement(
                    result,
                    java.util.regex.Matcher.quoteReplacement(
                            valueText(select(output, "$." + matcher.group(1)))));
        matcher.appendTail(result);
        return result.toString();
    }

    private static JsonNode renderCard(JsonNode template, JsonNode output) {
        if (template.isTextual()) return Json.tree(render(template.asText(), output));
        if (template.isObject()) {
            var result = Json.mapper().createObjectNode();
            template.fields()
                    .forEachRemaining(
                            entry ->
                                    result.set(
                                            entry.getKey(), renderCard(entry.getValue(), output)));
            return result;
        }
        if (template.isArray()) {
            var result = Json.mapper().createArrayNode();
            template.forEach(item -> result.add(renderCard(item, output)));
            return result;
        }
        return template;
    }

    private void addFiles(JsonNode value, List<Map<String, Object>> refs, String runId) {
        if (value.isArray()) {
            value.forEach(item -> addFiles(item, refs, runId));
            return;
        }
        Map<String, Object> ref =
                value.isTextual()
                        ? Map.of("artifactId", value.asText())
                        : Json.read(value.toString(), Map.class);
        refs.add(artifacts.controlledReference(ref, runId));
    }

    private Raw mcp(
            Map<String, Object> contract,
            Map<String, Object> arguments,
            long callId,
            String runId) {
        return new McpAdapter().invoke(contract, arguments, callId, runId, this);
    }
}
