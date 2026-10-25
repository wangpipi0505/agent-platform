package cn.agentplatform.app.infra;
import cn.agentplatform.app.api.IntegrationPolicyResolverController;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import com.nimbusds.jose.jwk.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="agent.policy-resolver",name="enabled",havingValue="true")
public class PolicyResolverSecurity {
    @Bean PolicyRequestVerifier policyRequestVerifier(@Value("${agent.policy-resolver.issuer:}") String issuer,@Value("${agent.policy-resolver.public-jwks:}") String jwks){
        try{return new PolicyRequestVerifier(issuer,JWKSet.parse(jwks).getKeys().stream().map(k->{if(!(k instanceof RSAKey r))throw new IllegalArgumentException("POLICY_RSA_REQUIRED");return r;}).toList(),Clock.systemUTC());}
        catch(Exception e){throw new IllegalArgumentException("POLICY_PUBLIC_CONFIGURATION_REQUIRED");}
    }
    public static java.lang.reflect.Method handler(){try{return IntegrationPolicyResolverController.class.getMethod("resolve",HttpServletRequest.class);}catch(Exception e){throw new IllegalStateException(e);}}
    public static RouteCoverageVerifier.Ingress ingress(){return new RouteCoverageVerifier.Ingress("POST",PolicyRequestVerifier.PATH,handler());}
    @Bean @Order(1) SecurityFilterChain policySecurity(HttpSecurity http,PolicyRequestVerifier verifier)throws Exception{
        // Security's first matching chain is exact; no /internal/** exemption.
        return http.securityMatcher(r->"POST".equals(r.getMethod())&&PolicyRequestVerifier.PATH.equals(r.getServletPath()))
            .csrf(x->x.disable()).sessionManagement(x->x.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(x->x.anyRequest().permitAll())
            .addFilterBefore(new OncePerRequestFilter(){
                @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
                    try{
                        var proof=verifier.verify(request,request.getInputStream().readNBytes(8193));request.setAttribute(PolicyRequestVerifier.ATTRIBUTE,proof);
                        try{chain.doFilter(request,response);}finally{request.removeAttribute(PolicyRequestVerifier.ATTRIBUTE);}
                    }catch(PlatformException denied){response.setStatus(denied.status());response.setContentType("application/json;charset=UTF-8");response.getWriter().write(Json.write(new ApiResponse<>(denied.code(),denied.getMessage(),null,null)));}
                }
            },AnonymousAuthenticationFilter.class).build();
    }
}
