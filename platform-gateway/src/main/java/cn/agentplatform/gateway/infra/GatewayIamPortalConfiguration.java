package cn.agentplatform.gateway.infra;

import cn.agentplatform.common.SecurityConfiguration;
import cn.agentplatform.common.authority.AuthorityProperties;
import cn.agentplatform.common.authority.CurrentAuthorityClient;
import cn.agentplatform.common.authority.GatewayIngressBoundary;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient;
import cn.agentplatform.common.authority.ServiceAuthorization;
import cn.agentplatform.common.authority.ServiceRoutePolicy;
import cn.agentplatform.common.authority.ServiceTrustChain;
import cn.agentplatform.common.authority.TrustedJwtDecoder;
import cn.agentplatform.common.authority.WorkloadCredentialResolver;
import cn.agentplatform.gateway.api.GatewayController;
import cn.agentplatform.gateway.api.PortalSessionController;
import cn.agentplatform.gateway.application.RunEventsProxy;
import cn.agentplatform.gateway.application.RunStatusProxy;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.servlet.handler.MappedInterceptor;

import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;

/**
 * EX-43 §3.1 gateway assembly (phase 2, group B). Only iam deployments take this path; dev-hmac and
 * test keep their previous wiring untouched, so rollback is a single property change.
 *
 * <p>Chain layout is fixed, not left to bean declaration order:
 *
 * <ol>
 *   <li>chain 1 = {@link SecurityConfiguration#serviceTrustChain} scoped to {@code /internal/**},
 *       ordered at {@link ServiceTrustChain#ORDER}. Gateway has no {@code /internal} handler today,
 *       so this chain authenticates the service trust domain and ends in a 404;
 *   <li>chain 2 = the scanned {@link SecurityConfiguration#security} chain for everything else; in
 *       iam it denies {@code /internal/**} outright and requires {@code platform.access} for the
 *       portal surface.
 * </ol>
 *
 * <p>The portal ingress filter is registered as a container filter ordered right after the security
 * chain instead of being spliced into chain 2: the shared chain is a scanned bean and must not be
 * rebuilt per service. Running after the security chain still puts it after authentication and
 * authorisation and before MVC, which is exactly the window where {@link GatewayIngressBoundary}
 * captures the source ticket for the controller claim.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent.security.mode", havingValue = "iam")
public class GatewayIamPortalConfiguration {
    @Bean(destroyMethod = "close")
    @org.springframework.context.annotation.Lazy
    cn.agentplatform.gateway.application.PublishedMetadataClient taskPublishedMetadata(
            CurrentAuthorityClient authority, Environment environment) {
        return new cn.agentplatform.gateway.application.PublishedMetadataClient(
                java.net.URI.create(environment.getRequiredProperty("agent.services.app")),
                authority, testProfile(environment));
    }

    /**
     * The deployment clock. Authority proof windows, ticket expiry and the ingress capture all read
     * the same instance, so a single bean keeps them consistent instead of three system clocks.
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock gatewayAuthorityClock() {
        return Clock.systemUTC();
    }

    /**
     * The authoritative decoder. It is {@code @Primary}: the scanned common configuration exposes its
     * own {@code JwtDecoder} for chain 2, and both are RS256 trust-domain decoders, so the portal-aware
     * one must win wherever a single {@code JwtDecoder} is injected.
     */
    @Bean(destroyMethod = "close")
    @Primary
    TrustedJwtDecoder gatewayTrustedDecoder(AuthorityProperties properties, Clock clock, Environment environment) {
        return new TrustedJwtDecoder(properties, clock, testProfile(environment));
    }

    /**
     * Only the reference name is configured ({@code env:AGENT_SECRET_IAM_GATEWAY_CLIENT}); the secret
     * itself stays in the deployment environment. The default resolver reads the process environment
     * and never caches the value into a bean property.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(CurrentAuthorityClient.class)
    CurrentAuthorityClient gatewayAuthorityClient(
            AuthorityProperties properties, Clock clock, Environment environment) {
        return new HttpCurrentAuthorityClient(
                properties, clock, new WorkloadCredentialResolver(properties, testProfile(environment)), testProfile(environment));
    }

    /**
     * Chain 1 receiver. It runs after MVC selected the real method, so a controller cannot be reached
     * by asserting caller, action or resource in a header; gateway exposes no guarded internal
     * capability yet, which is why the chain exists as a deny-by-default trust boundary only.
     */
    @Bean
    ServiceAuthorization gatewayServiceAuthorization(CurrentAuthorityClient authority) {
        return new ServiceAuthorization("gateway", new ServiceRoutePolicy(), authority);
    }

    /**
     * Chain 1 is matched by path in gateway code, not only by the shared {@code HttpSecurity}.
     *
     * <p>The {@code HttpSecurity} instance handed to two chain builders in one application context is
     * the same object: whichever chain builds first fixes the matcher, so a later
     * {@code securityMatcher("/internal/**")} is silently dropped and chain 1 ends up matching every
     * request. That failure mode is invisible until a browser ticket reaches {@code /admin/**} and gets
     * refused by the service-domain decoder, so the scope is re-asserted here and pinned by the test.
     * {@link ServiceTrustChain} stays the outer bean so the ordering guarantee (order -100) still comes
     * from the shared base instead of a local {@code @Order}.
     */
    static final class InternalSurfaceOnly implements SecurityFilterChain {
        private final SecurityFilterChain delegate;

        InternalSurfaceOnly(SecurityFilterChain delegate) {
            this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
        }

        /** {@code /internal/**} and nothing else: {@code /internal} itself is not a route. */
        static boolean internalSurface(String path) {
            return path != null && (path.equals("/internal") || path.startsWith("/internal/"));
        }

        @Override
        public boolean matches(HttpServletRequest request) {
            return internalSurface(request.getRequestURI()) && delegate.matches(request);
        }

        @Override
        public java.util.List<Filter> getFilters() {
            return delegate.getFilters();
        }
    }

    /**
     * Chain 1 must not accept the portal domain. The shared trust-domain decoder routes on {@code iss}
     * and therefore decodes a user ticket as well; the {@code token_use} check is what keeps a portal
     * ticket out of {@code /internal/**}, so a future internal capability cannot be reached with a
     * browser credential. The wrapper is local to the chain and never becomes a second {@code JwtDecoder}
     * bean, so the general chain keeps its scanned decoder.
     */
    @Bean
    ServiceTrustChain gatewayInternalServiceChain(
            HttpSecurity http, JwtDecoder decoder, ServiceAuthorization receiver) throws Exception {
        var chain = SecurityConfiguration.serviceTrustChain(http, serviceDomainOnly(decoder), receiver);
        return new ServiceTrustChain(new InternalSurfaceOnly(chain));
    }

    static JwtDecoder serviceDomainOnly(JwtDecoder delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return token -> {
            var decoded = delegate.decode(token);
            if (!"service".equals(decoded.getClaimAsString("token_use")))
                throw new BadJwtException("IAM_SERVICE_DOMAIN_REQUIRED");
            return decoded;
        };
    }

    @Bean
    GatewayIngressBoundary gatewayIngressBoundary(
            GatewayController controller,
            ObjectProvider<PortalSessionController> session,
            ObjectProvider<RunStatusProxy> status,
            ObjectProvider<RunEventsProxy> events,
            TrustedJwtDecoder decoder,
            Clock clock) {
        return new GatewayIngressBoundary(
                controller,
                session.getIfAvailable(),
                status.getIfAvailable() != null,
                events.getIfAvailable() != null,
                decoder,
                clock);
    }

    /** Registered as a bean because MVC discovers interceptors across every handler mapping. */
    @Bean
    MappedInterceptor gatewayIngressInterceptor(GatewayIngressBoundary boundary) {
        return boundary.mappedInterceptor();
    }

    /**
     * Ordered one slot before the security filter chain: a credential in the query string is refused on
     * request shape alone, so the caller can tell "wrong carrier" (400) from "invalid ticket" (401) and
     * the parameter never reaches the chain, the boundary or the SSE proxying.
     */
    @Bean
    FilterRegistrationBean<PortalTicketSourceFilter> gatewayPortalTicketSource() {
        var registration = new FilterRegistrationBean<>(new PortalTicketSourceFilter());
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 1);
        registration.addUrlPatterns("/admin/*", "/auth/session", "/v1/apps/*");
        registration.setName("gatewayPortalTicketSource");
        return registration;
    }

    /**
     * Ordered one slot after {@link SecurityProperties#DEFAULT_FILTER_ORDER}: the request has already
     * passed chains 1/2 and is about to enter MVC. No chain is bypassed, and the boundary can never
     * see a request that authorisation rejected.
     */
    @Bean
    FilterRegistrationBean<Filter> gatewayPortalIngress(GatewayIngressBoundary boundary) {
        Filter capture = boundary.filter();
        var registration = new FilterRegistrationBean<>(capture);
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 1);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
        registration.addUrlPatterns("/admin/*", "/auth/session", "/v1/apps/*");
        registration.setName("gatewayPortalIngress");
        return registration;
    }

    private static boolean testProfile(Environment environment) {
        return Arrays.asList(environment.getActiveProfiles()).contains("test");
    }
}
