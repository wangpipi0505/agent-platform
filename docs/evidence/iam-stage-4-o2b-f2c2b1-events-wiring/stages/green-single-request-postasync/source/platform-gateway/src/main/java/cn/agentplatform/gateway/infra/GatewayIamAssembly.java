package cn.agentplatform.gateway.infra;

import cn.agentplatform.common.authority.*;
import cn.agentplatform.gateway.api.GatewayController;
import cn.agentplatform.gateway.api.PortalSessionController;
import cn.agentplatform.gateway.application.RunStatusProxy;
import cn.agentplatform.gateway.application.RunEventsProxy;
import org.springframework.beans.factory.ObjectProvider;

import jakarta.servlet.*;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.*;
import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import java.time.Clock;
import java.util.*;

/**
 * Explicitly imported finite assembly only. No component/configuration stereotype enables normal
 * application scanning.
 */
public final class GatewayIamAssembly {
    @Bean
    GatewayIngressBoundary gatewayIngressBoundary(
            GatewayController controller,
            ObjectProvider<PortalSessionController> session,
            ObjectProvider<RunStatusProxy> status,
            ObjectProvider<RunEventsProxy> events,
            TrustedJwtDecoder decoder,
            Clock clock,
            AuthorityProperties properties) {
        if (!"iam".equals(properties.mode()) || !"gateway".equals(properties.serviceId()))
            throw new IllegalStateException("GATEWAY_IAM_REQUIRED");
        return new GatewayIngressBoundary(controller,session.getIfAvailable(),status.getIfAvailable()!=null,events.getIfAvailable()!=null,decoder,clock);
    }

    @Bean
    SecurityFilterChain gatewayIamChain(HttpSecurity http, GatewayIngressBoundary boundary)
            throws Exception {
        return http.csrf(c -> c.disable())
                .requestCache(c -> c.disable())
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(boundary.filter(), SecurityContextHolderAwareRequestFilter.class)
                .authorizeHttpRequests(c -> c.anyRequest().authenticated())
                .build();
    }

    @Bean
    MappedInterceptor gatewayIamInterceptor(GatewayIngressBoundary boundary) {
        return boundary.mappedInterceptor();
    }

    @Bean
    GatewayIngressBoundary.InstalledFilterRegistration gatewayIamRegistration(
            @Qualifier("springSecurityFilterChain") Filter actual) {
        if (!(actual instanceof FilterChainProxy chain))
            throw new IllegalStateException("GATEWAY_FRAMEWORK_CHAIN_REQUIRED");
        // Keep Security 6.5's actual MVC cache/path wrappers. Boot marks this exact Filter as seen.
        return new GatewayIngressBoundary.InstalledFilterRegistration(chain);
    }

    @Bean
    RouteCoverageVerifier gatewayIamCoverage(
            List<RequestMappingInfoHandlerMapping> mappings,
            GatewayIngressBoundary boundary,
            @Qualifier("springSecurityFilterChain") Filter actual,
            @Qualifier("gatewayIamChain") SecurityFilterChain expected,
            GatewayIngressBoundary.InstalledFilterRegistration registration,
            ServletContext context) {
        if (!(actual instanceof FilterChainProxy chain))
            throw new IllegalStateException("GATEWAY_FRAMEWORK_CHAIN_REQUIRED");
        return new RouteCoverageVerifier(
                "gateway",
                new ServiceRoutePolicy(),
                mappings,
                Set.of(),
                new RouteCoverageVerifier.GatewayProxyIngress(
                        boundary, chain, expected, registration, context));
    }
}
