package cn.agentplatform.skill.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.infra.ConnectionResolutionReceipts;
import cn.agentplatform.skill.infra.SkillClients;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 受控连接解析（skill 侧）：向 app 取"某技能可用的连接事实"，并留下回执。
 *
 * 三条边界：
 * 1) 不解析就是不缓存的代理——每次都向 app 要，撤权、停用、换版在下一次调用立即生效；
 * 2) 回执与解析同一次请求完成，回执写失败就不把事实交出去（否则审计链会缺一段）；
 * 3) 只做形状与摘要校验，不在这里推断"该技能是否被允许用这个连接"——那是 app 侧 grant 的职责，
 *    本类只保证"交出去的事实与契约一致、且可追溯"。
 */
@Component
public class ApprovedConnectionResolver {

    static final Pattern ID = Pattern.compile("^[a-z][a-z0-9-]{2,47}$");
    static final Pattern ENVIRONMENT = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");
    static final Set<String> RUNTIME_AUTH_KINDS = Set.of("NONE", "BEARER", "API_KEY");
    static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

    private final SkillClients clients;
    private final ConnectionResolutionReceipts receipts;
    private final String appUrl;

    public ApprovedConnectionResolver(SkillClients clients, ConnectionResolutionReceipts receipts,
            @Value("${agent.services.app}") String appUrl) {
        this.clients = Objects.requireNonNull(clients);
        this.receipts = Objects.requireNonNull(receipts);
        this.appUrl = Objects.requireNonNull(appUrl).replaceAll("/+$", "");
    }

    public Map<String, Object> resolve(String skillCode, String profileId, int profileRev, String environment) {
        Actor actor = Actor.current();
        if (!ID.matcher(skillCode).matches())
            throw new PlatformException(400, "INVALID_INPUT", "技能编码格式不合法: " + skillCode);
        if (!ID.matcher(profileId).matches())
            throw new PlatformException(400, "INVALID_INPUT", "连接档案编码格式不合法: " + profileId);
        if (profileRev < 1)
            throw new PlatformException(400, "INVALID_INPUT", "profileRev 必须不小于 1");
        String env = environment == null || environment.isBlank() ? null : environment;
        if (env != null && !ENVIRONMENT.matcher(env).matches())
            throw new PlatformException(400, "INVALID_INPUT", "environment 格式不合法: " + env);

        String url = appUrl + "/internal/connection-profiles/" + encode(profileId) + "/versions/" + profileRev
                + "/resolve" + (env == null ? "" : "?environment=" + encode(env));
        Map<String, Object> facts = shape(clients.get(url), profileId, profileRev);
        return receipts.record(actor, skillCode, facts, env);
    }

    /**
     * 形状校验：远端少字段、摘要不合法、鉴权形态不在开放集合，都按协议破坏处理（502），
     * 不猜默认值——把"看不懂"降级成"没鉴权"是最危险的失败方式。
     */
    static Map<String, Object> shape(JsonNode data, String profileId, int profileRev) {
        if (data == null || !data.isObject()) throw malformed("空响应或非对象");
        if (!profileId.equals(data.path("profileId").asText())) throw malformed("profileId 不一致");
        if (!data.path("profileRev").isIntegralNumber() || data.path("profileRev").asInt() != profileRev)
            throw malformed("profileRev 不一致");
        String digest = data.path("resolutionDigest").asText("");
        if (!DIGEST.matcher(digest).matches()) throw malformed("resolutionDigest 不是 64 位小写十六进制");
        String authKind = data.path("authKind").asText("");
        if (!RUNTIME_AUTH_KINDS.contains(authKind))
            throw new PlatformException(400, "CONFIG_AUTH_SCHEME_UNSUPPORTED",
                    "该鉴权形态尚未开放给运行时: " + authKind);
        for (String array : List.of("operationAllowlist", "allowedUses", "grantedUses", "bindings", "grants"))
            if (!data.path(array).isArray()) throw malformed(array + " 必须是数组");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profileId", profileId);
        out.put("profileRev", profileRev);
        out.put("status", data.path("status").asText(""));
        out.put("protocol", data.path("protocol").asText(""));
        out.put("operationAllowlist", Json.read(data.path("operationAllowlist").toString(), List.class));
        out.put("endpointPolicy", Json.read(data.path("endpointPolicy").toString(), Map.class));
        out.put("authScheme", Json.read(data.path("authScheme").toString(), Map.class));
        out.put("authKind", authKind);
        out.put("identityPolicy", Json.read(data.path("identityPolicy").toString(), Map.class));
        out.put("inputContractRefs", Json.read(data.path("inputContractRefs").toString(), List.class));
        out.put("outputContractRefs", Json.read(data.path("outputContractRefs").toString(), List.class));
        out.put("allowedUses", Json.read(data.path("allowedUses").toString(), List.class));
        out.put("grantedUses", Json.read(data.path("grantedUses").toString(), List.class));
        out.put("bindings", Json.read(data.path("bindings").toString(), List.class));
        out.put("grants", Json.read(data.path("grants").toString(), List.class));
        out.put("resolutionDigest", digest);
        out.put("resolvedAt", data.path("resolvedAt").asText(""));
        return out;
    }

    private static PlatformException malformed(String detail) {
        return new PlatformException(502, "CONNECTION_RESOLUTION_MALFORMED", "连接解析回执不符合契约: " + detail);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
