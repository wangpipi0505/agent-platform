package cn.agentplatform.common.authority;

import cn.agentplatform.common.PlatformException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;

public final class PlatformAccessClient {
    private final RestClient app;

    public PlatformAccessClient(@Value("${agent.services.app}") String origin) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(2));
        app = RestClient.builder().baseUrl(origin).requestFactory(factory).build();
    }

    public JsonNode session(ServiceTicket ticket, SubjectKey subject) {
        var access = read(PlatformBuildRoutes.SESSION, ticket, subject);
        if (access.path("roles").isArray() && access.path("capabilities").isArray()
                && access.path("pages").isArray()) return access;
        throw unavailable();
    }

    public JsonNode runtime(ServiceTicket ticket, SubjectKey subject) {
        return read(PortalRuntimeRoutes.ACCESS_PATH, ticket, subject).path("roleScopes");
    }

    private JsonNode read(String path, ServiceTicket ticket, SubjectKey subject) {
        try {
            var envelope = app.get().uri(path)
                    .headers(headers -> headers.setBearerAuth(ticket.value()))
                    .retrieve().body(JsonNode.class);
            if (envelope != null && "OK".equals(envelope.path("code").asText())) {
                var access = envelope.path("data");
                if (subject.tenantId().equals(access.path("tenantId").asText())
                        && subject.principalId().equals(access.path("principalId").asText())) return access;
            }
        } catch (RestClientException unavailable) {
            throw unavailable();
        }
        throw unavailable();
    }

    public void require(ServiceTicket ticket, SubjectKey subject, String capability) {
        requireCapability(session(ticket, subject), capability);
    }

    public void requireApplicationView(ServiceTicket ticket, SubjectKey subject, String appCode) {
        var access = session(ticket, subject);
        requireCapability(access, "agent.view");
        var scope = access.path("viewScope");
        if (!scope.path("all").isBoolean() || !scope.path("codes").isArray()) throw unavailable();
        if (!scope.path("all").booleanValue() && !contains(scope.path("codes"), appCode))
            throw new PlatformException(403, "PLATFORM_CAPABILITY_DENIED", "当前平台授权不允许访问此应用");
    }

    boolean isGlobalAdministrator(ServiceTicket ticket, SubjectKey subject, String capability) {
        var access = session(ticket, subject);
        requireCapability(access, capability);
        var roles = access.path("globalRoles");
        if (!roles.isArray()) throw unavailable();
        return contains(roles, "ADMIN");
    }

    private static void requireCapability(JsonNode access, String capability) {
        if (contains(access.path("capabilities"), capability)) return;
        throw new PlatformException(403, "PLATFORM_CAPABILITY_DENIED", "当前平台授权不允许此操作");
    }

    private static boolean contains(JsonNode values, String expected) {
        for (var value : values)
            if (value.isTextual() && expected.equals(value.textValue())) return true;
        return false;
    }

    private static PlatformException unavailable() {
        return new PlatformException(503, "PLATFORM_ACCESS_UNAVAILABLE", "平台授权暂不可用");
    }
}
