package cn.agentplatform.model.infra;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import cn.agentplatform.common.SecurityConfiguration;
import cn.agentplatform.common.authority.AuthorityProperties;
import cn.agentplatform.common.authority.CurrentAuthorityClient;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient;
import cn.agentplatform.common.authority.ServiceAuthorization;
import cn.agentplatform.common.authority.ServiceRoutePolicy;
import cn.agentplatform.common.authority.ServiceTrustChain;
import cn.agentplatform.common.authority.WorkloadCredentialResolver;

/**
 * EX-43 phase 2 group B: model inbound service trust chain (iam only).
 *
 * <p>Same layout as the group A services: chain 1 covers {@code /internal/**} at
 * {@link ServiceTrustChain#ORDER} and authenticates the service trust domain; chain 2 is the scanned
 * application chain. dev-hmac keeps the single legacy chain untouched, so rollback is one property.
 *
 * <p>The decoder and the startup gate are shared beans and are not redeclared here: a second decoding
 * policy would let the trust domain drift, and re-registering the deployment would hide configuration
 * errors behind the first successful registration.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent.security.mode", havingValue = "iam")
public class ServiceTrustChainConfiguration implements WebMvcConfigurer {

    /** This deployment's catalog identity; must match {@code agent.security.service-id}. */
    static final String SERVICE_ID = "model";

    private final ObjectProvider<ServiceAuthorization> receiver;

    public ServiceTrustChainConfiguration(ObjectProvider<ServiceAuthorization> receiver) {
        this.receiver = receiver;
    }

    /** MVC registration keeps the actuator and {@code /error} dispatches outside the guard. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(receiver.getObject().mappedInterceptor());
    }

    @Bean
    ServiceAuthorization serviceAuthorization(AuthorityProperties deployment,
            ObjectProvider<CurrentAuthorityClient> authority) {
        if (!SERVICE_ID.equals(deployment.serviceId())) {
            throw new IllegalStateException(
                    "IAM_SERVICE_ID_MISMATCH: expected " + SERVICE_ID + " but deployment is " + deployment.serviceId());
        }
        return new ServiceAuthorization(SERVICE_ID, new ServiceRoutePolicy(), authority.getObject());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(CurrentAuthorityClient.class)
    HttpCurrentAuthorityClient serviceCurrentAuthorityClient(AuthorityProperties deployment, Environment environment,
            ObjectProvider<WorkloadCredentialResolver> credentials, ObjectProvider<Clock> clocks) {
        return new HttpCurrentAuthorityClient(deployment, clocks.getIfAvailable(Clock::systemUTC),
                credentials.getIfAvailable(() -> new WorkloadCredentialResolver(deployment,
                        environment.acceptsProfiles(Profiles.of("test")))),
                environment.acceptsProfiles(Profiles.of("test")));
    }

    @Bean
    ServiceTrustChain serviceTrustChain(HttpSecurity http, JwtDecoder jwtDecoder,
            ServiceAuthorization serviceAuthorization) throws Exception {
        return SecurityConfiguration.serviceTrustChain(http, jwtDecoder, serviceAuthorization);
    }

    @Bean(destroyMethod = "close")
    cn.agentplatform.model.application.InitialModelInputVerifier initialModelInputVerifier(
            HttpCurrentAuthorityClient authority, Environment environment,
            @org.springframework.beans.factory.annotation.Value("${agent.services.engine:http://127.0.0.1:18082}") String engine) {
        return new cn.agentplatform.model.application.InitialModelInputVerifier(authority, java.net.URI.create(engine),
                environment.acceptsProfiles(Profiles.of("test")));
    }
}
