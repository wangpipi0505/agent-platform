package cn.agentplatform.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import cn.agentplatform.common.authority.AuthorityProperties;
import cn.agentplatform.common.authority.ServiceAuthorization;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.core.env.Environment;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
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
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import javax.crypto.spec.SecretKeySpec;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean
    static BeanFactoryPostProcessor authorityModeGuard(Environment environment) {
        return beanFactory -> {
            var properties = bindDeployment(environment);
            // Spring Context 6.2.7 invokes factory processors before onRefresh; Boot 3.5.0
            // creates its web server in onRefresh. Reject IAM here, before any listener exists.
            // D1 component receivers do not open production IAM; D3 must migrate every service first.
            if ("iam".equals(properties.mode())) throw new IllegalStateException("IAM_TRUST_CHAIN_UNAVAILABLE");
            beanFactory.registerSingleton("authorityProperties", properties);
        };
    }

    /** Bind and validate actual deployment sources before publishing any signing permission. */
    public static AuthorityProperties bindDeployment(Environment environment) {
        var properties = Binder.get(environment).bind("agent.security",
                Bindable.of(AuthorityProperties.class), new NoUnboundElementsBindHandler(BindHandler.DEFAULT))
                .orElseGet(() -> new AuthorityProperties(null, null, null, null, null, null, null, null, null));
        properties.validateDeployment(environment.getProperty("agent.dev-auth", Boolean.class, false),
                environment.getProperty("server.address"),
                Arrays.asList(environment.getActiveProfiles()).contains("test"));
        // Boot 3.5.0 Binder/NoUnboundElementsBindHandler finish strict binding before this
        // registration. Invalid input cannot change the classloader signing state.
        ServiceTokens.registerDeployment(properties.mode());
        return properties;
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${agent.security.jwt-secret}") String secret,
            @Value("${agent.security.allow-embed:false}") boolean allowEmbed) {
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
    SecurityFilterChain security(
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
                            if (request.getRequestURI().startsWith("/v1/")
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
                                                            new ApiResponse<>(
                                                                    error.code(),
                                                                    error.getMessage(),
                                                                    null,
                                                                    null)));
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
                        x ->
                                x.requestMatchers(
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/auth/dev-token",
                                                "/embed/ticket",
                                                "/assistant.js")
                                        .permitAll()
                                        .requestMatchers("/admin/**", "/v3/api-docs")
                                        .access(
                                                (auth, context) ->
                                                        new AuthorizationDecision(
                                                                auth.get().isAuthenticated()
                                                                        && (!trusted || portalScope(auth.get()))
                                                                        && !(auth.get()
                                                                                                .getPrincipal()
                                                                                        instanceof
                                                                                        Jwt jwt
                                                                                && "integration"
                                                                                        .equals(
                                                                                                jwt
                                                                                                        .getClaimAsString(
                                                                                                                "token_use")))))
                                        .requestMatchers("/internal/**")
                                        .access((auth, context) -> new AuthorizationDecision(
                                                // Current-authority/resource guards are not yet installed. No RS256
                                                // service ticket may turn a role string into a service capability.
                                                !trusted && auth.get().getAuthorities().stream()
                                                        .anyMatch(a -> a.getAuthority().equals("ROLE_SERVICE"))))
                                        .anyRequest()
                                        .access((auth, context) -> new AuthorizationDecision(auth.get().isAuthenticated()
                                                && (!trusted || portalScope(auth.get())))))
                .exceptionHandling(x -> x
                        .authenticationEntryPoint((request, response, error) -> authError(response, 401, "SYS-AUTH-401"))
                        .accessDeniedHandler((request, response, error) -> authError(response, 403, "SYS-AUTH-403")))
                .oauth2ResourceServer(
                        x -> x.authenticationEntryPoint((request, response, error) -> authError(response, 401, "SYS-AUTH-401"))
                                .accessDeniedHandler((request, response, error) -> authError(response, 403, "SYS-AUTH-403"))
                                .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter)))
                .build();
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

    private static boolean portalScope(org.springframework.security.core.Authentication auth) {
        return auth.getPrincipal() instanceof Jwt jwt && "user".equals(jwt.getClaimAsString("token_use"))
                && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("SCOPE_platform.access"));
    }

    private static void authError(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(Json.write(new ApiResponse<>(code, "身份或授权验证未通过", null, null)));
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
