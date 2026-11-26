package cn.agentplatform.engine.infra;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.authority.CurrentAuthorityClient;
import cn.agentplatform.common.authority.PortalTaskRoutes;
import cn.agentplatform.common.authority.ServiceCall;
import cn.agentplatform.common.authority.VerifiedAuthority;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "agent.security.mode", havingValue = "iam")
public class PortalOwnedTaskAccess {
    private final JdbcTemplate db;
    private final CurrentAuthorityClient authority;
    private final RestClient app;

    public PortalOwnedTaskAccess(JdbcTemplate db, CurrentAuthorityClient authority, @Value("${agent.services.app}") String appUrl) {
        this.db = db;
        this.authority = authority;
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.app = RestClient.builder().baseUrl(appUrl).requestFactory(factory).build();
    }

    public record Access(long id, boolean reasonRequired, boolean canConfirm, boolean canExecute) {}

    public Access authorize(String publicId, VerifiedAuthority current) {
        var actor = current.actor();
        var rows = db.queryForList("""
                select t.id,t.app_code,t.principal_id,t.purpose,
                       exists(select 1 from engine.task_integration_scope s where s.task_id=t.id) as integration
                from engine.task t where t.tenant_id=? and t.public_id=?
                """, actor.tenantId(), publicId);
        if (rows.isEmpty()) throw new PlatformException(404, "TASK_NOT_FOUND", "任务不存在或无权读取");
        var row = rows.getFirst();
        if (!actor.principalId().equals(row.get("principal_id")) || Boolean.TRUE.equals(row.get("integration"))) {
            org.slf4j.LoggerFactory.getLogger("agent.portal.audit").warn(
                    "PORTAL_TASK_DENIED tenant={} subject={} task={} traceId={} reason=SUBJECT_MISMATCH",
                    actor.tenantId(), actor.principalId(), publicId, cn.agentplatform.common.TraceContextFilter.current());
            throw new PlatformException(403, "TASK_OWNER_DENIED", "当前主体无权操作此任务");
        }
        var root = current.call().resource();
        String permission = PortalTaskRoutes.permission(root);
        if (!"agent.view".equals(permission) && !"PLAN".equals(row.get("purpose")))
            throw new PlatformException(409, "TASK_NOT_PLAN", "此操作需要计划任务");
        // Only the engine selects the application, from the same owned record used for the operation.
        String code = (String) row.get("app_code");
        var ticket = authority.forward(current, new ServiceCall("app", PortalTaskRoutes.ACCESS_ACTION, root));
        JsonNode response;
        try {
            response = app.post().uri(PortalTaskRoutes.ACCESS_PATH)
                    .headers(h -> h.setBearerAuth(ticket.value())).body(Map.of("appCode", code))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 403)
                throw new PlatformException(403, "PLATFORM_CAPABILITY_DENIED", "当前平台授权不允许此操作");
            throw unavailable();
        } catch (RestClientException e) {
            throw unavailable();
        }
        if (response == null || !"OK".equals(response.path("code").asText())) throw unavailable();
        var decision = response.path("data");
        if (!code.equals(decision.path("appCode").textValue()) || !permission.equals(decision.path("permission").textValue())
                || !decision.path("reasonRequired").isBoolean() || !decision.path("canConfirm").isBoolean()
                || !decision.path("canExecute").isBoolean()) throw unavailable();
        return new Access(((Number) row.get("id")).longValue(), decision.path("reasonRequired").booleanValue(),
                decision.path("canConfirm").booleanValue(), decision.path("canExecute").booleanValue());
    }

    private static PlatformException unavailable() {
        return new PlatformException(503, "PLATFORM_ACCESS_UNAVAILABLE", "平台授权暂不可用");
    }
}
