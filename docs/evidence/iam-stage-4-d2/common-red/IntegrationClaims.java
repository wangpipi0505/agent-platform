package cn.agentplatform.common.authority;
import com.fasterxml.jackson.databind.JsonNode;
public final class IntegrationClaims {
    private IntegrationClaims(){}
    public static String principal(String biz,String user){return "";}
    public static String digest(JsonNode credential){return "";}
    public static boolean platformRole(String role){return false;}
}
