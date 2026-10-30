package cn.agentplatform.skill.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.SubjectSnapshot;
import cn.agentplatform.skill.domain.SkillRules;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/** 仅通过 app 服务读取 PG 当前映射；不缓存，不查询相邻仓库数据库，不把映射摘要解释为外部 subject。 */
@Component
public class IdentityMappingClient {
    private final SkillClients clients;
    private final String appUrl;
    public IdentityMappingClient(SkillClients clients, @Value("${agent.services.app}") String appUrl) {
        this.clients = Objects.requireNonNull(clients); this.appUrl = Objects.requireNonNull(appUrl).replaceAll("/+$", "");
    }

    /** 可信本地类型只标记当前 HTTP 业务请求尚未发送；远端同名 code 不能构造此发送阶段。 */
    public static final class BeforeDispatchFailure extends SkillAdapters.Failure {
        private BeforeDispatchFailure(String code) {
            super("NO_PERMISSION", false, "主体映射未就绪或已变化，本次未发送业务请求", code, null);
        }
    }

    static boolean declared(Map<String, Object> contract) {
        return contract.get("identity") instanceof Map<?, ?> identity
                && SkillRules.IDENTITY_MODE.equals(identity.get("mode"));
    }

    static BeforeDispatchFailure missingClient() { return new BeforeDispatchFailure("IDENTITY_MAPPING_CLIENT_MISSING"); }

    public Map<String, String> headers(Map<String, Object> contract) {
        if (!declared(contract)) return Map.of();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)
                || "workload".equals(jwt.getClaimAsString("authority_mode"))) return Map.of();
        try {
            // delegated Actor 会重新核验当前 AuthorityContext；撤权异常不能变成“无主体，跳过”。
            var actor = Actor.current();
            SubjectSnapshot current = actor.subject();
            var mapped = clients.get(appUrl + "/internal/identity-mappings/current");
            if (!mapped.isObject() || !actor.tenantId().equals(mapped.path("tenantId").asText())
                    || !actor.principalId().equals(mapped.path("principalId").asText())
                    || !mapped.path("revision").isIntegralNumber() || !mapped.path("revision").canConvertToLong()
                    || mapped.path("revision").asLong() < 1
                    || !current.mappingKey().equals(mapped.path("mappingKey").asText())
                    || !actor.tenantId().equals(mapped.path("subject").path("tenantId").asText())
                    || !actor.principalId().equals(mapped.path("subject").path("principalId").asText()))
                throw new BeforeDispatchFailure("IDENTITY_MAPPING_MISMATCH");
            SubjectSnapshot stored;
            try { stored = cn.agentplatform.common.Json.mapper().convertValue(mapped.path("subject"), SubjectSnapshot.class); }
            catch (IllegalArgumentException malformed) { throw new BeforeDispatchFailure("IDENTITY_MAPPING_MISMATCH"); }
            if (!current.equals(stored) || !stored.mappingKey().equals(mapped.path("mappingKey").asText()))
                throw new BeforeDispatchFailure("IDENTITY_MAPPING_MISMATCH");
            return SkillRules.identityHeaders(contract, current);
        } catch (BeforeDispatchFailure blocked) {
            throw blocked;
        } catch (PlatformException blocked) {
            throw new BeforeDispatchFailure(blocked.code().startsWith("IDENTITY_MAPPING_")
                    ? blocked.code() : "IDENTITY_MAPPING_UNAVAILABLE");
        }
    }
}
