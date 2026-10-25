package cn.agentplatform.gateway.application;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.gateway.api.GatewayController;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Actual controller and signed portal HTTP, never a same-FQN common substitute. */
class GatewayPublishedRunProxyTest {
    static LoopbackAuthority iam;
    static HttpServer app;
    static String origin;
    static final AtomicInteger legacy=new AtomicInteger();
    static ServletWebServerApplicationContext context;
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity
    @Import(GatewayController.class)
    static class Receiver {
        @Bean AuthorityProperties authorityProperties(){return iam.config("gateway");}
        @Bean(destroyMethod="close") TrustedJwtDecoder decoder(){return new TrustedJwtDecoder(iam.config("gateway"),iam.clock(),true);}
        @Bean IntegrationAccess integration(TrustedJwtDecoder d){return new IntegrationAccess(origin,origin,d);}
        @Bean SecurityFilterChain security(HttpSecurity h,JwtDecoder d)throws Exception {
            return h.csrf(c->c.disable()).authorizeHttpRequests(a->a.anyRequest().authenticated()).oauth2ResourceServer(o->o.jwt(j->j.decoder(d))).build();
        }
    }
    @BeforeAll static void start()throws Exception{
        iam=new LoopbackAuthority();
        app=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        app.createContext("/",x->{legacy.incrementAndGet();x.getRequestBody().readAllBytes();byte[] b=Json.write(ApiResponse.ok(Map.of("legacy",true))).getBytes(java.nio.charset.StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();});app.start();origin="http://127.0.0.1:"+app.getAddress().getPort();
        var boot=new SpringApplication(Receiver.class);boot.setAdditionalProfiles("test");boot.setDefaultProperties(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","0"),Map.entry("spring.config.location","optional:classpath:/n4b2-no-config/"),Map.entry("spring.main.banner-mode","off"),Map.entry("springdoc.api-docs.enabled",false),Map.entry("spring.flyway.enabled",false),Map.entry("management.endpoints.web.exposure.exclude","*"),Map.entry("management.endpoints.web.discovery.enabled",false),Map.entry("logging.level.root","ERROR"),Map.entry("agent.services.app",origin),Map.entry("agent.services.engine",origin)));context=(ServletWebServerApplicationContext)boot.run();
    }
    @AfterAll static void stop(){if(context!=null)context.close();if(app!=null)app.stop(0);if(iam!=null)iam.close();}
    @Test void actualIamControllerRejectsQueryBeforeAnyLegacyOrAuthorityBusinessSend()throws Exception {
        legacy.set(0);int before=iam.authorityCalls();
        try(var http=HttpClient.newHttpClient()){
            var r=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+"/admin/apps/Demo/runs?clientTenant=forged")).header("Authorization","Bearer "+iam.portal()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"inputs\":{},\"requestId\":\"query-denial\"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as("IAM query must fail closed at real controller ingress").isEqualTo(403);
            assertThat(legacy).hasValue(0);assertThat(iam.authorityCalls()).isEqualTo(before);
        }
    }
}
