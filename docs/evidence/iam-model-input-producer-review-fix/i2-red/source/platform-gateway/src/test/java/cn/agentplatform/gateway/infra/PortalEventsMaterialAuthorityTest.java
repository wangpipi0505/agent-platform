package cn.agentplatform.gateway.infra;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.*;
import org.springframework.web.servlet.HandlerMapping;
import static org.junit.jupiter.api.Assertions.*;
/** Public receiver -> retained initial -> real E receipt; all endpoints are isolated test peers. */
public class PortalEventsMaterialAuthorityTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String HOST = "engine.f2a.test", MIME = "application/vnd.agent-platform.portal-events.v1";
    static final String ASSET_SHA = "7f5a2616d58e869eaea3b58a50a963f8161e0512910421270e358eae39f4f479";
    static final byte[] EMPTY = new byte[0];
    record Asset(byte[] metadata, byte[] payload) {
        ObjectNode model() throws IOException {
            return (ObjectNode) JSON.readTree(metadata);
        }
    }
    static Asset asset() throws Exception {
        byte[] bytes;
        try (var in = PortalEventsMaterialAuthorityTest.class.getResourceAsStream("/iam-o2b-f2c1/e-event-window.fixture.json")) {
            assertNotNull(in);
            bytes = in.readAllBytes();
        }
        assertEquals(262079, bytes.length);
        assertEquals(ASSET_SHA, sha(bytes));
        var x = JSON.readTree(bytes).get("segments");
        byte[] m = Base64.getDecoder().decode(x.get("metadata").get("base64").asText());
        byte[] p = Base64.getDecoder().decode(x.get("payload").get("base64").asText());
        assertEquals(195843, m.length);
        assertEquals(58, p.length);
        assertEquals("f0f762ee8b72248decad59b91c12682399dba5afbfc8eaa6fca392f8aec6b5e1", sha(m));
        assertEquals("522cf3fa9b36823decb1057a2c4cbf7498f16b4da037c9c12c823f86261d1199", sha(p));
        return new Asset(m, p);
    }
    static byte[] utf(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
    static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }
    static Path root() {
        return PortalRunEventsHttpTest.root();
    }
    static Path runs() throws IOException {
        return Files.createDirectories(root().resolve(".local/iam-o2b-f2c1-fixes/peers"));
    }
    @Test void signedReceiptTamperingNeverReachesWindow() throws Exception {
        child("receipt-red");
    }
    @Test void nestedUnknownFieldWithValidCommitNeverReachesWindow() throws Exception {
        child("nested-red");
    }
    @Test void changedBusinessPayloadWithValidWireShaNeverReachesWindow() throws Exception {
        child("business-red");
    }
    @Test void frozenEngineWindowRetainsAllSixtyEightParents() throws Exception {
        child("fixture");
    }
    static void child(String group) throws Exception {
        Path run = Files.createDirectory(runs().resolve(group + "-" + UUID.randomUUID()));
        var args = List.of("-Djdk.net.unixdomain.tmpdir=V:/.local/tmp", "-Djdk.tls.maxHandshakeMessageSize=32768", "-Djdk.tls.maxCertificateChainLength=4",
        "-Dcom.sun.security.enableAIAcaIssuers=false", "-Djdk.tls.server.newSessionTicketCount=0", "-Djdk.tls.server.enableStatusRequestExtension=false",
        "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), PortalEventsMaterialAuthorityTest.class.getName(),
        group, run.toString());
        Files.writeString(run.resolve("client.args"), String.join("\n", args));
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin/java.exe").toString());
        command.addAll(args);
        Process p = new ProcessBuilder(command).directory(root().toFile()).redirectErrorStream(true).start();
        Files.writeString(run.resolve("launch.properties"), "pid=" + p.pid() + "\ncwd=" + root() + "\n");
        var failure = new AtomicReference<Throwable>();
        Thread collector = Thread.ofPlatform().daemon().start(() -> {
            try (var out = Files.newOutputStream(run.resolve("client.out")); var in = p.getInputStream()) {
                byte[] b = new byte[4096];
                int n, total = 0;
                while ((n = in.read(b)) != -1) {
                    if ((total += n) > 131072) throw new IOException("bounded child output");
                    out.write(b, 0, n);
                }
            }
            catch (Throwable t) {
                failure.set(t);
                p.destroyForcibly();
            }
        });
        try {
            assertTrue(p.waitFor(55, TimeUnit.SECONDS), "owned child deadline");
            collector.join(3000);
            assertFalse(collector.isAlive());
            assertNull(failure.get());
            assertEquals(0, p.exitValue(), new String(Files.readAllBytes(run.resolve("client.out")), StandardCharsets.UTF_8));
        }
        finally {
            if (p.isAlive()) p.destroyForcibly();
            assertTrue(p.waitFor(3, TimeUnit.SECONDS));
            collector.join(3000);
            assertFalse(collector.isAlive());
            Files.writeString(run.resolve("exit.properties"), "pid=" + p.pid() + "\nexit=" + p.exitValue() + "\nalive=" + p.isAlive() + "\ncollectorAlive=" + collector.isAlive() + "\n");
        }
    }
    @Test void nestedSchemasRejectAtExactFieldWithCorrectWireDigests() throws Exception {
        child("schema");
    }
    @Test void qFactsScopeUnionRevisionsAndBusinessIdentityAreExact() throws Exception {
        child("relations");
    }
    @Test void freshlyRehashedWeakFloorsAndParentRequirementsAreRejected() throws Exception {
        child("floors");
    }
    @Test void receiptChildAssociationIsCheckedAfterActualSignature() throws Exception {
        child("association");
    }
    @Test void borrowIsInvalidAfterReturnAndNewGeneration() throws Exception {
        child("lifecycle");
    }
    static ObjectNode selected(ObjectNode m, String key) {
        String hash=m.get(key).asText();
        for(JsonNode row:m.get("scopes"))if(row.get("scope").get("scopeSha256").asText().equals(hash))return (ObjectNode)row.get("scope");
        throw new AssertionError("target absent");
    }
    static void rehash(ObjectNode m) {
        try {
            var rows=new ArrayList<JsonNode>();
            m.get("scopes").forEach(rows::add);
            rows.sort(Comparator.comparingLong(x->x.get("revision").asLong()));
            var replacements=new HashMap<String, String>();
            for(JsonNode row:rows) {
                var scope=(ObjectNode)row.get("scope");
                String old=scope.get("scopeSha256").asText();
                var parents=(ArrayNode)scope.get("parentScopeSha256");
                for(int i=0; i<parents.size(); i++) {
                    String hash=parents.get(i).asText();
                    if(replacements.containsKey(hash))parents.set(i, JSON.getNodeFactory().textNode(replacements.get(hash)));
                }
                var typed=JSON.treeToValue(scope, MaterialScopeV1.class);
                String updated=MaterialAuthorization.scopeDigest(typed);
                scope.put("scopeSha256", updated);
                replacements.put(old, updated);
            }
            for(String key:List.of("stateScope", "eventScope"))if(!m.get(key).isNull())m.put(key, replacements.get(m.get(key).asText()));
            var roots=(ObjectNode)m.get("rootScopes");
            for(String key:List.of("run-inputs", "run-snapshot", "run-version-refs"))roots.put(key, replacements.get(roots.get(key).asText()));
        }
        catch(Exception e) {
            throw new AssertionError(e);
        }
    }
    record Negative(String name, Consumer<ObjectNode> mutate, String expected) {
    }
    static void negatives(Path run, List<Negative> cases)throws Exception {
        for(var c:cases) {
            Path vector=Files.createDirectory(run.resolve(c.name()));
            exercise(vector, "normal", c.mutate(), null, true, c.expected());
        }
    }
    static void schema(Path run)throws Exception {
        negatives(run, List.of( new Negative("owner-unknown", m->((ObjectNode)m.get("owner")).put("x", 1), "unknown OWNER.x"),
        new Negative("root-missing", m->((ObjectNode)m.get("provenance").get("executionRoot")).remove("id"), "missing ROOT"),
        new Negative("provenance-type", m->((ObjectNode)m.get("provenance")).put("appVersionId", 1.0), "JSON type"), new Negative("initial-duplicate-role",
        m->((ArrayNode)m.get("initialCeiling").get("roles")).add("EDITOR"), "duplicate/empty array value"), new Negative("attr-unknown",
        m->((ObjectNode)m.get("initialCeiling").get("attributes")).putArray("other"), "attribute key"), new Negative("scope-unknown",
        m->selected(m, "stateScope").put("x", true), "unknown SCOPE.x"), new Negative("material-unknown", m->((ObjectNode)selected(m,
        "stateScope").get("material")).put("x", true), "unknown MATERIAL.x"), new Negative("schema-not-one", m->selected(m,
        "stateScope").put("schemaVersion", 2), "integer range"), new Negative("parent-duplicate", m-> {
            var a=(ArrayNode)selected(m, "stateScope").get("parentScopeSha256");
            a.add(a.get(0));
        }, "duplicate/empty array value"), new Negative("output-object", m->((ObjectNode)m.get("state")).putObject("output_ref"),
        "JSON type"), new Negative("state-missing-null", m->((ObjectNode)m.get("state")).remove("ended_at"), "missing STATE"),
        new Negative("identity-overflow", m->((ObjectNode)m.get("eventIdentity")).put("id", new java.math.BigInteger("9223372036854775808")),
        "long range"), new Negative("window-float", m->((ObjectNode)m.get("window")).put("watermark", 1.0), "JSON type")));
    }
    static void relations(Path run)throws Exception {
        negatives(run, List.of( new Negative("Q-owner", m->((ObjectNode)m.get("owner")).put("principalId", "bob"), "owner original Q"),
        new Negative("Q-accepted", m->m.put("acceptedSnapshotSha", "0".repeat(64)), "Q hashes"), new Negative("Q-initial",
        m->m.put("initialCeilingSha256", "0".repeat(64)), "Q hashes"), new Negative("initial-hash", m->((ArrayNode)m.get("initialCeiling").get("roles")).add("ADMIN"),
        "initial ceiling"), new Negative("Q-version", m->((ObjectNode)m.get("provenance")).put("appVersionNo", 2), "provenance original Q"),
        new Negative("duplicate-revision", m-> {
            var a=m.get("scopes");
            ((ObjectNode)a.get(1)).set("revision", a.get(0).get("revision"));
        }, "duplicate revision"), new Negative("missing-parent", m->((ArrayNode)m.get("scopes")).remove(5), "direct parent"),
        new Negative("state-row", m->((ObjectNode)m.get("state")).put("public_id", "r_"+"b".repeat(32)), "state/window identity"),
        new Negative("event-run", m->((ObjectNode)m.get("eventIdentity")).put("run_id", 139), "event identity"), new Negative("state-SHA",
        m->((ObjectNode)m.get("state")).put("output_ref", "changed"), "state business SHA"), new Negative("event-SHA", m->((ObjectNode)m.get("eventIdentity")).put("created_at",
        "2026-10-21T06:49:20Z"), "event business SHA"), new Negative("scope-SHA", m->((ObjectNode)selected(m, "eventScope").get("material")).put("payloadSha256",
        "0".repeat(64)), "scope digest/owner")));
    }
    static void floors(Path run)throws Exception {
        negatives(run, List.of( new Negative("weak-state-roles", m-> {
            selected(m, "stateScope").putArray("requiredRoles");
            rehash(m);
        }, "target floor"), new Negative("weak-event-attrs", m-> {
            selected(m, "eventScope").putObject("requiredEntitlements");
            rehash(m);
        }, "target floor"), new Negative("weak-state-label", m-> {
            selected(m, "stateScope").putArray("requiredDataLabels");
            rehash(m);
        }, "target floor"), new Negative("parent-requires-more", m-> {
            for(JsonNode r:m.get("scopes")) {
                var scope=(ObjectNode)r.get("scope");
                if(scope.get("material").get("kind").asText().equals("run-budget")) {
                    ((ObjectNode)scope.get("requiredEntitlements")).putArray("region").add("C");
                    break;
                }
            }
            rehash(m);
        }, null), new Negative("root-provenance", m-> {
            for(JsonNode r:m.get("scopes")) {
                var scope=(ObjectNode)r.get("scope");
                if(scope.get("material").get("kind").asText().equals("run-inputs"))scope.put("sourceDecisionId", "foreign");
            }
            rehash(m);
        }, "root provenance/floor"), new Negative("dynamic-version", m-> {
            ((ObjectNode)selected(m, "eventScope").get("material")).put("version", "01");
            rehash(m);
        }, "dynamic revision/parents")));
    }
    static void lifecycle(Path run)throws Exception {
        try(var f=new Fixture(); var peer=new Peer(f, run, m-> {
        }, "normal", null); var tls=peer.tls()) {
            var session=new PortalRunEventsHttp(tls).begin(f.observed, f.source, f.run, 0);
            var retained=new AtomicReference<PortalEventsMaterialVerifier.VerifiedWindow>();
            try {
                session.lookupMetadata();
                session.consumeVerified(w-> {
                    retained.set(w);
                    assertEquals(f.run, w.run());
                    assertEquals("PENDING", w.status());
                    assertEquals(1, w.watermark());
                    assertEquals(1, w.eventSeq());
                    assertFalse(w.drained());
                    assertNull(w.resetReason());
                    assertTrue(PortalEventsPacketBudget.SHARED.reservedBytes()>83886080L);
                    // Same controlled consumer may synchronously wait for another callback thread. No thread-affinity permit.
                    try(var worker=Executors.newSingleThreadExecutor()) {
                        try {
                            assertEquals(f.run, worker.submit(w::run).get(1, TimeUnit.SECONDS));
                        }
                        catch(Exception e) {
                            throw new AssertionError(e);
                        }
                    }
                    f.gateway.checkGatewayPortalEvents(currentGateway.get());
                    assertThrows(PlatformException.class, w::run);
                });
                fail("generation invalidation must terminate this borrow");
            }
            catch(PlatformException expected) {
                assertNotNull(retained.get());
            }
            finally {
                session.close();
                assertTrue(session.awaitClosed(Duration.ofSeconds(3)));
            }
            assertThrows(PlatformException.class, retained.get()::run);
            assertEquals(0, PortalEventsPacketBudget.SHARED.reservedBytes());
        }
    }
    static void semantic(Path run)throws Exception {
        exercise(run, "normal", m-> {
            m.put("hasEvent", false);
            m.putNull("eventScope");
            m.putNull("eventIdentity");
        }, EMPTY, false);
        String type="界".repeat(32);
        exercise(run, "normal", m-> {
            try {
                var id=(ObjectNode)m.get("eventIdentity");
                id.put("type", type);
                String canonical="{\"created_at\":"+JSON.writeValueAsString(id.get("created_at").asText())+",\"id\":"+id.get("id")+",\"payload\":null,\"run_id\":"+id.get("run_id")+",\"seq\":"+id.get("seq")+",\"type\":"+JSON.writeValueAsString(type)+"}";
                ((ObjectNode)selected(m, "eventScope").get("material")).put("payloadSha256", sha(utf(canonical)));
                rehash(m);
            }
            catch(Exception e) {
                throw new AssertionError(e);
            }
        }, utf("null"), false);
    }

    @Test void sameWindowConcurrentWriterIsRejected() throws Exception {
        child("single-writer");
    }
    @Test void sameWindowReentrantWriterIsRejected() throws Exception {
        child("reentrant-writer");
    }
    static void singleWriter(Path run) throws Exception {
        try (var f=new Fixture(); var peer=new Peer(f, run, m->{}, "normal", null);
             var tls=peer.tls(); var workers=Executors.newFixedThreadPool(2)) {
            var session=new PortalRunEventsHttp(tls).begin(f.observed, f.source, f.run, 0);
            var entered=new CountDownLatch(1);
            var release=new CountDownLatch(1);
            try {
                session.lookupMetadata();
                session.consumeVerified(w->{
                    var first=workers.submit(()->{
                        try (var g=new com.fasterxml.jackson.core.util.JsonGeneratorDelegate(
                                new com.fasterxml.jackson.core.JsonFactory().createGenerator(OutputStream.nullOutputStream())) {
                            @Override public void writeString(String value) throws IOException {
                                entered.countDown();
                                try {
                                    if(!release.await(12, TimeUnit.SECONDS))throw new IOException("writer release deadline");
                                }
                                catch(InterruptedException e) { throw new IOException(e); }
                                super.writeString(value);
                            }
                        }) { w.writePayload(g); }
                        return null;
                    });
                    var secondCalls=new AtomicInteger();
                    try {
                        assertTrue(entered.await(3, TimeUnit.SECONDS));
                        long held=PortalEventsPacketBudget.SHARED.reservedBytes();
                        assertTrue(held>83886080L);
                        assertEquals(1, PortalEventsPacketBudget.SHARED.owners());
                        var second=workers.submit(()->{
                            try (var g=new com.fasterxml.jackson.core.util.JsonGeneratorDelegate(
                                    new com.fasterxml.jackson.core.JsonFactory().createGenerator(OutputStream.nullOutputStream())) {
                                @Override public void writeStartObject() throws IOException {
                                    secondCalls.incrementAndGet();
                                    super.writeStartObject();
                                }
                            }) { return assertThrows(PlatformException.class, ()->w.writePayload(g)); }
                        });
                        var denied=second.get(2, TimeUnit.SECONDS);
                        assertEquals("EVENT_MATERIAL_DENIED", denied.code());
                        assertEquals("window writer active", denied.getMessage());
                        assertEquals(0, secondCalls.get());
                        assertFalse(first.isDone());
                        assertEquals(held, PortalEventsPacketBudget.SHARED.reservedBytes());
                        release.countDown();
                        first.get(3, TimeUnit.SECONDS);
                        // An admitted writer's exception must release admission exactly once.
                        try (var g=new com.fasterxml.jackson.core.util.JsonGeneratorDelegate(
                                new com.fasterxml.jackson.core.JsonFactory().createGenerator(OutputStream.nullOutputStream())) {
                            @Override public void writeString(String value) throws IOException { throw new IOException("writer sentinel"); }
                        }) { assertEquals("writer sentinel", assertThrows(IOException.class, ()->w.writePayload(g)).getMessage()); }
                        try (var g=new com.fasterxml.jackson.core.JsonFactory().createGenerator(OutputStream.nullOutputStream())) {
                            w.writePayload(g);
                        }
                        Files.writeString(run.resolve("writer.properties"), "secondGeneratorCalls="+secondCalls.get()
                                +"\nheldBytes="+held+"\nfirstCrossThreadExited=true\nexceptionThenSequentialWrite=true\n");
                    }
                    catch(ExecutionException e) {
                        throw new AssertionError("second writer entered traversal; generatorCalls="+secondCalls.get(), e.getCause());
                    }
                    catch(InterruptedException|TimeoutException e) { throw new IOException(e); }
                    finally {
                        release.countDown();
                        try { first.get(3, TimeUnit.SECONDS); }
                        catch(InterruptedException|ExecutionException|TimeoutException e) { throw new IOException(e); }
                    }
                });
            }
            finally {
                release.countDown();
                session.close();
                assertTrue(session.awaitClosed(Duration.ofSeconds(3)));
                assertEquals(0, PortalEventsPacketBudget.SHARED.reservedBytes());
            }
        }
    }
    static void reentrantWriter(Path run) throws Exception {
        try (var f=new Fixture(); var peer=new Peer(f, run, m->{}, "normal", null); var tls=peer.tls()) {
            var session=new PortalRunEventsHttp(tls).begin(f.observed, f.source, f.run, 0);
            var attempted=new AtomicBoolean();
            var innerCalls=new AtomicInteger();
            try {
                session.lookupMetadata();
                session.consumeVerified(w->{
                    long held=PortalEventsPacketBudget.SHARED.reservedBytes();
                    try (var g=new com.fasterxml.jackson.core.util.JsonGeneratorDelegate(
                            new com.fasterxml.jackson.core.JsonFactory().createGenerator(OutputStream.nullOutputStream())) {
                        @Override public void writeString(String value) throws IOException {
                            if(attempted.compareAndSet(false, true)) {
                                try (var inner=new com.fasterxml.jackson.core.util.JsonGeneratorDelegate(
                                        new com.fasterxml.jackson.core.JsonFactory().createGenerator(OutputStream.nullOutputStream())) {
                                    @Override public void writeStartObject() throws IOException {
                                        innerCalls.incrementAndGet();
                                        super.writeStartObject();
                                    }
                                }) {
                                    var denied=assertThrows(PlatformException.class, ()->w.writePayload(inner));
                                    assertEquals("EVENT_MATERIAL_DENIED", denied.code());
                                    assertEquals("window writer active", denied.getMessage());
                                }
                                assertEquals(0, innerCalls.get());
                                assertEquals(held, PortalEventsPacketBudget.SHARED.reservedBytes());
                            }
                            super.writeString(value);
                        }
                    }) { w.writePayload(g); }
                    assertTrue(attempted.get());
                    try (var g=new com.fasterxml.jackson.core.JsonFactory().createGenerator(OutputStream.nullOutputStream())) {
                        w.writePayload(g);
                    }
                    Files.writeString(run.resolve("writer.properties"), "reentrantAttempted=true\ninnerGeneratorCalls="
                            +innerCalls.get()+"\nheldBytes="+held+"\nouterAndSequentialExited=true\n");
                });
            }
            finally {
                session.close();
                assertTrue(session.awaitClosed(Duration.ofSeconds(3)));
                assertEquals(0, PortalEventsPacketBudget.SHARED.reservedBytes());
            }
        }
    }
    static void retention(Path run)throws Exception {
        try(var f=new Fixture(); var a=new Peer(f, run, m-> {
        }, "normal", null); var b=new Peer(f, run, m-> {
        }, "normal", null); var ta=a.tls(); var tb=b.tls(); var workers=Executors.newFixedThreadPool(4)) {
            var sa=new PortalRunEventsHttp(ta).begin(f.observed, f.source, f.run, 0);
            sa.lookupMetadata();
            var sb=new PortalRunEventsHttp(tb).begin(f.observed, f.source, f.run, 0);
            sb.lookupMetadata();
            var entered=new CountDownLatch(2);
            var release=new CountDownLatch(1);
            var callbacksReturned=new CountDownLatch(2);
            var writing=new ConcurrentLinkedQueue<Future<?>>();
            PortalRunEventsHttp.Session.WindowConsumer consumer=w-> {
                writing.add(workers.submit(()-> {
                    try(var generator=new com.fasterxml.jackson.core.util.JsonGeneratorDelegate(new com.fasterxml.jackson.core.JsonFactory().createGenerator(OutputStream.nullOutputStream())) {
                        @Override public void writeString(String value)throws IOException {
                            entered.countDown();
                            try {
                                if(!release.await(12, TimeUnit.SECONDS))throw new IOException("test release deadline");
                            }
                            catch(InterruptedException e) {
                                throw new IOException(e);
                            }
                            super.writeString(value);
                        }
                    }) {
                        w.writePayload(generator);
                    }
                    catch(IOException|PlatformException expected) {
                    }
                }));
                try {
                    assertTrue(entered.await(3, TimeUnit.SECONDS));
                }
                catch(InterruptedException e) {
                    throw new IOException(e);
                }
                callbacksReturned.countDown();
            };
            var fa=workers.submit(()->sa.consumeVerified(consumer));
            var fb=workers.submit(()->sb.consumeVerified(consumer));
            try {
                assertTrue(callbacksReturned.await(4, TimeUnit.SECONDS));
                sa.close();
                sb.close();
                assertFalse(sa.awaitClosed(Duration.ZERO));
                assertFalse(sb.awaitClosed(Duration.ZERO));
                assertEquals(2, PortalEventsPacketBudget.SHARED.owners());
                long held=PortalEventsPacketBudget.SHARED.reservedBytes();
                assertTrue(held>2*83886080L);
                assertThrows(IllegalStateException.class, ()->new PortalRunEventsHttp(ta).begin(f.observed, f.source, f.run, 0));
                release.countDown();
                for(var future:writing)future.get(3, TimeUnit.SECONDS);
                assertThrows(ExecutionException.class, ()->fa.get(3, TimeUnit.SECONDS));
                assertThrows(ExecutionException.class, ()->fb.get(3, TimeUnit.SECONDS));
                assertTrue(sa.awaitClosed(Duration.ofSeconds(3)));
                assertTrue(sb.awaitClosed(Duration.ofSeconds(3)));
                assertEquals(0, PortalEventsPacketBudget.SHARED.reservedBytes());
                Files.writeString(run.resolve("retention.properties"), "heldBytes="+held+"\nthirdDenied=true\nactualWorkersExited=true\n");
            }
            finally {
                release.countDown();
                sa.close();
                sb.close();
                assertTrue(sa.awaitClosed(Duration.ofSeconds(3)));
                assertTrue(sb.awaitClosed(Duration.ofSeconds(3)));
            }
        }
    }
    static void generations(Path run)throws Exception {
        for(String point:List.of("first", "middle", "digest", "exhaust")) {
            Path vector=Files.createDirectory(run.resolve(point));
            try(var f=new Fixture(); var peer=new Peer(f, vector, m-> {
            }, "normal", null); var tls=peer.tls()) {
                var session=new PortalRunEventsHttp(tls).begin(f.observed, f.source, f.run, 0);
                var starts=new AtomicInteger();
                var visits=new AtomicInteger();
                var changes=new AtomicInteger();
                var callbacks=new AtomicInteger();
                try {
                    session.lookupMetadata();
                    session.materialCheckpointForTest((phase, index)-> {
                        if(phase.equals("scope")) {
                            visits.incrementAndGet();
                            if(index==0)starts.incrementAndGet();
                        }
                        boolean trigger=point.equals("first")?phase.equals("scope")&&index==0:point.equals("middle")?phase.equals("scope")&&index==34:phase.equals("digest");
                        int allowed=point.equals("exhaust")?3:2;
                        if(trigger&&changes.get()<allowed) {
                            var g=currentGateway.get();
                            var old=g.currentCapture();
                            f.gateway.checkGatewayPortalEvents(g);
                            assertTrue(g.isSuperseded(old));
                            changes.incrementAndGet();
                        }
                    });
                    Runnable consume=()->session.consumeVerified(w-> {
                        callbacks.incrementAndGet();
                        assertEquals(f.run, w.run());
                    });
                    if(point.equals("exhaust")) {
                        var e=assertThrows(PlatformException.class, consume::run);
                        assertEquals("SYS-AUTH-403", e.code());
                        assertEquals(0, callbacks.get());
                    }
                    else {
                        consume.run();
                        assertEquals(1, callbacks.get());
                    }
                    assertEquals(3, starts.get());
                    assertEquals(point.equals("exhaust")?3:2, changes.get());
                    assertEquals(point.equals("first")?71:point.equals("middle")?139:207, visits.get());
                    Files.writeString(vector.resolve("generation.properties"), "starts="+starts.get()+"\nverifiedScopeVisits="+visits.get()+"\nactualChanges="+changes.get()+"\ncallbacks="+callbacks.get()+"\n");
                }
                finally {
                    session.close();
                    assertTrue(session.awaitClosed(Duration.ofSeconds(3)));
                    assertEquals(0, PortalEventsPacketBudget.SHARED.reservedBytes());
                }
            }
        }
    }
    static int stringUnits(JsonNode n) {
        int units=0;
        if(n.isObject()) {
            var entries=n.fields();
            while(entries.hasNext()) {
                var e=entries.next();
                units+=e.getKey().length()+stringUnits(e.getValue());
            }
        }
        else if(n.isArray()) {
            for(JsonNode x:n)units+=stringUnits(x);
        }
        else if(n.isTextual())units+=n.asText().length();
        return units;
    }
    static void profiles(Path run)throws Exception {
        exercise(run, "normal", m->((ObjectNode)m.get("provenance")).put("originAuthenticationId", "x".repeat(65536)), null,
        false);
        exercise(run, "normal", m->((ObjectNode)m.get("provenance")).put("originAuthenticationId", "x".repeat(65537)), null,
        true, "CAPACITY:parser constraint");
        for(int extra:new int[] {
            0, 1
        })exercise(run, "normal", m-> {
            ObjectNode target=selected(m, "stateScope");
            JsonNode row=null;
            for(JsonNode r:m.get("scopes"))if(r.get("scope")==target)row=r;
            int base=stringUnits(row)-target.get("sourceDecisionId").asText().length()-target.get("sourceAuthorizationVersion").asText().length();
            target.put("sourceDecisionId", "s".repeat(65536));
            target.put("sourceAuthorizationVersion", "v".repeat(131072-base-65536+extra));
            rehash(m);
        }, null, extra==1, extra==1?"CAPACITY:scope strings":null);
    }
    static final AtomicReference<GatewayPortalEventsResponse> currentGateway=new AtomicReference<>();
    @Test void helloCapacityAndBoundaryEofConveyNoRunFacts() throws Exception {
        child("nonfacts");
    }
    @Test void noEventWindowAndUnicodeNullEventUseIndependentBusinessOracle() throws Exception {
        child("semantic");
    }
    @Test void twoActualBorrowedWritesKeepFeesUntilWorkersExit() throws Exception {
        child("retention");
    }
    @Test void genuineGenerationChangesRevalidateEntireP3AtMostTwice() throws Exception {
        child("generations");
    }
    @Test void reachableMetadataScalarAndScopeStringProfilesUseCapacityFailure() throws Exception {
        child("profiles");
    }
    public void handler() {
    }
    static final class Fixture implements AutoCloseable {
        final LoopbackAuthority iam = new LoopbackAuthority();
        final HttpCurrentAuthorityClient gateway, engine;
        final ObjectNode original;
        final String run, source, accepted;
        final CurrentAuthorityClient observed;
        Fixture() throws Exception {
            original = asset().model();
            run = original.get("state").get("public_id").asText();
            accepted = original.get("acceptedSnapshotSha").asText();
            iam.portalActor(new Actor("tenant-a", "alice", Set.of("EDITOR"), Map.of("region", Set.of("A", "B"), "dataLabels",
            Set.of("initial"))));
            iam.registerObservation(new SubjectKey("tenant-a", "alice"), run, accepted);
            gateway = iam.client("gateway");
            engine = iam.client("engine");
            source = iam.portal(120);
            observed=(CurrentAuthorityClient)java.lang.reflect.Proxy.newProxyInstance(CurrentAuthorityClient.class.getClassLoader(),
            new Class<?>[] {
                CurrentAuthorityClient.class, ResponseAuthorityClient.class
            }, (p, m, a)-> {
                try {
                    Object value=m.invoke(gateway, a);
                    if(m.getName().equals("openGatewayPortalEvents"))currentGateway.set((GatewayPortalEventsResponse)value);
                    return value;
                }
                catch(java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        }
        byte[] q() throws Exception {
            var data = JSON.createObjectNode();
            data.put("runId", run);
            data.set("owner", original.get("owner"));
            data.set("root", original.get("provenance").get("executionRoot"));
            data.set("appVersionId", original.get("provenance").get("appVersionId"));
            data.set("appVersionNo", original.get("provenance").get("appVersionNo"));
            data.put("acceptedSnapshotSha256", accepted);
            data.set("initialCeilingSha256", original.get("initialCeilingSha256"));
            var envelope = JSON.createObjectNode();
            envelope.put("code", "OK");
            envelope.put("message", "");
            envelope.set("data", data);
            envelope.putNull("traceId");
            return response(JSON.writeValueAsBytes(envelope), "application/json");
        }
        public void close() {
            try {
                engine.close();
            }
            finally {
                try {
                    gateway.close();
                }
                finally {
                    iam.close();
                }
            }
        }
    }
    static byte[] response(byte[] body, String type) {
        return PortalEventsPacketDecoderTest.join(utf("HTTP/1.1 200 Finite\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length + "\r\n\r\n"),
        body);
    }
    static String head(SSLSocket s) throws IOException {
        var out = new ByteArrayOutputStream();
        int tail = 0, b;
        while ((b = s.getInputStream().read()) != -1) {
            out.write(b);
            tail = tail << 8 | b;
            if (tail == 0x0d0a0d0a) return out.toString(StandardCharsets.US_ASCII);
            if (out.size() > 32768) throw new IOException("head limit");
        }
        throw new EOFException();
    }
    static final class Peer implements AutoCloseable {
        final Fixture f;
        final SSLServerSocket listener;
        final Thread worker;
        final Path run;
        volatile SSLSocket socket;
        volatile Throwable failure;
        int connections;
        String receipt;
        PortalEventsResponse engineResponse;
        Peer(Fixture f, Path run, Consumer<ObjectNode> mutation, String mode, byte[] payload) throws Exception {
            this.f = f;
            this.run = run;
            var c = SSLContext.getInstance("TLSv1.3", "SunJSSE");
            var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            km.init(PortalRunEventsHttpTest.fixtures(), "f2a-test-only".toCharArray());
            var key = Arrays.stream(km.getKeyManagers()).filter(X509ExtendedKeyManager.class::isInstance).map(X509ExtendedKeyManager.class::cast).findFirst().orElseThrow();
            c.init(new KeyManager[] {
                new PortalEventsTlsTest.FixedKey(key, "normal", new Properties())
            }, null, null);
            listener = (SSLServerSocket)c.getServerSocketFactory().createServerSocket(0, 2, InetAddress.getByAddress(new byte[] {
                127, 0, 0, 1
            }));
            listener.setEnabledProtocols(new String[] {
                "TLSv1.3"
            });
            listener.setEnabledCipherSuites(new String[] {
                PortalEventsTls.CIPHER
            });
            listener.setSoTimeout(5000);
            worker = Thread.ofPlatform().daemon().start(() -> {
                try {
                    try (var q = (SSLSocket)listener.accept()) {
                        socket = q;
                        connections++;
                        q.setSoTimeout(5000);
                        q.startHandshake();
                        head(q);
                        q.getOutputStream().write(f.q());
                        q.getOutputStream().flush();
                    }
                    try (var e = (SSLSocket)listener.accept()) {
                        socket = e;
                        connections++;
                        e.setSoTimeout(10000);
                        e.startHandshake();
                        String h = head(e);
                        String bearer = h.substring(h.indexOf("Authorization: Bearer ") + 22).split("\r\n", 2)[0];
                        // Real mapped receiver frame. Public bind(currentOnly) cannot supply this initial provenance.
                        var previousSecurity = SecurityContextHolder.getContext();
                        var previousRequest = RequestContextHolder.getRequestAttributes();
                        var request = new MockHttpServletRequest("GET", "/admin/runs/" + f.run + "/events");
                        var response = new MockHttpServletResponse();
                        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/admin/runs/{id}/events");
                        var handler = new org.springframework.web.method.HandlerMethod(new PortalEventsMaterialAuthorityTest(), PortalEventsMaterialAuthorityTest.class.getMethod("handler"));
                        var gate = new ServiceAuthorization("engine", new ServiceRoutePolicy(), f.engine);
                        try (var decoder = new TrustedJwtDecoder(f.iam.config("engine"), f.iam.clock(), true)) {
                            var context = SecurityContextHolder.createEmptyContext();
                            context.setAuthentication(new JwtAuthenticationToken(decoder.decode(bearer), List.of()));
                            SecurityContextHolder.setContext(context);
                            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
                            assertTrue(gate.preHandle(request, response, handler));
                            var binding = RunObservationSelectors.events(f.run, f.accepted);
                            var retained = AuthorityContext.requireInitialFor(AuthorityContext.current().actor(), binding);
                            engineResponse = f.engine.openPortalEventsChild(retained, 0, () -> {
                            });
                            receipt = f.engine.portalEventsChildReceipt(engineResponse);
                            var capture = engineResponse.currentCapture();
                            String child = capture.responseId();
                            var model = f.original.deepCopy();
                            mutation.accept(model);
                            byte[] m = JSON.writeValueAsBytes(model), p = payload == null ? asset().payload() : payload;
                            String signed = mode.equals("bad-receipt") ? receipt.substring(0, receipt.lastIndexOf('.')+1) + (receipt.charAt(receipt.lastIndexOf('.')+1)=='a'?"b":"a") + receipt.substring(receipt.lastIndexOf('.')+2) : receipt;
                            byte[] hello = JSON.writeValueAsBytes(Map.of("receipt", signed));
                            String helloChild = mode.equals("wrong-child") ? "foreign-child" : child;
                            byte[] first=packet(1, hello, EMPTY, helloChild, model);
                            byte[] wire=mode.equals("hello-only")?first:mode.equals("capacity")?PortalEventsPacketDecoderTest.join(first, packet(3,
                            utf("{\"code\":\"BUFFER_LIMIT\"}"), EMPTY, child, model)):PortalEventsPacketDecoderTest.join(first, packet(2, m,
                            p, child, model));
                            e.getOutputStream().write(response(wire, MIME));
                            e.getOutputStream().flush();
                            try {
                                e.getInputStream().read();
                            }
                            catch (SocketException expected) {
                            }
                        }
                        finally {
                            gate.afterCompletion(request, response, handler, null);
                            RequestContextHolder.setRequestAttributes(previousRequest);
                            SecurityContextHolder.setContext(previousSecurity);
                        }
                    }
                }
                catch (SocketException expected) {
                    if (engineResponse == null) failure = expected;
                }
                catch (Throwable t) {
                    failure = t;
                }
            });
        }
        byte[] packet(int kind, byte[] m, byte[] p, String child, JsonNode model) throws Exception {
            var h = JSON.createObjectNode();
            h.put("childResponseId", child);
            h.put("runId", f.run);
            h.put("resourceSha256", f.accepted);
            h.put("initialCursor", 0);
            h.put("afterSeq", 0);
            h.put("payloadBytes", p.length);
            h.put("metadataSha256", sha(m));
            h.put("payloadSha256", sha(p));
            if (kind == 2 && model.get("hasEvent").asBoolean()) {
                h.set("eventSeq", model.get("eventIdentity").get("seq"));
                h.set("eventType", model.get("eventIdentity").get("type"));
            }
            else {
                h.putNull("eventSeq");
                h.putNull("eventType");
            }
            return PortalEventsPacketDecoderTest.packet(kind, kind == 1 ? 0 : 1, m, p, JSON.writeValueAsString(h));
        }
        PortalEventsTls tls() throws Exception {
            return new PortalEventsTls(URI.create("https://"+HOST+":"+listener.getLocalPort()), new byte[] {
                127, 0, 0, 1
            }, PortalRunEventsHttpTest.ca(run, "root"));
        }
        public void close() throws Exception {
            listener.close();
            if (socket != null) socket.close();
            worker.join(5000);
            assertFalse(worker.isAlive());
            if (engineResponse != null) engineResponse.close();
            if (failure != null) throw new AssertionError("receiver peer", failure);
        }
    }
    static void exercise(Path run, String mode, Consumer<ObjectNode> mutation, byte[] payload, boolean reject) throws Exception {
        exercise(run, mode, mutation, payload, reject, null);
    }
    static void exercise(Path run, String mode, Consumer<ObjectNode> mutation, byte[] payload, boolean reject, String detail) throws Exception {
        try (var f = new Fixture(); var peer = new Peer(f, run, mutation, mode, payload); var tls = peer.tls()) {
            var session = new PortalRunEventsHttp(tls).begin(f.observed, f.source, f.run, 0);
            var calls = new AtomicInteger();
            var borrowed=new AtomicReference<PortalEventsMaterialVerifier.VerifiedWindow>();
            try {
                session.lookupMetadata();
                Runnable consume = () -> session.consumeVerified(window -> {
                    borrowed.set(window);
                    calls.incrementAndGet();
                    if (!reject) assertEquals(f.run, window.run());
                });
                if (reject) {
                    var failure=assertThrows(PlatformException.class, consume::run);
                    if(detail!=null) {
                        if(detail.startsWith("CAPACITY:")) {
                            assertEquals("EVENT_MATERIAL_CAPACITY", failure.code());
                            assertEquals(detail.substring(9), failure.getMessage());
                        }
                        else {
                            assertEquals("EVENT_MATERIAL_DENIED", failure.code());
                            assertEquals(detail, failure.getMessage());
                        }
                    }
                    Files.writeString(run.resolve("rejection.properties"), "code="+failure.code()+"\nreason="+failure.getMessage()+"\ncallbacks="+calls.get()+"\n");
                    assertEquals(0, calls.get());
                }
                else {
                    consume.run();
                    assertEquals(mode.equals("hello-only")||mode.equals("capacity")?0:1, calls.get());
                    if(borrowed.get()!=null)assertThrows(PlatformException.class, borrowed.get()::run);
                }
                assertEquals(2, peer.connections);
                assertEquals(1, Collections.frequency(f.iam.observedOperations(), "portal_events_child_exchange"));
            }
            finally {
                session.close();
                assertTrue(session.awaitClosed(Duration.ofSeconds(3)));
                assertEquals(0, PortalEventsPacketBudget.SHARED.reservedBytes());
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Path run = Path.of(args[1]);
        switch (args[0]) {
            case "receipt-red" -> exercise(run, "bad-receipt", m -> {
            }, null, true);
            case "nested-red" -> exercise(run, "normal", m -> ((ObjectNode)m.get("owner")).put("unknown", 1), null, true);
            case "business-red" -> exercise(run, "normal", m -> {
            }, utf("{\"text\":\"changed\"}"), true);
            case "nonfacts" -> {
                exercise(run, "hello-only", m-> {
                }, null, false);
                exercise(run, "capacity", m-> {
                }, null, false);
            }
            case "generations" -> generations(run);
            case "profiles" -> profiles(run);
            case "semantic" -> semantic(run);
            case "retention" -> retention(run);
            case "single-writer" -> singleWriter(run);
            case "reentrant-writer" -> reentrantWriter(run);
            case "schema" -> schema(run);
            case "relations" -> relations(run);
            case "floors" -> floors(run);
            case "association" -> exercise(run, "wrong-child", m-> {
            }, null, true, "receipt association");
            case "lifecycle" -> lifecycle(run);
            case "fixture" -> exercise(run, "normal", m -> {
                assertEquals(69, m.get("scopes").size());
                assertTrue(PortalEventsPacketDecoderTest.parentCount(m) >= 65);
            }, null, false);
            default -> throw new IllegalArgumentException(args[0]);
        }
        Files.writeString(run.resolve("summary.properties"), "group=" + args[0] + "\ncompleted=true\n");
        System.out.println("C1_GROUP_OK=" + args[0]);
    }
}
