package cn.agentplatform.common.authority;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;
import java.util.*;

/** Validated deployment input only; never populated from an HTTP identity header or token. */
@ConfigurationProperties(prefix = "agent.security", ignoreUnknownFields = false)
public record AuthorityProperties(
        String mode, String serviceId, Portal portal, Authority authority, Integration integration,
        String jwtSecret, String issuer, Boolean allowEmbed, List<String> corsOrigins) {
    public static final Set<String> SERVICES = Set.of("gateway", "app", "engine", "skill", "model", "monitor");

    public record Portal(boolean enabled, String issuer, String jwksUri, String audience,
                         String clientId, Set<String> roleAllowlist, Set<String> attributeAllowlist,
                         Map<String, Set<String>> attributeValueAllowlist, Set<String> scopeAllowlist) {}
    public record Integration(boolean enabled, String keySecretRef) {}
    public record Authority(
            String issuer, String jwksUri, String tokenUri, String decisionUri,
            String clientId, String clientSecretRef, String allowedAlgorithm, String audience,
            @DefaultValue("60") int maxTicketSeconds, @DefaultValue("5") int maxDecisionSeconds,
            @DefaultValue("1000") int connectTimeoutMs, @DefaultValue("2000") int readTimeoutMs) {}

    public void validate() {
        if (mode == null || mode.isBlank()) throw invalid("SECURITY_MODE_REQUIRED");
        if (!Set.of("dev-hmac", "test", "iam").contains(mode)) throw invalid("SECURITY_MODE_INVALID");
        if (!SERVICES.contains(Objects.toString(serviceId, ""))) throw invalid("SERVICE_ID_INVALID");
        if (!"gateway".equals(serviceId)
                && ((portal != null && portal.enabled()) || (integration != null && integration.enabled())
                    || Boolean.TRUE.equals(allowEmbed)))
            throw invalid("PORTAL_SERVICE_FORBIDDEN");
        if (!"iam".equals(mode)) return;
        if (authority == null) throw invalid("IAM_CONFIG_REQUIRED");
        var a = authority;
        for (String required : Arrays.asList(a.issuer(), a.jwksUri(), a.tokenUri(), a.decisionUri(),
                a.clientId(), a.clientSecretRef(), a.allowedAlgorithm(), a.audience()))
            if (required == null || required.isBlank()) throw invalid("IAM_CONFIG_REQUIRED");
        if (!serviceId.equals(a.clientId())) throw invalid("IAM_CLIENT_SERVICE_MISMATCH");
        if (!a.clientSecretRef().equals("env:AGENT_SECRET_IAM_" + serviceId.toUpperCase(Locale.ROOT) + "_CLIENT"))
            throw invalid("IAM_SECRET_REF_INVALID");
        if (!"RS256".equals(a.allowedAlgorithm())) throw invalid("IAM_ALGORITHM_INVALID");
        if (!("urn:agent-platform:service:" + serviceId).equals(a.audience())) throw invalid("IAM_AUDIENCE_INVALID");
        for (String uri : List.of(a.issuer(), a.jwksUri(), a.tokenUri(), a.decisionUri())) requireHttps(uri);
        if (a.maxTicketSeconds() < 1 || a.maxTicketSeconds() > 60
                || a.maxDecisionSeconds() < 1 || a.maxDecisionSeconds() > 5
                || a.connectTimeoutMs() < 1 || a.connectTimeoutMs() > 1000
                || a.readTimeoutMs() < 1 || a.readTimeoutMs() > 2000) throw invalid("IAM_BUDGET_INVALID");
        if (portal != null && portal.enabled()) {
            requireHttps(portal.issuer()); requireHttps(portal.jwksUri());
            if (portal.audience() == null || portal.audience().isBlank() || portal.clientId() == null
                    || portal.clientId().isBlank() || portal.roleAllowlist() == null || portal.roleAllowlist().isEmpty()
                    || portal.attributeAllowlist() == null || portal.attributeAllowlist().isEmpty())
                throw invalid("PORTAL_CONFIG_REQUIRED");
        }
        if (integration != null && integration.enabled()
                && (integration.keySecretRef() == null || integration.keySecretRef().isBlank()))
            throw invalid("INTEGRATION_CONFIG_REQUIRED");
    }

    public void validateDeployment(boolean devAuth, String address, boolean testProfile) {
        validate();
        if ("iam".equals(mode)) {
            if (devAuth) throw invalid("IAM_DEV_AUTH_FORBIDDEN");
            return;
        }
        if ("dev-hmac".equals(mode) && !devAuth) throw invalid("DEV_AUTH_REQUIRED");
        if ("test".equals(mode) && !testProfile) throw invalid("TEST_PROFILE_REQUIRED");
        // A fixed bind address is checked before server construction, never a request Host header.
        if (!Set.of("127.0.0.1", "::1", "[::1]").contains(Objects.toString(address, "")))
            throw invalid("LOOPBACK_BIND_REQUIRED");
    }

    private static void requireHttps(String value) {
        try {
            var uri = URI.create(Objects.requireNonNull(value));
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getRawQuery() != null) throw invalid("IAM_URI_INVALID");
        } catch (IllegalArgumentException | NullPointerException ex) { throw invalid("IAM_URI_INVALID"); }
    }

    private static IllegalStateException invalid(String reason) { return new IllegalStateException(reason); }

    @Override public String toString() { return "AuthorityProperties[mode=" + mode + ",serviceId=" + serviceId + "]"; }
}
