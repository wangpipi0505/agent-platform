package cn.agentplatform.skill.infra;

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
 * EX-43 phase 2 group A: skill-registry inbound service trust chain (iam only).
 *
 * <p>Only {@code agent.security.mode=iam} takes this path. dev-hmac keeps the single legacy chain byte
 * for byte, so the migration is reversible with one property change and no code rollback.
 *
 * <p>Chain layout is fixed by the shared configuration, never by bean declaration order:
 *
 * <ol>
 *   <li>chain 1 = {@link SecurityConfiguration#serviceTrustChain} scoped to {@code /internal/**} and
 *       ordered at {@link ServiceTrustChain#ORDER}; it authenticates the service trust domain
 *       ({@code token_use=service}, RS256, {@code at+jwt});
 *   <li>chain 2 = the scanned {@link SecurityConfiguration#security} chain for everything else; in iam
 *       it hides {@code /auth/dev-token} behind 404 and denies {@code /internal/**} outright, so no
 *       request can fall through from one chain into the other.
 * </ol>
 *
 * <p>The decoder is the shared bean and is not redeclared here: {@code SecurityConfiguration.jwtDecoder}
 * already returns {@code AuthorityDecoders.iam(...)} in iam and the legacy HS256 decoder otherwise. That
 * keeps this service free of a second decoding policy that could drift from the trust domain.
 *
 * <p>The startup gate is also not re-implemented here. {@code SecurityConfiguration.authorityModeGuard}
 * already binds and validates {@link AuthorityProperties}, runs
 * {@code IamStartupGate.requireLocalClientSecret} and registers the deployment exactly once. Re-running
 * either step in the service would double-register {@code ServiceTokens} and hide configuration errors.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent.security.mode", havingValue = "iam")
public class ServiceTrustChainConfiguration implements WebMvcConfigurer {

    /** This deployment's catalog identity; must match {@code agent.security.service-id}. */
    static final String SERVICE_ID = "skill";

    private final ObjectProvider<ServiceAuthorization> receiver;

    /**
     * The receiver is looked up lazily: this class is also the MVC configurer, so resolving the guard in
     * the constructor would make the guard's own {@code @Bean} method depend on the configuration
     * instance that is still being created (a circular reference at startup).
     */
    public ServiceTrustChainConfiguration(ObjectProvider<ServiceAuthorization> receiver) {
        this.receiver = receiver;
    }

    /**
     * The guard must be visible as an {@code adaptedInterceptor} of the MVC handler mapping, otherwise
     * {@code RouteCoverageVerifier} fails the deployment with {@code ROUTE_GUARD_MISSING}. Registering it
     * through MVC (instead of as a {@code MappedInterceptor} bean) deliberately leaves the actuator
     * handler mapping and the {@code /error} dispatch outside the guard: health stays reachable in iam
     * and an error response is not rewritten into a 403 by a guard that never saw a service ticket.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(receiver.getObject().mappedInterceptor());
    }

    /**
     * The catalog-driven guard. It sits after handler selection, so caller/action/resource cannot be
     * spoofed with headers: the ticket claims must equal the catalog row and the retained decision.
     */
    @Bean
    ServiceAuthorization serviceAuthorization(AuthorityProperties deployment,
            ObjectProvider<CurrentAuthorityClient> authority) {
        if (!SERVICE_ID.equals(deployment.serviceId())) {
            throw new IllegalStateException(
                    "IAM_SERVICE_ID_MISMATCH: expected " + SERVICE_ID + " but deployment is " + deployment.serviceId());
        }
        return new ServiceAuthorization(SERVICE_ID, new ServiceRoutePolicy(), authority.getObject());
    }

    /**
     * Only the reference name is configured ({@code env:AGENT_SECRET_IAM_SKILL_CLIENT}); the secret stays in
     * the deployment environment. A host that already publishes a client (skill's AUTHORITY ticket mode)
     * keeps it, so there is exactly one credential holder per service.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(CurrentAuthorityClient.class)
    HttpCurrentAuthorityClient serviceCurrentAuthorityClient(AuthorityProperties deployment, Environment environment,
            ObjectProvider<WorkloadCredentialResolver> credentials, ObjectProvider<Clock> clocks) {
        return new HttpCurrentAuthorityClient(deployment, clocks.getIfAvailable(Clock::systemUTC),
                credentials.getIfAvailable(() -> new WorkloadCredentialResolver(deployment)),
                environment.acceptsProfiles(Profiles.of("test")));
    }

    /** Chain 1, ordered ahead of the application chain by the shared {@link ServiceTrustChain} wrapper. */
    @Bean
    ServiceTrustChain serviceTrustChain(HttpSecurity http, JwtDecoder jwtDecoder, ServiceAuthorization serviceAuthorization)
            throws Exception {
        return SecurityConfiguration.serviceTrustChain(http, jwtDecoder, serviceAuthorization);
    }
}
