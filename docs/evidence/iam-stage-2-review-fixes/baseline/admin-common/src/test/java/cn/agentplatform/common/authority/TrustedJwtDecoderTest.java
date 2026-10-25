package cn.agentplatform.common.authority;

import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.JwtException;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import cn.agentplatform.common.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.net.*;
import java.net.http.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.cors.CorsConfigurationSource;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import com.sun.net.httpserver.HttpServer;
import static org.assertj.core.api.Assertions.*;

class TrustedJwtDecoderTest {
    static final Instant NOW=Instant.parse("2026-10-20T00:00:00Z");
    @Test void validRsaSignatureWorksButWrongSignatureNeverPasses() throws Exception {
        try(var issuer=new LoopbackIssuer()){
            var decoder=new TrustedJwtDecoder(issuer.config(),Clock.fixed(NOW,ZoneOffset.UTC),true);
            assertThat(decoder.decode(issuer.sign(issuer.portal(NOW))).getSubject()).isEqualTo("alice");
            assertThatThrownBy(()->decoder.decode(issuer.sign(issuer.portal(NOW),issuer.k2,"k1","at+jwt")))
                .isInstanceOf(JwtException.class);
        }
    }
    @Test void invalidClaimMatrixIsRejectedWithoutClaimCoercion() throws Exception {
        try(var issuer=new LoopbackIssuer()){
            var decoder=new TrustedJwtDecoder(issuer.config(),Clock.fixed(NOW,ZoneOffset.UTC),true);
            Map<String,Consumer<Map<String,Object>>> cases=new LinkedHashMap<>();
            for(String key:List.of("exp","iat","jti","tenant_id","sub","client_id"))cases.put("missing-"+key,c->c.remove(key));
            cases.put("issuer",c->c.put("iss","https://attacker.invalid"));
            cases.put("audience",c->c.put("aud",List.of("wrong")));
            cases.put("multi-audience",c->c.put("aud",List.of("portal-gateway","other")));
            cases.put("use",c->c.put("token_use","service"));
            cases.put("id-token",c->c.put("token_use","id"));
            cases.put("client",c->c.put("client_id","other"));
            cases.put("expired",c->c.put("exp",NOW.minusSeconds(1).getEpochSecond()));
            cases.put("exact-expiry",c->c.put("exp",NOW.getEpochSecond()));
            cases.put("future-nbf",c->c.put("nbf",NOW.plusSeconds(1).getEpochSecond()));
            cases.put("future-iat",c->c.put("iat",NOW.plusSeconds(1).getEpochSecond()));
            cases.put("numeric-sub",c->c.put("sub",42));
            cases.put("numeric-jti",c->c.put("jti",42));
            cases.put("string-exp",c->c.put("exp","1792454700"));
            cases.put("fraction-iat",c->c.put("iat",1.5));
            cases.put("overflow-exp",c->c.put("exp",(1L<<61)+NOW.plusSeconds(300).getEpochSecond()));
            cases.put("overflow-iat",c->c.put("iat",(1L<<61)+NOW.getEpochSecond()));
            cases.put("scope-type",c->c.put("scope",List.of("platform.access")));
            cases.put("role-type",c->c.put("roles",List.of(42)));
            cases.put("attribute-type",c->c.put("attributes",Map.of("region","A")));
            for(var entry:cases.entrySet()){
                var c=issuer.portal(NOW);entry.getValue().accept(c);
                assertThat(catchThrowable(()->decoder.decode(issuer.sign(c)))).as(entry.getKey()).isInstanceOf(JwtException.class);
            }
            for(String kid:Arrays.asList(null,"","unknown"))assertThatThrownBy(()->decoder.decode(issuer.sign(issuer.portal(NOW),issuer.k1,kid,"at+jwt"))).isInstanceOf(JwtException.class);
            assertThatThrownBy(()->decoder.decode(issuer.sign(issuer.portal(NOW),issuer.k1,"k1","JWT"))).isInstanceOf(JwtException.class);
            assertThatThrownBy(()->decoder.decode("a".repeat(16385))).isInstanceOf(JwtException.class);
            var claims=JWTClaimsSet.parse(Json.write(issuer.portal(NOW)));
            var hs=new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),claims);
            hs.sign(new MACSigner(issuer.k1.toRSAPublicKey().getEncoded()));
            assertThatThrownBy(()->decoder.decode(hs.serialize())).isInstanceOf(JwtException.class);
            assertThatThrownBy(()->decoder.decode(new PlainJWT(claims).serialize())).isInstanceOf(JwtException.class);
        }
    }
    @Test void rotationOutageRecoveryAndClockNeverReuseAStaleKeyOrExpiredToken() throws Exception {
        try(var issuer=new LoopbackIssuer()){
            var clock=new MutableClock();var decoder=new TrustedJwtDecoder(issuer.config(),clock,true);
            var one=issuer.sign(issuer.portal(NOW));var two=issuer.sign(issuer.portal(NOW),issuer.k2,"k2","at+jwt");
            decoder.decode(one);issuer.keys(issuer.k1,issuer.k2);decoder.decode(two);decoder.decode(one);
            issuer.keys(issuer.k2);assertThatThrownBy(()->decoder.decode(one)).isInstanceOf(JwtException.class);
            decoder.decode(two);issuer.status=503;
            assertThatThrownBy(()->decoder.decode(two)).isInstanceOf(PlatformException.class).extracting(e->((PlatformException)e).status()).isEqualTo(503);
            issuer.status=200;decoder.decode(two);clock.now=NOW.plusSeconds(301);
            assertThatThrownBy(()->decoder.decode(two)).isInstanceOf(JwtException.class);
            assertThat(issuer.requests.get()).isGreaterThanOrEqualTo(7);
        }
    }
    @Test void malformedJwksIsUnavailableNotAKeyFallback() throws Exception {
        try(var issuer=new LoopbackIssuer()){
            var decoder=new TrustedJwtDecoder(issuer.config(),Clock.fixed(NOW,ZoneOffset.UTC),true);
            var good=issuer.k1.toPublicJWK().toJSONObject();
            var privateKey=issuer.k1.toJSONObject();
            var wrongUse=new LinkedHashMap<>(good);wrongUse.put("use","enc");
            var noKid=new LinkedHashMap<>(good);noKid.remove("kid");
            var wrongAlg=new LinkedHashMap<>(good);wrongAlg.put("alg","HS256");
            var oct=Map.<String,Object>of("kid","oct","kty","oct","k","dGVzdA");
            for(var keys:List.of(List.of(good,good),List.of(privateKey),List.of(wrongUse),List.of(noKid),List.of(wrongAlg),List.of(good,oct),Collections.nCopies(9,good))){
                issuer.jwks=Json.write(Map.of("keys",keys));
                assertThatThrownBy(()->decoder.decode(issuer.sign(issuer.portal(NOW)))).isInstanceOf(PlatformException.class);
            }
            issuer.jwks="not-json";assertThatThrownBy(()->decoder.decode(issuer.sign(issuer.portal(NOW)))).isInstanceOf(PlatformException.class);
        }
    }
    @Test void tokenUrlsAndDuplicateRawClaimsNeverDriveNetworkDiscovery()throws Exception{
        try(var issuer=new LoopbackIssuer();var decoder=new TrustedJwtDecoder(issuer.config(),Clock.fixed(NOW,ZoneOffset.UTC),true)){
            var header=new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").type(new JOSEObjectType("at+jwt")).jwkURL(issuer.uri("/unexpected")).build();
            assertThatThrownBy(()->decoder.decode(issuer.signRaw(Json.write(issuer.portal(NOW)),header))).isInstanceOf(JwtException.class);
            var plainHeader=new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").type(new JOSEObjectType("at+jwt")).build();
            String duplicate="{\"iss\":\"https://attacker.invalid\","+Json.write(issuer.portal(NOW)).substring(1);
            assertThatThrownBy(()->decoder.decode(issuer.signRaw(duplicate,plainHeader))).isInstanceOf(JwtException.class);
            assertThat(issuer.requests).hasValue(0);assertThat(issuer.unexpectedRequests).hasValue(0);
        }
    }
    @Test void trustedInputRequiresExplicitTestProfileForHttp() throws Exception {
        try(var issuer=new LoopbackIssuer()){
            assertThatThrownBy(()->new TrustedJwtDecoder(issuer.config(),Clock.fixed(NOW,ZoneOffset.UTC),false)).hasMessageContaining("IAM_URI_INVALID");
        }
    }
    @Test void delegatedAndWorkloadTicketsHaveDisjointSignedBindings()throws Exception{
        try(var issuer=new LoopbackIssuer();var decoder=new TrustedJwtDecoder(issuer.internalConfig(),Clock.fixed(NOW,ZoneOffset.UTC),true)){
            var delegated=issuer.service(NOW,false);var workload=issuer.service(NOW,true);
            assertThat(decoder.decode(issuer.sign(delegated)).getClaimAsString("grant_ref")).isEqualTo("test-grant");
            var jwt=decoder.decode(issuer.sign(workload));assertThat(jwt.getSubject()).isEqualTo("gateway");
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
            try{assertThatThrownBy(Actor::current).isInstanceOf(PlatformException.class).extracting(e->((PlatformException)e).status()).isEqualTo(403);}
            finally{SecurityContextHolder.clearContext();}
            for(String k:List.of("tenant_id","grant_ref","authorization_version")){
                var c=new LinkedHashMap<>(delegated);c.remove(k);assertThatThrownBy(()->decoder.decode(issuer.sign(c))).isInstanceOf(JwtException.class);
            }
            for(String k:List.of("tenant_id","grant_ref","roles","attributes","authorization_version","user_id")){
                var c=new LinkedHashMap<>(workload);c.put(k,k.equals("roles")?List.of("ADMIN"):k.equals("attributes")?Map.of():"user");
                assertThatThrownBy(()->decoder.decode(issuer.sign(c))).isInstanceOf(JwtException.class);
            }
            for(var original:List.of(delegated,workload)){
                Map<String,Object> changes=Map.of("client_id","other","caller","engine","target","engine","authority_mode","other",
                    "action","business.delete","scope","unrelated extra","resource",Map.of("kind","origin","id","test"),"exp",NOW.plusSeconds(61).getEpochSecond());
                for(var entry:changes.entrySet()){
                    var c=new LinkedHashMap<>(original);c.put(entry.getKey(),entry.getValue());
                    assertThat(catchThrowable(()->decoder.decode(issuer.sign(c)))).as(entry.getKey()).isInstanceOf(JwtException.class);
                }
            }
            var c=new LinkedHashMap<>(workload);c.put("sub","alice");assertThatThrownBy(()->decoder.decode(issuer.sign(c))).isInstanceOf(JwtException.class);
            var missingNbf=new LinkedHashMap<>(workload);missingNbf.remove("nbf");assertThatThrownBy(()->decoder.decode(issuer.sign(missingNbf))).isInstanceOf(JwtException.class);
            assertThatThrownBy(()->decoder.decode(issuer.sign(issuer.portal(NOW)))).isInstanceOf(JwtException.class);
            assertThatThrownBy(()->new TrustedJwtDecoder(issuer.config(),Clock.fixed(NOW,ZoneOffset.UTC),true).decode(issuer.sign(workload))).isInstanceOf(JwtException.class);
        }
    }
    @Test void fixedHttpEndpointsNoRedirectAndFiniteBodySizes()throws Exception{
        try(var issuer=new LoopbackIssuer();var http=new BoundedIamHttp(issuer.config(),true)){
            assertThat(new String(http.postForm(issuer.uri("/token"),Map.of("a","x y&z")),StandardCharsets.UTF_8)).isEqualTo("a=x+y%26z");
            assertThat(Json.read(new String(http.postJson(issuer.uri("/decision"),Map.of("a","b")),StandardCharsets.UTF_8)).path("a").asText()).isEqualTo("b");
            int count=issuer.requests.get();assertThatThrownBy(()->http.get(issuer.uri("/unexpected"))).hasMessage("IAM_ENDPOINT_NOT_CONFIGURED");assertThat(issuer.requests).hasValue(count);
            issuer.status=302;assertThatThrownBy(()->http.get(issuer.uri("/jwks"))).isInstanceOf(PlatformException.class);
            assertThat(issuer.unexpectedRequests).hasValue(0);
            issuer.status=200;issuer.jwks="x".repeat(65536);assertThat(http.get(issuer.uri("/jwks"))).hasSize(65536);
            issuer.jwks="x".repeat(65537);assertThatThrownBy(()->http.get(issuer.uri("/jwks"))).isInstanceOf(PlatformException.class);
        }
    }
    @Test void bodyDeadlineCancelsReadingAfterHeadersAndChunkedOverflowIsBounded()throws Exception{
        try(var issuer=new LoopbackIssuer()){
            var started=new CountDownLatch(1);var release=new CountDownLatch(1);var finished=new CountDownLatch(1);
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/body",exchange->{
                try{
                    exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(new byte[]{1});exchange.getResponseBody().flush();started.countDown();
                    release.await(3,TimeUnit.SECONDS);
                }catch(Exception ignored){}finally{exchange.close();finished.countDown();}
            });server.start();
            var uri="http://127.0.0.1:"+server.getAddress().getPort()+"/body";
            var c=issuer.config();var a=c.authority();
            var shortConfig=new AuthorityProperties(c.mode(),c.serviceId(),null,
                new AuthorityProperties.Authority(a.issuer(),uri,a.tokenUri(),a.decisionUri(),a.clientId(),a.clientSecretRef(),a.allowedAlgorithm(),a.audience(),60,5,1000,200),null,null,null,false,null);
            try(var http=new BoundedIamHttp(shortConfig,true)){
                long begin=System.nanoTime();
                try{
                    assertThatThrownBy(()->http.get(URI.create(uri))).isInstanceOf(PlatformException.class);
                    assertThat(started.getCount()).isZero();assertThat(Duration.ofNanos(System.nanoTime()-begin)).isLessThan(Duration.ofSeconds(2));
                }finally{release.countDown();assertThat(finished.await(2,TimeUnit.SECONDS)).isTrue();}
            }finally{server.stop(0);}
            var chunks=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            chunks.createContext("/body",exchange->{
                try{exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(new byte[65537]);}catch(Exception ignored){}finally{exchange.close();}
            });chunks.start();
            var u="http://127.0.0.1:"+chunks.getAddress().getPort()+"/body";
            var overflowConfig=new AuthorityProperties(c.mode(),c.serviceId(),null,
                new AuthorityProperties.Authority(a.issuer(),u,a.tokenUri(),a.decisionUri(),a.clientId(),a.clientSecretRef(),a.allowedAlgorithm(),a.audience(),60,5,1000,2000),null,null,null,false,null);
            try(var http=new BoundedIamHttp(overflowConfig,true)){
                assertThatThrownBy(()->http.get(URI.create(u))).isInstanceOf(PlatformException.class);
            }finally{chunks.stop(0);}
        }
    }
    static final class MutableClock extends Clock {
        Instant now=NOW;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    static LoopbackIssuer webIssuer;
    static final AtomicInteger handlers=new AtomicInteger();
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity
    static class ProbeApp {
        @Bean JwtDecoder decoder(){return new TrustedJwtDecoder(webIssuer.config(),Clock.fixed(NOW,ZoneOffset.UTC),true);}
        @Bean SecurityFilterChain filter(HttpSecurity http,JwtDecoder decoder,ObjectProvider<ApiKeyAuthenticator> keys)throws Exception{
            // Invoke the real chain method without importing production configuration's closed IAM startup guard.
            var method=SecurityConfiguration.class.getDeclaredMethod("security",HttpSecurity.class,JwtDecoder.class,CorsConfigurationSource.class,ObjectProvider.class);
            method.setAccessible(true);
            return (SecurityFilterChain)method.invoke(new SecurityConfiguration(),http,decoder,(CorsConfigurationSource)request->null,keys);
        }
        @Bean ProbeController probe(){return new ProbeController();}
    }
    @RestController static class ProbeController {
        @GetMapping({"/v1/probe","/internal/probe"}) Object get(){handlers.incrementAndGet();return Actor.current();}
    }
    @Test void realHttpRejectsPortalServiceRoleMissingScopeAndJwksOutageBeforeHandler()throws Exception{
        try(var issuer=new LoopbackIssuer()){
            webIssuer=issuer;handlers.set(0);
            var app=new SpringApplication(ProbeApp.class);app.setAdditionalProfiles("test");
            app.setDefaultProperties(Map.of("server.address","127.0.0.1","server.port","0","spring.main.banner-mode","off",
                "spring.config.location","optional:classpath:/iam-stage-2-no-config/","spring.jmx.enabled",false,"springdoc.api-docs.enabled",false,"logging.level.root","ERROR"));
            try(var context=(ServletWebServerApplicationContext)app.run();var client=HttpClient.newHttpClient()){
                String base="http://127.0.0.1:"+context.getWebServer().getPort();
                var token=issuer.sign(issuer.portal(NOW));
                assertResponse(client,base+"/v1/probe",token,200,null);assertThat(handlers).hasValue(1);
                var noScope=issuer.portal(NOW);noScope.remove("scope");
                assertResponse(client,base+"/v1/probe",issuer.sign(noScope),403,"SYS-AUTH-403");assertThat(handlers).hasValue(1);
                // A correctly signed portal token directly presents the historical broad SERVICE role.
                assertResponse(client,base+"/internal/probe",token,403,"SYS-AUTH-403");assertThat(handlers).hasValue(1);
                assertResponse(client,base+"/v1/probe",issuer.sign(issuer.portal(NOW),issuer.k2,"k1","at+jwt"),401,"SYS-AUTH-401");assertThat(handlers).hasValue(1);
                issuer.status=503;assertResponse(client,base+"/v1/probe",token,503,"IAM_AUTHORITY_UNAVAILABLE");assertThat(handlers).hasValue(1);
                issuer.status=200;assertResponse(client,base+"/v1/probe",token,200,null);assertThat(handlers).hasValue(2);
            }
        }
    }
    static void assertResponse(HttpClient client,String url,String token,int status,String code)throws Exception{
        var response=client.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(status);if(code!=null)assertThat(Json.read(response.body()).path("code").asText()).isEqualTo(code);
    }
}
