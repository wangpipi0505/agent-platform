package cn.agentplatform.app.infra;

import cn.agentplatform.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 固定服务 origin 上的只读版本请求：不接受包内地址、不重定向、不透传用户票据。 */
@Component
public class CapabilitySkillClient {
    private final String origin;
    @Value("${agent.security.mode:dev-hmac}")
    private String securityMode = "dev-hmac";
    private AppAuthorityTransport authorityTransport;

    @Autowired(required = false)
    public void setAuthorityTransport(AppAuthorityTransport authorityTransport) {
        this.authorityTransport = authorityTransport;
    }

    public CapabilitySkillClient(@Value("${agent.services.skill:http://127.0.0.1:18083}") String base) {
        var uri = URI.create(base);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("技能服务必须配置为固定 HTTP origin");
        origin = base.replaceAll("/+$", "");
    }

    public Map<String, Object> version(String code, int version, Actor actor, long deadline) {
        if (!code.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || version < 1)
            throw new PlatformException(400, "INVALID_INPUT", "技能必须使用精确 code/version");
        boolean iam = "iam".equals(securityMode);
        String ticket = iam ? null : ServiceTokens.issue(actor);
        HttpURLConnection connection = null;
        try {
            if (iam) {
                if (authorityTransport == null) throw unavailable();
                remaining(deadline);
                var data = authorityTransport.call(origin, "/internal/skills/" + code
                        + "?version=" + version + "&requireRegistered=true", null, deadline);
                remaining(deadline);
                return validate(code, version, Json.tree(data));
            }
            connection = (HttpURLConnection) URI.create(origin + "/internal/skills/" + code
                    + "?version=" + version + "&requireRegistered=true").toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(remaining(deadline));
            connection.setReadTimeout(remaining(deadline));
            connection.setRequestProperty("Authorization", "Bearer " + ticket);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            String raw;
            try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    var output = new ByteArrayOutputStream()) {
                if (input == null) throw unavailable();
                byte[] buffer = new byte[8192];
                while (true) {
                    connection.setReadTimeout(remaining(deadline));
                    int count = input.read(buffer);
                    if (count < 0) break;
                    if (output.size() + count > 2 * 1024 * 1024) throw unavailable();
                    output.write(buffer, 0, count);
                }
                raw = output.toString(StandardCharsets.UTF_8);
            }
            remaining(deadline);
            var envelope = Json.read(raw);
            if (status != 200) {
                String error = envelope.path("code").asText();
                if ((status == 404 || status == 409) && Set.of("SKILL_NOT_FOUND", "SKILL_VERSION_NOT_FOUND",
                        "SKILL_NOT_PUBLISHED", "SKILL_ADAPTER_NOT_REGISTERED", "SKILL_DIGEST_MISMATCH").contains(error))
                    throw new PlatformException(status, error, "精确技能版本不能用于当前绑定");
                throw unavailable();
            }
            if (!"OK".equals(envelope.path("code").asText())) throw unavailable();
            return validate(code, version, envelope.path("data"));
        } catch (PlatformException ex) {
            if ("INVALID_JSON".equals(ex.code())) throw unavailable();
            throw ex;
        } catch (Exception ex) {
            throw unavailable();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** 两种认证模式共用原始精确版本断言，授权交换不能替代来源与发布状态校验。 */
    private static Map<String, Object> validate(String code, int version, JsonNode data) {
        if (data == null || !data.isObject()
                || !code.equals(data.path("code").asText())
                || !data.path("version").isIntegralNumber() || !data.path("version").canConvertToInt() || data.path("version").asInt() != version
                || !data.path("versionNo").isIntegralNumber() || !data.path("versionNo").canConvertToInt() || data.path("versionNo").asInt() != version
                || !data.path("id").isIntegralNumber() || !data.path("id").canConvertToLong() || data.path("id").asLong() < 1
                || !data.path("skillVersionId").isIntegralNumber() || !data.path("skillVersionId").canConvertToLong()
                || data.path("id").asLong() != data.path("skillVersionId").asLong()
                || !data.path("definition").isObject()) throw unavailable();
        if (!Set.of("DRAFT", "PUBLISHED", "DISABLED", "ARCHIVED").contains(data.path("skillStatus").asText())) throw unavailable();
        if (!"PUBLISHED".equals(data.path("status").asText())
                || Set.of("DISABLED", "ARCHIVED").contains(data.path("skillStatus").asText()))
            throw new PlatformException(409, "SKILL_NOT_PUBLISHED", "技能版本尚未发布或已停用");
        if (!data.path("registered").isBoolean() || !data.path("registered").asBoolean())
            throw new PlatformException(409, "SKILL_ADAPTER_NOT_REGISTERED", "当前技能服务未确认注册组合");
        String sha = data.path("sha256").asText();
        if (!sha.matches("[0-9a-f]{64}") || !sha.equals(data.path("digest").asText())
                || !sha.equals(Digest.sha256(Json.canonical(data.path("definition"))))) throw unavailable();
        return Json.mapper().convertValue(data, Map.class);
    }

    private static int remaining(long deadline) {
        long millis = (deadline - System.nanoTime()) / 1_000_000;
        if (millis < 1) throw unavailable();
        return (int) Math.min(2000, millis);
    }
    private static PlatformException unavailable() {
        return new PlatformException(502, "DEPENDENCY_UNAVAILABLE", "技能元数据不可用或回复不完整，请稍后重试");
    }
}
