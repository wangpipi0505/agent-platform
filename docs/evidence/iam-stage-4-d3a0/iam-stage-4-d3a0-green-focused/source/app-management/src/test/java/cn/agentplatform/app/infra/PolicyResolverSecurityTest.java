package cn.agentplatform.app.infra;
import cn.agentplatform.app.api.IntegrationPolicyResolverController;
import cn.agentplatform.common.authority.*;
import com.nimbusds.jose.jwk.JWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import static org.assertj.core.api.Assertions.*;
class PolicyResolverSecurityTest {
    @Configuration(proxyBeanMethods=false) @EnableWebSecurity static class Web {}
    @Test void disabledRegistersNeitherPolicyChainNorController(){
        new WebApplicationContextRunner().withUserConfiguration(Web.class,PolicyResolverSecurity.class,IntegrationPolicyResolverController.class).withPropertyValues("agent.policy-resolver.enabled=false")
            .run(c->{assertThat(c).hasNotFailed();assertThat(c).doesNotHaveBean(PolicyRequestVerifier.class);assertThat(c).doesNotHaveBean(IntegrationPolicyResolverController.class);assertThat(c).doesNotHaveBean("policySecurity");});
    }
    @Test void enabledMissingPrivateAndUnknownPublicKeyConfigurationFailStartup()throws Exception{
        try(var f=new LoopbackAuthority()){
            for(String keys:java.util.List.of("","{}",new JWKSet(f.policySigningKey()).toString(false))){
                new WebApplicationContextRunner().withUserConfiguration(Web.class,PolicyResolverSecurity.class).withPropertyValues("agent.policy-resolver.enabled=true","agent.policy-resolver.issuer=independent-policy","agent.policy-resolver.public-jwks="+keys)
                    .run(c->{assertThat(c).hasFailed();assertThat(c.getStartupFailure()).hasRootCauseMessage("POLICY_PUBLIC_CONFIGURATION_REQUIRED");});
            }
        }
    }
}
