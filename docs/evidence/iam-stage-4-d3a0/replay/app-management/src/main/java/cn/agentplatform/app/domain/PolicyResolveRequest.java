package cn.agentplatform.app.domain;
import cn.agentplatform.common.*;
import java.util.*;
/** Strict internal v1 DTO. Coercion and unknown fields are forbidden at the trust boundary. */
public record PolicyResolveRequest(int schemaVersion,long credentialId,String expectedTenantId,
    String expectedAppCode,String expectedBizSystemCode,String userId,String expectedPrincipalId,
    String expectedPolicyVersion,String nonce) {
    public PolicyResolveRequest {
        if(schemaVersion!=1||credentialId<1)throw invalid();
        text(expectedTenantId,128);text(expectedAppCode,64);text(expectedBizSystemCode,64);
        text(userId,128);text(expectedPrincipalId,64);text(nonce,128);
        if(expectedPolicyVersion!=null&&!expectedPolicyVersion.matches("[1-9][0-9]{0,18}:[1-9][0-9]{0,18}"))throw invalid();
    }
    public static PolicyResolveRequest parse(byte[] bytes){
        var n=Json.read(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        var fields=Set.of("schemaVersion","credentialId","expectedTenantId","expectedAppCode","expectedBizSystemCode","userId","expectedPrincipalId","expectedPolicyVersion","nonce");
        if(!n.isObject()||n.size()!=fields.size())throw invalid();
        n.fieldNames().forEachRemaining(k->{if(!fields.contains(k))throw invalid();});
        if(!n.path("schemaVersion").isIntegralNumber()||!n.path("schemaVersion").canConvertToInt()||!n.path("credentialId").isIntegralNumber()||!n.path("credentialId").canConvertToLong())throw invalid();
        for(String k:fields)if(!Set.of("schemaVersion","credentialId","expectedPolicyVersion").contains(k)&&!n.path(k).isTextual())throw invalid();
        if(!n.has("expectedPolicyVersion")||!(n.get("expectedPolicyVersion").isNull()||n.get("expectedPolicyVersion").isTextual()))throw invalid();
        return new PolicyResolveRequest(n.get("schemaVersion").intValue(),n.get("credentialId").longValue(),n.get("expectedTenantId").textValue(),n.get("expectedAppCode").textValue(),n.get("expectedBizSystemCode").textValue(),n.get("userId").textValue(),n.get("expectedPrincipalId").textValue(),n.get("expectedPolicyVersion").isNull()?null:n.get("expectedPolicyVersion").textValue(),n.get("nonce").textValue());
    }
    private static void text(String value,int max){if(value==null||value.isBlank()||value.length()>max||value.codePoints().anyMatch(Character::isISOControl))throw invalid();}
    private static PlatformException invalid(){return new PlatformException(400,"POLICY_REQUEST_INVALID","策略查询字段无效");}
}
