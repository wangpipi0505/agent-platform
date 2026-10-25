package cn.agentplatform.gateway.infra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

public class PortalEventsTlsTest {
    static final String HOST = "engine.f2a.test";
    static final String CIPHER = "TLS_AES_128_GCM_SHA256";
    static final char[] PASSWORD = "f2a-test-only".toCharArray();
    static Path projectRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path root = Files.exists(cwd.resolve("AGENTS.md"))?cwd:cwd.getParent();
        if (!Files.exists(root.resolve("AGENTS.md")) || !root.getFileName().toString().equals("agent-platform"))throw new IllegalStateException("Isolated project root required");
        return root;
    }

    @Test void normalTlsAuthenticatesAndEchoesWithPersistentRecordOverhead() throws Exception {
        var result = scenario("normal");
        assertEquals("true", result.client.getProperty("success"), result.client.toString());
        assertEquals("TLSv1.3", result.client.getProperty("protocol"));
        assertEquals(CIPHER, result.client.getProperty("cipher"));
        assertEquals("5120", result.server.getProperty("application"));
        assertEquals("true", result.client.getProperty("closed"));
        assertTrue(Long.parseLong(result.client.getProperty("debtAfter")) >
        Long.parseLong(result.client.getProperty("debtBefore")));
    }
    @Test void http11AlpnNegotiationIsAccepted() throws Exception {
        var result = scenario("http11");
        assertEquals("true", result.client.getProperty("success"), result.client.toString());
        assertEquals("http/1.1", result.client.getProperty("alpn"));
    }
    @ParameterizedTest @ValueSource(strings = {"transfer-owner", "transfer-abort", "transfer-target-failure"})
    void bulkReaderOwnsWholeCallIncludingBlockedTarget(String name) throws Exception {
        var result = scenario(name);
        assertEquals("true", result.client.getProperty("checked"), result.client.toString());
        assertEquals("1024", result.client.getProperty("delivered"));
        assertEquals("true", result.client.getProperty("closed"));
    }
    @Test void boundedReadNBytesReturnsShortCountAtEofWithUniqueCredit() throws Exception {
        var result = scenario("bulk-eof");
        assertEquals("true", result.client.getProperty("checked"), result.client.toString());
        assertEquals("1024", result.client.getProperty("delivered"));
        assertEquals("true", result.client.getProperty("closed"));
    }
    @Test void plainObserverDetectsApplicationAndAdditionalConnectionNegativeControl() throws Exception {
        Path run = peerRun("plain-negative-control");
        try (Child server = child(run, "server", "plain", "0")) {
            int port = Integer.parseInt(awaitPort(run, server));
            byte[] marker = "F2A-APPLICATION-NEGATIVE-CONTROL\n".getBytes(StandardCharsets.US_ASCII);
            try (var sender = new Socket(Proxy.NO_PROXY); var extra = new Socket(Proxy.NO_PROXY)) {
                sender.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
                // Queue the extra connection while the first is still open, before the old observer exits.
                extra.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
                sender.getOutputStream().write(marker);
                sender.shutdownOutput();
            }
            server.await(6);
            assertEquals(0, server.process.exitValue(), server.output());
        }
        var observed = properties(run.resolve("server.properties"));
        assertTrue(Integer.parseInt(observed.getProperty("application")) > 0, observed.toString());
        assertEquals("2", observed.getProperty("accepted"), observed.toString());
    }
    @ParameterizedTest @ValueSource(strings = {"server.launch.properties", "server.out"})
    void launchMetadataFailureActuallyTerminatesNewOwnedProcess(String failingTarget) throws Exception {
        Path run = peerRun("launch-failure-" + failingTarget);
        Files.createDirectory(run.resolve(failingTarget));
        var started = new java.util.concurrent.atomic.AtomicReference<Process>();
        try {
            assertThrows(IOException.class, () -> child(run, "server", "stall", "0", started::set));
            Process process = Objects.requireNonNull(started.get());
            assertTrue(process.waitFor(1, TimeUnit.SECONDS), "metadata failure must actually exit the new owned process");
            assertFalse(process.isAlive());
        } finally {
            // RED reproduces a real leak; this test owns this exact Process and always cleans it.
            Process process = started.get();
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                assertTrue(process.waitFor(3, TimeUnit.SECONDS));
                var exit = new Properties();
                exit.setProperty("pid", Long.toString(process.pid()));
                exit.setProperty("alive", Boolean.toString(process.isAlive()));
                exit.setProperty("exitCode", Integer.toString(process.exitValue()));
                store(run.resolve("failed-launch.exit.properties"), exit);
            }
        }
    }
    @Test void closedSocketStateSurvivesCloseFailureWithoutReleasingSlot() throws Exception {
        Path run = peerRun("close-state");
        try (var child = child(run, "mechanics", "close-state", "0")) {
            child.await(10);
            assertEquals(0, child.process.exitValue(), child.output());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"wrong-ca","wrong-san","expired","no-cert","plain","tls12","aes256","alpn"})
    void credentialOrProfileFailureDeliversNoApplication(String name) throws Exception {
        var r = scenario(name);
        assertEquals("false", r.client.getProperty("success"), r.client.toString());
        assertNotNull(r.client.getProperty("failure"));
        assertEquals("0", r.server.getProperty("application"));
        assertEquals("1", r.server.getProperty("accepted"));
        assertEquals("true", r.client.getProperty("closed"));
        if (name.equals("plain")) {
            assertTrue(Integer.parseInt(r.server.getProperty("captured")) > 0);
            assertTrue(Integer.parseInt(r.server.getProperty("failureHandshakeRecords")) > 0);
            assertEquals("0", r.server.getProperty("opaqueTlsRecords"));
            assertEquals("0", r.server.getProperty("incompleteTlsBytes"));
            assertEquals("false", r.server.getProperty("applicationMarker"));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"four","five","h","h-plus"})
    void validSignedPeerFixturesExerciseExactStaticThreshold(String name) throws Exception {
        var r = scenario(name);
        boolean good = name.equals("four") || name.equals("h");
        assertEquals(Boolean.toString(good), r.client.getProperty("success"), r.client.toString());
        assertEquals(name.equals("four")?"4":name.equals("five")?"5":"2", r.server.getProperty("chain"));
        if (name.startsWith("h"))assertEquals(name.equals("h")?"32768":"32769", r.server.getProperty("certificateBody"));
        if (!good) {
            assertEquals("0", r.server.getProperty("application"));
            assertTrue(r.client.getProperty("failure").contains(name.equals("five")?"maximum allowed length (4)":"maximum allowed size (32768)"), r.client.toString());
        }
        assertEquals("true", r.client.getProperty("closed"));
    }
    @Test void normalTicketsInterleaveWithoutReset() throws Exception { assertTraffic("nst"); }
    @ParameterizedTest @ValueSource(strings = {"tiny","keyupdate","output","reuse","concurrent-read","transfer","stream-close"})
    void realTlsTrafficKeepsCumulativeDebtAndOutputBounded(String name) throws Exception {
        assertTraffic(name);
    }
    void assertTraffic(String name) throws Exception {
        var r = scenario(name);
        assertEquals("true", r.client.getProperty("checked"), r.client.toString());
        assertEquals("true", r.client.getProperty("closed"));
        if (name.equals("tiny")) {
            assertEquals("65536", r.client.getProperty("debtAfter"));
            assertTrue(Long.parseLong(r.client.getProperty("delivered"))>1000);
            assertEquals("false", r.client.getProperty("eof"));
        }
        if (name.equals("keyupdate")) {
            assertEquals("0", r.client.getProperty("delivered"));
            assertTrue(Long.parseLong(r.client.getProperty("raw"))>10000, r.client.toString());
            assertTrue(Integer.parseInt(r.server.getProperty("updates"))>100);
        }
        if (name.equals("output")) assertTrue(Long.parseLong(r.client.getProperty("output")) <= 131072);
        if (name.equals("nst")) {
            String server = Files.readString(Path.of(r.server.getProperty("run")).resolve("server.out"));
            String client = Files.readString(Path.of(r.server.getProperty("run")).resolve("client.out"));
            assertEquals(10, server.split("Produced NewSessionTicket", -1).length-1);
            assertEquals(10, client.split("Consuming NewSessionTicket", -1).length-1);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"stall","drip","idle","blocked-read","blocked-write","absolute","caller"})
    void realEndpointsObserveFixedDeadlinesAndAbort(String name) throws Exception {
        var r = scenario(name);
        assertEquals("true", r.client.getProperty("checked"), r.client.toString());
        assertEquals("true", r.client.getProperty("closed"));
        long ms = Long.parseLong(r.client.getProperty("elapsedMillis"));
        if (name.equals("stall") || name.equals("drip"))assertTrue(ms >= 1700 && ms<3500,"elapsed="+ms);
        if (name.equals("idle") || name.equals("blocked-read"))assertTrue(ms >= 14000 && ms<18000,"elapsed="+ms);
        if (name.equals("absolute"))assertTrue(ms >= 54000 && ms<58000,"elapsed="+ms);
        if (name.equals("caller"))assertTrue(ms >= 2300 && ms<4500,"elapsed="+ms);
    }
    @Test void mechanicalLifecycleAndConfigurationHaveNoAuthorityBypass() throws Exception {
        Path run = peerRun("mechanics");
        try (var child = child(run,"mechanics","mechanics","0")) {
            child.await(15);
            assertEquals(0, child.process.exitValue(), child.output());
        }
        assertEquals("true", properties(run.resolve("mechanics.properties")).getProperty("checked"));
    }
    @ParameterizedTest @ValueSource(strings = {"missing-profile","aia-enabled"})
    void missingOrNetworkEnabledJvmProfileFailsBeforeAcquire(String name) throws Exception {
        Path run = peerRun("profile");
        try (var child = child(run,"mechanics", name,"0")) {
            child.await(10);
            assertEquals(0, child.process.exitValue(), child.output());
        }
    }

    record Result(Properties client, Properties server) { }
    static Path peerRun(String name) throws IOException {
        return Files.createDirectories(projectRoot().resolve(Path.of(".local", "iam-o2b-f2a-fixes", "peers", name + "-" + UUID.randomUUID())));
    }
    static String awaitPort(Path run, Child server) throws Exception {
        Path portFile = run.resolve("port");
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while ((!Files.exists(portFile) || Files.size(portFile) == 0) && server.process.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(Files.exists(portFile) && Files.size(portFile) > 0, "server port not published: " + server.output());
        return Files.readString(portFile);
    }
    Result scenario(String name) throws Exception {
        Path run = peerRun(name);
        try (Child server = child(run, "server", name, "0")) {
            String port = awaitPort(run, server);
            try (Child client = child(run, "client", name, port)) {
                client.await(70);
                server.await(5);
                assertEquals(0, client.process.exitValue(), client.output());
                assertEquals(0, server.process.exitValue(), server.output());
                return new Result(properties(run.resolve("client.properties")), properties(run.resolve("server.properties")));
            }
        }
    }
    static Properties properties(Path file) throws IOException {
        var p = new Properties();
        try (var in = Files.newInputStream(file)) {
            p.load(in);
        }
        return p;
    }
    static void store(Path file, Properties p) throws IOException {
        try (var out = Files.newOutputStream(file)) {
            p.store(out, "isolated F2a test result; no credentials");
        }
    }
    static Child child(Path run, String mode, String scenario, String port) throws Exception {
        return child(run, mode, scenario, port, process -> { });
    }
    static Child child(Path run, String mode, String scenario, String port, java.util.function.Consumer<Process> started) throws Exception {
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path args = run.resolve(mode + ".args");
        List<String> words = new ArrayList<>(List.of("-Djdk.tls.maxHandshakeMessageSize=32768", "-Djdk.tls.maxCertificateChainLength=4",
        "-Dcom.sun.security.enableAIAcaIssuers=false",
        "-Djdk.tls.server.newSessionTicketCount="+(scenario.startsWith("h")?"0":"10"), "-Djdk.tls.server.enableStatusRequestExtension=false",
        "-cp", cp, PortalEventsTlsTest.class.getName(), mode, scenario, port, run.toAbsolutePath().toString()));
        if (scenario.equals("nst"))words.addFirst("-Djavax.net.debug=ssl,handshake");
        if (scenario.equals("missing-profile"))words.removeIf(s -> s.startsWith("-Djdk.tls.maxHandshakeMessageSize="));
        if (scenario.equals("aia-enabled")) {
            words.remove("-Dcom.sun.security.enableAIAcaIssuers=false");
            words.addFirst("-Dcom.sun.security.enableAIAcaIssuers=true");
        }
        Files.writeString(args, words.stream().map(s -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .reduce((a, b) -> a + "\n" + b).orElseThrow(), StandardCharsets.UTF_8);
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        var command = new ArrayList<String>();
        command.add(java);
        command.addAll(words);
        Process process = new ProcessBuilder(command).directory(projectRoot().toFile()).redirectErrorStream(true).start();
        // From the first instruction after start, this scope owns cleanup until Child takes over.
        try {
            started.accept(process);
            var launch = new Properties();
            launch.setProperty("pid", Long.toString(process.pid()));
            launch.setProperty("java", java);
            launch.setProperty("cwd", projectRoot().toString());
            launch.setProperty("argumentsFile", args.toString());
            launch.setProperty("mergedStderr", "true");
            store(run.resolve(mode + ".launch.properties"), launch);
            return new Child(process, run.resolve(mode + ".out"), scenario.equals("nst") ? 1048576 : 131072);
        } catch (Exception | Error failure) {
            try {
                terminate(process);
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }
    static void terminate(Process process) throws IOException {
        if (process.isAlive()) process.destroyForcibly();
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        boolean interrupted = false;
        try {
            while (process.isAlive() && System.nanoTime() < deadline) {
                try {
                    process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (process.isAlive()) throw new IOException("new owned child failed to actually exit");
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    static final class Child implements AutoCloseable {
        final Process process;
        final Path file;
        final Thread collector;
        volatile Throwable error;
        Child(Process process, Path file, int limit) throws IOException {
            this.process = process;
            this.file = file;
            var output = Files.newOutputStream(file);
            try {
                collector = Thread.ofPlatform().daemon().unstarted(() -> {
                    try (var in = process.getInputStream(); var out = output) {
                        byte[] buffer = new byte[4096];
                        int n, total = 0;
                        while ((n = in.read(buffer)) != -1) {
                            total += n;
                            if (total > limit) throw new IOException("child output limit");
                            out.write(buffer, 0, n);
                        }
                    } catch (Throwable e) {
                        error = e;
                        process.destroyForcibly();
                    }
                });
                collector.start();
            } catch (RuntimeException | Error failure) {
                try {
                    output.close();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
        void await(int seconds) throws Exception {
            assertTrue(process.waitFor(seconds, TimeUnit.SECONDS), "child deadline");
            collector.join(2000);
            assertFalse(collector.isAlive());
            assertNull(error);
            persistExit();
        }
        String output() throws IOException { return Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : ""; }
        public void close() throws Exception {
            terminate(process);
            collector.join(2000);
            assertFalse(collector.isAlive());
            persistExit();
        }
        void persistExit() throws IOException {
            Path exit = file.resolveSibling(file.getFileName()+".exit.properties");
            if (!Files.exists(exit)) {
                var p = new Properties();
                p.setProperty("pid", Long.toString(process.pid()));
                p.setProperty("exitCode", Integer.toString(process.exitValue()));
                p.setProperty("alive", Boolean.toString(process.isAlive()));
                p.setProperty("collectorAlive", Boolean.toString(collector.isAlive()));
                store(exit, p);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Path run = Path.of(args[3]);
        if (args[0].equals("server")) server(args[1], run);
        else if (args[0].equals("mechanics"))mechanics(args[1], run);
        else client(args[1], Integer.parseInt(args[2]), run);
    }
    static KeyStore fixtures() throws Exception {
        var ks = KeyStore.getInstance("PKCS12");
        try (var in = PortalEventsTlsTest.class.getResourceAsStream("/iam-o2b-f2a/tls-fixtures.p12")) {
            ks.load(in, PASSWORD);
        }
        return ks;
    }
    static Path ca(KeyStore ks, String alias, Path run) throws Exception {
        byte[] der = ks.getCertificate(alias).getEncoded();
        String pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der)
        + "\n-----END CERTIFICATE-----\n";
        return Files.writeString(run.resolve("ca.pem"), pem);
    }
    static void runtime(Properties p) throws Exception {
        p.setProperty("runtime", System.getProperty("java.runtime.version"));
        p.setProperty("vendor", System.getProperty("java.vendor"));
        p.setProperty("provider", SSLContext.getInstance("TLSv1.3", "SunJSSE").getProvider().toString());
        p.setProperty("H", System.getProperty("jdk.tls.maxHandshakeMessageSize"));
        p.setProperty("chainLimit", System.getProperty("jdk.tls.maxCertificateChainLength"));
        p.setProperty("tickets", System.getProperty("jdk.tls.server.newSessionTicketCount"));
        p.setProperty("AIA", System.getProperty("com.sun.security.enableAIAcaIssuers"));
    }
    static void client(String scenario, int port, Path run) throws Exception {
        var p = new Properties();
        runtime(p);
        boolean success = false;
        long started = System.nanoTime();
        try (var tls = new PortalEventsTls(URI.create("https://" + HOST + ":" + port), new byte[] {
                127, 0, 0, 1
        }, ca(fixtures(), scenario.equals("wrong-ca")?"other":"root", run))) {
            var c = (PortalEventsTlsConnection)tls.acquire(System.nanoTime() + Duration.ofMillis(scenario.equals("caller")?3000:60000).toNanos());
            long hard = c.hardDeadlineNanos(), opening = c.openingDeadlineNanos();
            try {
                if (scenario.equals("blocked-write"))c.rawSocketForConnect().setSendBufferSize(1024);
                SSLSocket s = c.connectAndHandshake();
                p.setProperty("protocol", s.getSession().getProtocol());
                p.setProperty("cipher", s.getSession().getCipherSuite());
                p.setProperty("alpn", s.getApplicationProtocol());
                // Snapshots are read-only and do not alter the ledger.
                p.setProperty("debtBefore", Long.toString(((PortalEventsTlsConnection)c).snapshot().debt()));
                if (scenario.equals("stream-close")) {
                    var input = s.getInputStream();
                    var output = s.getOutputStream();
                    input.close();
                    output.close();
                    assertTrue(c.awaitClosed(Duration.ofSeconds(2)));
                    assertThrows(IOException.class, () -> input.read());
                    assertThrows(IOException.class, () -> output.write(1));
                    assertThrows(IOException.class, s::getInputStream);
                    assertTrue(s.isClosed());
                } else if (scenario.equals("concurrent-read")) {
                    try (var pool = Executors.newSingleThreadExecutor()) {
                        var read = pool.submit(() -> {
                            try {
                                s.getInputStream().read();
                                return false;
                            } catch (IOException e) {
                                return true;
                            }
                        });
                        long wait = System.nanoTime()+Duration.ofSeconds(1).toNanos();
                        while (c.snapshot().reserved() == 0 && System.nanoTime()<wait)Thread.sleep(5);
                        assertTrue(c.snapshot().reserved()>0);
                        assertThrows(IOException.class, () -> s.getInputStream().read());
                        c.abort();
                        assertTrue(read.get(2, TimeUnit.SECONDS));
                    }
                } else if (scenario.equals("bulk-eof")) {
                    byte[] buffer = new byte[2048];
                    assertEquals(1024, s.getInputStream().readNBytes(buffer, 0, buffer.length));
                    byte[] expected = new byte[1024];
                    Arrays.fill(expected, (byte) 42);
                    assertArrayEquals(expected, Arrays.copyOf(buffer, 1024));
                    assertArrayEquals(new byte[1024], Arrays.copyOfRange(buffer, 1024, 2048));
                    assertEquals(1024, c.snapshot().delivered());
                } else if (Set.of("transfer-owner", "transfer-abort", "transfer-target-failure").contains(scenario)) {
                    var targetEntered = new CountDownLatch(1);
                    var targetRelease = new CountDownLatch(1);
                    var bytes = new ByteArrayOutputStream();
                    var target = new OutputStream() {
                        public void write(int value) throws IOException {
                            throw new IOException("bulk write required");
                        }
                        public void write(byte[] data, int off, int len) throws IOException {
                            targetEntered.countDown();
                            try {
                                if (!targetRelease.await(4, TimeUnit.SECONDS)) throw new IOException("target latch deadline");
                            } catch (InterruptedException e) {
                                throw new IOException(e);
                            }
                            bytes.write(data, off, len);
                            if (scenario.equals("transfer-target-failure")) throw new IOException("deliberate target write failure");
                        }
                    };
                    var input = s.getInputStream();
                    try (var pool = Executors.newSingleThreadExecutor()) {
                        var bulk = pool.submit(() -> input.transferTo(target));
                        try {
                            assertTrue(targetEntered.await(2, TimeUnit.SECONDS));
                            assertEquals(1024, c.snapshot().delivered());
                            assertThrows(IOException.class, input::read, "second reader while target.write is blocked");
                            assertThrows(IOException.class, () -> input.readNBytes(new byte[1], 0, 1));
                            assertThrows(IOException.class, () -> input.transferTo(new ByteArrayOutputStream()));
                            if (scenario.equals("transfer-abort")) {
                                var other = tls.acquire(deadline());
                                try {
                                    c.abort();
                                    assertFalse(c.awaitClosed(Duration.ofMillis(100)), "blocked bulk still owns active I/O");
                                    assertThrows(IOException.class, () -> tls.acquire(deadline()));
                                } finally {
                                    other.abort();
                                    assertTrue(other.awaitClosed(Duration.ofSeconds(2)));
                                }
                            }
                        } finally {
                            targetRelease.countDown();
                        }
                        if (scenario.equals("transfer-abort") || scenario.equals("transfer-target-failure")) {
                            var failure = assertThrows(ExecutionException.class, () -> bulk.get(2, TimeUnit.SECONDS));
                            assertInstanceOf(IOException.class, failure.getCause());
                        } else {
                            assertEquals(1024L, bulk.get(2, TimeUnit.SECONDS));
                            assertEquals(0, input.readNBytes(new byte[0], 0, 0));
                            assertThrows(IndexOutOfBoundsException.class, () -> input.readNBytes(new byte[1], 1, 1));
                            assertThrows(NullPointerException.class, () -> input.transferTo(null));
                        }
                    } finally {
                        targetRelease.countDown();
                    }
                    byte[] expected = new byte[1024];
                    Arrays.fill(expected, (byte) 42);
                    assertArrayEquals(expected, bytes.toByteArray());
                    assertEquals(1024, c.snapshot().delivered(), "one credit per returned TLS byte");
                } else if (scenario.equals("transfer")) {
                    var out = new ByteArrayOutputStream();
                    assertEquals(1024, s.getInputStream().transferTo(out));
                    assertEquals(1024, c.snapshot().delivered());
                } else if (scenario.equals("tiny") || scenario.equals("keyupdate")) {
                    boolean eof = false;
                    try {
                        while (true) {
                            int n = s.getInputStream().read();
                            if (n<0) {
                                eof = true;
                                break;
                            }
                        }
                    } catch (IOException e) {
                        p.setProperty("failure", failure(e));
                    }
                    p.setProperty("eof", Boolean.toString(eof));
                    assertFalse(eof,"capacity/timeout must not masquerade as EOF");
                    assertTrue(c.snapshot().debt() == 65536 || scenario.equals("keyupdate"));
                } else if (scenario.equals("output")) {
                    boolean limited = false;
                    try {
                        for (int i = 0; i<300; i++)s.getOutputStream().write(new byte[1024]);
                    } catch (IOException e) {
                        limited = true;
                        p.setProperty("failure", failure(e));
                    }
                    assertTrue(limited);
                    assertTrue(c.snapshot().output() <= 131072);
                } else if (scenario.equals("idle") || scenario.equals("caller")) {
                    assertTrue(c.awaitClosed(Duration.ofSeconds(19)));
                } else if (scenario.equals("blocked-read")) {
                    assertThrows(IOException.class, () -> s.getInputStream().read());
                } else if (scenario.equals("blocked-write")) {
                    try (var pool = Executors.newSingleThreadExecutor()) {
                        var entered = new CountDownLatch(1);
                        var write = pool.submit(() -> {
                            entered.countDown();
                            try {
                                s.getOutputStream().write(new byte[120000]);
                                return false;
                            } catch (IOException e) {
                                return true;
                            }
                        });
                        assertTrue(entered.await(1, TimeUnit.SECONDS));
                        Thread.sleep(350);
                        assertFalse(write.isDone(),"real peer must stop reading before W is reached");
                        assertThrows(IOException.class, () -> s.getOutputStream().flush());
                        c.abort();
                        assertTrue(write.get(3, TimeUnit.SECONDS));
                    }
                } else {
                    verifyPublicView(s, c, port);
                    byte[] message = new byte[64];
                    Arrays.fill(message, (byte) 42);
                    int rounds = scenario.equals("absolute")?650:80;
                    try {
                        for (int i = 0; i < rounds; i++) {
                            s.getOutputStream().write(message);
                            s.getOutputStream().flush();
                            byte[] echo = new byte[64];
                            assertEquals(64, s.getInputStream().readNBytes(echo, 0, echo.length));
                            assertArrayEquals(message, echo);
                            if (scenario.equals("absolute"))Thread.sleep(100);
                            if (scenario.equals("reuse") && i == 39) {
                                long debt = c.snapshot().debt();
                                Thread.sleep(100);
                                assertEquals(debt, c.snapshot().debt());
                            }
                        }
                    } catch (IOException e) {
                        if (!scenario.equals("absolute"))throw e;
                    }
                    if (!scenario.equals("absolute"))assertEquals(5120, c.snapshot().delivered());
                }
                success = true;
                p.setProperty("checked","true");
            } catch (IOException e) {
                p.setProperty("failure", failure(e));
                if (scenario.equals("stall") || scenario.equals("drip"))p.setProperty("checked","true");
            } finally {
                assertEquals(hard, c.hardDeadlineNanos());
                assertEquals(opening, c.openingDeadlineNanos());
                c.abort();
                p.setProperty("closed", Boolean.toString(c.awaitClosed(Duration.ofSeconds(3))));
                var snap = c.snapshot();
                p.setProperty("debtAfter", Long.toString(snap.debt()));
                p.setProperty("delivered", Long.toString(snap.delivered()));
                p.setProperty("raw", Long.toString(snap.raw()));
                p.setProperty("output", Long.toString(snap.output()));
                Files.writeString(run.resolve("client-done"),"exited I/O");
            }
        }
        p.setProperty("elapsedMillis", Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)));
        p.setProperty("success", Boolean.toString(success));
        store(run.resolve("client.properties"), p);
    }
    static String failure(Throwable e) {
        var b = new StringBuilder();
        for (int n = 0; e != null && n<8; n++, e = e.getCause())b.append(e.getClass().getSimpleName()).append(':').append(e.getMessage()).append(';');
        return b.substring(0, Math.min(b.length(), 2048));
    }
    static PortalEventsTls factory(Path ca) throws IOException{return new PortalEventsTls(URI.create("https://"+HOST+":443"), new byte[]{127, 0, 0, 1}, ca); }
    static long deadline(){return System.nanoTime()+Duration.ofSeconds(30).toNanos(); }
    static void mechanics(String name, Path run) throws Exception{
        Path ca = ca(fixtures(),"root", run);
        if (name.equals("close-state")) {
            try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()); var f = factory(ca)) {
                var bottom = new Socket(Proxy.NO_PROXY) {
                    @Override public void close() throws IOException {
                        super.close();
                        throw new IOException("failure after physical socket close");
                    }
                };
                bottom.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()), 1000);
                try (var peer = listener.accept()) {
                    bottom.shutdownInput();
                    bottom.shutdownOutput();
                    var c = (PortalEventsTlsConnection) f.acquire(deadline());
                    c.installRawForTest(bottom);
                    // State-only reflection: no handshake/verified flag/stream or authority is manufactured.
                    Class<?> viewType = Arrays.stream(PortalEventsTlsConnection.class.getDeclaredClasses())
                            .filter(type -> type.getSimpleName().equals("SafeSsl")).findFirst().orElseThrow();
                    var constructor = viewType.getDeclaredConstructor(PortalEventsTlsConnection.class);
                    constructor.setAccessible(true);
                    var view = (SSLSocket) constructor.newInstance(c);
                    var other = f.acquire(deadline());
                    try {
                        c.abort();
                        assertFalse(c.awaitClosed(Duration.ofMillis(200)), "close failure retains slot");
                        assertTrue(bottom.isClosed());
                        assertTrue(view.isClosed(), "known physical closed state must survive cleanup failure");
                        assertTrue(view.isInputShutdown());
                        assertTrue(view.isOutputShutdown());
                        assertThrows(IOException.class, () -> f.acquire(deadline()));
                        assertThrows(IOException.class, view::getInputStream);
                    } finally {
                        other.abort();
                        assertTrue(other.awaitClosed(Duration.ofSeconds(2)));
                    }
                }
            }
            return;
        }
        if (name.equals("missing-profile") || name.equals("aia-enabled")) {
            assertThrows(IOException.class, () -> factory(ca));
            return;
        }
        String pem = Files.readString(ca);
        for (int n = 1; n <= 4; n++)try (var f = factory(Files.writeString(run.resolve("ca-"+n+".pem"), pem.repeat(n)))) {
        }
        Path maximum = Files.writeString(run.resolve("ca-max.pem"), pem+" ".repeat(65536-pem.length()));
        try (var f = factory(maximum)) {
        }
        assertThrows(IOException.class, () -> factory(Files.writeString(run.resolve("ca-over.pem"), pem+" ".repeat(65537-pem.length()))));
        assertThrows(IOException.class, () -> factory(Files.writeString(run.resolve("ca-five.pem"), pem.repeat(5))));
        assertThrows(IOException.class, () -> factory(Files.writeString(run.resolve("ca-tail.pem"), pem+"garbage")));
        assertThrows(IOException.class, () -> factory(ca(fixtures(),"normal", Files.createDirectory(run.resolve("nonca")))));
        assertThrows(IOException.class, () -> factory(Files.writeString(run.resolve("empty.pem"),"")));
        for (String origin:List.of("http://engine.f2a.test:443","https://engine.f2a.test","https://engine.f2a.test:0","https://engine.f2a.test:65536","https://engine.f2a.test:443/","https://user@engine.f2a.test:443","https://engine.f2a.test:443?q=x","https://engine.f2a.test:443#x","https://127.0.0.1:443","https://bad-.f2a.test:443"))
        assertThrows(IOException.class, () -> new PortalEventsTls(URI.create(origin), new byte[]{127, 0, 0, 1}, ca));
        assertThrows(IOException.class, () -> new PortalEventsTls(URI.create("https://"+HOST+":443"), new byte[5], ca));
        try (var one = factory(ca); var two = factory(ca)) {
            assertThrows(IOException.class, () -> one.acquire(System.nanoTime()-1));
            var a = (PortalEventsTlsConnection)one.acquire(deadline());
            var b = (PortalEventsTlsConnection)two.acquire(deadline());
            var rawA = new BlockingSocket();
            var rawB = new BlockingSocket();
            a.installRawForTest(rawA);
            b.installRawForTest(rawB);
            Socket view = a.rawSocketForConnect();
            assertNull(view.getChannel());
            assertSame(view.getInputStream(), view.getInputStream());
            assertSame(view.getOutputStream(), view.getOutputStream());
            try (var pool = Executors.newSingleThreadExecutor()) {
                var read = pool.submit(() -> {
                    try {
                        view.getInputStream().read(new byte[20]);
                        return false;
                    } catch (IOException e) {
                        return true;
                    }
                });
                assertTrue(rawA.readEntered.await(2, TimeUnit.SECONDS));
                assertEquals(20, a.snapshot().reserved());
                a.abort();
                a.close();
                a.abort();
                b.abort();
                assertTrue(rawA.closeEntered.await(2, TimeUnit.SECONDS));
                assertTrue(rawB.closeEntered.await(2, TimeUnit.SECONDS));
                assertFalse(a.awaitClosed(Duration.ofMillis(10)));
                assertThrows(IOException.class, () -> one.acquire(deadline()));
                rawA.closeRelease.countDown();
                rawB.closeRelease.countDown();
                assertTrue(b.awaitClosed(Duration.ofSeconds(2)));
                // Fill the newly released second slot while the first read still has not actually returned.
                var replacement = two.acquire(deadline());
                assertThrows(IOException.class, () -> one.acquire(deadline()));
                assertFalse(a.awaitClosed(Duration.ofMillis(10)));
                rawA.readRelease.countDown();
                assertTrue(read.get(2, TimeUnit.SECONDS));
                assertTrue(a.awaitClosed(Duration.ofSeconds(2)));
                assertEquals(1, rawA.closes.get());
                replacement.abort();
                assertTrue(replacement.awaitClosed(Duration.ofSeconds(2)));
            } finally {
                rawA.closeRelease.countDown();
                rawB.closeRelease.countDown();
                rawA.readRelease.countDown();
            }
            var before = (PortalEventsTlsConnection)one.acquire(deadline());
            before.abort();
            assertThrows(IOException.class, before::rawSocketForConnect);
            assertTrue(before.awaitClosed(Duration.ofSeconds(2)));
            var still = two.acquire(deadline());
            one.close();
            assertThrows(IOException.class, () -> one.acquire(deadline()));
            var another = two.acquire(deadline());
            still.abort();
            another.abort();
            assertTrue(still.awaitClosed(Duration.ofSeconds(2)));
            assertTrue(another.awaitClosed(Duration.ofSeconds(2)));
        }
        try (var f = factory(ca)) {
            // A close error deliberately poisons its physical slot until this isolated JVM exits.
            var a = (PortalEventsTlsConnection)f.acquire(deadline());
            var b = (PortalEventsTlsConnection)f.acquire(deadline());
            a.installRawForTest(new Socket() {
                @Override public void close() throws IOException {
                    throw new IOException("deliberate close failure");
                }
            });
            b.installRawForTest(new Socket() {
                @Override public void close() throws IOException {
                    throw new IOException("deliberate close failure");
                }
            });
            a.abort();
            b.abort();
            assertFalse(a.awaitClosed(Duration.ofMillis(100)));
            assertFalse(b.awaitClosed(Duration.ofMillis(100)));
            assertThrows(IOException.class, () -> f.acquire(deadline()));
        }
        var p = new Properties();
        p.setProperty("checked","true");
        store(run.resolve("mechanics.properties"), p);
    }
    static final class BlockingSocket extends Socket{
        final CountDownLatch readEntered = new CountDownLatch(1), readRelease = new CountDownLatch(1), closeEntered = new CountDownLatch(1), closeRelease = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        @Override public InputStream getInputStream() {
            return new InputStream() {
                public int read() throws IOException {
                    return read(new byte[1], 0, 1);
                }
                public int read(byte[] b, int off, int len) throws IOException {
                    readEntered.countDown();
                    try {
                        if (!readRelease.await(5, TimeUnit.SECONDS))throw new IOException("latch timeout");
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                    return 1;
                }
            };
        }
        @Override public OutputStream getOutputStream(){return new ByteArrayOutputStream(); }
        @Override public boolean isConnected(){return true; }
        @Override public void close() throws IOException {
            closes.incrementAndGet();
            closeEntered.countDown();
            try {
                if (!closeRelease.await(5, TimeUnit.SECONDS))throw new IOException("close latch timeout");
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }
    }
    static void verifyPublicView(SSLSocket s, PortalEventsTlsConnection c, int port) throws Exception{
        assertSame(s.getInputStream(), s.getInputStream());
        assertSame(s.getOutputStream(), s.getOutputStream());
        assertNull(s.getChannel());
        assertTrue(s.isConnected());
        assertTrue(s.isBound());
        assertFalse(s.isClosed());
        assertEquals(port, s.getPort());
        assertEquals(InetAddress.getByAddress(new byte[] {
            127, 0, 0, 1
        }), s.getInetAddress());
        assertTrue(s.getLocalPort()>0);
        assertEquals("HTTPS", s.getSSLParameters().getEndpointIdentificationAlgorithm());
        assertArrayEquals(new String[]{"http/1.1"}, s.getSSLParameters().getApplicationProtocols());
        assertEquals(HOST, ((SNIHostName)s.getSSLParameters().getServerNames().getFirst()).getAsciiName());
        assertThrows(IOException.class, s::startHandshake);
        assertThrows(UnsupportedOperationException.class, () -> s.setEnabledProtocols(new String[] {
            "TLSv1.2"
        }));
        assertThrows(UnsupportedOperationException.class, () -> s.setSSLParameters(new SSLParameters()));
        assertNull(s.getSession().getSessionContext());
        assertThrows(IOException.class, () -> s.getInputStream().skip(1));
        assertThrows(IOException.class, () -> s.getInputStream().readNBytes(2));
        assertThrows(IOException.class, () -> s.getInputStream().readAllBytes());
        assertThrows(IOException.class, () -> s.getInputStream().reset());
        assertFalse(s.getInputStream().markSupported());
        assertEquals(0, s.getInputStream().read(new byte[0]));
        s.setTcpNoDelay(true);
        assertTrue(s.getTcpNoDelay());
        s.setKeepAlive(true);
        assertTrue(s.getKeepAlive());
        assertThrows(IOException.class, () -> s.setSoTimeout(0));
        assertTrue(s.getSoTimeout()>0);
        assertNotNull(s.supportedOptions());
    }
    static void server(String scenario, Path run) throws Exception {
        if (Set.of("plain","stall","drip").contains(scenario)) {
            rawServer(scenario, run);
            return;
        }
        var p = new Properties();
        runtime(p);
        int application = 0, accepted = 0, updates = 0;
        var context = SSLContext.getInstance("TLSv1.3", "SunJSSE");
        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(fixtures(), PASSWORD);
        X509ExtendedKeyManager original = Arrays.stream(kmf.getKeyManagers()).filter(X509ExtendedKeyManager.class::isInstance)
                .map(X509ExtendedKeyManager.class::cast).findFirst().orElseThrow();
        String alias = Set.of("wrong-san","expired","four","five","h","h-plus").contains(scenario)?scenario:"normal";
        var serverTrust = KeyStore.getInstance("PKCS12");
        serverTrust.load(null, null);
        serverTrust.setCertificateEntry("root", fixtures().getCertificate("root"));
        var tmf = TrustManagerFactory.getInstance("PKIX");
        tmf.init(serverTrust);
        context.init(new KeyManager[]{new FixedKey(original, scenario.equals("no-cert")?null:alias, p)}, tmf.getTrustManagers(), null);
        try (var listener = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 1, InetAddress.getByAddress(new byte[] {
            127, 0, 0, 1
        }))) {
            listener.setEnabledProtocols(new String[]{scenario.equals("tls12")?"TLSv1.2":"TLSv1.3"});
            listener.setEnabledCipherSuites(new String[]{scenario.equals("tls12")?"TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256":scenario.equals("aes256")?"TLS_AES_256_GCM_SHA384":CIPHER});
            if (scenario.equals("blocked-write"))listener.setReceiveBufferSize(1024);
            listener.setNeedClientAuth(false);
            listener.setSoTimeout(3000);
            Files.writeString(run.resolve("port"), Integer.toString(listener.getLocalPort()));
            try (var socket = (SSLSocket) listener.accept()) {
                accepted++;
                if (scenario.equals("alpn"))socket.setHandshakeApplicationProtocolSelector((s, offered) -> "h2");
                if (scenario.equals("http11")) {
                    var parameters = socket.getSSLParameters();
                    parameters.setApplicationProtocols(new String[] {
                        "http/1.1"
                    });
                    socket.setSSLParameters(parameters);
                }
                socket.setSoTimeout(65000);
                socket.startHandshake();
                p.setProperty("protocol", socket.getSession().getProtocol());
                p.setProperty("cipher", socket.getSession().getCipherSuite());
                p.setProperty("clientAuth", Boolean.toString(socket.getNeedClientAuth()));
                assertEquals(Integer.parseInt(p.getProperty("chain")), socket.getSession().getLocalCertificates().length);
                if (scenario.equals("tiny")) {
                    for (int i = 0; i<4000; i++) {
                        socket.getOutputStream().write(7);
                        socket.getOutputStream().flush();
                    }
                } else if (scenario.equals("keyupdate")) {
                    for (int i = 0; i<10000; i++) {
                        socket.startHandshake();
                        updates++;
                    }
                } else if (scenario.equals("blocked-write")) {
                    long deadline = System.nanoTime()+Duration.ofSeconds(18).toNanos();
                    while (!Files.exists(run.resolve("client-done")) && System.nanoTime()<deadline)Thread.sleep(10);
                } else if (scenario.equals("output")) {
                    byte[] b = new byte[2048];
                    int n;
                    while ((n = socket.getInputStream().read(b)) != -1)application+=n;
                } else if (Set.of("transfer", "transfer-owner", "transfer-abort", "transfer-target-failure", "bulk-eof").contains(scenario)) {
                    byte[] payload = new byte[1024];
                    Arrays.fill(payload, (byte) 42);
                    socket.getOutputStream().write(payload);
                    socket.getOutputStream().flush();
                    socket.shutdownOutput();
                } else if (Set.of("idle","caller","blocked-read","concurrent-read","stream-close").contains(scenario)) {
                    assertEquals(-1, socket.getInputStream().read());
                }else{
                    byte[] b = new byte[64];
                    int rounds = scenario.equals("absolute")?650:80;
                    for (int i = 0; i < rounds; i++) {
                        int n = socket.getInputStream().readNBytes(b, 0, b.length);
                        if (n == 0) break;
                        application += n;
                        socket.getOutputStream().write(b, 0, n);
                        socket.getOutputStream().flush();
                    }
                }
            } catch (IOException e) {
                p.setProperty("failure", failure(e));
            }
            listener.setSoTimeout(200);
            try (var unexpected = listener.accept()) {
                accepted++;
            } catch (SocketTimeoutException expected) {
            }
        } finally {
            p.setProperty("accepted", Integer.toString(accepted));
            p.setProperty("updates", Integer.toString(updates));
            p.setProperty("run", run.toString());
            p.setProperty("application", Integer.toString(application));
            store(run.resolve("server.properties"), p);
        }
    }
    static void rawServer(String name, Path run) throws Exception {
        var p = new Properties();
        runtime(p);
        int sent = 0, accepted = 0;
        var captured = new ByteArrayOutputStream();
        try (var listener = new ServerSocket(0, 2, InetAddress.getByAddress(new byte[] {
            127, 0, 0, 1
        }))) {
            listener.setSoTimeout(3000);
            Files.writeString(run.resolve("port"), Integer.toString(listener.getLocalPort()));
            try (var socket = listener.accept()) {
                accepted++;
                if (name.equals("plain")) socket.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                if (name.equals("drip")) {
                    byte[] record = {22, 3, 3, 0, 64};
                    for (int i = 0; i < 10; i++) {
                        socket.getOutputStream().write(i < 5 ? record[i] : 0);
                        socket.getOutputStream().flush();
                        sent++;
                        Thread.sleep(350);
                    }
                } else {
                    long deadline = System.nanoTime() + Duration.ofMillis(3500).toNanos();
                    byte[] buffer = new byte[4096];
                    while (captured.size() < 65536) {
                        socket.setSoTimeout(PortalEventsTlsConnection.timeoutMillis(deadline, 3500));
                        int n = socket.getInputStream().read(buffer, 0, Math.min(buffer.length, 65536 - captured.size()));
                        if (n < 0) break;
                        captured.write(buffer, 0, n);
                    }
                    if (captured.size() == 65536) throw new IOException("raw peer capture limit");
                }
            } catch (IOException e) {
                p.setProperty("failure", failure(e));
            }
            // One bounded window observes queued/retry connects. Every accepted socket is closed immediately.
            long acceptDeadline = System.nanoTime() + Duration.ofMillis(300).toNanos();
            while (accepted < 8 && System.nanoTime() < acceptDeadline) {
                listener.setSoTimeout(PortalEventsTlsConnection.timeoutMillis(acceptDeadline, 300));
                try (var extra = listener.accept()) {
                    accepted++;
                } catch (SocketTimeoutException expected) {
                    break;
                }
            }
            p.setProperty("acceptLimitReached", Boolean.toString(accepted == 8));
        } finally {
            byte[] wire = captured.toByteArray();
            Files.write(run.resolve("client-wire.bin"), wire);
            observePlainBytes(wire, p);
            p.setProperty("accepted", Integer.toString(accepted));
            p.setProperty("sent", Integer.toString(sent));
            store(run.resolve("server.properties"), p);
        }
    }
    static void observePlainBytes(byte[] wire, Properties p) {
        int cursor = 0, application = 0, allowed = 0, opaque = 0, incomplete = 0;
        while (cursor < wire.length) {
            int remaining = wire.length - cursor;
            int type = wire[cursor] & 255;
            if (remaining < 3 || type < 20 || type > 23 || wire[cursor + 1] != 3 || (wire[cursor + 2] & 255) > 4) {
                application += remaining;
                break;
            }
            if (remaining < 5) {
                incomplete += remaining;
                break;
            }
            int length = ((wire[cursor + 3] & 255) << 8) | (wire[cursor + 4] & 255);
            if (length > 18432 || length + 5 > remaining) {
                incomplete += remaining;
                break;
            }
            // TLS ciphertext type 23 is opaque: it is never asserted to be decrypted application content.
            if (type == 23) opaque++;
            else allowed++;
            cursor += length + 5;
        }
        p.setProperty("captured", Integer.toString(wire.length));
        p.setProperty("application", Integer.toString(application));
        p.setProperty("applicationMarker", Boolean.toString(new String(wire, StandardCharsets.ISO_8859_1).contains("F2A-APPLICATION-NEGATIVE-CONTROL")));
        p.setProperty("failureHandshakeRecords", Integer.toString(allowed));
        p.setProperty("opaqueTlsRecords", Integer.toString(opaque));
        p.setProperty("incompleteTlsBytes", Integer.toString(incomplete));
    }
    static final class FixedKey extends X509ExtendedKeyManager {
        final X509ExtendedKeyManager original;
        final String alias;
        final Properties p;
        FixedKey(X509ExtendedKeyManager original, String alias, Properties p) {
            this.original = original;
            this.alias = alias;
            this.p = p;
        }
        public String chooseServerAlias(String type, Principal[] issuers, Socket s) { return type.equals("RSA") ? alias : null; }
        public String chooseEngineServerAlias(String type, Principal[] issuers, SSLEngine e) { return type.equals("RSA") ? alias : null; }
        public X509Certificate[] getCertificateChain(String name) {
            X509Certificate[] chain = original.getCertificateChain(name);
            try {
                int body = 4;
                var lengths = new ArrayList<Integer>();
                for (var cert : chain) {
                    int n = cert.getEncoded().length;
                    lengths.add(n);
                    body += n+5;
                }
                p.setProperty("chain", Integer.toString(chain.length));
                p.setProperty("certificateBody", Integer.toString(body));
                p.setProperty("DER", lengths.toString());
                p.setProperty("requestContext", "0");
                p.setProperty("entryExtensions", "0");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return chain;
        }
        public PrivateKey getPrivateKey(String name) { return original.getPrivateKey(name); }
        public String[] getClientAliases(String t, Principal[] i) { return null; }
        public String chooseClientAlias(String[] t, Principal[] i, Socket s) { return null; }
        public String[] getServerAliases(String t, Principal[] i) { return new String[]{alias}; }
    }
}
