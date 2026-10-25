package cn.agentplatform.common.authority;
import cn.agentplatform.common.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class IntegrationClaimsTest {
    @Test void shortLongPrincipalAndCanonicalDigestRetainIssuedTicketContract(){
        assertThat(IntegrationClaims.principal("biz","alice")).isEqualTo("biz:alice");
        String user="u".repeat(90);assertThat(IntegrationClaims.principal("biz",user)).isEqualTo("ext_"+Digest.sha256("biz\u0000"+user).substring(0,60));
        var c=Json.tree(Map.of("secretRef","test-ref","claimLimits",Map.of("roles",List.of("reader")),"allowedOrigins",List.of("https://example.invalid")));
        assertThat(IntegrationClaims.digest(c)).isEqualTo(Digest.sha256(Json.canonical(Map.of("secretRef",c.path("secretRef"),"claimLimits",c.path("claimLimits"),"allowedOrigins",c.path("allowedOrigins")))));
        for(String role:List.of("ADMIN","admin","EDITOR","SERVICE","GATEWAY_AUTH"))assertThat(IntegrationClaims.platformRole(role)).isTrue();
        assertThat(IntegrationClaims.platformRole("reader")).isFalse();
    }
}
