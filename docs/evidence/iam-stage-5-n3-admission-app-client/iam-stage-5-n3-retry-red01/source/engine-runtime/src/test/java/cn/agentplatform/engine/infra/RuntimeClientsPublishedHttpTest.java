package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;
import cn.agentplatform.engine.api.RunController;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import java.util.function.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class RuntimeClientsPublishedHttpTest {
    static final String CODE="client-flow",SHA=Digest.sha256("stored-client-candidate");
    static final ResourceBinding ROOT=new ResourceBinding("app-version",CODE+"@1",SHA);
    static final ServiceCall MAIN=new ServiceCall("engine","proxy.engine.post.RunController.run",ROOT);
    static final Actor ACTOR=new Actor("tenant-a","alice",Set.of("EDITOR"),Map.of("region",Set.of("A"),"dataLabels",Set.of("initial")));

    @FunctionalInterface interface BoundWork<T>{T apply(RetainedAuthorization proof)throws Exception;}
    static <T>T bound(LoopbackAuthority iam,HttpCurrentAuthorityClient engine,BoundWork<T> work)throws Exception{
        try(var gateway=iam.client("gateway");var decoder=new TrustedJwtDecoder(iam.config("engine"),iam.clock(),true)){
            var ticket=gateway.exchange(iam.portal(),MAIN);var request=new MockHttpServletRequest("POST","/admin/apps/"+CODE+"/runs");var response=new MockHttpServletResponse();
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/admin/apps/{code}/runs");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(ticket.value()),List.of()));
            var handler=new HandlerMethod(new RunController(null,null,null,null),RunController.class.getMethod("run",String.class,JsonNode.class));var guard=new ServiceAuthorization("engine",new ServiceRoutePolicy(),engine);
            try{guard.preHandle(request,response,handler);return work.apply(AuthorityContext.requireInitialFor(Actor.current(),ROOT));}
            finally{guard.afterCompletion(request,response,handler,null);SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}
        }
    }
    static JsonNode payload(){return Json.tree(Map.of("code","OK","data",Map.of("appCode",CODE,"appVersionId",17,"versionId",17,"versionNo",1,"appVersionKey",CODE+"@1","candidateSha256",SHA,"type","FLOW","config",Map.of(),"definition",Map.of("nodes",List.of()))));}
    static final class Fixture implements AutoCloseable{
        final LoopbackAuthority iam=new LoopbackAuthority();final HttpCurrentAuthorityClient engine;final HttpServer server;
        final ExecutorService executor=Executors.newCachedThreadPool();final AtomicInteger sends=new AtomicInteger(),redirected=new AtomicInteger();
        volatile Consumer<com.sun.net.httpserver.HttpExchange> handler=x->reply(x,200,payload().toString());volatile String received;
        Fixture()throws Exception{iam.portalActor(ACTOR);engine=iam.client("engine");server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);server.createContext("/internal/apps/"+CODE+"/published",x->{sends.incrementAndGet();received=x.getRequestHeaders().getFirst("Authorization");handler.accept(x);});server.createContext("/redirected",x->{redirected.incrementAndGet();reply(x,200,payload().toString());});server.start();}
        String origin(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        RuntimeClients clients(){return new RuntimeClients(iam.config("engine"),engine,origin(),true);}
        @Override public void close()throws Exception{server.stop(0);executor.shutdownNow();assertThat(executor.awaitTermination(3,TimeUnit.SECONDS)).isTrue();engine.close();iam.close();}
    }
    static void reply(com.sun.net.httpserver.HttpExchange x,int status,String value){try{var b=value.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(status,b.length);try(var out=x.getResponseBody()){out.write(b);}}catch(java.io.IOException ignored){x.close();}}
    static void unavailable(Throwable failure){assertThat(failure).isInstanceOfSatisfying(PlatformException.class,e->{assertThat(e.status()).isEqualTo(503);assertThat(e.code()).isEqualTo("APP_PUBLISHED_UNAVAILABLE");assertThat(e.getMessage()).doesNotContain("private-upstream-body");});}

    @Test void boundMainForwardsExactOperationGrantOriginAndNeverPortalSource()throws Exception{
        try(var f=new Fixture();var clients=f.clients()){
            bound(f.iam,f.engine,proof->{assertThat(clients.published(CODE,proof)).isEqualTo(payload().path("data"));assertThat(f.received).startsWith("Bearer ");String token=f.received.substring(7);var claims=Json.read(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),StandardCharsets.UTF_8));assertThat(claims.path("caller").asText()).isEqualTo("engine");assertThat(claims.path("action").asText()).isEqualTo("app.published.read");assertThat(claims.path("grant_ref").asText()).isEqualTo(proof.current().delegation().value());assertThat(claims.path("operation").path("operation_id").asText()).isEqualTo(proof.current().operation().operationId());assertThat(claims.path("resource")).isEqualTo(Json.tree(Map.of("kind",ROOT.kind(),"id",ROOT.id(),"version_digest",ROOT.versionDigest())));assertThat(claims.path("origin_authentication_id").asText()).isEqualTo(proof.requireOriginAuthenticationId());return null;});assertThat(f.sends).hasValue(1);
        }
    }
    @Test void iamActorGetPostStreamAndUnknownTargetsNeverSignOrSend()throws Exception{
        try(var f=new Fixture();var clients=f.clients()){
            int before=f.iam.requestAttempts();
            for(String target:Arrays.asList("app","skill","model",null,"","modle")){
                assertThatThrownBy(()->clients.get(target,"/private",ACTOR)).isInstanceOf(PlatformException.class);
                assertThatThrownBy(()->clients.post(target,"/private",Map.of(),ACTOR)).isInstanceOf(PlatformException.class);
            }
            assertThatThrownBy(()->clients.chatStream(Map.of(),ACTOR,s->{})).isInstanceOf(PlatformException.class);assertThat(f.iam.requestAttempts()).isEqualTo(before);assertThat(f.sends).hasValue(0);
        }
    }
    @Test void trustedModeRejectsMissingCapabilityAndUnsafeOrigins()throws Exception{
        try(var f=new Fixture()){
            assertThatThrownBy(()->new RuntimeClients(f.iam.config("engine"),null,f.origin(),true)).isInstanceOf(IllegalStateException.class);
            for(String origin:List.of(f.origin()+"/",f.origin()+"/prefix",f.origin()+"?q=1",f.origin()+"#x","http://localhost:1","http://127.0.0.1@external.invalid","ftp://127.0.0.1:1"))assertThatThrownBy(()->new RuntimeClients(f.iam.config("engine"),f.engine,origin,true)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->new RuntimeClients(f.iam.config("app"),f.engine,f.origin(),true)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void publicVerifiedObjectAndUnboundCaptureCannotSend()throws Exception{
        try(var f=new Fixture();var clients=f.clients()){
            var retained=bound(f.iam,f.engine,proof->{try(var fake=AuthorityContext.bind(proof.current())){assertThatThrownBy(()->clients.published(CODE,proof)).isInstanceOf(PlatformException.class);}return proof;});assertThatThrownBy(()->clients.published(CODE,retained)).isInstanceOf(PlatformException.class);assertThat(f.sends).hasValue(0);
        }
    }
    @Test void unsafeCodeAndWrongRootNeverSend()throws Exception{
        try(var f=new Fixture();var clients=f.clients()){
            bound(f.iam,f.engine,proof->{for(String code:List.of("../x","client-flow?x=1","other","a%2Fb"))assertThatThrownBy(()->clients.published(code,proof)).isInstanceOf(PlatformException.class);return null;});assertThat(f.sends).hasValue(0);
        }
    }
    @Test void appHttpFailureSemanticsRemainDistinct()throws Exception{
        try(var f=new Fixture();var clients=f.clients()){
            for(int status:List.of(401,403,409,503)){f.handler=x->reply(x,status,"private-upstream-body");bound(f.iam,f.engine,proof->{assertThatThrownBy(()->clients.published(CODE,proof)).isInstanceOfSatisfying(PlatformException.class,e->{assertThat(e.status()).isEqualTo(status);assertThat(e.code()).isEqualTo("APP_PUBLISHED_HTTP_"+status);assertThat(e.getMessage()).doesNotContain("private-upstream-body");});return null;});}assertThat(f.sends).hasValue(4);
        }
    }
    @Test void redirectIsNotFollowedOrRetried()throws Exception{
        try(var f=new Fixture();var clients=f.clients()){
            f.handler=x->{x.getResponseHeaders().set("Location",f.origin()+"/redirected");reply(x,302,"private-upstream-body");};bound(f.iam,f.engine,proof->{assertThatThrownBy(()->clients.published(CODE,proof)).satisfies(RuntimeClientsPublishedHttpTest::unavailable);return null;});assertThat(f.sends).hasValue(1);assertThat(f.redirected).hasValue(0);
        }
    }
    @Test void disconnectedPublishedGetIsNeverAutomaticallyResent()throws Exception{
        try(var f=new Fixture();var clients=f.clients()){
            bound(f.iam,f.engine,proof->{clients.published(CODE,proof);return null;});assertThat(f.sends).hasValue(1);
            f.handler=x->x.close();bound(f.iam,f.engine,proof->{assertThatThrownBy(()->clients.published(CODE,proof)).satisfies(RuntimeClientsPublishedHttpTest::unavailable);return null;});assertThat(f.sends).as("one warmup plus one failed GET; the transport must not resend it").hasValue(2);
        }
    }
    @Test void numericIdsVersionKeyAndStoredDigestAreAllValidated()throws Exception{
        try(var f=new Fixture();var clients=f.clients()){
            for(String field:List.of("appCode","appVersionId","versionId","versionNo","appVersionKey","candidateSha256")){
                var envelope=(com.fasterxml.jackson.databind.node.ObjectNode)payload();((com.fasterxml.jackson.databind.node.ObjectNode)envelope.path("data")).set(field,Json.tree(field.contains("Id")?0:"wrong"));f.handler=x->reply(x,200,envelope.toString());bound(f.iam,f.engine,proof->{assertThatThrownBy(()->clients.published(CODE,proof)).satisfies(RuntimeClientsPublishedHttpTest::unavailable);return null;});
            }
        }
    }
    @Test void malformedJsonIsUnavailableWithoutBodyEcho()throws Exception{
        try(var f=new Fixture();var clients=f.clients()){f.handler=x->reply(x,200,"private-upstream-body");bound(f.iam,f.engine,proof->{assertThatThrownBy(()->clients.published(CODE,proof)).satisfies(RuntimeClientsPublishedHttpTest::unavailable);return null;});}
    }
    @Test void completeResponseStillRequiresOriginalBoundFrame()throws Exception{
        try(var f=new Fixture();var clients=f.clients()){f.handler=x->{f.iam.advanceClock(Duration.ofSeconds(6));reply(x,200,payload().toString());};bound(f.iam,f.engine,proof->{assertThatThrownBy(()->clients.published(CODE,proof)).isInstanceOfSatisfying(PlatformException.class,e->assertThat(e.status()).isEqualTo(403));return null;});assertThat(f.sends).hasValue(1);}
    }
    @Test void oversizedBodyCancelsBeforeUnboundedAllocation()throws Exception{
        try(var f=new Fixture();var clients=f.clients()){f.handler=x->reply(x,200," ".repeat(4194305));long start=System.nanoTime();bound(f.iam,f.engine,proof->{assertThatThrownBy(()->clients.published(CODE,proof)).satisfies(RuntimeClientsPublishedHttpTest::unavailable);return null;});assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(4));assertThat(f.sends).hasValue(1);}
    }
    @Test void headersThenUnfinishedBodyHasCompleteDeadlineAndClientCloses()throws Exception{
        try(var f=new Fixture()){
            var release=new CountDownLatch(1);var entered=new CountDownLatch(1);
            f.handler=x->{try{x.sendResponseHeaders(200,0);x.getResponseBody().write('{');x.getResponseBody().flush();entered.countDown();release.await(5,TimeUnit.SECONDS);}catch(Exception ignored){}finally{x.close();}};
            var before=Thread.getAllStackTraces().keySet().stream().map(Thread::threadId).collect(java.util.stream.Collectors.toSet());
            try(var clients=f.clients()){long start=System.nanoTime();bound(f.iam,f.engine,proof->{assertThatThrownBy(()->clients.published(CODE,proof)).satisfies(RuntimeClientsPublishedHttpTest::unavailable);return null;});assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();assertThat(Duration.ofNanos(System.nanoTime()-start)).isBetween(Duration.ofMillis(1800),Duration.ofSeconds(4));}finally{release.countDown();}
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(newSelectors(before)>0&&System.nanoTime()<until)Thread.sleep(10);assertThat(newSelectors(before)).isZero();assertThat(f.sends).hasValue(1);
        }
    }
    static long newSelectors(Set<Long> before){return Thread.getAllStackTraces().keySet().stream().filter(t->!before.contains(t.threadId())&&t.isAlive()&&t.getName().contains("SelectorManager")).count();}
    @Test void unknownServiceNeverSignsOrSends() throws Exception {
        var sends = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            sends.incrementAndGet();
            byte[] data = "{\"code\":\"OK\",\"data\":{}}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, data.length);
            try (var out=x.getResponseBody()) {out.write(data);}
        });
        server.start();
        try {
            var clients = new RuntimeClients();
            clients.model = "http://127.0.0.1:" + server.getAddress().getPort();
            var actor = new Actor("tenant-a", "alice", Set.of("EDITOR"), Map.of());
            for (String target : Arrays.asList(null, "", "modle", "APP")) {
                assertThatThrownBy(() -> clients.get(target, "/must-not-send", actor))
                    .isInstanceOfSatisfying(PlatformException.class, e -> assertThat(e.status()).isEqualTo(403));
            }
            assertThat(sends).hasValue(0);
        } finally {server.stop(0);}
    }
}
