package cn.agentplatform.common.authority;

import cn.agentplatform.common.Json;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Finite test-only issuer. Keys never leave this test process as production configuration. */
final class LoopbackIssuer implements AutoCloseable {
    final RSAKey k1 = key("k1"), k2 = key("k2");
    final HttpServer server;
    final AtomicInteger requests = new AtomicInteger();
    final AtomicInteger unexpectedRequests = new AtomicInteger();
    volatile String jwks = new JWKSet(k1.toPublicJWK()).toString();
    volatile int status = 200;
    LoopbackIssuer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            requests.incrementAndGet();
            var body = jwks.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (status == 302) exchange.getResponseHeaders().set("Location", issuer()+"/unexpected");
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
            exchange.close();
        });
        server.createContext("/token", exchange -> {
            var body=exchange.getRequestBody().readNBytes(4096);
            exchange.sendResponseHeaders(200,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}exchange.close();
        });
        server.createContext("/decision", exchange -> {
            var body=exchange.getRequestBody().readNBytes(4096);
            exchange.sendResponseHeaders(200,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}exchange.close();
        });
        server.createContext("/unexpected",exchange->{unexpectedRequests.incrementAndGet();exchange.sendResponseHeaders(200,0);exchange.close();});
        server.start();
    }
    static RSAKey key(String id) {
        try { return new RSAKeyGenerator(2048).keyID(id).algorithm(JWSAlgorithm.RS256).keyUse(KeyUse.SIGNATURE).generate(); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    String issuer() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
    URI uri(String path) { return URI.create(issuer()+path); }
    void keys(RSAKey... keys) { jwks=Json.write(Map.of("keys",Arrays.stream(keys).map(k->k.toPublicJWK().toJSONObject()).toList())); }
    Map<String,Object> portal(Instant now) {
        var c = new LinkedHashMap<String,Object>();
        c.put("iss",issuer());c.put("aud",List.of("portal-gateway"));c.put("sub","alice");
        c.put("tenant_id","tenant-a");c.put("iat",now.getEpochSecond());c.put("exp",now.plusSeconds(300).getEpochSecond());
        c.put("jti","test-jti");c.put("token_use","user");c.put("client_id","portal-web");
        c.put("roles",List.of("EDITOR","ADMIN","SERVICE","GATEWAY_AUTH"));
        c.put("attributes",Map.of("region",List.of("A","B"),"dataLabels",List.of("confidential"),"unknown",List.of("all")));
        c.put("scope","platform.access extra");return c;
    }
    Map<String,Object> service(Instant now, boolean workload) {
        var c=portal(now);c.put("iss",issuer()+"/authority");c.put("aud",List.of("urn:agent-platform:service:app"));
        c.put("token_use","service");c.put("client_id","gateway");c.put("caller","gateway");c.put("target","app");
        c.put("exp",now.plusSeconds(60).getEpochSecond());c.put("nbf",now.getEpochSecond());
        String action=workload?"integration.origin.check":new ServiceRoutePolicy().routes().stream()
            .filter(r->"delegated".equals(r.mode())&&"gateway".equals(r.caller())&&"app".equals(r.target())).findFirst().orElseThrow().action();
        c.put("action",action);c.put("scope",action);
        c.put("resource",Map.of("kind","origin","id","test-origin","version_digest","v1"));
        c.put("authority_mode",workload?"workload":"delegated");
        if(workload){c.put("sub","gateway");for(var k:List.of("tenant_id","roles","attributes"))c.remove(k);}
        else{c.put("grant_ref","test-grant");c.put("authorization_version","v1");}
        return c;
    }
    String sign(Map<String,Object> claims) { return sign(claims,k1,"k1","at+jwt"); }
    String signRaw(String payload,JWSHeader header){
        try{var jwt=new JWSObject(header,new Payload(payload));jwt.sign(new RSASSASigner(k1));return jwt.serialize();}
        catch(Exception e){throw new AssertionError(e);}
    }
    String sign(Map<String,Object> claims,RSAKey key,String kid,String typ) {
        try {
            var h=new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType(typ));
            if(kid!=null)h.keyID(kid);
            // Preserve malformed raw claim types; JWTClaimsSet.parse would coerce dates before signing.
            var jwt=new JWSObject(h.build(),new Payload(Json.write(claims)));
            jwt.sign(new RSASSASigner(key));return jwt.serialize();
        }catch(Exception e){throw new AssertionError(e);}
    }
    AuthorityProperties config() {
        return new AuthorityProperties("iam","gateway",
            new AuthorityProperties.Portal(true,issuer(),uri("/jwks").toString(),"portal-gateway","portal-web",
                Set.of("EDITOR"),Set.of("region","dataLabels"),Map.of("region",Set.of("A"),"dataLabels",Set.of("restricted")),Set.of("platform.access")),
            new AuthorityProperties.Authority(issuer()+"/authority",uri("/jwks").toString(),uri("/token").toString(),uri("/decision").toString(),
                "gateway","env:AGENT_SECRET_IAM_GATEWAY_CLIENT","RS256","urn:agent-platform:service:gateway",60,5,1000,2000),null,null,null,false,null);
    }
    AuthorityProperties internalConfig(){
        var c=config();var a=c.authority();
        return new AuthorityProperties("iam","app",null,
            new AuthorityProperties.Authority(a.issuer(),a.jwksUri(),a.tokenUri(),a.decisionUri(),"app","env:AGENT_SECRET_IAM_APP_CLIENT",
                "RS256","urn:agent-platform:service:app",60,5,1000,2000),null,null,null,false,null);
    }
    @Override public void close(){server.stop(0);}
}
