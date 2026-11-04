package cn.agentplatform.app.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.ServiceTokens;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * ONB-02：服务端预检缺口。
 *
 * <p>这里不复制 OpenAPI 解析器：只以已验主体向技能注册中心既有预览端点做一次只读探测，再把结果
 * 归一成前端 {@code normalizePreview} 消费的形状（operations/warnings/documentSha256）。解析方言、
 * 支持子集与诊断仍由技能注册中心唯一实现，避免两套解析器漂移。
 */
@Service
public class OnboardingPreflightService {

    private static final int MAX_DOCUMENT_BYTES = 1024 * 1024;

    private final String origin;

    public OnboardingPreflightService(
            @Value("${agent.services.skill:http://127.0.0.1:18083}") String base) {
        URI uri = URI.create(base);
        if (!Set.of("http", "https").contains(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("技能服务必须配置为固定 HTTP origin");
        }
        this.origin = base.replaceAll("/+$", "");
    }

    /**
     * 只读预检：不生成技能、应用或任何持久事实。上游 4xx 原码透传（技能侧已登记），
     * 网络/5xx 归一为 {@code DEPENDENCY_UNAVAILABLE}，不把不可达伪装成“全部不支持”。
     */
    public Map<String, Object> preview(Actor actor, String document) {
        if (document == null || document.isBlank()) {
            throw new PlatformException(400, "INVALID_INPUT", "缺少 OpenAPI 规格文本");
        }
        if (document.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            throw new PlatformException(413, "OPENAPI_SIZE", "请上传不超过 1MiB 的 OpenAPI JSON/YAML 文本");
        }
        String ticket = ServiceTokens.issue(actor);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(origin + "/admin/skills/import-openapi/preview")
                    .toURL()
                    .openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("Authorization", "Bearer " + ticket);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            byte[] body = Json.write(Map.of("document", document)).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (var output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            String raw = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            var envelope = Json.read(raw);
            if (status != 200) {
                String code = envelope.path("code").asText("");
                String message = envelope.path("message").asText("预检不可用");
                if (status >= 400 && status < 500 && !code.isBlank()) {
                    throw new PlatformException(status, code, message);
                }
                throw unavailable();
            }
            if (!"OK".equals(envelope.path("code").asText())) {
                throw unavailable();
            }
            return normalize(envelope.path("data"));
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static Map<String, Object> normalize(com.fasterxml.jackson.databind.JsonNode data) {
        if (data == null || !data.isObject()) {
            throw unavailable();
        }
        var operations = new ArrayList<Map<String, Object>>();
        var unsupported = new ArrayList<Map<String, Object>>();
        int supported = 0;
        var raw = data.path("operations");
        if (raw.isArray()) {
            for (var node : raw) {
                String operationId = node.path("operationId").asText("");
                String method = node.path("method").asText("");
                String path = node.path("path").asText("");
                if (operationId.isBlank() || method.isBlank() || path.isBlank()) {
                    throw unavailable();
                }
                boolean isSupported = node.path("supported").asBoolean(false);
                var reasons = new ArrayList<String>();
                var diagnostics = node.path("diagnostics");
                if (diagnostics.isArray()) {
                    for (var diagnostic : diagnostics) {
                        reasons.add(diagnostic.asText(""));
                    }
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("operationId", operationId);
                item.put("method", method);
                item.put("path", path);
                item.put("supported", isSupported);
                item.put("requiresAuth", node.path("requiresAuth").asBoolean(false));
                item.put("reasons", reasons);
                operations.add(item);
                if (isSupported) {
                    supported++;
                } else {
                    unsupported.add(Map.of("operationId", operationId, "reasons", reasons));
                }
            }
        }
        var warnings = new ArrayList<String>();
        var rawWarnings = data.path("warnings");
        if (rawWarnings.isArray()) {
            for (var warning : rawWarnings) {
                warnings.add(warning.asText(""));
            }
        }
        String documentSha256 = data.path("documentSha256").asText("");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operations", operations);
        out.put("warnings", warnings);
        out.put("documentSha256", documentSha256);
        out.put("total", operations.size());
        out.put("supportedCount", supported);
        out.put("unsupported", unsupported);
        out.put("zeroCreated", true);
        return out;
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) {
            throw unavailable();
        }
        try (input; var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            while (true) {
                int count = input.read(buffer);
                if (count < 0) {
                    break;
                }
                if (output.size() + count > 2 * 1024 * 1024) {
                    throw unavailable();
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static PlatformException unavailable() {
        return new PlatformException(502, "DEPENDENCY_UNAVAILABLE", "预检服务不可用，请稍后重试");
    }

    /** 供服务层计算计划摘要时复用：只读探测结果的稳定摘要素材。 */
    public static List<Object> planMaterial(List<Map<String, Object>> operations) {
        var material = new ArrayList<Object>();
        for (Map<String, Object> operation : operations) {
            material.add(List.of(
                    String.valueOf(operation.get("operationId")),
                    String.valueOf(operation.get("method")),
                    String.valueOf(operation.get("path")),
                    Boolean.TRUE.equals(operation.get("supported"))));
        }
        return material;
    }
}
