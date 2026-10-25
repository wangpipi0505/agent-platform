package cn.agentplatform.gateway.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class PublishedMetadataClientTest {
    LoopbackAuthority fixture;HttpCurrentAuthorityClient gateway;HttpServer server;
    String source;final SubjectKey subject=new SubjectKey("tenant-a","alice");
    final AtomicInteger hits=new AtomicInteger(),redirectHits=new AtomicInteger();String received;
    volatile String response;volatile int status=200;
    @BeforeEach void start()throws Exception{
        fixture=new LoopbackAuthority();fixture.portalActor(new Actor("tenant-a","alice",Set.of("EDITOR"),Map.of("dataLabels",Set.of("restricted"))));gateway=fixture.client("gateway");source=fixture.portal();response=valid();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/internal/apps/Demo/published-metadata",x->{hits.incrementAndGet();received=x.getRequestHeaders().getFirst("Authorization");byte[] bytes=response.getBytes(StandardCharsets.UTF_8);if(status==302)x.getResponseHeaders().set("Location","/redirect");try(x){x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);}});server.createContext("/redirect",x->{redirectHits.incrementAndGet();x.sendResponseHeaders(200,0);x.close();});server.start();
    }
    @AfterEach void close(){if(server!=null)server.stop(0);if(gateway!=null)gateway.close();if(fixture!=null)fixture.close();}
    URI origin(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort());}
    PublishedMetadataClient client(){return new PublishedMetadataClient(origin(),gateway,true);}
    String valid(){return Json.write(ApiResponse.ok(Map.of("appCode","Demo","versionId",17,"versionNo",3,"versionSha256",Digest.sha256("stored"))));}
    @Test void targetedTicketAndActualVersionUseDistinctNormalExchanges()throws Exception{
        try(var client=client();var app=fixture.client("app")){
            var version=client.lookup(source,subject,"Demo");assertThat(version.resource()).isEqualTo(new ResourceBinding("app-version","Demo@3",Digest.sha256("stored")));assertThat(received).startsWith("Bearer ").doesNotContain(source);
            String raw=received.substring(7);var claims=Json.read(new String(Base64.getUrlDecoder().decode(raw.split("\\.")[1]),StandardCharsets.UTF_8));assertThat(claims.path("target").asText()).isEqualTo("app");assertThat(claims.path("aud").asText()).isEqualTo("urn:agent-platform:service:app");assertThat(claims.path("jti").asText()).isNotBlank();
            var query=new ServiceCall("app",MetadataSelectors.ACTION,MetadataSelectors.published(subject.tenantId(),"Demo"));var proof=app.authorize(raw,query);
            var main=new ServiceCall("app",new ServiceRoutePolicy().require("gateway","app","GET","/admin/apps/{code}").action(),version.resource());
            assertThatThrownBy(()->gateway.resume(proof.delegation(),main)).isInstanceOf(PlatformException.class);assertThatThrownBy(()->app.forward(proof,main)).isInstanceOf(PlatformException.class);
            var mainTicket=gateway.exchange(source,main);assertThat(mainTicket.operation().grantRef()).isNotEqualTo(proof.delegation().value());assertThat(mainTicket.operation().subject()).isEqualTo(subject);assertThat(mainTicket.operation().resource()).isEqualTo(version.resource());assertThat(mainTicket.operation().expiresAt()).isEqualTo(proof.operation().expiresAt());assertThat(hits).hasValue(1);
        }
    }
    @Test void strictResponseTypesExtraFieldsWrongCodeAndInvalidVersionsAreRejected(){
        try(var client=client()){
            for(String invalid:List.of(valid().replace("\"Demo\"","\"Other\""),valid().replace("\"versionId\":17","\"versionId\":\"17\""),valid().replace("\"versionNo\":3","\"versionNo\":0"),valid().replace("\"data\":{","\"data\":{\"snapshot\":{},"),valid().replace(Digest.sha256("stored"),"NOT_SHA"))){response=invalid;assertThatThrownBy(()->client.lookup(source,subject,"Demo")).as(invalid).isInstanceOf(PlatformException.class);}
        }
    }
    @Test void redirectAndBoundedOversizeNeverFollowAnotherDestination(){try(var client=client()){
        status=302;assertThatThrownBy(()->client.lookup(source,subject,"Demo")).isInstanceOf(PlatformException.class);assertThat(redirectHits).hasValue(0);
        status=200;response="x".repeat(4097);assertThatThrownBy(()->client.lookup(source,subject,"Demo")).isInstanceOf(PlatformException.class);
    }}
    @Test void configuredOriginAndCodeCannotBecomeUserSelectedUrls(){
        for(String uri:List.of("http://example.invalid","https://app.invalid/path","https://user@app.invalid","https://app.invalid?x=y","https://app.invalid/#x","file:///tmp/a"))assertThatThrownBy(()->new PublishedMetadataClient(URI.create(uri),gateway)).isInstanceOf(IllegalArgumentException.class);
        try(var client=client()){for(String code:List.of("../Other","Demo?x=y","Demo/Other","1Demo"))assertThatThrownBy(()->client.lookup(source,subject,code)).isInstanceOf(PlatformException.class);assertThat(hits).hasValue(0);assertThat(fixture.grantCount()).isZero();}
    }
    @Test void expiredOriginalSourceCannotAcquireMainGrantAfterLookup(){try(var client=client()){
        var version=client.lookup(source,subject,"Demo");fixture.advanceClock(Duration.ofSeconds(41));var main=new ServiceCall("app",new ServiceRoutePolicy().require("gateway","app","GET","/admin/apps/{code}").action(),version.resource());
        assertThatThrownBy(()->gateway.exchange(source,main)).isInstanceOfAny(PlatformException.class,org.springframework.security.oauth2.jwt.JwtException.class);assertThat(fixture.grantCount()).isEqualTo(1);
    }}
    @Test void overflowAndExplicitCancelCloseTheActualBodySubscription(){
        var cancelled=new AtomicInteger();var body=new PublishedMetadataClient.LimitedBody();body.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){cancelled.incrementAndGet();}});body.onNext(List.of(ByteBuffer.wrap(new byte[4097])));assertThat(body.getBody().toCompletableFuture()).isCompletedExceptionally();assertThat(cancelled.get()).isPositive();
        var pending=new PublishedMetadataClient.LimitedBody();pending.cancel();pending.onSubscribe(new Flow.Subscription(){public void request(long n){throw new AssertionError();}public void cancel(){cancelled.incrementAndGet();}});assertThat(cancelled.get()).isGreaterThan(1);
    }
    @Test void closeIsTerminalAndNoCrossSubjectResultIsCached(){var client=client();client.lookup(source,subject,"Demo");client.close();assertThatThrownBy(()->client.lookup(source,subject,"Demo")).isInstanceOf(PlatformException.class);assertThat(hits).hasValue(1);}
}
