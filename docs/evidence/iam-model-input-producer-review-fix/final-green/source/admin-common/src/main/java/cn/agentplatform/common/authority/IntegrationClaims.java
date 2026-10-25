package cn.agentplatform.common.authority;
import cn.agentplatform.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
/** Pure legacy integration rules shared by gateway issuance and the PG policy resolver. */
public final class IntegrationClaims {
    private static final Set<String> PLATFORM_ROLES=Set.of("ADMIN","EDITOR","SERVICE","GATEWAY_AUTH");
    private IntegrationClaims(){}
    public static String principal(String biz,String user){String value=biz+":"+user;return value.length()<=64?value:"ext_"+Digest.sha256(biz+"\u0000"+user).substring(0,60);}
    public static String digest(JsonNode credential){return Digest.sha256(Json.canonical(Map.of("secretRef",credential.path("secretRef"),"claimLimits",credential.path("claimLimits"),"allowedOrigins",credential.path("allowedOrigins"))));}
    public static boolean platformRole(String role){return PLATFORM_ROLES.contains(role.toUpperCase(Locale.ROOT));}
}
