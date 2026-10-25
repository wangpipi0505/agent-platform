package cn.agentplatform.gateway.application;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.GatewayEventsOpening;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Real signed issuer/Opening; parsing facts is deliberately separate from permission. */
public class RunObservationEventsMetadataTest {
    public static final String RUN = "r_" + "a".repeat(32), SHA = "a".repeat(64);
    public static final SubjectKey OWNER = new SubjectKey("tenant-a", "alice");
    public static class Fixture implements AutoCloseable {
        public final LoopbackAuthority iam;
        public final HttpCurrentAuthorityClient gateway;
        public final String source;
        public Fixture() throws Exception {
            this(OWNER);
        }
        public Fixture(SubjectKey owner) throws Exception {
            iam = new LoopbackAuthority();
            iam.portalActor(new Actor(owner.tenantId(), owner.principalId(), Set.of("EDITOR"), Map.of("region", Set.of("A"), "dataLabels", Set.of("restricted"))));
            iam.registerObservation(owner, RUN, SHA);
            gateway = iam.client("gateway");
            source = iam.portal(120);
        }
        public GatewayEventsOpening begin() {
            return gateway.beginGatewayPortalEvents(source, RUN, 7);
        }
        public void close() {
            try {
                gateway.close();
            } finally {
                iam.close();
            }
        }
    }
    public static String valid() {
        return "{\"code\":\"OK\",\"message\":\"\",\"data\":{" + "\"runId\":\"" + RUN + "\",\"owner\":{\"tenantId\":\"tenant-a\",\"principalId\":\"alice\"},\"root\":{\"kind\":\"app-version\",\"id\":\"Demo@3\",\"versionDigest\":\"" + SHA + "\"},\"appVersionId\":17,\"appVersionNo\":3,\"acceptedSnapshotSha256\":\"" + SHA + "\",\"initialCeilingSha256\":\"" + SHA + "\"},\"traceId\":null}";
    }
    static Object parse(GatewayEventsOpening opening, byte[] bytes) {
        try {
            return RunObservationMetadataClient.class.getMethod("parseEvents", GatewayEventsOpening.class, byte[].class).invoke(null, opening, bytes);
        } catch (NoSuchMethodException e) {
            fail("EVENTS bounded parser must consume its original verified Opening/Q");
            return null;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException x) throw x;
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
    static Object parse(GatewayEventsOpening o, String s) {
        return parse(o, s.getBytes(StandardCharsets.UTF_8));
    }
    @Test void realOpeningProducesSevenBoundFactsWithoutAnotherExchange() throws Exception {
        try (var f = new Fixture(); var opening = f.begin()) {
            var q = opening.queryAuthorization();
            assertNotNull(q.ticket());
            var facts = parse(opening, valid());
            assertNotNull(facts);
            assertEquals(1, f.iam.grantCount());
            assertEquals("EventsMetadata[validated-facts]", facts.toString());
            assertEquals(RunObservationSelectors.events(RUN, SHA), facts.getClass().getMethod("snapshot").invoke(facts));
        }
    }
    @Test void exact8192EnvelopeAndWhitespaceAreAcceptedButPlusOneIsRejected() throws Exception {
        try (var f = new Fixture(); var o = f.begin()) {
            String exact = valid() + " ".repeat(8192 - valid().getBytes(StandardCharsets.UTF_8).length);
            assertNotNull(parse(o, exact));
            assertThrows(PlatformException.class, () -> parse(o, exact + " "));
        }
    }
    @Test void wrongOwnerRunRootVersionHashAndUnknownFieldsCannotEscape() throws Exception {
        try (var f = new Fixture(); var o = f.begin()) {
            for (String s : List.of(valid().replace("tenant-a", "foreign"), valid().replace("alice", "mallory"), valid().replace(RUN, "r_" + "b".repeat(32)), valid().replace("Demo@3", "Demo@4"), valid().replace("app-version", "run"), valid().replace("\"appVersionId\":17", "\"appVersionId\":0"), valid().replace("\"appVersionNo\":3", "\"appVersionNo\":3.0"), valid().replace("\"appVersionId\":17", "\"appVersionId\":9223372036854775808"), valid().replace(SHA, "A".repeat(64)), valid().replace("\"data\":{", "\"data\":{\"extra\":0,"), valid().replace("\"owner\":{", "\"owner\":{\"extra\":0,"))) assertThrows(PlatformException.class, () -> parse(o, s));
        }
    }
    @Test void duplicateTrailingBomUtf16MalformedAndUnpairedEscapesFailClosed() throws Exception {
        try (var f = new Fixture(); var o = f.begin()) {
            for (String s : List.of(valid() + "{}", "\ufeff" + valid(), valid().replace("\"code\":\"OK\"", "\"code\":\"OK\",\"code\":\"OK\""), valid().replace("\"message\":\"\"", "\"message\":\"\\uD800\""), valid().replace("\"message\":\"\"", "\"message\":\"" + "x".repeat(513) + "\""), valid().replace("\"traceId\":null", "\"traceId\":\"" + "x".repeat(129) + "\""), valid().replace("\"appVersionId\":17", "\"appVersionId\":" + "1".repeat(1000)))) assertThrows(PlatformException.class, () -> parse(o, s));
            assertThrows(PlatformException.class, () -> parse(o, valid().getBytes(StandardCharsets.UTF_16)));
            assertThrows(PlatformException.class, () -> parse(o, new byte[] {
                (byte) 0xc3, 0x28
            }));
        }
    }
    @Test void expiredOriginalQueryCannotProduceFacts() throws Exception {
        try (var f = new Fixture(); var o = f.begin()) {
            f.iam.advanceClock(Duration.ofSeconds(6));
            assertThrows(PlatformException.class, () -> parse(o, valid()));
        }
    }
    @Test void existingStatusLookupRetainsNormalFactsAndStrictErrorMeaning() throws Exception {
        try (var f = new Fixture(); var http = new cn.agentplatform.gateway.infra.PublishedRunHttp(); var decoder = new TrustedJwtDecoder(f.iam.config("gateway"), f.iam.clock(), true)) {
            var body = new java.util.concurrent.atomic.AtomicReference<>(valid());
            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/runs/" + RUN + "/observation-metadata", x -> {
                byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
                x.getResponseHeaders().set("Content-Type", "application/json");
                try (x) {
                    x.sendResponseHeaders(200, bytes.length);
                    x.getResponseBody().write(bytes);
                }
            });
            server.start();
            try (var metadata = new RunObservationMetadataClient(java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()), f.gateway, http, true)) {
                var controller = new cn.agentplatform.gateway.api.GatewayController(new org.springframework.mock.env.MockEnvironment(), null, f.iam.config("gateway"), Optional.empty());
                var boundary = new GatewayIngressBoundary(controller, null, true, decoder, f.iam.clock());
                var handler = new org.springframework.web.method.HandlerMethod(controller, controller.getClass().getMethod("proxy", jakarta.servlet.http.HttpServletRequest.class, jakarta.servlet.http.HttpServletResponse.class));
                for (boolean invalid : List.of(false, true)) {
                    body.set(invalid ? valid().replace("Demo@3", "Demo@4") : valid());
                    var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/admin/runs/" + RUN);
                    request.addHeader("Authorization", "Bearer " + f.source);
                    var response = new org.springframework.mock.web.MockHttpServletResponse();
                    boundary.filter().doFilter(request, response, (rq, rs) -> {
                        boundary.preHandle(request, response, handler);
                        var capture = GatewayIngressBoundary.claimStatus(request);
                        if (invalid) {
                            var failure = assertThrows(PlatformException.class, () -> metadata.lookup(capture, request, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3), new cn.agentplatform.gateway.infra.PublishedRunHttp.Cancellation()));
                            assertEquals(503, failure.status());
                            assertEquals("RUN_METADATA_UNAVAILABLE", failure.code());
                        } else assertEquals(RunObservationSelectors.status(RUN, SHA), metadata.lookup(capture, request, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3), new cn.agentplatform.gateway.infra.PublishedRunHttp.Cancellation()).snapshot());
                    });
                    assertEquals(200, response.getStatus());
                }
            } finally {
                server.stop(0);
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        }
    }
    @Test void widestLegalOwnerRootAndLongFieldsRemainBoundedAndFactsAreClosable() throws Exception {
        var subject = new SubjectKey("T".repeat(128), "P".repeat(128));
        try (var f = new Fixture(subject); var o = f.begin()) {
            String widest = valid().replace("tenant-a", subject.tenantId()).replace("alice", subject.principalId()).replace("Demo@3", "A".repeat(64) + "@9223372036854775807").replace("\"appVersionId\":17", "\"appVersionId\":9223372036854775807").replace("\"appVersionNo\":3", "\"appVersionNo\":9223372036854775807");
            var facts = (RunObservationMetadataClient.EventsMetadata) parse(o, widest);
            assertEquals(subject, facts.owner());
            assertEquals(Long.MAX_VALUE, facts.appVersionId());
            assertEquals(Long.MAX_VALUE, facts.appVersionNo());
            assertEquals(84, facts.root().id().length());
            assertEquals(RUN, facts.runId());
            assertEquals(SHA, facts.acceptedSnapshotSha256());
            assertEquals(SHA, facts.initialCeilingSha256());
            assertThrows(PlatformException.class, () -> parse(o, widest.replace(subject.tenantId(), subject.tenantId() + "X")));
            facts.close();
            assertThrows(PlatformException.class, facts::snapshot);
        }
    }
}
