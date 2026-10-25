package cn.agentplatform.common.authority;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import static org.assertj.core.api.Assertions.*;

class RouteCoverageVerifierTest {
    static final AtomicInteger starts=new AtomicInteger();
    static ServiceRoutePolicy selected=new ServiceRoutePolicy();
    static Set<RouteCoverageVerifier.Ingress> ingress=Set.of();
    static Object controller=new UnknownController();
    static LoopbackAuthority issuer;
    static HttpCurrentAuthorityClient authority;
    @BeforeEach void authorityFixture()throws Exception {issuer=new LoopbackAuthority();authority=issuer.client("engine");}
    @AfterEach void closeAuthority(){authority.close();issuer.close();}
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    static class UnregisteredApp {
        @Bean Object unknown(){return controller;}
        @Bean org.springframework.web.servlet.handler.MappedInterceptor receiverGuard(){
            return new ServiceAuthorization("engine",new ServiceRoutePolicy(),authority).mappedInterceptor();
        }
        @Bean RouteCoverageVerifier coverage(List<RequestMappingInfoHandlerMapping> mapping){return new RouteCoverageVerifier("engine",selected,mapping,ingress);}
        @Bean TomcatServletWebServerFactory factory(){return new TomcatServletWebServerFactory(){
            @Override public WebServer getWebServer(ServletContextInitializer... initializers){
                var real=super.getWebServer(initializers);
                return new WebServer(){public void start(){starts.incrementAndGet();real.start();}public void stop(){real.stop();}public int getPort(){return real.getPort();}};
            }
        };}
    }
    @RestController static class UnknownController {
        @GetMapping("/internal/unregistered") String unknown(){return "must never listen";}
    }
    @Test void unknownRuntimeHandlerFailsBeforeWebServerStart(){
        starts.set(0);selected=new ServiceRoutePolicy();ingress=Set.of();controller=new UnknownController();
        fails("ROUTE_COVERAGE_MISSING");
    }
    @RestController static class Aliases {
        @GetMapping({"/admin/runs/{id}","/v1/runs/{id}"}) String get(){return "ok";}
    }
    @RestController static class Methods {
        @RequestMapping(value="/admin/runs/{id}",method={RequestMethod.GET,RequestMethod.POST}) String get(){return "ok";}
    }
    @RestController static class CustomError {
        @RequestMapping("/error") String error(){return "custom";}
    }
    @RestController static class Docs {
        @GetMapping("/v3/api-docs/custom") String docs(){return "custom docs";}
    }
    @Test void everyRuntimeAliasMustBeCoveredBeforeListening(){
        selected=new ServiceRoutePolicy(new ServiceRoutePolicy().routes().stream().filter(r->!r.template().equals("/v1/runs/{id}")).toList());
        controller=new Aliases();ingress=Set.of();fails("ROUTE_COVERAGE_MISSING");
    }
    @Test void everyRuntimeMethodMustBeCoveredBeforeListening(){
        selected=new ServiceRoutePolicy();controller=new Methods();ingress=Set.of();fails("ROUTE_COVERAGE_MISSING");
    }
    @Test void customDocsHaveNoFrameworkPackageOrPathExemption(){
        selected=new ServiceRoutePolicy();controller=new Docs();ingress=Set.of();fails("ROUTE_COVERAGE_MISSING");
    }
    @Test void explicitIngressRequiresExactHandlerMethodAndCannotBeAnUnusedBypass()throws Exception {
        selected=new ServiceRoutePolicy();controller=new UnknownController();
        ingress=Set.of(new RouteCoverageVerifier.Ingress("GET","/internal/unregistered",Aliases.class.getDeclaredMethod("get")));
        fails("ROUTE_COVERAGE_MISSING");
        ingress=Set.of(new RouteCoverageVerifier.Ingress("GET","/unused",UnknownController.class.getDeclaredMethod("unknown")));
        controller=new Aliases();fails("INGRESS_REGISTRATION_UNUSED");
        assertThatThrownBy(()->new RouteCoverageVerifier.Ingress("GET","/admin/**",Aliases.class.getDeclaredMethod("get")))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void fullyRegisteredRuntimeAliasesStartAndServeActualHttp()throws Exception {
        selected=new ServiceRoutePolicy();controller=new Aliases();ingress=Set.of();starts.set(0);
        try(var app=ServiceAuthorizationTest.start(UnregisteredApp.class)){
            assertThat(starts).hasValue(1);
            // Runtime coverage itself is independent of the security chain, whose HTTP tests
            // exercise the real signed receiver. A missing bearer here is rejected by Boot.
            assertThat(app.getWebServer().getPort()).isGreaterThan(0);
            assertThat(ServiceAuthorizationTest.request(app,"/admin/runs/r-test","none","GET").statusCode()).isEqualTo(401);
        }
    }
    private static void fails(String reason){fails(UnregisteredApp.class,reason);}
    private static void fails(Class<?> appClass,String reason){
        starts.set(0);
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(SpringApplication.class);
        boolean additive=logger.isAdditive();
        var capture=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();capture.start();
        try{
            assertThatThrownBy(()->{try(var app=ServiceAuthorizationTest.start(appClass,application->application.addInitializers(context->{
                // Logging initializes before this hook. Capture only SpringApplication's expected
                // startup-failure event; other loggers and every production exception stay intact.
                logger.addAppender(capture);logger.setAdditive(false);
            }))) {}}).hasStackTraceContaining(reason);
            assertThat(capture.list).anyMatch(event->event.getFormattedMessage().equals("Application run failed")&&event.getThrowableProxy()!=null);
            assertThat(starts).as("Boot webServer.start and protocol listening remain zero").hasValue(0);
            System.out.println("Captured expected startup rejection: "+reason+"; webServer.start=0");
        }finally{logger.detachAppender(capture);logger.setAdditive(additive);capture.stop();}
    }

    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    static class SecondaryMappingApp extends UnregisteredApp {
        @Bean org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping secondaryMapping(){
            return new org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping(){
                @Override protected boolean isHandler(Class<?> type){return false;}
                @Override public void afterPropertiesSet(){
                    super.afterPropertiesSet();
                    try {registerMapping(org.springframework.web.servlet.mvc.method.RequestMappingInfo.paths("/actuator/info")
                        .methods(RequestMethod.GET).build(),new UnknownController(),UnknownController.class.getDeclaredMethod("unknown"));}
                    catch(Exception e){throw new AssertionError(e);}
                }
            };
        }
    }
    @Test void secondaryRequestMappingInfoBeanIsAlsoCheckedBeforeListenerStarts(){
        selected=new ServiceRoutePolicy();controller=new Aliases();ingress=Set.of();starts.set(0);
        fails(SecondaryMappingApp.class,"ROUTE_COVERAGE_MISSING: engine GET /actuator/info");
    }

    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    static class PartialGuardApp extends UnregisteredApp {
        @Override @Bean org.springframework.web.servlet.handler.MappedInterceptor receiverGuard(){
            return new org.springframework.web.servlet.handler.MappedInterceptor(new String[]{"/only-this"},new ServiceAuthorization("engine",new ServiceRoutePolicy(),authority));
        }
    }
    @RestController static class Disabled {
        @PostMapping("/internal/tool-calls/register") String register(){return "must not run";}
    }
    @Test void partialGuardDoesNotCountAsCoverageOfRegisteredCapabilities(){
        selected=new ServiceRoutePolicy();controller=new Aliases();ingress=Set.of();
        fails(PartialGuardApp.class,"ROUTE_GUARD_MISSING");
    }
    @Test void disabledOnlyMappingAlsoNeedsARealDenialGuard(){
        selected=new ServiceRoutePolicy();controller=new Disabled();ingress=Set.of();
        fails(PartialGuardApp.class,"ROUTE_GUARD_MISSING");
    }

}
