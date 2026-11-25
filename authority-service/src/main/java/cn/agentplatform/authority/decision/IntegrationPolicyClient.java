package cn.agentplatform.authority.decision;

import cn.agentplatform.authority.AuthorityException;
import cn.agentplatform.authority.StrictJson;
import cn.agentplatform.authority.sign.KeyRing;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.authority.PolicyRequestVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Reads the live credential ceiling through app-management's independent policy domain. */
@Component
public final class IntegrationPolicyClient {
    private static final int MAX_RESPONSE_BYTES = 49152;
    private final URI endpoint;
    private final String issuer;
    private final KeyRing signer;
    private final RestClient app;
    private final Clock clock;

    public IntegrationPolicyClient(
            @Value("${agent.authority.integration-policy.url:}") String url,
            @Value("${agent.authority.integration-policy.issuer:}") String issuer,
            @Value("${agent.authority.integration-policy.key-path:}") String keyPath,
            @Value("${agent.authority.integration-policy.kid:}") String kid,
            Environment environment,
            Clock clock) {
        this.clock = clock;
        this.issuer = issuer;
        if (url.isBlank()) {
            endpoint = null;
            signer = null;
            app = null;
            return;
        }
        endpoint = URI.create(url);
        boolean loopback = environment.acceptsProfiles(Profiles.of("test")) && "http".equals(endpoint.getScheme())
                && "127.0.0.1".equals(endpoint.getHost());
        if (issuer.isBlank() || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !PolicyRequestVerifier.PATH.equals(endpoint.getRawPath())
                || (!loopback && !"https".equals(endpoint.getScheme())))
            throw new IllegalArgumentException("INTEGRATION_POLICY_ENDPOINT_INVALID");
        signer = KeyRing.policySigner(kid, keyPath, clock);
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(2));
        app = RestClient.builder().requestFactory(factory).build();
    }

    public void require(JsonNode identity, String origin) {
        if (endpoint == null) throw unavailable();
        String expectedVersion = identity.path("policy_version").textValue();
        if (expectedVersion == null) throw denied();
        String nonce = UUID.randomUUID().toString();
        var request = new LinkedHashMap<String, Object>();
        request.put("schemaVersion", 1);
        request.put("credentialId", identity.path("credential_id").longValue());
        request.put("expectedTenantId", identity.path("tenant_id").textValue());
        request.put("expectedAppCode", identity.path("app_code").textValue());
        request.put("expectedBizSystemCode", identity.path("biz_system_code").textValue());
        request.put("userId", identity.path("user_id").textValue());
        request.put("expectedPrincipalId", identity.path("sub").textValue());
        request.put("expectedPolicyVersion", expectedVersion);
        request.put("nonce", nonce);

        var json = Json.write(request);
        var body = json.getBytes(StandardCharsets.UTF_8);
        long now = clock.instant().getEpochSecond();
        var proof = signer.sign("agent-policy-request+jwt", Map.ofEntries(
                Map.entry("iss", issuer), Map.entry("aud", "app-policy-resolver"),
                Map.entry("token_use", "policy-query"), Map.entry("iat", now), Map.entry("nbf", now),
                Map.entry("exp", now + 15), Map.entry("jti", UUID.randomUUID().toString()),
                Map.entry("sub", "iam-policy-resolver"), Map.entry("caller", "iam-policy-resolver"),
                Map.entry("target", "app-policy-resolver"), Map.entry("action", "integration.policy.resolve"),
                Map.entry("bodySha256", Digest.sha256(json)), Map.entry("nonce", nonce)));
        JsonNode response;
        try {
            response = app.post().uri(endpoint).contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(proof)).body(body)
                    .exchange((query, result) -> {
                        var status = result.getStatusCode();
                        if (Set.of(403, 404, 409).contains(status.value())) throw denied();
                        if (!status.is2xxSuccessful()) throw unavailable();
                        byte[] bytes = result.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                        if (bytes.length > MAX_RESPONSE_BYTES) throw unavailable();
                        return StrictJson.read(bytes);
                    });
        } catch (RestClientException error) {
            throw unavailable();
        }
        if (response == null || !response.isObject() || !response.path("schemaVersion").isIntegralNumber()
                || response.path("schemaVersion").intValue() != 1 || !nonce.equals(response.path("nonce").textValue())
                || !response.path("credentialId").isIntegralNumber()
                || response.path("credentialId").longValue() != identity.path("credential_id").longValue())
            throw unavailable();
        for (var field : Map.of("tenantId", "tenant_id", "appCode", "app_code", "bizSystemCode", "biz_system_code",
                "userId", "user_id", "principalId", "sub", "policyDigest", "policy_digest").entrySet()) {
            if (!identity.path(field.getValue()).equals(response.path(field.getKey()))) throw denied();
        }

        if (!expectedVersion.equals(response.path("policyVersion").textValue())
                || !response.path("active").isBoolean() || !response.path("active").booleanValue()
                || !response.path("subjectAllowed").isBoolean() || !response.path("subjectAllowed").booleanValue()
                || !strings(response.path("roleCeiling")).containsAll(strings(identity.path("roles")))
                || (origin != null && !strings(response.path("allowedOrigins")).contains(origin))) throw denied();
        var attributeCeiling = response.path("attributeCeiling");
        if (!attributeCeiling.isObject()) throw unavailable();
        identity.path("attributes").fields().forEachRemaining(field -> {
            var ceiling = "dataLabels".equals(field.getKey())
                    ? response.path("dataLabels") : attributeCeiling.path(field.getKey());
            if (!strings(ceiling).containsAll(strings(field.getValue()))) throw denied();
        });
    }

    private static Set<String> strings(JsonNode array) {
        if (!array.isArray()) throw unavailable();
        var result = new HashSet<String>();
        for (var value : array) {
            if (!value.isTextual() || !result.add(value.textValue())) throw unavailable();
        }
        return result;
    }

    private static AuthorityException denied() {
        return AuthorityException.denied("INTEGRATION_POLICY_REJECTED");
    }

    private static AuthorityException unavailable() {
        return new AuthorityException(503, "INTEGRATION_IDENTITY_PROVIDER_UNAVAILABLE",
                "集成策略暂不可用，未执行任何业务操作", "INTEGRATION_POLICY_UNAVAILABLE");
    }
}
