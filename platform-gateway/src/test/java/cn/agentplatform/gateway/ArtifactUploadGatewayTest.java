package cn.agentplatform.gateway;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.SecurityConfiguration;
import cn.agentplatform.common.ServiceTokens;
import cn.agentplatform.common.authority.AuthorityProperties;
import cn.agentplatform.gateway.api.GatewayController;
import cn.agentplatform.gateway.application.IntegrationAccess;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Real proxy/HTTP transport; an authenticated Servlet context is the explicit ingress premise. */
@Timeout(60)
class ArtifactUploadGatewayTest {
    private static final int MIB = 1024 * 1024;
    private static final String BOUNDARY = "ex15a-gateway-boundary";
    private static final String MULTIPART = "multipart/form-data; boundary=" + BOUNDARY;
    private static final byte[] PREFIX = ("--" + BOUNDARY + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"ex15a.bin\"\r\n"
            + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SUFFIX = ("\r\n--" + BOUNDARY + "--\r\n")
            .getBytes(StandardCharsets.US_ASCII);
    private static AuthorityProperties deployment;
    private static NimbusJwtDecoder decoder;
    private static String priorSecret;

    @BeforeAll
    static void explicitDevelopmentFixture() {
        priorSecret = System.getProperty("agent.jwt.secret");
        System.setProperty("agent.jwt.secret", "ex15a-public-test-key-only-01234567890123456789");
        deployment = SecurityConfiguration.bindDeployment(new MockEnvironment()
                .withProperty("agent.security.mode", "dev-hmac")
                .withProperty("agent.security.service-id", "gateway")
                .withProperty("agent.dev-auth", "true")
                .withProperty("server.address", "127.0.0.1"));
        decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(ServiceTokens.secretBytes(), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ServiceTokens.ISSUER));
    }

    @AfterAll
    static void restoreFixtureProperty() {
        if (priorSecret == null) System.clearProperty("agent.jwt.secret");
        else System.setProperty("agent.jwt.secret", priorSecret);
        SecurityContextHolder.clearContext();
    }

    @Test
    void forwards64MiBMultipartWithKnownLengthAndVerifiedInternalIdentity() throws Exception {
        assertAccepted(call("POST", "/admin/artifacts", MULTIPART,
                Body.multipart(64L * MIB), true), Body.multipart(64L * MIB));
    }

    @Test
    void forwards64MiBMultipartWithoutContentLength() throws Exception {
        var body = Body.multipart(64L * MIB);
        assertAccepted(call("POST", "/admin/artifacts",
                "Multipart/Form-Data; boundary=\"" + BOUNDARY + "\"", body, false), body);
    }

    @Test
    void acceptsExactly65MiBTotalWireWithKnownLength() throws Exception {
        var body = Body.multipartWire(65L * MIB);
        assertAccepted(call("POST", "/admin/artifacts", MULTIPART, body, true), body);
    }

    @Test
    void acceptsExactly65MiBTotalWireWithoutContentLength() throws Exception {
        var body = Body.multipartWire(65L * MIB);
        assertAccepted(call("POST", "/admin/artifacts", MULTIPART, body, false), body);
    }

    @Test
    void rejects65MiBTotalWirePlusOneWithKnownLength() throws Exception {
        assertRejected(call("POST", "/admin/artifacts", MULTIPART,
                Body.multipartWire(65L * MIB + 1), true), 65L * MIB);
    }

    @Test
    void rejects65MiBTotalWirePlusOneWithoutContentLength() throws Exception {
        assertRejected(call("POST", "/admin/artifacts", MULTIPART,
                Body.multipartWire(65L * MIB + 1), false), 65L * MIB);
    }

    @Test
    void ordinaryRequestsKeepExact2MiBAllowance() throws Exception {
        var body = Body.plain(2L * MIB);
        for (boolean known : List.of(true, false))
            assertAccepted(call("POST", "/admin/skills", "application/json", body, known), body);
    }

    @Test
    void ordinaryRequestsReject2MiBPlusOneIncludingUnknownLength() throws Exception {
        for (boolean known : List.of(true, false))
            assertRejected(call("POST", "/admin/skills", "application/json",
                    Body.plain(2L * MIB + 1), known), 2L * MIB);
    }

    @Test
    void putMultipartDoesNotReceiveUploadAllowance() throws Exception {
        assertRejected(call("PUT", "/admin/artifacts", MULTIPART,
                Body.multipartWire(2L * MIB + 1), false), 2L * MIB);
    }

    @Test
    void otherRoutesAndArtifactDownloadPathKeep2MiB() throws Exception {
        for (String path : List.of("/admin/skills", "/admin/artifacts/art_fixture/download"))
            assertRejected(call("POST", path, MULTIPART,
                    Body.multipartWire(2L * MIB + 1), false), 2L * MIB);
    }

