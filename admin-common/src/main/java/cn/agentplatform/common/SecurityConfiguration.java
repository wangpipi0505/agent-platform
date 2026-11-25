package cn.agentplatform.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import cn.agentplatform.common.authority.AuthorityDecoders;
import cn.agentplatform.common.authority.AuthorityProperties;
import cn.agentplatform.common.authority.CoverageGate;
import cn.agentplatform.common.authority.IamStartupGate;
import cn.agentplatform.common.authority.IntegrationRuntimeRoutes;
import cn.agentplatform.common.authority.ServiceAuthorization;
import cn.agentplatform.common.authority.ServiceRoutePolicy;
import cn.agentplatform.common.authority.ServiceTrustChain;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.core.env.Environment;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import javax.crypto.spec.SecretKeySpec;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    @Value("${management.server.port:}")
    private Integer managementPort;

    @Value("${server.port:8080}")
    private int businessPort = 8080;

    /** IAM_CONFIG_INCOMPLETE covers every authority-block validation reason, keeping the detail. */
    private static final Set<String> IAM_CONFIG_REASONS =
            Set.of(
                    "IAM_CONFIG_REQUIRED",
                    "IAM_CLIENT_SERVICE_MISMATCH",
                    "IAM_SECRET_REF_INVALID",
                    "IAM_ALGORITHM_INVALID",
                    "IAM_AUDIENCE_INVALID",
                    "IAM_URI_INVALID",
                    "IAM_BUDGET_INVALID",
                    IamStartupGate.CONFIG_INCOMPLETE);

    @Bean
    static BeanFactoryPostProcessor authorityModeGuard(Environment environment) {
        return beanFactory -> {
            var properties = bindValidatedDeployment(environment);
            // Spring Context 6.2.7 invokes factory processors before onRefresh; Boot 3.5.0
            // creates its web server in onRefresh. Every check below is local, so a rejected iam
            // deployment never opens a listener, and a healthy one starts without any network call.
            // EX-43 §3.2: iam is a validating gate now, not an unconditional refusal.
            if ("iam".equals(properties.mode())) IamStartupGate.requireLocalClientSecret(environment, properties);
            // Publish signing permission only after every local iam check passed.
            ServiceTokens.registerDeployment(properties.mode());
            beanFactory.registerSingleton("authorityProperties", properties);
        };
    }

    /** Bind and validate actual deployment sources before publishing any signing permission. */
    public static AuthorityProperties bindDeployment(Environment environment) {
        var properties = bindValidatedDeployment(environment);
        // Boot 3.5.0 Binder/NoUnboundElementsBindHandler finish strict binding before this
        // registration. Invalid input cannot change the classloader signing state.
        ServiceTokens.registerDeployment(properties.mode());
        return properties;
    }

    /**
     * Binds and validates deployment input without publishing signing permission. The guard uses this
     * split so iam registration happens only after the client-secret reference resolves; callers that
     * only need the validated view cannot accidentally mutate the classloader-wide signing state.
     */
    public static AuthorityProperties bindValidatedDeployment(Environment environment) {
        var properties = Binder.get(environment).bind("agent.security",
                Bindable.of(AuthorityProperties.class), new NoUnboundElementsBindHandler(BindHandler.DEFAULT))
                .orElseGet(() -> new AuthorityProperties(null, null, null, null, null, null, null, null, null));
        try {
            properties.validateDeployment(environment.getProperty("agent.dev-auth", Boolean.class, false),
                    environment.getProperty("server.address"),
                    Arrays.asList(environment.getActiveProfiles()).contains("test"));
        } catch (IllegalStateException invalid) {
            // EX-43 §3.2 keeps the authority block's concrete reason for diagnosis but reports it
            // under the stable iam startup code. Mode/bind/dev-auth errors stay as they were.
            if ("iam".equals(properties.mode()) && IAM_CONFIG_REASONS.stream()
                    .anyMatch(reason -> String.valueOf(invalid.getMessage()).startsWith(reason)))
                throw new IllegalStateException(
                        IamStartupGate.CONFIG_INCOMPLETE + ": " + invalid.getMessage(), invalid);
            throw invalid;
        }
        return properties;
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${agent.security.jwt-secret:}") String secret,
            @Value("${agent.security.allow-embed:false}") boolean allowEmbed,
            AuthorityProperties deployment) {
        // EX-43 §3.4: iam verifies RS256 against the deployment's fixed trust domains. Building the
        // decoder only wires a lazy JWKS key source, so startup still performs no network call.
        if ("iam".equals(deployment.mode())) return AuthorityDecoders.iam(deployment);
        if (secret.length() < 32) throw new IllegalStateException("JWT密钥必须独立且至少32字符");
        var decoder =
                NimbusJwtDecoder.withSecretKey(
                                new SecretKeySpec(
                                        secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                        .macAlgorithm(MacAlgorithm.HS256)
                        .build();
        OAuth2TokenValidator<Jwt> audience =
                jwt -> {
                    boolean portal =
                            jwt.getAudience().contains("agent-platform")
                                    && "user".equals(jwt.getClaimAsString("token_use"));
                    boolean internal =
                            jwt.getAudience().contains("agent-platform-internal")
                                    && "service".equals(jwt.getClaimAsString("token_use"));
                    boolean integration =
                            allowEmbed
                                    && jwt.getAudience().contains("agent-platform-embed")
                                    && "integration".equals(jwt.getClaimAsString("token_use"));
                    return portal || internal || integration
                            ? OAuth2TokenValidatorResult.success()
                            : OAuth2TokenValidatorResult.failure(
                                    new OAuth2Error("invalid_token", "票据受众无效", null));
                };
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(ServiceTokens.ISSUER), audience));
        return decoder;
    }

    @Bean
    public SecurityFilterChain security(
            HttpSecurity http,
            JwtDecoder decoder,
            @Qualifier("cors") CorsConfigurationSource corsSource,
            ObjectProvider<ApiKeyAuthenticator> apiKeys, AuthorityProperties deployment)
            throws Exception {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        boolean trusted = "iam".equals(deployment.mode());
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            if (!trusted) return authorities.convert(jwt);
            var result = new ArrayList<GrantedAuthority>();
            if ("user".equals(jwt.getClaimAsString("token_use"))) {
                jwt.getClaimAsStringList("roles").stream()
                        .filter(role -> !Set.of("SERVICE", "GATEWAY_AUTH").contains(role))
                        .forEach(role -> result.add(new SimpleGrantedAuthority("ROLE_" + role)));
                String scope = jwt.getClaimAsString("scope");
                if (scope != null && !scope.isEmpty())
                    Arrays.stream(scope.split(" ")).forEach(s -> result.add(new SimpleGrantedAuthority("SCOPE_" + s)));
            }
            return result;
        });
        if (trusted) http.addFilterBefore(new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                try { chain.doFilter(request, response); }
                catch (PlatformException error) { authError(response, error.status(), error.code()); }
            }
        }, BearerTokenAuthenticationFilter.class);
        if (trusted) http.addFilterBefore(new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                // Q1/EX-43: a production iam deployment must not keep the legacy dev-token backdoor.
                // denyAll would answer 403 and reveal that the route exists; the agreed contract is 404.
                if ("/auth/dev-token".equals(request.getServletPath())) {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(Json.write(ApiResponse.error(404, "NOT_FOUND", "接口不存在")));
                    return;
                }
                chain.doFilter(request, response);
            }
        }, BearerTokenAuthenticationFilter.class);
        ApiKeyAuthenticator apiKey = apiKeys.getIfAvailable();
        if (apiKey != null)
            http.addFilterBefore(
                    new OncePerRequestFilter() {
                        @Override
                        protected void doFilterInternal(
                                HttpServletRequest request,
                                HttpServletResponse response,
                                FilterChain chain)
                                throws ServletException, IOException {
                            String header = request.getHeader("Authorization");
                            if ((trusted ? Boolean.TRUE.equals(deployment.allowEmbed())
                                    && IntegrationRuntimeRoutes.supported(
                                            request.getMethod(), request.getRequestURI())
                                    : request.getRequestURI().startsWith("/v1/"))
                                    && header != null
                                    && header.startsWith("ApiKey ")) {
                                try {
                                    SecurityContextHolder.getContext()
                                            .setAuthentication(apiKey.authenticate(request));
                                } catch (PlatformException error) {
                                    response.setStatus(error.status());
                                    response.setContentType("application/json;charset=UTF-8");
                                    response.getWriter()
                                            .write(
                                                    Json.write(
                                                            ApiResponse.error(
                                                                    error.status(),
                                                                    error.code(),
                                                                    error.getMessage())));
                                    return;
                                }
                            }
                            chain.doFilter(request, response);
                        }
                    },
                    BearerTokenAuthenticationFilter.class);
        return http.csrf(x -> x.disable())
                .cors(x -> x.configurationSource(corsSource))
                .sessionManagement(x -> x.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        x -> {
                            // Path-lambda matchers: the MVC matcher resolution used by
                            // requestMatchers(String...) does not resolve actuator endpoints, so the
                            // health probe fell through to anyRequest and was denied in iam mode.
                            // Named RequestMatchers: the MVC resolver behind requestMatchers(String...)
                            // does not resolve actuator endpoints, and a path lambda is not registered
                            // as an AuthorizedUrl rule, so the probe fell through to anyRequest (403).
                            x.requestMatchers(request -> managementScrape(request, managementPort, businessPort))
                                    .permitAll()
                                    .requestMatchers(
                                            new AntPathRequestMatcher("/actuator/health"),
                                            new AntPathRequestMatcher("/actuator/health/**"),
                                            new AntPathRequestMatcher("/auth/dev-token"),
                                            new AntPathRequestMatcher("/embed/ticket"),
                                            new AntPathRequestMatcher("/assistant.js"))
                                    .permitAll()
                                    .requestMatchers("/admin/**", "/v3/api-docs")
                                    .access(
                                            (auth, context) ->
                                                    new AuthorizationDecision(
                                                            auth.get().isAuthenticated()
                                                                    && !(auth.get() instanceof AnonymousAuthenticationToken)
                                                                    && (!trusted || portalScope(auth.get()))
                                                                    && !(auth.get().getPrincipal() instanceof Jwt jwt
                                                                            && "integration"
                                                                                    .equals(jwt.getClaimAsString("token_use")))));
                            // EX-43 §3.1: in iam /internal/** belongs exclusively to the dedicated
                            // service trust chain. Reaching this chain means chain 1 was not installed
                            // or was matched later, so deny outright instead of accepting a role string.
                            if (trusted) x.requestMatchers("/internal/**").denyAll();
                            else
                                x.requestMatchers("/internal/**")
                                        .access(
                                                (auth, context) ->
                                                        new AuthorizationDecision(
                                                                auth.get().getAuthorities().stream()
                                                                        .anyMatch(
                                                                                a -> a.getAuthority().equals("ROLE_SERVICE"))));
                            if (trusted && "gateway".equals(deployment.serviceId())) {
                                x.requestMatchers(request -> IntegrationRuntimeRoutes.metadata(
                                        request.getMethod(), request.getRequestURI()))
                                        .access((auth, context) -> new AuthorizationDecision(
                                                IntegrationRuntimeRoutes.currentSource() != null));
                                x.requestMatchers(request -> IntegrationRuntimeRoutes.taskRead(
                                        request.getMethod(), request.getRequestURI())
                                        || IntegrationRuntimeRoutes.taskCancel(request.getMethod(), request.getRequestURI()))
                                        .access((auth, context) -> new AuthorizationDecision(
                                                IntegrationRuntimeRoutes.currentSource() != null || portalScope(auth.get())));
                            }
                            x.anyRequest()
                                    .access(
                                            (auth, context) ->
                                                    new AuthorizationDecision(
                                                            auth.get().isAuthenticated()
                                                                    && !(auth.get() instanceof AnonymousAuthenticationToken)
                                                                    && (!trusted || portalScope(auth.get()))));
                        })
                .exceptionHandling(x -> x
                        .authenticationEntryPoint((request, response, error) -> authError(response, 401, "SYS-AUTH-401"))
                        .accessDeniedHandler((request, response, error) -> accessDenied(request, response, deployment)))
                .oauth2ResourceServer(
                        x -> x.authenticationEntryPoint((request, response, error) -> authError(response, 401, "SYS-AUTH-401"))
                                .accessDeniedHandler((request, response, error) -> accessDenied(request, response, deployment))
                                .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter)))
                .build();
    }

    static boolean managementScrape(HttpServletRequest request, Integer managementPort, int businessPort) {
        return managementPort != null && managementPort > 0 && managementPort != businessPort
                && request.getLocalPort() == managementPort && request.getDispatcherType() == DispatcherType.REQUEST
                && "127.0.0.1".equals(request.getLocalAddr()) && "127.0.0.1".equals(request.getRemoteAddr())
                && request.getContextPath().isEmpty() && request.getQueryString() == null
                && "GET".equals(request.getMethod()) && "/actuator/prometheus".equals(request.getRequestURI());
    }

    private static void accessDenied(HttpServletRequest request, HttpServletResponse response,
            AuthorityProperties deployment) throws IOException {
        if ("iam".equals(deployment.mode()) && "gateway".equals(deployment.serviceId())
                && request.getRequestURI().startsWith("/v1/")) {
            var source = IntegrationRuntimeRoutes.currentSource();
            if (source != null) {
                // Record the authenticated credential and a path digest. Never log a header or request body.
                org.slf4j.LoggerFactory.getLogger("agent.integration.audit").warn(
                        "AUDIT INTEGRATION_PATH_FORBIDDEN credential={} method={} pathSha256={} traceId={}",
                        source.getClaim("credential_id"), request.getMethod(),
                        Digest.sha256(request.getRequestURI()), org.slf4j.MDC.get("traceId"));
                authError(response, 403, "APP_BINDING_DENIED");
                return;
            }
        }
        authError(response, 403, "SYS-AUTH-403");
    }

    /** Explicit downstream entry; its decoder belongs to the service trust domain. */
    public static SecurityFilterChain serviceSecurity(HttpSecurity http, JwtDecoder decoder, ServiceAuthorization receiver) throws Exception {
        Objects.requireNonNull(receiver);
        http.addFilterBefore(new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
                    throws ServletException,IOException {
                try { chain.doFilter(request,response); }
                catch(PlatformException error) { authError(response,error.status(),error.code()); }
            }
        },BearerTokenAuthenticationFilter.class);
        return http.csrf(x -> x.disable())
                .sessionManagement(x -> x.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(x -> x.requestMatchers(receiver::healthRequest).permitAll().anyRequest().authenticated())
                .exceptionHandling(x -> x.authenticationEntryPoint((request,response,error)->authError(response,401,"SYS-AUTH-401"))
                    .accessDeniedHandler((request,response,error)->authError(response,403,"SYS-AUTH-403")))
                .oauth2ResourceServer(x -> x.authenticationEntryPoint((request,response,error)->authError(response,401,"SYS-AUTH-401"))
                    .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(jwt->new JwtAuthenticationToken(jwt,List.of()))))
                .build();
    }

    /**
     * EX-43 §3.1 chain 1 for internal and explicitly delegated gateway routes. Kept separate from
     * {@link #serviceSecurity} so existing single-chain assemblies keep their exact behaviour: this
     * entry is the one that narrows the match and wraps the chain in {@link ServiceTrustChain}, whose
     * order guarantees chain 1 is consulted before the application chain regardless of bean
     * declaration order. Services expose the returned value as a {@code SecurityFilterChain} bean.
     */
    public static ServiceTrustChain serviceTrustChain(
            HttpSecurity http, JwtDecoder decoder, ServiceAuthorization receiver) throws Exception {
        http.securityMatcher("/internal/**");
        return new ServiceTrustChain(serviceSecurity(http, decoder, receiver), receiver::delegatedPortalRequest);
    }

    /**
     * EX-43 §3.2: catalog self-check before Boot accepts requests. iam fails closed (a handler with no
     * usable catalog row is an unusable capability); every other mode only warns so the dev-hmac
     * migration/rollback path stays observable instead of silently broken.
     */
    @Bean
    static SmartInitializingSingleton routeCoverageGate(
            AuthorityProperties deployment,
            ObjectProvider<ServiceRoutePolicy> routes,
            ObjectProvider<RequestMappingInfoHandlerMapping> mappings) {
        return () -> CoverageGate.verify(deployment.mode(), deployment.serviceId(),
                routes.getIfAvailable(ServiceRoutePolicy::new), mappings.orderedStream().toList());
    }

    private static boolean portalScope(org.springframework.security.core.Authentication auth) {
        return auth.getPrincipal() instanceof Jwt jwt && "user".equals(jwt.getClaimAsString("token_use"))
                && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("SCOPE_platform.access"));
    }

    private static void authError(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(Json.write(ApiResponse.error(status, code, "身份或授权验证未通过")));
    }

    @Bean
    CorsConfigurationSource cors(
            ObjectProvider<AllowedOriginPolicy> policies,
            @Value(
                            "${agent.security.cors-origins:http://localhost:15173,http://127.0.0.1:15173,http://localhost:15174,http://127.0.0.1:15174}")
                    List<String> origins) {
        return request -> {
            String origin = request.getHeader("Origin");
            boolean allowed =
                    origin != null
                            && (origins.contains(origin)
                                    || policies.orderedStream().anyMatch(p -> p.allowed(origin)));
            CorsConfiguration cors = new CorsConfiguration();
            cors.setAllowedOrigins(allowed ? List.of(origin) : List.of());
            cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            cors.setAllowedHeaders(
                    List.of(
                            "Authorization",
                            "Content-Type",
                            "Last-Event-ID",
                            "X-User-Id",
                            "X-User-Roles",
                            "X-User-Attributes"));
            cors.setMaxAge(30L);
            return cors;
        };
    }
}
