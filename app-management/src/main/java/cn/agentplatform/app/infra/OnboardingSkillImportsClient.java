package cn.agentplatform.app.infra;

import cn.agentplatform.common.PlatformException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 技能接入协议薄客户端；同模式授权、目录匹配和网络预算统一由 transport 承担。 */
@Component
public class OnboardingSkillImportsClient {
    private final String origin;
    private final AppAuthorityTransport transport;

    public OnboardingSkillImportsClient(
            @Value("${agent.services.skill:http://127.0.0.1:18083}") String origin,
            AppAuthorityTransport transport) {
        this.origin = origin;
        this.transport = transport;
    }

    public Map<String, Object> preview(String document) {
        if (document == null || document.isBlank()
                || document.getBytes(StandardCharsets.UTF_8).length > 1_048_576)
            throw new PlatformException(400, "OPENAPI_SIZE", "请提供不超过1MiB的真实OpenAPI规格");
        return transport.call(origin, "/admin/skills/import-openapi/preview", Map.of("document", document));
    }

    public Map<String, Object> create(Map<String, Object> body) {
        if (body == null || body.isEmpty())
            throw new PlatformException(400, "INVALID_INPUT", "技能导入缺少冻结请求");
        return transport.call(origin, "/internal/onboarding-skill-imports", body);
    }

    public Map<String, Object> get(String sessionId, String planSha256) {
        if (sessionId == null || !sessionId.matches("os_[a-f0-9]{32}")
                || planSha256 == null || !planSha256.matches("[a-f0-9]{64}"))
            throw new PlatformException(400, "INVALID_INPUT", "导入回查必须使用精确会话及计划摘要");
        return transport.call(origin, "/internal/onboarding-skill-imports/" + sessionId
                + "?planSha256=" + planSha256, null);
    }

    public Map<String, Object> version(String code, int version) {
        if (code == null || !code.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || version < 1)
            throw new PlatformException(400, "INVALID_INPUT", "技能必须使用精确编码和发布版本");
        return transport.call(origin, "/internal/skills/" + code
                + "?version=" + version + "&requireRegistered=true", null);
    }
}