    @Test
    void missingInvalidSimilarAndWildcardContentTypesKeep2MiB() throws Exception {
        for (String type : Arrays.asList(null, "", "application/json", "multipart",
                "multipart/form-datax", "multipart/*", "*/*",
                "multipart/form-data; boundary=\"unterminated"))
            assertRejected(call("POST", "/admin/artifacts", type,
                    Body.plain(2L * MIB + 1), false), 2L * MIB);
    }

    private static void assertAccepted(Result result, Body body) throws Exception {
        assertEquals(200, result.status, "valid bounded request must reach the HTTP upstream");
        assertEquals("ok", result.response);
        assertNull(result.receipt.readError);
        assertEquals(body.wireBytes(), result.receipt.bytes);
        assertEquals(body.wireBytes(), result.consumed);
        assertEquals(body.digest(false), result.receipt.sha256);
        if (body.multipart) assertEquals(body.digest(true), result.receipt.fileSha256);
    }

    private static void assertRejected(Result result, long maximum) {
        assertEquals(502, result.status, "preserve the existing proxy I/O error envelope");
        assertTrue(result.response.contains("DEPENDENCY_UNAVAILABLE"));
        assertTrue(result.receipt.bytes <= maximum, "bytes beyond the policy limit must not be forwarded");
        assertTrue(result.consumed <= maximum + 8192, "only one bounded read may cross the limit");
    }

