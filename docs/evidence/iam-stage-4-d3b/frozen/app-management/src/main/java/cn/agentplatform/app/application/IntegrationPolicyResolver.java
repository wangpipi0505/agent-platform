package cn.agentplatform.app.application;
import cn.agentplatform.app.domain.*;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.IntegrationClaims;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service
public class IntegrationPolicyResolver {
    private final JdbcTemplate db;
    public IntegrationPolicyResolver(JdbcTemplate db){this.db=db;}
    public PolicyResolveResult resolve(PolicyResolveRequest request){
        String principal=IntegrationClaims.principal(request.expectedBizSystemCode(),request.userId());
        if(!principal.equals(request.expectedPrincipalId()))throw new PlatformException(403,"POLICY_SUBJECT_MISMATCH","策略主体不匹配");
        // One parameterized PostgreSQL statement observes status, revision and ceilings in one
        // MVCC snapshot. No Actor, SecretVault, credential HTTP endpoint or authority dependency.
        var rows=db.query("select /* credentialId */ id,tenant_id,app_code,biz_system_code,status,secret_ref,claim_limits,allowed_origins,policy_revision from app.integration_credential where id=? and tenant_id=? and app_code=? and biz_system_code=?",
            (r,n)->{
                String version=r.getLong("id")+":"+r.getLong("policy_revision");
                if(request.expectedPolicyVersion()!=null&&!request.expectedPolicyVersion().equals(version))throw new PlatformException(409,"POLICY_VERSION_CHANGED","策略版本已变更");
                JsonNode limits=Json.read(r.getString("claim_limits")),origins=Json.read(r.getString("allowed_origins"));
                boolean subject=strings(limits.path("principalIds")).contains(request.userId());
                var roles=strings(limits.path("roles"));roles.removeIf(IntegrationClaims::platformRole);
                var attrs=new TreeMap<String,Set<String>>();limits.path("attributes").fields().forEachRemaining(e->{if(!Set.of("dataLabels","bizSystemCode","userId").contains(e.getKey()))attrs.put(e.getKey(),strings(e.getValue()));});
                attrs.put("bizSystemCode",Set.of(r.getString("biz_system_code")));attrs.put("userId",Set.of(request.userId()));
                String digest=IntegrationClaims.digest(Json.tree(Map.of("secretRef",r.getString("secret_ref"),"claimLimits",limits,"allowedOrigins",origins)));
                return new PolicyResolveResult(1,r.getLong("id"),r.getString("tenant_id"),r.getString("app_code"),r.getString("biz_system_code"),request.userId(),principal,"ACTIVE".equals(r.getString("status")),subject,subject?Set.copyOf(roles):Set.of(),subject?Map.copyOf(attrs):Map.of(),strings(limits.path("attributes").path("dataLabels")),strings(origins),version,digest,request.nonce());
            },request.credentialId(),request.expectedTenantId(),request.expectedAppCode(),request.expectedBizSystemCode());
        if(rows.isEmpty())throw new PlatformException(404,"POLICY_NOT_FOUND","策略不存在");return rows.getFirst();
    }
    private static Set<String> strings(JsonNode node){var out=new TreeSet<String>();if(node.isArray())node.forEach(v->{if(v.isTextual())out.add(v.textValue());});return out;}
}
