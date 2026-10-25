package cn.agentplatform.gateway.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.gateway.api.GatewayController;
import cn.agentplatform.gateway.application.IntegrationAccess;
import org.junit.jupiter.api.Test;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated explicit launcher; the default GatewayApplication is outside this component test. */
public class GatewayPortalEventsServletTest {
    static LoopbackAuthority iam;
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class)
    @EnableWebSecurity
    @Import({GatewayController.class,GatewayIamAssembly.class,PlatformErrors.class})
    static class Receiver {
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor properties(){return factory->factory.registerSingleton("authorityProperties",iam.config("gateway"));}
        @Bean Clock clock(){return iam.clock();}
        @Bean(destroyMethod="close") TrustedJwtDecoder decoder(AuthorityProperties p){return new TrustedJwtDecoder(p,iam.clock(),true);}
        @Bean IntegrationAccess integration(TrustedJwtDecoder decoder){return new IntegrationAccess(iam.issuer(),iam.issuer(),decoder);}
    }
    static ServletWebServerApplicationContext start(){
        var app=new SpringApplication(Receiver.class);app.setAdditionalProfiles("test");
        app.setDefaultProperties(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","0"),Map.entry("spring.config.location","optional:classpath:/c2b1-no-config/"),Map.entry("spring.main.banner-mode","off"),Map.entry("logging.level.root","ERROR"),Map.entry("springdoc.api-docs.enabled",false),Map.entry("management.endpoints.web.exposure.exclude","*"),Map.entry("management.endpoints.web.discovery.enabled",false)));
        return (ServletWebServerApplicationContext)app.run();
    }
    @Test void actualControllerSecurityChainHasExactEventsIngress()throws Exception {
        try(var fixture=new LoopbackAuthority()){
            iam=fixture;
            try(var context=start();var client=HttpClient.newHttpClient()){
                var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+"/admin/runs/r_"+"a".repeat(32)+"/events")).header("Authorization","Bearer "+fixture.portal()).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,response.statusCode(),"EVENTS must reach its exact IAM ingress");
            }
        }
    }
}
