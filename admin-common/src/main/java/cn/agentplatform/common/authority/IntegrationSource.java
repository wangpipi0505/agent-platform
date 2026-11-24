package cn.agentplatform.common.authority;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** The credential-derived subject accepted by the integration source issuer. */
public final class IntegrationSource {
    public static final String OPERATION = "issue_integration";
    public static final String TYPE = "agent-integration+jwt";
    public static final String AUDIENCE = "agent-platform-embed";
    private static final Set<String> FIELDS = Set.of("tenant_id", "sub", "app_code", "biz_system_code",
            "user_id", "credential_id", "policy_digest", "roles", "attributes");
    // The current-decision assertion embeds the service ticket and each carries the subject ceiling.
    private static final int MAX_IDENTITY_BYTES = 2048;

    private IntegrationSource() {}

    public record Ticket(String value, Instant expiresAt) {
        @Override public String toString() { return "IntegrationSource.Ticket[redacted]"; }
    }

    public static Map<String, Object> claims(long id, String app, String biz, String user, String digest, Actor actor) {
        var roles = new TreeSet<>(actor.roles());
        roles.retainAll(IntegrationClaims.RUNTIME_ROLES);
        Map<String, Object> claims = Map.of("credential_id", id, "app_code", app, "biz_system_code", biz, "user_id", user,
                "policy_digest", digest, "tenant_id", actor.tenantId(), "sub", actor.principalId(),
                "roles", roles, "attributes", actor.attributes());
        if (tooLarge(Json.tree(claims)))
            throw new PlatformException(400, "CLAIMS_TOO_LARGE", "集成身份声明超过短票上限");
        return claims;
    }

    public static ServiceCall call(JsonNode claims) {
        if (claims == null || !claims.isObject() || claims.size() != FIELDS.size()
                || FIELDS.stream().anyMatch(field -> !claims.hasNonNull(field))
                || tooLarge(claims))
            throw AuthorityProtocol.denied();
        MetadataSelectors.code(AuthorityProtocol.text(claims, "app_code"));
        String biz = MetadataSelectors.code(AuthorityProtocol.text(claims, "biz_system_code"));
        String digest = AuthorityProtocol.text(claims, "policy_digest");
        AuthorityProtocol.hash(digest);
        String user = AuthorityProtocol.text(claims, "user_id");
        String tenant = AuthorityProtocol.text(claims, "tenant_id");
        if (user.length() > 128 || tenant.length() > 128 || !claims.path("credential_id").isIntegralNumber()
                || !claims.path("credential_id").canConvertToLong() || claims.path("credential_id").longValue() <= 0)
            throw AuthorityProtocol.denied();
        AuthorityProtocol.same(IntegrationClaims.principal(biz, user), AuthorityProtocol.text(claims, "sub"));
        var actor = AuthorityProtocol.actor(claims);
        if (!IntegrationClaims.RUNTIME_ROLES.containsAll(actor.roles())
                || !Set.of(biz).equals(actor.attributes().get("bizSystemCode"))
                || !Set.of(user).equals(actor.attributes().get("userId"))) throw AuthorityProtocol.denied();
        return new ServiceCall("gateway", "integration.source.issue",
                new ResourceBinding("integration-credential", claims.path("credential_id").asText(), digest));
    }

    static void requireMatches(JsonNode expected, JsonNode issued) {
        for (String field : FIELDS) {
            // Object key order is immaterial. JWT signing retains each array's wire order.
            AuthorityProtocol.same(Json.canonical(expected.get(field)), Json.canonical(issued.get(field)));
        }
    }

    public static JsonNode identity(JsonNode signed) {
        ObjectNode identity = Json.mapper().createObjectNode();
        for (String field : FIELDS) identity.set(field, signed.get(field));
        call(identity);
        return identity;
    }

    public static JsonNode credentialBinding(JsonNode identity) {
        ObjectNode binding = Json.mapper().createObjectNode();
        for (String field : FIELDS)
            if (!"roles".equals(field) && !"attributes".equals(field)) binding.set(field, identity.get(field));
        return binding;
    }

    public static JsonNode decisionIdentity(JsonNode credentialBinding, JsonNode serviceTicket) {
        if (!(credentialBinding instanceof ObjectNode binding)) throw AuthorityProtocol.denied();
        ObjectNode identity = binding.deepCopy();
        for (String field : Set.of("roles", "attributes")) identity.set(field, serviceTicket.get(field));
        call(identity);
        AuthorityProtocol.same(identity.path("tenant_id"), serviceTicket.path("tenant_id"));
        AuthorityProtocol.same(identity.path("sub"), serviceTicket.path("sub"));
        return identity;
    }

    private static boolean tooLarge(JsonNode claims) {
        return Json.canonical(claims).getBytes(StandardCharsets.UTF_8).length > MAX_IDENTITY_BYTES;
    }
}
