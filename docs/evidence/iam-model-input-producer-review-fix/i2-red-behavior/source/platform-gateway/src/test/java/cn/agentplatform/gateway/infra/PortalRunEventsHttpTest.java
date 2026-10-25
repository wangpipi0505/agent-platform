package cn.agentplatform.gateway.infra;
import cn.agentplatform.common.*;
import cn.agentplatform.gateway.application.RunObservationEventsMetadataTest;
import cn.agentplatform.gateway.application.RunObservationEventsMetadataTest.Fixture;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Fresh fixed-profile JVMs and real issuer/TLS sockets; no business JAR or engine/PG claim. */
public class PortalRunEventsHttpTest {
    static final String HOST = "engine.f2a.test", RUN = RunObservationEventsMetadataTest.RUN;
    static Path root() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.exists(p.resolve("AGENTS.md"))) p = p.getParent();
        if (!Files.exists(p.resolve("AGENTS.md"))) throw new IllegalStateException();
        return p;
    }
    @Test void originalQUsesRealHttpsAndNeverLogsBearerAtHeadersOrWireDebug() throws Exception {
        child("normal");
    }
    @Test void rawPeersRejectUnsafeHeadsFramingTrailersInformationalAndBodies() throws Exception {
        child("wire");
    }
    @Test void finiteStrictErrorMappingsKeepInternal401Unavailable() throws Exception {
        child("errors");
    }
    @Test void slowHeadAndBlockedReadHaveOneAbsoluteDeadlineAndNoDrain() throws Exception {
        child("deadline");
    }
    @Test void invalidatedOpeningAtTlsAndWriterSeamsDeliversZeroRequestBytes() throws Exception {
        child("proof");
    }
    @Test void twoSessionsRetainReservationUntilActualCloseAndRejectThird() throws Exception {
        child("capacity");
    }
    @Test void credentialFailuresNeverDeliverAnHttpRequest() throws Exception {
        child("credentials");
    }
    @Test void concurrentLookupAndCancellationCannotDeliverFactsOrReleaseEarly() throws Exception {
        child("cancel");
    }
    @Test void physicalCloseGateRetainsHttpOwnerAndReleasesExactlyOnce() throws Exception {
        child("close-gate");
    }
    @Test void concurrentClosersHaveOneCleanupExecutorAndRespectWaitBudgets() throws Exception {
        child("concurrent-close");
    }
    @Test void cleanupInterruptionAndExceptionRetainReferencesForRetry() throws Exception {
        child("cleanup-retry");
    }
    @Test void realHttpsBody8192AndChunked8193RespectReadAndCloseBounds() throws Exception {
        child("body-bounds");
    }
    @Test void requestWireBoundsAndIdentityMissAreCheckedBeforeWriter() throws Exception {
        child("mechanics");
    }
    static void child(String group) throws Exception {
        Path run = Files.createDirectories(root().resolve(System.getProperty("iam.portal.http.ownedRoot", ".local/iam-o2b-f2b2-private-http") + "/peers/original-q-" + group + "-" + UUID.randomUUID()));
        var words = new ArrayList<>(List.of("-Djdk.net.unixdomain.tmpdir=V:/.local/tmp", "-Djdk.tls.maxHandshakeMessageSize=32768", "-Djdk.tls.maxCertificateChainLength=4", "-Dcom.sun.security.enableAIAcaIssuers=false", "-Djdk.tls.server.newSessionTicketCount=0", "-Djdk.tls.server.enableStatusRequestExtension=false", "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), PortalRunEventsHttpTest.class.getName(), group, run.toString()));
        Files.writeString(run.resolve("client.args"), String.join("\n", words.stream().map(s -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList()));
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin/java.exe").toString());
        command.addAll(words);
        Process p = new ProcessBuilder(command).directory(root().toFile()).redirectErrorStream(true).start();
        Thread collector = null;
        var failure = new AtomicReference<Throwable>();
        try {
            Files.writeString(run.resolve("launch.properties"), "pid=" + p.pid() + "\ncwd=" + root() + "\nargs=client.args\n");
            var out = Files.newOutputStream(run.resolve("client.out"));
            collector = Thread.ofPlatform().daemon().start(() -> {
                try (out; var in = p.getInputStream()) {
                    byte[] b = new byte[4096];
                    int n, total = 0;
                    while ((n = in.read(b)) != - 1) {
                        total += n;
                        if (total > 131072) throw new IOException("bounded child output");
                        out.write(b, 0, n);
                    }
                } catch (Throwable t) {
                    failure.set(t);
                    p.destroyForcibly();
                }
            });
            assertTrue(p.waitFor(group.equals("wire") ? 55 : 30, TimeUnit.SECONDS), "owned child deadline");
            collector.join(3000);
            assertFalse(collector.isAlive());
            assertNull(failure.get());
            String text = Files.readString(run.resolve("client.out"));
            assertEquals(0, p.exitValue(), text);
        } finally {
            if (p.isAlive()) p.destroyForcibly();
            assertTrue(p.waitFor(3, TimeUnit.SECONDS));
            if (collector != null) {
                collector.join(3000);
                assertFalse(collector.isAlive());
            }
            Files.writeString(run.resolve("exit.properties"), "pid=" + p.pid() + "\nexit=" + p.exitValue() + "\nalive=" + p.isAlive() + "\ncollectorAlive=" + (collector != null && collector.isAlive()) + "\n");
        }
    }
    static Object call(Object receiver, String name, Class<?>[] types, Object ... args) {
        try {
            return receiver.getClass().getMethod(name, types).invoke(receiver, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Original-Q HTTPS behavior is required", e);
        }
    }
    static Object transport(PortalEventsTls tls) {
        try {
            return Class.forName("cn.agentplatform.gateway.infra.PortalRunEventsHttp").getConstructor(PortalEventsTls.class).newInstance(tls);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Original-Q HTTPS behavior is required", e);
        }
    }
    static Object begin(Object t, Fixture f) {
        return call(t, "begin", new Class<?>[] {
            cn.agentplatform.common.authority.CurrentAuthorityClient.class, String.class, String.class, long.class
        }, f.gateway, f.source, RUN, 7L);
    }
    static Object lookup(Object s) {
        return call(s, "lookupMetadata", new Class<?>[0]);
    }
    static void close(Object s) {
        call(s, "close", new Class<?>[0]);
        assertEquals(true, call(s, "awaitClosed", new Class<?>[] {
            Duration.class
        }, Duration.ofSeconds(3)));
    }
    static PlatformException denied(Object s) {
        return assertThrows(PlatformException.class, () -> lookup(s));
    }
    static byte[] utf(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
    static byte[] fixed(int status, String body) {
        return utf("HTTP/1.1 " + status + " Finite\r\nContent-Type: application/json\r\nContent-Length: " + utf(body).length + "\r\n\r\n" + body);
    }
    static byte[] normal() {
        return fixed(200, RunObservationEventsMetadataTest.valid());
    }
    static Path ca(Path run, String alias) throws Exception {
        var cert = fixtures().getCertificate(alias);
        Path p = run.resolve("ca-" + alias + ".pem");
        if (!Files.exists(p)) Files.writeString(p, "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, new byte[] {
            10
        }).encodeToString(cert.getEncoded()) + "\n-----END CERTIFICATE-----\n");
        return p;
    }
    static KeyStore fixtures() throws Exception {
        var k = KeyStore.getInstance("PKCS12");
        try (var in = PortalRunEventsHttpTest.class.getResourceAsStream("/iam-o2b-f2a/tls-fixtures.p12")) {
            k.load(in, "f2a-test-only".toCharArray());
        }
        return k;
    }
    static PortalEventsTls tls(Peer peer, Path run, String ca) throws Exception {
        return new PortalEventsTls(URI.create("https://" + HOST + ":" + peer.listener.getLocalPort()), new byte[] {
            127, 0, 0, 1
        }, ca(run, ca));
    }
    static final class Peer implements AutoCloseable {
        final SSLServerSocket listener;
        final Thread worker;
        final AtomicInteger connects = new AtomicInteger(), bytes = new AtomicInteger();
        final CountDownLatch connected = new CountDownLatch(1), requestDone = new CountDownLatch(1), release = new CountDownLatch(1), beforeHandshake = new CountDownLatch(1), clientClosed = new CountDownLatch(1);
        volatile SSLSocket socket;
        volatile String request = "";
        volatile Throwable failure;
        volatile Runnable beforeBodyEnd;
        volatile boolean closeObserved;
        Peer(byte[] response, String mode) throws Exception {
            var c = SSLContext.getInstance("TLSv1.3", "SunJSSE");
            var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            km.init(fixtures(), "f2a-test-only".toCharArray());
            var original = Arrays.stream(km.getKeyManagers()).filter(X509ExtendedKeyManager.class::isInstance).map(X509ExtendedKeyManager.class::cast).findFirst().orElseThrow();
            c.init(new KeyManager[] {
                new PortalEventsTlsTest.FixedKey(original, mode.equals("san") ? "wrong-san" : "normal", new Properties())
            }, null, null);
            listener = (SSLServerSocket) c.getServerSocketFactory().createServerSocket(0, 2, InetAddress.getByAddress(new byte[] {
                127, 0, 0, 1
            }));
            listener.setEnabledProtocols(new String[] {
                "TLSv1.3"
            });
            listener.setEnabledCipherSuites(new String[] {
                PortalEventsTls.CIPHER
            });
            listener.setSoTimeout(3000);
            worker = Thread.ofPlatform().daemon().start(() -> {
                try {
                    try (var s = (SSLSocket) listener.accept()) {
                        socket = s;
                        connects.incrementAndGet();
                        connected.countDown();
                        s.setSoTimeout(4000);
                        beforeHandshake.countDown();
                        if (mode.equals("handshake")) assertTrue(release.await(4, TimeUnit.SECONDS));
                        s.startHandshake();
                        var b = new ByteArrayOutputStream();
                        int last = 0, x;
                        while ((x = s.getInputStream().read()) != - 1) {
                            bytes.incrementAndGet();
                            b.write(x);
                            last = (last << 8) | x;
                            if (last == 0x0d0a0d0a || b.size() > 32768) break;
                        }
                        request = b.toString(StandardCharsets.US_ASCII);
                        requestDone.countDown();
                        if (response != null) {
                            if (mode.equals("slow")) {
                                for (byte v : response) {
                                    s.getOutputStream().write(v);
                                    s.getOutputStream().flush();
                                    Thread.sleep(120);
                                }
                            } else {
                                if (beforeBodyEnd != null) {
                                    s.getOutputStream().write(response, 0, response.length - 1);
                                    s.getOutputStream().flush();
                                    beforeBodyEnd.run();
                                    s.getOutputStream().write(response[response.length - 1]);
                                } else s.getOutputStream().write(response);
                                s.getOutputStream().flush();
                            }
                        }
                        if (mode.equals("hold") || mode.equals("read")) release.await(4, TimeUnit.SECONDS);
                        if (mode.equals("observe-close")) {
                            // The overflow vector deliberately withholds the next chunk's payload and terminator.
                            // EOF/reset here is peer-observed client close; no test-side close releases the read.
                            try {
                                assertEquals(- 1, s.getInputStream().read());
                                closeObserved = true;
                            } catch (SocketException closed) {
                                closeObserved = true;
                            } finally {
                                clientClosed.countDown();
                            }
                        }
                    } catch (IOException expected) {
                    }
                    listener.setSoTimeout(150);
                    try (var extra = listener.accept()) {
                        connects.incrementAndGet();
                    } catch (SocketTimeoutException expected) {
                    }
                } catch (Throwable e) {
                    failure = e;
                } finally {
                    requestDone.countDown();
                }
            });
        }
        public void close() throws Exception {
            release.countDown();
            listener.close();
            if (socket != null) socket.close();
            worker.join(5000);
            assertFalse(worker.isAlive());
            if (failure != null && !(failure instanceof SocketException)) throw new AssertionError(failure);
        }
    }
    static void normal(Path run) throws Exception {
        var logs = new StringBuilder();
        var context = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        var appender = new ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
            protected void append(ch.qos.logback.classic.spi.ILoggingEvent e) {
                String s = e.getFormattedMessage();
                if (logs.length() + s.length() > 32768) throw new AssertionError("bounded headers/wire logs");
                logs.append(s);
            }
        };
        appender.setContext(context);
        appender.start();
        for (String n : List.of("org.apache.hc.client5.http.headers", "org.apache.hc.client5.http.wire")) {
            var l = context.getLogger(n);
            l.setLevel(ch.qos.logback.classic.Level.DEBUG);
            l.setAdditive(false);
            l.addAppender(appender);
        }
        try (var f = new Fixture(); var p = new Peer(normal(), "normal"); var tls = tls(p, run, "root")) {
            System.setProperty("https.proxyHost", "127.0.0.1");
            System.setProperty("https.proxyPort", "1");
            System.setProperty("socksProxyHost", "127.0.0.1");
            System.setProperty("socksProxyPort", "1");
            Object s = begin(transport(tls), f);
            try {
                Object result = lookup(s);
                assertNotNull(result);
                assertEquals("EventsMetadata[validated-facts]", result.toString());
                assertEquals(1, f.iam.grantCount());
                assertTrue(p.requestDone.await(2, TimeUnit.SECONDS));
                assertTrue(p.request.startsWith("GET /internal/runs/" + RUN + "/observation-metadata HTTP/1.1\r\n"));
                String q = ((cn.agentplatform.common.authority.HttpCurrentAuthorityClient.GatewayEventsOpening) field(s, "opening")).queryAuthorization().ticket().value();
                assertTrue(p.request.contains("Authorization: Bearer " + q + "\r\n"));
                assertEquals(1, p.request.split("Authorization:", - 1).length - 1);
                assertFalse(logs.toString().contains(q));
                assertFalse(logs.toString().contains(f.source));
                assertTrue(p.request.contains("Host: " + HOST + ":" + p.listener.getLocalPort()));
                assertTrue(p.request.contains("Accept: application/json\r\n"));
                assertTrue(p.request.contains("Accept-Encoding: identity\r\n"));
                assertFalse(p.request.contains("Cookie:"));
                assertFalse(p.request.contains(f.source));
                assertEquals(true, field(s, "identityMatched"));
                assertEquals(1, p.connects.get());
                assertThrows(RuntimeException.class, () -> lookup(s));
            } finally {
                close(s);
            }
            assertEquals(0, PortalEventsPacketBudget.SHARED.owners());
            assertEquals(0, PortalEventsPacketBudget.SHARED.reservedBytes());
        }
        appender.stop();
        Files.writeString(run.resolve("summary.properties"), "normal=true\nheadersWireDebug=true\ncredentialLogAbsent=true\n");
    }
    static Object field(Object s, String name) throws Exception {
        var f = s.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(s);
    }
    static void wire(Path run) throws Exception {
        var vectors = new ArrayList<byte[]>();
        String body = RunObservationEventsMetadataTest.valid();
        String good = new String(normal(), StandardCharsets.UTF_8);
        for (String h : List.of("Content-Encoding: identity", "Content-Encoding: gzip", "Set-Cookie: x=y", "Location: https://other.invalid/", "Trailer: X", "Content-Type: application/json", "Content-Length: 0", "Transfer-Encoding: chunked", "Content-Length: 00", "Content-Length: -1", "Content-Length: 9223372036854775808", "X-Fold: a\r\n b", "X-Ctrl: \u0001")) vectors.add(utf(good.replace("\r\n\r\n", "\r\n" + h + "\r\n\r\n")));
        for (int code : List.of(100, 101, 103)) vectors.add(utf("HTTP/1.1 " + code + " Info\r\n\r\n" + good));
        vectors.add(utf(good.replace("HTTP/1.1", "HTTP/1.0")));
        vectors.add(utf(good.replace("Content-Length: " + utf(body).length + "\r\n", "")));
        int end = good.indexOf("\r\n\r\n") + 4;
        String padHeader = "X-Pad: ";
        String headExact = good.substring(0, end - 2) + padHeader + "x".repeat(8192 - end - padHeader.length() - 2) + "\r\n\r\n" + body;
        vectors.add(utf(headExact.replace("X-Pad: ", "X-Pad: xx")));
        vectors.add(utf(good.substring(0, good.length() - 1)));
        vectors.add(fixed(200, body + " ".repeat(8193 - utf(body).length)));
        vectors.add(utf("\r\n".repeat(4) + good));
        vectors.add(utf(good.replace("\r\n\r\n", "\r\nX-Large: " + "x".repeat(8192) + "\r\n\r\n")));
        vectors.add(utf(good.replace("\r\n\r\n", "\r\n" + "X-A: a\r\n".repeat(62) + "\r\n")));
        String chunkHead = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n";
        vectors.add(utf(chunkHead + Integer.toHexString(utf(body).length) + "\r\n" + body + "\r\n0\r\nX-Trailer: x\r\n\r\n"));
        vectors.add(utf(chunkHead + "1;" + "x".repeat(8192) + "\r\nx\r\n0\r\n\r\n"));
        try (var f = new Fixture()) {
            for (byte[] v : vectors) try (var p = new Peer(v, "normal"); var tls = tls(p, run, "root")) {
                Object s = begin(transport(tls), f);
                try {
                    assertEquals(503, denied(s).status());
                } finally {
                    close(s);
                }
                assertEquals(1, p.connects.get());
            }
            for (byte[] v : List.of(normal(), utf(headExact), utf(good.replace("\r\n\r\n", "\r\n" + "X-A: a\r\n".repeat(61) + "\r\n")), utf(chunkHead + Integer.toHexString(utf(body).length) + "\r\n" + body + "\r\n0\r\n\r\n"))) try (var p = new Peer(v, "normal"); var tls = tls(p, run, "root")) {
                Object s = begin(transport(tls), f);
                try {
                    assertNotNull(lookup(s));
                } finally {
                    close(s);
                }
            }
        }
        Files.writeString(run.resolve("summary.properties"), "rejectedVectors=" + vectors.size() + "\npositiveVectors=4\n");
    }
    static void errors(Path run) throws Exception {
        try (var f = new Fixture()) {
            for (String code : List.of("RUN_NOT_FOUND", "AUTHORITY_EXPIRED", "AUTHORITY_CONTEXT_MISSING", "RUN_AUTHORITY_DENIED", "MATERIAL_SCOPE_MISSING_OR_CHANGED", "SYS-AUTH-403", "AUTHORITY_DENIED", "UNKNOWN")) {
                int status = code.equals("RUN_NOT_FOUND") ? 404 : 403;
                try (var p = new Peer(fixed(status, Json.write(new ApiResponse<>(code, "private upstream", null, null))), "normal"); var tls = tls(p, run, "root")) {
                    Object s = begin(transport(tls), f);
                    try {
                        var e = denied(s);
                        assertEquals(code.equals("UNKNOWN") ? 503 : status, e.status());
                        assertFalse(e.getMessage().contains("private upstream"));
                    } finally {
                        close(s);
                    }
                }
            }
            for (int status : List.of(401, 302, 500)) try (var p = new Peer(fixed(status, Json.write(new ApiResponse<>("RUN_NOT_FOUND", "x", null, null))), "normal"); var tls = tls(p, run, "root")) {
                Object s = begin(transport(tls), f);
                try {
                    assertEquals(503, denied(s).status());
                } finally {
                    close(s);
                }
            }
        }
    }
    static void deadline(Path run) throws Exception {
        try (var f = new Fixture()) {
            for (String mode : List.of("slow", "hold", "read")) try (var p = new Peer(mode.equals("slow") ? normal() : utf("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + (mode.equals("read") ? 32 : 8193) + "\r\n\r\n"), mode); var tls = tls(p, run, "root")) {
                Object s = begin(transport(tls), f);
                long start = System.nanoTime();
                try {
                    assertEquals(503, denied(s).status());
                    assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(mode.equals("hold") ? 1500 : 3200));
                } finally {
                    close(s);
                }
            }
        }
    }
    static void proof(Path run) throws Exception {
        try (var f = new Fixture(); var p = new Peer(normal(), "handshake"); var tls = tls(p, run, "root"); var worker = Executors.newSingleThreadExecutor()) {
            Object s = begin(transport(tls), f);
            try {
                var future = worker.submit(() -> lookup(s));
                assertTrue(p.beforeHandshake.await(2, TimeUnit.SECONDS));
                f.iam.advanceClock(Duration.ofSeconds(6));
                p.release.countDown();
                assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
                assertTrue(p.requestDone.await(2, TimeUnit.SECONDS));
                assertEquals(0, p.bytes.get());
            } finally {
                p.release.countDown();
                close(s);
            }
        }
        try (var f = new Fixture(); var p = new Peer(normal(), "normal"); var tls = tls(p, run, "root"); var worker = Executors.newSingleThreadExecutor()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var session = (PortalRunEventsHttp.Session) begin(transport(tls), f);
            session.beforeOutputForTest(() -> {
                entered.countDown();
                try {
                    if (!release.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("bounded writer gate");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException();
                }
            });
            try {
                var future = worker.submit(session::lookupMetadata);
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                f.iam.advanceClock(Duration.ofSeconds(6));
                release.countDown();
                assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
                assertTrue(p.requestDone.await(2, TimeUnit.SECONDS));
                assertEquals(0, p.bytes.get());
            } finally {
                release.countDown();
                close(session);
            }
        }
    }
    @Test void bodyCompletionCannotReturnFactsAfterOriginalQueryExpires() throws Exception {
        child("body-expiry");
    }
    static void bodyExpiry(Path run) throws Exception {
        try (var f = new Fixture(); var p = new Peer(normal(), "normal"); var tls = tls(p, run, "root")) {
            p.beforeBodyEnd = () -> f.iam.advanceClock(Duration.ofSeconds(6));
            Object s = begin(transport(tls), f);
            try {
                assertThrows(PlatformException.class, () -> lookup(s));
            } finally {
                close(s);
            }
        }
    }
    static void credentials(Path run) throws Exception {
        for (String mode : List.of("ca", "san")) try (var f = new Fixture(); var p = new Peer(normal(), mode); var tls = tls(p, run, mode.equals("ca") ? "other" : "root")) {
            Object s = begin(transport(tls), f);
            try {
                assertEquals(503, denied(s).status());
                assertTrue(p.requestDone.await(2, TimeUnit.SECONDS));
                assertEquals(0, p.bytes.get());
                assertEquals(1, p.connects.get());
            } finally {
                close(s);
            }
        }
        try (var f = new Fixture(); var listener = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] {
            127, 0, 0, 1
        })); var worker = Executors.newSingleThreadExecutor(); var tls = new PortalEventsTls(URI.create("https://" + HOST + ":" + listener.getLocalPort()), new byte[] {
            127, 0, 0, 1
        }, ca(run, "root"))) {
            listener.setSoTimeout(3000);
            var seen = new AtomicInteger();
            var peer = worker.submit(() -> {
                try (var sock = listener.accept()) {
                    seen.incrementAndGet();
                    sock.setSoTimeout(3000);
                    sock.getOutputStream().write(utf("HTTP/1.1 200 Finite\r\n\r\n"));
                    byte[] first = sock.getInputStream().readNBytes(5);
                    assertEquals(22, first[0] & 255);
                }
                return null;
            });
            Object s = begin(transport(tls), f);
            try {
                assertEquals(503, denied(s).status());
                peer.get(4, TimeUnit.SECONDS);
                assertEquals(1, seen.get());
            } finally {
                close(s);
            }
        }
    }
    static void capacity(Path run) throws Exception {
        try (var f = new Fixture(); var p = new Peer(normal(), "normal"); var tls = tls(p, run, "root")) {
            Object t = transport(tls), a = begin(t, f), b = null;
            try {
                assertEquals(3211392L, PortalEventsPacketBudget.SHARED.reservedBytes());
                b = begin(t, f);
                assertEquals(6422784L, PortalEventsPacketBudget.SHARED.reservedBytes());
                assertThrows(RuntimeException.class, () -> begin(t, f));
                assertEquals(2, f.iam.grantCount());
            } finally {
                close(a);
                if (b != null) close(b);
            }
            assertEquals(0, PortalEventsPacketBudget.SHARED.reservedBytes());
        }
    }
    static void mechanics(Path run) throws Exception {
        assertEquals("32768", System.getProperty("jdk.tls.maxHandshakeMessageSize"));
        var head = new org.apache.hc.core5.http.message.BasicClassicHttpRequest("GET", "/internal/runs/" + RUN + "/observation-metadata");
        head.setVersion(org.apache.hc.core5.http.HttpVersion.HTTP_1_1);
        head.setHeader("Authorization", "Bearer " + "x".repeat(8495));
        assertTrue(PortalRunEventsHttp.requestOctets(head) < 32768);
        head.setHeader("X-Padding", "");
        int base = PortalRunEventsHttp.requestOctets(head);
        head.setHeader("X-Padding", "x".repeat(32768 - base));
        assertEquals(32768, PortalRunEventsHttp.requestOctets(head));
        head.setHeader("X-Padding", "x".repeat(32769 - base));
        assertThrows(IOException.class, () -> PortalRunEventsHttp.requestOctets(head));
        try (var f = new Fixture(); var p = new Peer(normal(), "normal"); var tls = tls(p, run, "root")) {
            Object s = begin(transport(tls), f);
            try {
                Class<?> connection = Class.forName("cn.agentplatform.gateway.infra.PortalRunEventsHttp$QuietConnection");
                var constructor = connection.getDeclaredConstructors()[0];
                constructor.setAccessible(true);
                Object c = constructor.newInstance(s);
                var writer = s.getClass().getDeclaredMethod("writerCheck", org.apache.hc.core5.http.ClassicHttpRequest.class, connection);
                writer.setAccessible(true);
                var e = assertThrows(InvocationTargetException.class, () -> writer.invoke(s, head, c));
                assertInstanceOf(IOException.class, e.getCause());
                assertEquals(0, p.bytes.get());
            } finally {
                close(s);
            }
        }
        Files.writeString(run.resolve("summary.properties"), "mechanical8495Fits=true\nrequest32768Exact=true\nrequest32769Rejected=true\nmissingIdentityRejected=true\n");
    }
    static void cancel(Path run) throws Exception {
        try (var f = new Fixture(); var p = new Peer(normal(), "normal"); var tls = tls(p, run, "root"); var worker = Executors.newSingleThreadExecutor()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var session = (PortalRunEventsHttp.Session) begin(transport(tls), f);
            session.beforeOutputForTest(() -> {
                entered.countDown();
                try {
                    if (!release.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("cancel gate limit");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException();
                }
            });
            try {
                var future = worker.submit(session::lookupMetadata);
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var timer = (ScheduledThreadPoolExecutor) field(session, "timer");
                assertThrows(PlatformException.class, session::lookupMetadata);
                session.abort();
                assertEquals(3211392L, PortalEventsPacketBudget.SHARED.reservedBytes());
                assertFalse(session.awaitClosed(Duration.ofMillis(10)));
                release.countDown();
                assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
                assertTrue(timer.isTerminated());
                assertTrue(timer.getQueue().isEmpty());
                assertEquals(0, p.bytes.get());
            } finally {
                release.countDown();
                close(session);
            }
            Object before = begin(transport(tls), f);
            call(before, "abort", new Class<?>[0]);
            try {
                denied(before);
            } finally {
                close(before);
            }
            assertEquals(0, PortalEventsPacketBudget.SHARED.owners());
        }
    }
    static void closeGate(Path run) throws Exception {
        try (var f = new Fixture(); var p = new Peer(normal(), "normal"); var tls = tls(p, run, "root")) {
            var session = (PortalRunEventsHttp.Session) begin(transport(tls), f);
            var handle = (PortalEventsTlsConnection) tls.acquire(System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            handle.installRawForTest(new Socket() {
                public void close() throws IOException {
                    entered.countDown();
                    try {
                        if (!release.await(6, TimeUnit.SECONDS)) throw new IOException("close gate limit");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("close gate interrupted");
                    }
                }
            });
            var field = session.getClass().getDeclaredField("handle");
            field.setAccessible(true);
            field.set(session, handle);
            try {
                session.close();
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                assertFalse(session.awaitClosed(Duration.ofMillis(20)));
                assertEquals(3211392L, PortalEventsPacketBudget.SHARED.reservedBytes());
                Object second = begin(transport(tls), f);
                try {
                    assertThrows(RuntimeException.class, () -> begin(transport(tls), f));
                } finally {
                    close(second);
                }
            } finally {
                release.countDown();
                assertTrue(session.awaitClosed(Duration.ofSeconds(3)));
                session.close();
                assertEquals(0, PortalEventsPacketBudget.SHARED.owners());
                assertEquals(0, PortalEventsPacketBudget.SHARED.reservedBytes());
            }
        }
    }
    // This gate delays a real timer worker's exit; it cannot supply TLS/Q/facts or fake termination.
    static class TerminationGate extends ScheduledThreadPoolExecutor {
        final CountDownLatch workerEntered = new CountDownLatch(1), release = new CountDownLatch(1), awaitEntered = new CountDownLatch(1);
        final AtomicInteger waits = new AtomicInteger();
        TerminationGate() {
            super(1, Thread.ofPlatform().daemon().factory());
            execute(() -> {
                workerEntered.countDown();
                try {
                    if (!release.await(6, TimeUnit.SECONDS)) throw new AssertionError("termination gate limit");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            waits.incrementAndGet();
            awaitEntered.countDown();
            return super.awaitTermination(timeout, unit);
        }
    }
    static void concurrentClose(Path run) throws Exception {
        try (var f = new Fixture(); var p = new Peer(normal(), "normal"); var tls = tls(p, run, "root"); var workers = Executors.newFixedThreadPool(2)) {
            var session = (PortalRunEventsHttp.Session) begin(transport(tls), f);
            var timer = new TerminationGate();
            assertTrue(timer.workerEntered.await(1, TimeUnit.SECONDS));
            var timerField = session.getClass().getDeclaredField("timer");
            timerField.setAccessible(true);
            timerField.set(session, timer);
            var handle = (PortalEventsTlsConnection) tls.acquire(System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            var socketEntered = new CountDownLatch(1);
            var socketRelease = new CountDownLatch(1);
            handle.installRawForTest(new Socket() {
                public void close() throws IOException {
                    socketEntered.countDown();
                    try {
                        if (!socketRelease.await(6, TimeUnit.SECONDS)) throw new IOException("physical close gate limit");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    }
                    super.close();
                }
            });
            var handleField = session.getClass().getDeclaredField("handle");
            handleField.setAccessible(true);
            handleField.set(session, handle);
            Object second = begin(transport(tls), f);
            var closer = workers.submit(session::close);
            Future<Boolean> waiter = null;
            try {
                assertTrue(socketEntered.await(1, TimeUnit.SECONDS));
                assertEquals(6422784L, PortalEventsPacketBudget.SHARED.reservedBytes());
                assertFalse(session.awaitClosed(Duration.ofMillis(20)));
                assertThrows(RuntimeException.class, () -> begin(transport(tls), f));
                socketRelease.countDown();
                assertTrue(timer.awaitEntered.await(1, TimeUnit.SECONDS));
                assertTrue(handle.awaitClosed(Duration.ZERO));
                long start = System.nanoTime();
                assertFalse(session.awaitClosed(Duration.ofMillis(40)));
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
                assertEquals(1, timer.waits.get(), "Only the elected caller may await transport termination");
                assertEquals(6422784L, PortalEventsPacketBudget.SHARED.reservedBytes());
                assertThrows(RuntimeException.class, () -> begin(transport(tls), f));
                var waiting = new CountDownLatch(1);
                waiter = workers.submit(() -> {
                    waiting.countDown();
                    return session.awaitClosed(Duration.ofSeconds(3));
                });
                assertTrue(waiting.await(1, TimeUnit.SECONDS));
                timer.release.countDown();
                closer.get(3, TimeUnit.SECONDS);
                assertTrue(waiter.get(3, TimeUnit.SECONDS));
                assertTrue(timer.isTerminated());
                assertEquals(1, timer.waits.get());
                assertEquals(3211392L, PortalEventsPacketBudget.SHARED.reservedBytes());
                session.close();
                assertTrue(session.awaitClosed(Duration.ZERO));
                assertEquals(3211392L, PortalEventsPacketBudget.SHARED.reservedBytes());
            } finally {
                socketRelease.countDown();
                timer.release.countDown();
                closer.get(3, TimeUnit.SECONDS);
                if (waiter != null) assertTrue(waiter.get(3, TimeUnit.SECONDS));
                close(session);
                close(second);
            }
            assertEquals(0, PortalEventsPacketBudget.SHARED.owners());
            assertEquals(0, PortalEventsPacketBudget.SHARED.reservedBytes());
        }
        Files.writeString(run.resolve("summary.properties"), "uniqueCleanup=true\nshortWaitBounded=true\nretainedBeforeTermination=true\nreleasedOnce=true\n");
    }
    static void cleanupRetry(Path run) throws Exception {
        try (var f = new Fixture(); var p = new Peer(normal(), "normal"); var tls = tls(p, run, "root")) {
            for (boolean interrupt : List.of(true, false)) {
                var session = (PortalRunEventsHttp.Session) begin(transport(tls), f);
                var failOnce = new AtomicBoolean(!interrupt);
                var timer = new TerminationGate() {
                    @Override public boolean awaitTermination(long n, TimeUnit unit) throws InterruptedException {
                        if (failOnce.compareAndSet(true, false)) throw new IllegalStateException("mechanical termination failure");
                        return super.awaitTermination(n, unit);
                    }
                };
                assertTrue(timer.workerEntered.await(1, TimeUnit.SECONDS));
                var timerField = session.getClass().getDeclaredField("timer");
                timerField.setAccessible(true);
                timerField.set(session, timer);
                var failure = new AtomicReference<Throwable>();
                var interrupted = new AtomicBoolean();
                Thread closer = Thread.ofPlatform().start(() -> {
                    try {
                        session.close();
                        interrupted.set(Thread.currentThread().isInterrupted());
                    } catch (Throwable e) {
                        failure.set(e);
                    }
                });
                try {
                    if (interrupt) {
                        assertTrue(timer.awaitEntered.await(1, TimeUnit.SECONDS));
                        closer.interrupt();
                    }
                    closer.join(3000);
                    assertFalse(closer.isAlive());
                    assertNull(failure.get());
                    assertEquals(interrupt, interrupted.get());
                    assertSame(timer, field(session, "timer"));
                    assertEquals(false, field(session, "cleanupActive"));
                    assertEquals(3211392L, PortalEventsPacketBudget.SHARED.reservedBytes());
                    assertFalse(timer.isTerminated());
                } finally {
                    timer.release.countDown();
                    closer.join(3000);
                    assertFalse(closer.isAlive());
                    close(session);
                }
                assertTrue(timer.isTerminated());
                assertEquals(0, PortalEventsPacketBudget.SHARED.reservedBytes());
            }
        }
        Files.writeString(run.resolve("summary.properties"), "interruptedCleanupRetried=true\nexceptionCleanupRetried=true\nreferencesRetainedUntilTermination=true\n");
    }
    static void bodyBounds(Path run) throws Exception {
        String body = RunObservationEventsMetadataTest.valid();
        String exact = body + " ".repeat(8192 - utf(body).length);
        assertEquals(8192, utf(exact).length);
        String chunkHead = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n";
        var vectors = List.of(fixed(200, exact), utf(chunkHead + "2000\r\n" + exact + "\r\n0\r\n\r\n"), utf(chunkHead + "2001\r\n" + exact + " \r\n1000\r\n"));
        try (var f = new Fixture()) {
            for (int i = 0; i < vectors.size(); i++) {
                try (var p = new Peer(vectors.get(i), "observe-close"); var tls = tls(p, run, "root")) {
                    var session = (PortalRunEventsHttp.Session) begin(transport(tls), f);
                    var opening = (cn.agentplatform.common.authority.HttpCurrentAuthorityClient.GatewayEventsOpening) field(session, "opening");
                    var original = opening.queryAuthorization();
                    int grants = f.iam.grantCount();
                    var handle = new AtomicReference<PortalEventsTlsConnection>();
                    var timer = new AtomicReference<ScheduledThreadPoolExecutor>();
                    session.beforeOutputForTest(() -> {
                        try {
                            handle.set((PortalEventsTlsConnection) field(session, "handle"));
                            timer.set((ScheduledThreadPoolExecutor) field(session, "timer"));
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                    });
                    long start = System.nanoTime();
                    try {
                        if (i < 2) {
                            var facts = session.lookupMetadata();
                            assertEquals(RUN, facts.runId());
                            assertEquals(RunObservationEventsMetadataTest.OWNER, facts.owner());
                            assertEquals(new cn.agentplatform.common.authority.ResourceBinding("app-version", "Demo@3", RunObservationEventsMetadataTest.SHA), facts.root());
                            assertEquals(17, facts.appVersionId());
                            assertEquals(3, facts.appVersionNo());
                            assertEquals(RunObservationEventsMetadataTest.SHA, facts.acceptedSnapshotSha256());
                            assertEquals(RunObservationEventsMetadataTest.SHA, facts.initialCeilingSha256());
                            assertSame(original, opening.queryAuthorization());
                        } else {
                            assertEquals(503, denied(session).status());
                            assertNull(field(session, "facts"));
                            assertEquals(true, field(session, "stopped"));
                            assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(1500), "overflow exits before hop deadline with tail withheld");
                        }
                        assertNotNull(handle.get());
                        assertTrue(handle.get().awaitClosed(Duration.ZERO));
                        assertTrue(timer.get().isTerminated());
                        assertTrue(p.clientClosed.await(1, TimeUnit.SECONDS));
                        assertTrue(p.closeObserved);
                        assertEquals(grants, f.iam.grantCount());
                        assertEquals(1, p.connects.get());
                        assertEquals(1, p.request.split("Authorization:", - 1).length - 1);
                        assertTrue(p.request.contains("Authorization: Bearer " + original.ticket().value() + "\r\n"));
                        assertThrows(PlatformException.class, session::lookupMetadata);
                        assertEquals(grants, f.iam.grantCount());
                        assertEquals(3211392L, PortalEventsPacketBudget.SHARED.reservedBytes());
                    } finally {
                        close(session);
                    }
                    assertEquals(0, PortalEventsPacketBudget.SHARED.owners());
                    assertEquals(0, PortalEventsPacketBudget.SHARED.reservedBytes());
                }
            }
        }
        Files.writeString(run.resolve("summary.properties"), "cl8192Bytes=8192\nchunk8192Bytes=8192\nchunkOverflowBytes=8193\nchunkOverflowHasContentLength=false\nunknownTailWithheld=true\npeerObservedClose=true\nphysicalExitAndTimerTermination=true\noriginalQueryOnly=true\n");
    }
    public static void main(String[] args) throws Exception {
        Path run = Path.of(args[1]);
        switch (args[0]) {
            case "normal" -> normal(run);
            case "wire" -> wire(run);
            case "errors" -> errors(run);
            case "deadline" -> deadline(run);
            case "proof" -> proof(run);
            case "capacity" -> capacity(run);
            case "mechanics" -> mechanics(run);
            case "close-gate" -> closeGate(run);
            case "concurrent-close" -> concurrentClose(run);
            case "cleanup-retry" -> cleanupRetry(run);
            case "body-bounds" -> bodyBounds(run);
            case "credentials" -> credentials(run);
            case "cancel" -> cancel(run);
            case "body-expiry" -> bodyExpiry(run);
            default -> throw new IllegalArgumentException();
        }
        System.out.println("F2B1_GROUP_OK=" + args[0]);
    }
}
