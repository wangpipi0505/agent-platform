package cn.agentplatform.skill.infra;

import cn.agentplatform.common.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 仅替换HTTP传输；真实主体选择、路由、逐次绑定回查和拒绝逻辑均直接执行。 */
class OntologySemanticTransportTest {
    final UUID release = UUID.randomUUID();
    final String key = "ontopa_" + "a".repeat(43), base = "http://or.test";
    final AtomicInteger identityCalls = new AtomicInteger(), dataCalls = new AtomicInteger();
    MockRestServiceServer server;
    RestClient client;
    @BeforeEach void start() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                Jwt.withTokenValue("verified-test-context").header("alg", "HS256").subject("alice")
                        .claim("tenant_id", "tenant").build(), null, List.of()));
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
    }
    @AfterEach void stop() { SecurityContextHolder.clearContext(); }
    OntologySemanticTransport transport() {
        String bindings = Json.write(List.of(Map.of("tenantId", "tenant", "principalId", "alice",
                "releaseId", release.toString(), "secretRef", "vault:" + UUID.randomUUID())));
        return new OntologySemanticTransport(new OntologyConsumeIdentity(bindings, (ref, current) -> key), base, client);
    }
    void identity(String actor) {
        var data = Map.of("platformTenant", "tenant", "platformActor", actor,
                "releaseId", release.toString(), "spaceId", UUID.randomUUID().toString(),
                "principalId", UUID.randomUUID().toString(), "credentialId", UUID.randomUUID().toString());
        server.expect(requestTo(base + "/onto/releases/" + release + "/semantic/identity"))
                .andExpect(headerDoesNotExist("Authorization")).andExpect(header("X-API-Key", key))
                .andExpect(req -> identityCalls.incrementAndGet())
                .andRespond(withSuccess(Json.write(Map.of("code", "OK", "data", data)), MediaType.APPLICATION_JSON));
    }
    @Test void definitionsAndConditionsRecheckCurrentIdentityEveryTime() {
        var transport = transport();
        for (String path : List.of("definitions/query", "conditions/evaluate")) {
            identity("alice");
            server.expect(requestTo(base + "/onto/releases/" + release + "/semantic/" + path))
                    .andExpect(headerDoesNotExist("Authorization")).andExpect(header("X-API-Key", key))
                    .andExpect(req -> dataCalls.incrementAndGet())
                    .andRespond(withSuccess(Json.write(Map.of("code", "OK", "data", Map.of("kind", "authoritative"))), MediaType.APPLICATION_JSON));
        }
        for (String path : List.of("definitions/query", "conditions/evaluate"))
            assertEquals("authoritative", transport.post(base + "/onto/releases/" + release + "/semantic/" + path, Map.of()).path("kind").asText());
        assertEquals(2, identityCalls.get()); assertEquals(2, dataCalls.get()); server.verify();
    }
    @Test void changedUserIsRejectedBeforeDataRead() {
        identity("bob");
        assertEquals("ONTOLOGY_IDENTITY_DENIED", assertThrows(PlatformException.class,
                () -> transport().post(base + "/onto/releases/" + release + "/semantic/definitions/query", Map.of())).code());
        assertEquals(0, dataCalls.get()); server.verify();
    }
    @Test void revokedCredentialIsRejectedBeforeDataRead() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                .body(Json.write(Map.of("code", "NO_PERMISSION", "message", "revoked"))));
        assertEquals("NO_PERMISSION", assertThrows(PlatformException.class,
                () -> transport().post(base + "/onto/releases/" + release + "/semantic/conditions/evaluate", Map.of())).code());
        assertEquals(0, dataCalls.get()); server.verify();
    }
    @Test void wrongOriginReleaseAndUnregisteredPathNeverSend() {
        var transport = transport();
        for (String url : List.of("http://other.invalid/onto/releases/" + release + "/semantic/facts",
                base + "/onto/releases/" + release + "/semantic/identity",
                base + "/onto/releases/" + UUID.randomUUID() + "/semantic/facts"))
            assertThrows(PlatformException.class, () -> transport.post(url, Map.of()));
        assertEquals(0, identityCalls.get()); server.verify();
    }
    @Test void unavailableSourceIsExplicit() {
        server.expect(anything()).andRespond(request -> { throw new java.io.IOException("test source unavailable"); });
        assertEquals("SEMANTIC_SOURCE_UNAVAILABLE", assertThrows(PlatformException.class,
                () -> transport().post(base + "/onto/releases/" + release + "/semantic/facts", Map.of())).code()); server.verify();
    }
    @Test void redirectsDoNotBecomeAnotherAuthorizedRequest() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.FOUND).header("Location", "http://other.invalid"));
        assertEquals("SEMANTIC_SOURCE_UNAVAILABLE", assertThrows(PlatformException.class,
                () -> transport().post(base + "/onto/releases/" + release + "/semantic/facts", Map.of())).code());
        assertEquals(0, dataCalls.get()); server.verify();
    }
}
