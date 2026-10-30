package cn.agentplatform.common.authority;

import cn.agentplatform.authority.AuthorityTestSupport;
import cn.agentplatform.authority.sign.KeyRing;
import cn.agentplatform.common.Actor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 造一张"上游已经拿到手的 delegated 服务票据"，作为 EX-42 两跳的起点。
 *
 * <p>为什么不从 {@code operation=session} 现取一张 portal 令牌：M2 的 input 是**已经判过权、已经携带
 * grant/operation/ceiling 的 delegated 票**（平台 {@code ServiceAuthorization} 拿到的就是这张），
 * 从它出发才能覆盖 {@code authorize → forward} 两跳。票面按 {@code TrustedJwtDecoder} +
 * {@code verifiedTicket} 的逐项要求拼，{@code ceiling_sha256} 直接用平台自己的
 * {@link AuthorityProtocol#ceiling(Actor)} 计算——测试不自己发明一份"看起来像"的摘要。
 */
public final class DelegatedTickets {

    public static final String GRANT_REF = "grant-1";
    public static final String AUTHORIZATION_VERSION = "delegated-v1";
    public static final String TENANT_ID = "tenant-a";
    public static final String SUBJECT = "alice";
    public static final String OPERATION_ID = "op-bootstrap";
    public static final Set<String> ROLES = Set.of("EDITOR");
    public static final Map<String, Set<String>> ATTRIBUTES =
            Map.of("region", Set.of("A"), "dataLabels", Set.of("confidential"));

    private DelegatedTickets() {
    }

    public static Actor actor() {
        return new Actor(TENANT_ID, SUBJECT, ROLES, ATTRIBUTES);
    }

    public static String bootstrap(KeyRing keys, ServiceCall call, String upstream) {
        return bootstrap(keys, call, upstream, Instant.now().getEpochSecond(), 60);
    }

    public static String bootstrap(KeyRing keys, ServiceCall call, String upstream, long iat, long ttl) {
        Actor actor = actor();
        long expires = iat + ttl;
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("kind", call.resource().kind());
        resource.put("id", call.resource().id());
        resource.put("version_digest", call.resource().versionDigest());

        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operation_id", OPERATION_ID);
        operation.put("source_kind", "PORTAL");
        operation.put("origin_kind", "PORTAL");
        operation.put("issued_at", iat);
        operation.put("expires_at", expires);
        operation.put("grant_ref", GRANT_REF);
        operation.put("tenant_id", actor.tenantId());
        operation.put("sub", actor.principalId());
        operation.put("resource", resource);
        operation.put("ceiling_sha256", AuthorityProtocol.ceiling(actor));

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", AuthorityTestSupport.ISSUER);
        claims.put("aud", "urn:agent-platform:service:" + call.target());
        claims.put("token_use", "service");
        claims.put("iat", iat);
        claims.put("nbf", iat);
        claims.put("exp", expires);
        claims.put("jti", "tkt-bootstrap");
        claims.put("target", call.target());
        claims.put("action", call.action());
        claims.put("resource", resource);
        claims.put("scope", call.action());
        claims.put("caller", upstream);
        claims.put("client_id", upstream);
        claims.put("tenant_id", actor.tenantId());
        claims.put("sub", actor.principalId());
        claims.put("roles", List.copyOf(actor.roles()));
        claims.put("attributes", actorAttributes());
        claims.put("authority_mode", "delegated");
        claims.put("grant_ref", GRANT_REF);
        claims.put("grant_expires_at", expires);
        claims.put("authorization_version", AUTHORIZATION_VERSION);
        claims.put("initial_ceiling", actorClaims());
        claims.put("operation", operation);
        claims.put("subject_caller", upstream);
        return keys.sign("at+jwt", claims);
    }

    /** Actor 形状的 initial_ceiling：客户端用 {@code AuthorityProtocol.actor(...)} 反序列化它。 */
    public static Map<String, Object> actorClaims() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("tenant_id", TENANT_ID);
        claims.put("sub", SUBJECT);
        claims.put("roles", List.copyOf(ROLES));
        claims.put("attributes", actorAttributes());
        return claims;
    }

    private static Map<String, Object> actorAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("region", List.copyOf(ATTRIBUTES.get("region")));
        attributes.put("dataLabels", List.copyOf(ATTRIBUTES.get("dataLabels")));
        return attributes;
    }
}
