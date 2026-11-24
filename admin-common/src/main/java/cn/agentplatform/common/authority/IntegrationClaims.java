package cn.agentplatform.common.authority;
import cn.agentplatform.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
/** Pure legacy integration rules shared by gateway issuance and the PG policy resolver. */
public final class IntegrationClaims {
    private static final Set<String> PLATFORM_ROLES=Set.of("ADMIN","EDITOR","SERVICE","GATEWAY_AUTH");
    public static final Set<String> RUNTIME_ROLES = Set.of("AGENT_VIEWER", "AGENT_OPERATOR", "AGENT_ADMIN");
    /** 短票的环境维度：写入 Actor attributes，上限来自 claimLimits.attributes.environment（已入 digest）。 */
    public static final String ENVIRONMENT_ATTRIBUTE="environment";
    private IntegrationClaims(){}
    public static String principal(String biz,String user){String value=biz+":"+user;return value.length()<=64?value:"ext_"+Digest.sha256(biz+"\u0000"+user).substring(0,60);}
    public static String digest(JsonNode credential) {
        var policy = new LinkedHashMap<>(Map.of("secretRef", credential.path("secretRef"),
                "claimLimits", credential.path("claimLimits"), "allowedOrigins", credential.path("allowedOrigins")));
        var mapping = credential.path("roleMapping");
        if (mapping.isObject() && !mapping.isEmpty()) policy.put("roleMapping", mapping);
        return Digest.sha256(Json.canonical(policy));
    }

    /** Input is the credential's allowed business roles, optionally intersected with a declaration. */
    public static Set<String> runtimeRoles(Set<String> allowedRoles, JsonNode mapping) {
        var result = new TreeSet<String>();
        boolean legacy = mapping.isMissingNode() || mapping.isObject() && mapping.isEmpty();
        for (String role : allowedRoles) {
            if ("AGENT_BUILDER".equalsIgnoreCase(role)) {
                result.add("AGENT_VIEWER");
            } else if (legacy) {
                if (!platformRole(role)) result.add(role);
            } else {
                var mapped = mapping.path(role);
                if (mapped.isTextual() && RUNTIME_ROLES.contains(mapped.textValue())) result.add(mapped.textValue());
            }
        }
        return Set.copyOf(result);
    }
    public static boolean platformRole(String role){return PLATFORM_ROLES.contains(role.toUpperCase(Locale.ROOT));}
}
