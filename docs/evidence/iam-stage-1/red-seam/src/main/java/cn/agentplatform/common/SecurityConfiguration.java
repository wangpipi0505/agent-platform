package cn.agentplatform.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

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
            ObjectProvider<ApiKeyAuthenticator> apiKeys)
            throws Exception {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
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
                                        .hasAuthority("ROLE_SERVICE")
                                        .anyRequest()
                                        .authenticated())
                .oauth2ResourceServer(
                        x -> x.jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter)))
                .build();
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
