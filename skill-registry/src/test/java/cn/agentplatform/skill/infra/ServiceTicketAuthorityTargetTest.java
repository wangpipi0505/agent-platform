package cn.agentplatform.skill.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 当前IAM有限签名peer与真实HTTP传输；不启用生产IAM入站，不声称外部IAM已联验。 */
class ServiceTicketAuthorityTargetTest {
    static final ResourceBinding RESOURCE=new ResourceBinding("run","r-ex26-target",Digest.sha256("ex26-target-snapshot"));
    static final String FACT="/internal/tool-calls/tc-ex26-target/fact", AUTHORITY="/internal/tool-calls/tc-ex26-target";
    static final Actor CLAIMED=new Actor("forged-tenant","mallory",Set.of("ADMIN"),Map.of());
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void canonicalRegisteredTargetForwardsTheCurrentGrantWhileUrlVariantsFailBeforeIssuingOrSending() throws Exception {
        try(var iam=new LoopbackAuthority();var gateway=iam.client("gateway");var skill=iam.client("skill");var engine=iam.client("engine");
            var peer=new Peer(engine,new ServiceCall("engine","tool.fact.record",RESOURCE),FACT)) {
            var entry=entry(); var current=skill.authorize(gateway.exchange(iam.portal(),entry).value(),entry);
            var clients=clients(iam,skill,peer.url());
            try(var frame=AuthorityContext.bind(current)) {
                assertTrue(clients.post(peer.url()+FACT,Map.of("state","DONE"),CLAIMED).path("accepted").asBoolean());
                assertEquals(current.actor(),peer.verified.get().actor()); assertNotEquals(CLAIMED,peer.verified.get().actor());
                assertEquals(current.operation(),peer.verified.get().operation());
                int attempts=iam.requestAttempts(), sends=peer.sends.get();
                for(String address:List.of(
                        peer.url().replace("http://","http://mallory@")+FACT,
                        peer.url()+FACT+"#shadow",
                        peer.url()+"/internal//tool-calls/tc-ex26-target/fact",
                        peer.url()+"/internal/tool-calls/./tc-ex26-target/fact",
                        peer.url()+"/internal/tool-calls/tc-ex26-target/../tc-ex26-target/fact",
                        peer.url()+"/internal/tool-calls/%74c-ex26-target/fact",
                        peer.url()+"/internal/tool-calls/tc-ex26-target;shadow/fact",
                        " "+peer.url()+FACT)) {
                    var failure=assertThrows(PlatformException.class,()->clients.post(address,Map.of(),CLAIMED),address);
                    assertEquals(403,failure.status(),address); assertEquals("SYS-AUTH-403",failure.code(),address);
                    assertEquals(attempts,iam.requestAttempts(),"URL变体不能发起新的IAM签发");
                    assertEquals(sends,peer.sends.get(),"URL变体不能发送到业务端");
                }
            }
        }
    }

    @Test void authorityRejectsRedirectEvenWithOkBodyWithoutContactingItsDestinationWhileLegacyTransportStillFollowsGet() throws Exception {
        try(var iam=new LoopbackAuthority();var gateway=iam.client("gateway");var skill=iam.client("skill");var engine=iam.client("engine");
            var destination=new Peer(null,null,AUTHORITY);
            var source=new Peer(engine,new ServiceCall("engine","tool.authority.read",RESOURCE),AUTHORITY)) {
            source.redirect=destination.url()+AUTHORITY;
            var entry=entry(); var current=skill.authorize(gateway.exchange(iam.portal(),entry).value(),entry);
            identity(current.actor());
            try(var frame=AuthorityContext.bind(current)) {
                var failure=assertThrows(PlatformException.class,()->clients(iam,skill,source.url()).get(source.url()+AUTHORITY));
                assertEquals(502,failure.status()); assertEquals("DEPENDENCY_UNAVAILABLE",failure.code());
            }
            assertEquals(1,source.sends.get()); assertEquals(0,destination.sends.get(),"不得跟随Location隐式调用另一origin");
            assertNotNull(source.verified.get(),"首个登记目标仍核验真实签名票据与当前授权");
            // 此处只隔离旧签发缝，不改部署签名状态；旧HMAC签名本身由既有功能测试覆盖。
            var legacyTickets=mock(ServiceTickets.class);
            when(legacyTickets.ticket(any(Actor.class),eq("skill"),eq("GET"),eq(source.url()+AUTHORITY))).thenReturn("legacy-transport-fixture");
            source.verify=false;
            var legacy=new SkillClients(); legacy.setServiceTickets(legacyTickets);
            assertTrue(legacy.get(source.url()+AUTHORITY).path("accepted").asBoolean());
            assertEquals(2,source.sends.get()); assertEquals(1,destination.sends.get());
        }
    }

    private static SkillClients clients(LoopbackAuthority iam,CurrentAuthorityClient authority,String engineUrl) {
        var clients=new SkillClients();
        clients.setServiceTickets(new ServiceTickets(ServiceTickets.AUTHORITY,new IamServiceTicketProvider(iam.clock(),authority),new ServiceRoutePolicy(),Map.of("engine",engineUrl)));
        return clients;
    }
    private static ServiceCall entry() {
        var route=new ServiceRoutePolicy().routes().stream().filter(row->"gateway".equals(row.caller())&&"skill".equals(row.target())&&"delegated".equals(row.mode())).findFirst().orElseThrow();
        return new ServiceCall("skill",route.action(),RESOURCE);
    }
    private static void identity(Actor actor) {
        var jwt=Jwt.withTokenValue("verified-fixture").header("alg","HS256").subject(actor.principalId()).claim("tenant_id",actor.tenantId())
                .claim("roles",actor.roles()).claim("attributes",actor.attributes()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(180)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
    private static final class Peer implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger sends=new AtomicInteger();
        final AtomicReference<VerifiedAuthority> verified=new AtomicReference<>();
        volatile String redirect;
        volatile boolean verify=true;
        Peer(CurrentAuthorityClient authority,ServiceCall call,String path) throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                sends.incrementAndGet(); exchange.getRequestBody().readAllBytes();
                int status=200; byte[] bytes;
                try {
                    if(!path.equals(exchange.getRequestURI().getRawPath())) throw new IllegalStateException("unexpected path");
                    if(authority!=null && verify){
                        String header=exchange.getRequestHeaders().getFirst("Authorization");
                        if(header==null || !header.startsWith("Bearer "))throw new IllegalStateException("missing bearer");
                        verified.set(authority.authorize(header.substring(7),call));
                    }
                    if(redirect!=null){status=302;exchange.getResponseHeaders().set("Location",redirect);}
                    bytes=Json.write(Map.of("code","OK","data",Map.of("accepted",true))).getBytes(StandardCharsets.UTF_8);
                }catch(Throwable failure){status=403;bytes=Json.write(Map.of("code","SYS-AUTH-403","message","fixture denied")).getBytes(StandardCharsets.UTF_8);}
                exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(status,bytes.length);
                try(var out=exchange.getResponseBody()){out.write(bytes);}finally{exchange.close();}
            });
            server.start();
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        public void close(){server.stop(0);}
    }
}
