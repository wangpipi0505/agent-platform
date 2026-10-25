package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import cn.agentplatform.gateway.api.GatewayController;
import cn.agentplatform.gateway.api.PortalSessionController;
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
import java.util.concurrent.atomic.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import static org.assertj.core.api.Assertions.*;

class PortalSessionHttpTest {
    static LoopbackAuthority iam;
    static volatile String releaseFault="";
    static HttpCurrentAuthorityClient owner;
    static final AtomicInteger successBytes=new AtomicInteger();
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class)
    @EnableWebSecurity
    @Import({GatewayController.class,PortalSessionController.class,GatewayIamAssembly.class,PlatformErrors.class})
    static class Receiver {
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor properties(){
            return factory->factory.registerSingleton("authorityProperties",iam.config("gateway"));
        }
        @Bean Clock clock(){return iam.clock();}
        @Bean(destroyMethod="close") TrustedJwtDecoder decoder(AuthorityProperties p){return new TrustedJwtDecoder(p,iam.clock(),true);}
        @Bean IntegrationAccess integration(TrustedJwtDecoder decoder){return new IntegrationAccess(iam.issuer(),iam.issuer(),decoder);}
        @Bean(destroyMethod="close") HttpCurrentAuthorityClient authority(){return owner=iam.client("gateway");}
        @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<Filter> observeOutput(){
            var once=new AtomicBoolean();
            var bean=new org.springframework.boot.web.servlet.FilterRegistrationBean<Filter>((request,response,chain)->chain.doFilter(request,new HttpServletResponseWrapper((HttpServletResponse)response){
                @Override public ServletOutputStream getOutputStream()throws IOException {
                    var original=super.getOutputStream();
                    if(!releaseFault.isEmpty()&&once.compareAndSet(false,true)){
                        if(releaseFault.equals("source"))iam.clock.advance(2);
                        if(releaseFault.equals("current"))iam.clock.advance(6);
                        if(releaseFault.equals("owner"))owner.close();
                    }
                    return new ServletOutputStream(){
                        public boolean isReady(){return original.isReady();}
                        public void setWriteListener(WriteListener listener){original.setWriteListener(listener);}
                        public void write(int b)throws IOException{original.write(b);}
                        public void write(byte[] bytes,int offset,int length)throws IOException{
                            var text=new String(bytes,offset,length,StandardCharsets.UTF_8);
                            if(text.contains("\"code\":\"OK\""))successBytes.addAndGet(length);
                            original.write(bytes,offset,length);
                        }
                    };
                }
            }));bean.setOrder(-200);return bean;
        }
    }
    static ServletWebServerApplicationContext start(Class<?> type){
        return start(type,(bean,name)->bean);
    }
    static ServletWebServerApplicationContext start(Class<?> type,java.util.function.BiFunction<Object,String,Object> transform){
        var app=new SpringApplication(type);app.setDefaultProperties(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","0"),Map.entry("spring.main.banner-mode","off"),Map.entry("logging.level.root","ERROR"),Map.entry("spring.config.location","optional:classpath:/d4a-no-config/"),Map.entry("management.endpoints.web.exposure.exclude","*"),Map.entry("management.endpoints.web.discovery.enabled","false"),Map.entry("springdoc.api-docs.enabled","false"),Map.entry("spring.web.resources.add-mappings","false")));
        app.addInitializers(context->context.getBeanFactory().addBeanPostProcessor(new org.springframework.beans.factory.config.BeanPostProcessor(){
            public Object postProcessAfterInitialization(Object bean,String name){return transform.apply(bean,name);}
        }));
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
    @Test void actualHttpIngressRejectsQueryEmptyQueryBodyCookiesDuplicateAndInjectedCredentials()throws Exception {
        try(var f=new LoopbackAuthority()){
            iam=f;try(var context=start(Receiver.class);var client=HttpClient.newHttpClient()){
                var base="http://127.0.0.1:"+context.getWebServer().getPort();var token=f.portal();
                for(String variant:List.of("query","post","body","cookie","apikey","duplicate","tenant","principal","user")){
                    var request=HttpRequest.newBuilder(URI.create(base+"/auth/session"+(variant.equals("query")?"?tenant=other":""))).header("Authorization","Bearer "+token).GET();
                    switch(variant){
                        case "post"->request.POST(HttpRequest.BodyPublishers.noBody());
                        case "body"->request.method("GET",HttpRequest.BodyPublishers.ofString("{}"));
                        case "cookie"->request.header("Cookie","ambient=1");
                        case "apikey"->request.header("X-Api-Key","other");
                        case "duplicate"->request.header("Authorization","Bearer "+token);
                        case "tenant"->request.header("X-Tenant-Id","other");
                        case "principal"->request.header("X-Principal-Id","other");
                        case "user"->request.header("X-User-Id","other");
                    }
                    var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).as(variant).isEqualTo(403);assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
                    assertThat(response.body()).doesNotContain("\"code\":\"OK\"");
                }
                // URI/HTTP client normalization cannot stand in for an empty query that reached Tomcat.
                for(String raw:List.of("GET /auth/session? HTTP/1.1\r\n","GET /auth/session HTTP/1.1\r\nProxy-Authorization: other\r\n"))try(var socket=new Socket("127.0.0.1",context.getWebServer().getPort())){
                    socket.setSoTimeout(2000);
                    socket.getOutputStream().write((raw+"Host: 127.0.0.1\r\nAuthorization: Bearer "+token+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    var response=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
                    assertThat(response).startsWith("HTTP/1.1 403");
                }
                assertThat(f.requests).isEmpty();assertThat(f.businessSends).hasValue(0);
            }
        }
    }
    @Test void actualHttpInvalidSourceCurrentDenialAndAuthorityFailureAre401403503()throws Exception {
        try(var f=new LoopbackAuthority()){
            iam=f;try(var context=start(Receiver.class)){
                for(String token:List.of("invalid",f.integration(),f.portal(0)))assertFailure(get(context,token,""),401);
                f.revoked=true;assertFailure(get(context,f.portal(),""),403);
                f.revoked=false;f.unavailable=true;assertFailure(get(context,f.portal(),""),503);
                assertThat(f.businessSends).hasValue(0);
            }
        }
    }
    @Test void remainingIdentityAndCredentialAliasesCannotEnterSessionHandler()throws Exception {
        try(var f=new LoopbackAuthority()){
            iam=f;try(var context=start(Receiver.class);var client=HttpClient.newHttpClient()){
                for(String header:List.of("X-Actor","X-Roles","X-Credential","Tenant-Id","Resource")){
                    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+"/auth/session")).header("Authorization","Bearer "+f.portal()).header(header,"injected").GET().build();
                    assertFailure(client.send(request,HttpResponse.BodyHandlers.ofString()),403);
                }
                assertThat(f.requests).isEmpty();
            }
        }
    }
    @Test void trueJsonFirstWriteRejectsSourceCurrentAndOwnerExpiryWithoutSuccessBytes()throws Exception {
        for(String fault:List.of("normal","source","current","owner")){
            try(var f=new LoopbackAuthority()){
                iam=f;releaseFault=fault;successBytes.set(0);
                try(var context=start(Receiver.class)){
                    var response=get(context,f.portal(fault.equals("source")?2:40),"");
                    if(fault.equals("normal")){assertThat(response.statusCode()).isEqualTo(200);assertThat(successBytes.get()).isPositive();}
                    else {assertFailure(response,switch(fault){case "source"->401;case "current"->403;default->503;});assertThat(successBytes).hasValue(0);}
                    assertThat(f.requests).hasSize(2);assertThat(f.businessSends).hasValue(0);
                }finally{releaseFault="";}
            }
        }
    }
    static void assertFailure(HttpResponse<String> response,int status){
        assertThat(response.statusCode()).isEqualTo(status);assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).doesNotContain("\"code\":\"OK\"");assertThat(Json.read(response.body()).path("data").isNull()).isTrue();
    }
    @Configuration(proxyBeanMethods=false) @Import(Receiver.class)
    static class Secondary {
        @Bean RequestMappingHandlerMapping secondary(PortalSessionController controller)throws Exception {
            var mapping=new RequestMappingHandlerMapping(){protected boolean isHandler(Class<?> type){return false;}};
            // It is a real second MVC mapping, with the same exact bean and method.
            mapping.registerMapping(org.springframework.web.servlet.mvc.method.RequestMappingInfo.paths("/auth/session").methods(org.springframework.web.bind.annotation.RequestMethod.GET).build(),controller,PortalSessionController.class.getDeclaredMethod("session",HttpServletRequest.class,HttpServletResponse.class));
            return mapping;
        }
    }
    @Configuration(proxyBeanMethods=false) @Import(Receiver.class)
    static class DuplicateRegistration {
        @Bean ServletContextInitializer duplicate(GatewayIngressBoundary.InstalledFilterRegistration registration){return registration::onStartup;}
    }
    public static class Replacement {
        public void session(HttpServletRequest request,HttpServletResponse response){}
    }
    @Test void installationRejectsReplacedAndSecondarySessionHandlerAndWrongOrDuplicateRegistration()throws Exception {
        try(var f=new LoopbackAuthority()){
            iam=f;
            for(String fault:List.of("replacement","secondary","registration","duplicate")){
                assertThatThrownBy(()->{try(var context=start(fault.equals("secondary")?Secondary.class:fault.equals("duplicate")?DuplicateRegistration.class:Receiver.class,(bean,name)->{
                    if(fault.equals("registration")&&bean instanceof GatewayIngressBoundary.InstalledFilterRegistration registration)registration.setEnabled(false);
                    if(fault.equals("replacement")&&bean instanceof RequestMappingHandlerMapping mapping){
                        var entry=mapping.getHandlerMethods().entrySet().stream().filter(e->e.getKey().getPatternValues().contains("/auth/session")).findFirst().orElse(null);
                        if(entry!=null){mapping.unregisterMapping(entry.getKey());try{mapping.registerMapping(entry.getKey(),new Replacement(),Replacement.class.getDeclaredMethod("session",HttpServletRequest.class,HttpServletResponse.class));}catch(Exception e){throw new AssertionError(e);}}
                    }return bean;
                })){throw new AssertionError("Invalid installed endpoint accepted");}}).as(fault).isInstanceOf(Exception.class).hasStackTraceContaining(switch(fault){case "replacement","secondary"->"GATEWAY_SESSION_MAPPING_INVALID";case "registration"->"GATEWAY_FILTER_NOT_INSTALLED";default->"GATEWAY_FILTER_REINSTALL";});
            }
        }
    }
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class)
    @EnableWebSecurity @Import({GatewayController.class,GatewayIamAssembly.class,PlatformErrors.class})
    static class RunOnly {
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor properties(){return factory->factory.registerSingleton("authorityProperties",iam.config("gateway"));}
        @Bean Clock clock(){return iam.clock();}
        @Bean(destroyMethod="close") TrustedJwtDecoder decoder(AuthorityProperties p){return new TrustedJwtDecoder(p,iam.clock(),true);}
        @Bean IntegrationAccess integration(TrustedJwtDecoder decoder){return new IntegrationAccess(iam.issuer(),iam.issuer(),decoder);}
    }
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude={org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class,org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration.class})
    @Import(PortalSessionController.class)
    static class NormalLegacy {}
    @Test void normalLegacyStartsWithoutIamClientAndRunOnlyFiniteAssemblyKeepsOriginalBoundary()throws Exception {
        try(var context=start(NormalLegacy.class)){
            assertThat(context.getBeansOfType(CurrentAuthorityClient.class)).isEmpty();
            assertThat(context.getBean(PortalSessionController.class)).isNotNull();
        }
        try(var f=new LoopbackAuthority()){
            iam=f;try(var context=start(RunOnly.class);var client=HttpClient.newHttpClient()){
                assertThat(context.getBean(GatewayIngressBoundary.class).sessionInstalled()).isFalse();
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+"/admin/apps/app-a/runs")).header("Authorization","Bearer "+f.portal()).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
                var response=client.send(request,HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(503);assertThat(response.body()).contains("PUBLISHED_RUN_UNAVAILABLE");
                assertThat(f.requests).isEmpty();assertThat(f.businessSends).hasValue(0);
            }
        }
    }
}
