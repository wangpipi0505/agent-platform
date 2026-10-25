package cn.agentplatform.gateway.infra;
import cn.agentplatform.common.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.agentplatform.gateway.infra.PortalEventsMaterialAuthorityTest.*;
/** Real issuer/TLS helpers receive this epoch's owned path; old launchers never run. */
public class PortalRunEventsSseTest {
    static Path runs() throws IOException { return Files.createDirectories(root().resolve(".local/iam-o2b-f2c2a-sse/peers")); }
    @Test void rawPayloadPreservesNumbersEscapesAndStringWhitespace() throws Exception { child("raw"); }
    @Test void legacyCanonicalConcurrentWriterWrapper() throws Exception { child("legacy-single"); }
    @Test void legacyCanonicalReentrantWriterWrapper() throws Exception { child("legacy-reentrant"); }
    @Test void legacyCanonicalRetentionWrapper() throws Exception { child("legacy-retention"); }
    static void child(String group) throws Exception {
        Path run = Files.createDirectory(runs().resolve(group + "-" + UUID.randomUUID()));
        var args = List.of("-Djdk.net.unixdomain.tmpdir=V:/.local/tmp", "-Djdk.tls.maxHandshakeMessageSize=32768", "-Djdk.tls.maxCertificateChainLength=4",
        "-Dcom.sun.security.enableAIAcaIssuers=false", "-Djdk.tls.server.newSessionTicketCount=0", "-Djdk.tls.server.enableStatusRequestExtension=false",
        "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), PortalRunEventsSseTest.class.getName(),
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
            assertTrue(p.waitFor(95, TimeUnit.SECONDS), "owned child deadline");
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

    static void payload(ObjectNode m, byte[] raw, String type) {
        try {
            var identity=(ObjectNode)m.get("eventIdentity"); identity.put("type", type);
            String json=identity.toString();
            String withPayload=json.substring(0,json.length()-1)+",\"payload\":"+new String(raw,StandardCharsets.UTF_8)+"}";
            ((ObjectNode)selected(m,"eventScope").get("material")).put("payloadSha256",PortalEventsMaterialVerifier.canonicalSha(utf(withPayload)));
            rehash(m);
        } catch(Exception e) { throw new AssertionError(e); }
    }
    static void raw(Path run) throws Exception {
        byte[] source=utf(" { \"n\" : 1.23456789012345678901234567890e+20, \"z\": -0.00, \"s\" : \" a \\\" b \\\\ \\n 界 \" } ");
        byte[] expected=utf("{\"n\":1.23456789012345678901234567890e+20,\"z\":-0.00,\"s\":\" a \\\" b \\\\ \\n 界 \"}");
        try(var f=new Fixture();var peer=new Peer(f,run,m->payload(m,source," 界".repeat(16)),"normal",source);var tls=peer.tls()) {
            var session=new PortalRunEventsHttp(tls).begin(f.gateway,f.source,f.run,0);
            try {
                session.lookupMetadata(); session.consumeVerified(w->{
                    var out=new ByteArrayOutputStream();
                    try(var g=new com.fasterxml.jackson.core.JsonFactory().createGenerator(out)) { w.writePayload(g); }
                    assertArrayEquals(expected,out.toByteArray(),"raw numeric lexemes and escapes must survive");
                });
            } finally { session.close();assertTrue(session.awaitClosed(Duration.ofSeconds(3)));assertEquals(0,PortalEventsPacketBudget.SHARED.reservedBytes()); }
        }
    }
    public static void main(String[] args) throws Exception {
        Path run=Path.of(args[1]);
        switch(args[0]) {
            case "raw" -> raw(run);
            case "legacy-single" -> singleWriter(run);
            case "legacy-reentrant" -> reentrantWriter(run);
            case "legacy-retention" -> retention(run);
            default -> PortalRunEventsSseLifecycleTest.scenario(args[0],run);
        }
    }
}
