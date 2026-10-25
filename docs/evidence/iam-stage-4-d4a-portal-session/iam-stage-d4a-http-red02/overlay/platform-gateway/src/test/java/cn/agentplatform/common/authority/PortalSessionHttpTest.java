package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import cn.agentplatform.gateway.api.GatewayController;
import cn.agentplatform.gateway.application.IntegrationAccess;
import cn.agentplatform.gateway.infra.GatewayIamAssembly;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PortalSessionHttpTest {
    static LoopbackAuthority iam;
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class)
    @EnableWebSecurity
    @Import({GatewayController.class,GatewayIamAssembly.class,PlatformErrors.class})
    static class Receiver {
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor properties(){
            return factory->factory.registerSingleton("authorityProperties",iam.config("gateway"));
        }
        @Bean Clock clock(){return iam.clock();}
        @Bean(destroyMethod="close") TrustedJwtDecoder decoder(AuthorityProperties p){return new TrustedJwtDecoder(p,iam.clock(),true);}
        @Bean IntegrationAccess integration(TrustedJwtDecoder decoder){return new IntegrationAccess(iam.issuer(),iam.issuer(),decoder);}
        @Bean(destroyMethod="close") HttpCurrentAuthorityClient authority(){return iam.client("gateway");}
    }
    static ServletWebServerApplicationContext start(Class<?> type){
        var app=new SpringApplication(type);app.setDefaultProperties(Map.of("server.address","127.0.0.1","server.port","0","spring.main.banner-mode","off","logging.level.root","ERROR"));
        return (ServletWebServerApplicationContext)app.run();
    }
    static HttpResponse<String> get(ServletWebServerApplicationContext context,String token,String suffix)throws Exception{
        try(var client=HttpClient.newHttpClient()){
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+"/auth/session"+suffix)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
        }
    }
    @Test void installedExactHandlerReturnsOnlyCurrentSessionWithNoBusinessCalls()throws Exception{
        try(var f=new LoopbackAuthority()){
            iam=f;try(var context=start(Receiver.class)){
                var response=get(context,f.portal(),"");
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
                var data=Json.read(response.body()).path("data");
                assertThat(data.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("tenantId","principalId","roles","expiresAt");
                assertThat(f.requests).extracting(r->r.path("operation").asText()).containsExactly("exchange","authorize");
                assertThat(f.businessSends).hasValue(0);assertThat(f.policyCalls).hasValue(0);
            }
        }
    }
}