    private static Result call(String method, String path, String type, Body body, boolean known)
            throws Exception {
        var actor = new Actor("ex15a-gateway-" + UUID.randomUUID(), "fixture-user", Set.of("EDITOR"), Map.of());
        String userToken = ServiceTokens.userToken(actor, 120);
        // Signature/issuer/time are checked here, then the authenticated context is installed explicitly.
        // This does not claim coverage of the production inbound FilterChain or its Servlet container.
        Jwt user = decoder.decode(userToken);
        assertEquals(List.of("agent-platform"), user.getAudience());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(user, List.of()));
        var priorContext = SecurityContextHolder.getContext();
        var source = new ObservedInput(body.open());
        var receipt = new CompletableFuture<Receipt>();
        var peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var peerThreads = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "ex15a-artifact-peer"); t.setDaemon(true); return t;
        });
        var deadline = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "ex15a-artifact-deadline"); t.setDaemon(true); return t;
        });
        var timedOut = new AtomicBoolean();
        ScheduledFuture<?> abort = null;
        try (source) {
            peer.setExecutor(peerThreads);
            peer.createContext("/", exchange -> {
                try (exchange) {
                    var wireDigest = sha256();
                    var fileDigest = sha256();
                    long count = 0;
                    String readError = null;
                    byte[] buffer = new byte[8192];
                    try (var input = exchange.getRequestBody()) {
                        int n;
                        while ((n = input.read(buffer)) != -1) {
                            wireDigest.update(buffer, 0, n);
                            long from = Math.max(count, body.fileStart());
                            long to = Math.min(count + n, body.fileStart() + body.fileBytes);
                            if (body.multipart && from < to)
                                fileDigest.update(buffer, (int) (from - count), (int) (to - from));
                            count += n;
                        }
                    } catch (IOException e) { readError = e.getClass().getSimpleName(); }
                    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    Jwt internal = decoder.decode(authorization.substring("Bearer ".length()));
                    receipt.complete(new Receipt(count, hex(wireDigest), hex(fileDigest), readError,
                            internal.getClaimAsString("tenant_id"), internal.getSubject(), internal.getAudience(),
                            internal.getClaimAsString("token_use"), internal.getClaimAsStringList("roles"),
                            authorization.equals("Bearer " + userToken)));
                    // A rejected proxy may already have disconnected; completion above records the received body.
                    try {
                        exchange.sendResponseHeaders(200, 2);
                        exchange.getResponseBody().write(new byte[] {'o', 'k'});
                    } catch (IOException ignored) { /* only the response to an already closed caller */ }
                } catch (Throwable e) { receipt.completeExceptionally(e); }
            });
            peer.start();
            abort = deadline.schedule(() -> {
                timedOut.set(true);
                peer.stop(0); // closes this fixture's active exchange and unblocks proxy I/O
            }, 30, TimeUnit.SECONDS);
            String origin = "http://127.0.0.1:" + peer.getAddress().getPort();
            var env = new MockEnvironment().withProperty("agent.services.skill", origin);
            var controller = new GatewayController(env,
                    new IntegrationAccess(origin, origin, decoder), deployment, Optional.empty());
            var request = new MockHttpServletRequest(method, path) {
                @Override public ServletInputStream getInputStream() { return source; }
                @Override public String getContentType() { return type; }
                @Override public int getContentLength() { return known ? (int) body.wireBytes() : -1; }
                @Override public long getContentLengthLong() { return known ? body.wireBytes() : -1; }
            };
            if (known) request.addHeader("Content-Length", body.wireBytes());
            else request.addHeader("Transfer-Encoding", "chunked");
            request.addHeader("Authorization", "Bearer " + userToken);
            request.addHeader("X-Tenant-Id", "untrusted-other-tenant");
            request.addHeader("X-User-Id", "untrusted-other-principal");
            request.addHeader("X-User-Roles", "[\"ADMIN\"]");
            var response = new MockHttpServletResponse();
            SecurityContextHolder.setContext(context);
            controller.proxy(request, response);
            var received = receipt.get(5, TimeUnit.SECONDS);
            assertEquals(actor.tenantId(), received.tenant);
            assertEquals(actor.principalId(), received.principal);
            assertEquals(List.of("agent-platform-internal"), received.audience);
            assertEquals("service", received.tokenUse);
            assertEquals(Set.of("EDITOR", "SERVICE"), Set.copyOf(received.roles));
            assertFalse(received.userTokenForwarded);
            assertTrue(source.largestRead <= 8192, "proxy must use bounded reads");
            return new Result(response.getStatus(), response.getContentAsString(), received, source.count);
        } finally {
            SecurityContextHolder.setContext(priorContext);
            if (abort != null) abort.cancel(false);
            peer.stop(0);
            deadline.shutdownNow();
            peerThreads.shutdownNow();
            boolean deadlineEnded = deadline.awaitTermination(5, TimeUnit.SECONDS);
            boolean peerEnded = peerThreads.awaitTermination(5, TimeUnit.SECONDS);
            assertAll(
                    () -> assertTrue(deadlineEnded, "fixture deadline thread leaked"),
                    () -> assertTrue(peerEnded, "HTTP fixture worker leaked"),
                    () -> assertTrue(source.closed, "fixture owns and closes the Servlet input"),
                    () -> assertFalse(timedOut.get(), "proxy did not finish within the fixture deadline"));
        }
    }

    private record Result(int status, String response, Receipt receipt, long consumed) {}
    private record Receipt(long bytes, String sha256, String fileSha256, String readError,
                           String tenant, String principal, List<String> audience, String tokenUse,
                           List<String> roles, boolean userTokenForwarded) {}

    private record Body(boolean multipart, long fileBytes) {
        static Body multipart(long fileBytes) { return new Body(true, fileBytes); }
        static Body multipartWire(long wireBytes) { return multipart(wireBytes - PREFIX.length - SUFFIX.length); }
        static Body plain(long bytes) { return new Body(false, bytes); }
        int fileStart() { return multipart ? PREFIX.length : 0; }
        long wireBytes() { return fileBytes + (multipart ? PREFIX.length + SUFFIX.length : 0); }
        InputStream open() {
            var file = new PatternInput(fileBytes);
            return multipart ? new SequenceInputStream(Collections.enumeration(List.of(
                    new ByteArrayInputStream(PREFIX), file, new ByteArrayInputStream(SUFFIX)))) : file;
        }
        String digest(boolean fileOnly) throws IOException {
            var digest = sha256();
            try (var stream = fileOnly ? new PatternInput(fileBytes) : open()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = stream.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            return hex(digest);
        }
    }

    private static final class PatternInput extends InputStream {
        private final long length;
        private long position;
        PatternInput(long length) { this.length = length; }
        @Override public int read() { return position == length ? -1 : (int) (position++ * 31 + 17) & 255; }
        @Override public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return 0;
            if (position == this.length) return -1;
            int n = (int) Math.min(Math.min(length, 8192), this.length - position);
            for (int i = 0; i < n; i++) bytes[offset + i] = (byte) (position++ * 31 + 17);
            return n;
        }
    }

    private static final class ObservedInput extends ServletInputStream {
        private final InputStream delegate;
        private long count;
        private int largestRead;
        private boolean closed, eof;
        ObservedInput(InputStream delegate) { this.delegate = delegate; }
        @Override public int read() throws IOException {
            int value = delegate.read();
            if (value == -1) eof = true; else count++;
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            largestRead = Math.max(largestRead, length);
            int n = delegate.read(bytes, offset, Math.min(length, 8192));
            if (n == -1) eof = true; else count += n;
            return n;
        }
        @Override public void close() throws IOException { closed = true; delegate.close(); }
        @Override public boolean isFinished() { return eof; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static String hex(MessageDigest digest) { return HexFormat.of().formatHex(digest.digest()); }
}
