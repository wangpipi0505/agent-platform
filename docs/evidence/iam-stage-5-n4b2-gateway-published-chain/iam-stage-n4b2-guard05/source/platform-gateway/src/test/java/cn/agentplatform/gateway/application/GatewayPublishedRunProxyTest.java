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
    static final AtomicInteger legacy=new AtomicInteger(),legacyChecks=new AtomicInteger();
    static ServletWebServerApplicationContext context;
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class) @EnableWebSecurity(debug=false)
    @Import({GatewayController.class,cn.agentplatform.gateway.infra.GatewayIamAssembly.class,PlatformErrors.class})
    static class Receiver {
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor deployment(org.springframework.core.env.Environment e){return f->f.registerSingleton("authorityProperties",SecurityConfiguration.bindDeployment(e));}
        @Bean(destroyMethod="close") TrustedJwtDecoder decoder(){return new TrustedJwtDecoder(iam.config("gateway"),iam.clock(),true);}
        @Bean IntegrationAccess integration(TrustedJwtDecoder d){return new IntegrationAccess(origin,origin,d){@Override public void check(jakarta.servlet.http.HttpServletRequest r,Actor a){legacyChecks.incrementAndGet();super.check(r,a);}};}
        @Bean java.time.Clock clock(){return iam.clock();}
    }
    }
    @BeforeAll static void start()throws Exception{
        iam=new LoopbackAuthority();
        app=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        app.createContext("/",x->{legacy.incrementAndGet();x.getRequestBody().readAllBytes();byte[] b=Json.write(ApiResponse.ok(Map.of("legacy",true))).getBytes(java.nio.charset.StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();});app.start();origin="http://127.0.0.1:"+app.getAddress().getPort();
        var boot=new SpringApplication(Receiver.class);boot.setAdditionalProfiles("test");boot.setDefaultProperties(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","0"),Map.entry("spring.config.location","optional:classpath:/n4b2-no-config/"),Map.entry("spring.main.banner-mode","off"),Map.entry("springdoc.api-docs.enabled",false),Map.entry("spring.flyway.enabled",false),Map.entry("management.endpoints.web.exposure.exclude","*"),Map.entry("management.endpoints.web.discovery.enabled",false),Map.entry("logging.level.root","ERROR"),Map.entry("agent.services.app",origin),Map.entry("agent.services.engine",origin)));boot.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("n4b2-trusted-deployment",trustSettings())));context=(ServletWebServerApplicationContext)boot.run();
    }
    static Map<String,Object> trustSettings(){var p=new LinkedHashMap<String,Object>();flatten("agent.security",Json.tree(iam.config("gateway")),p);p.put("agent.dev-auth",false);return p;}
    static void flatten(String prefix,com.fasterxml.jackson.databind.JsonNode value,Map<String,Object> p){
        if(value.isObject())value.fields().forEachRemaining(e->{String key=prefix.endsWith("attribute-value-allowlist")?"["+e.getKey()+"]":"."+e.getKey().replaceAll("([a-z])([A-Z])","$1-$2").toLowerCase(java.util.Locale.ROOT);flatten(prefix+key,e.getValue(),p);});
        else if(value.isArray()){for(int i=0;i<value.size();i++)flatten(prefix+"["+i+"]",value.get(i),p);}else if(!value.isNull())p.put(prefix,value.isBoolean()?value.booleanValue():value.isNumber()?value.numberValue():value.asText());
    }    @AfterAll static void stop(){if(context!=null)context.close();if(app!=null)app.stop(0);if(iam!=null)iam.close();}
    @Test void actualIamControllerRejectsQueryBeforeAnyLegacyOrAuthorityBusinessSend()throws Exception {
        legacy.set(0);legacyChecks.set(0);int before=iam.authorityCalls();
        try(var http=HttpClient.newHttpClient()){
            var r=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+"/admin/apps/Demo/runs?clientTenant=forged")).header("Authorization","Bearer "+iam.portal()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"inputs\":{},\"requestId\":\"query-denial\"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(legacyChecks).as("query rejected before entering legacy branch").hasValue(0);assertThat(r.statusCode()).as("IAM query must fail closed at real controller ingress").isEqualTo(403);
            assertThat(legacy).hasValue(0);assertThat(iam.authorityCalls()).isEqualTo(before);
        }
    }
}
