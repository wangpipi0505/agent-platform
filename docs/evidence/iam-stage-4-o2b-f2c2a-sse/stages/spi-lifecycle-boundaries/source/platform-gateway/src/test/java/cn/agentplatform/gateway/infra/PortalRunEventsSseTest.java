package cn.agentplatform.gateway.infra;
import cn.agentplatform.common.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.databind.*;
import java.math.*;
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
    @Test void rawAndCanonicalShareWriterAndRawFailureReleasesAdmission() throws Exception { child("raw-writer"); }
    @Test void utf8TypesNullMaxPayloadAndReachablePlusOne() throws Exception { child("bounds"); }
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
            var oracle=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(com.fasterxml.jackson.core.json.JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8).build()).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
            var business=oracle.convertValue(identity,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
            business.put("payload",oracle.readValue(raw,Object.class));
            String hash=sha(oracle.writeValueAsBytes(normalize(business)));
            ((ObjectNode)selected(m,"eventScope").get("material")).put("payloadSha256",hash);
            rehash(m);
        } catch(Exception e) { throw new AssertionError(e); }
    }
    static Object normalize(Object v) {
        if(v instanceof Map<?,?> m) { var sorted=new TreeMap<String,Object>();m.forEach((k,x)->sorted.put((String)k,normalize(x)));return sorted; }
        if(v instanceof List<?> a)return a.stream().map(PortalRunEventsSseTest::normalize).toList();
        if(v instanceof BigDecimal n) { n=n.stripTrailingZeros();return n.signum()==0?BigInteger.ZERO:n.scale()<=0?n.toBigIntegerExact():n; }
        return v;
    }
    static void raw(Path run) throws Exception {
        byte[] source=utf(" { \"n\" : 1.23456789012345678901234567890e+20, \"z\": -0.00, \"s\" : \" a \\\" b \\\\ \\n 界 \" } ");
        byte[] expected=utf("{\"n\":1.23456789012345678901234567890e+20,\"z\":-0.00,\"s\":\" a \\\" b \\\\ \\n 界 \"}");
        try(var f=new Fixture();var peer=new Peer(f,run,m->payload(m,source," "+"界".repeat(31)),"normal",source);var tls=peer.tls()) {
            var session=new PortalRunEventsHttp(tls).begin(f.gateway,f.source,f.run,0);
            try {
                session.lookupMetadata(); session.consumeVerified(w->{
                    var out=new ByteArrayOutputStream();
                    w.writeRawPayload(out);
                    assertArrayEquals(expected,out.toByteArray(),"raw numeric lexemes and escapes must survive");
                });
            } finally { session.close();assertTrue(session.awaitClosed(Duration.ofSeconds(3)));assertEquals(0,PortalEventsPacketBudget.SHARED.reservedBytes()); }
        }
    }
    static void rawWriter(Path run)throws Exception {
        try(var f=new Fixture();var peer=new Peer(f,run,m->{},"normal",null);var tls=peer.tls()) {
            var session=new PortalRunEventsHttp(tls).begin(f.gateway,f.source,f.run,0);
            try {
                session.lookupMetadata();session.consumeVerified(w->{
                    var calls=new AtomicInteger();
                    w.writeRawPayload(new OutputStream() {
                        @Override public void write(int b)throws IOException {
                            if(calls.getAndIncrement()!=0)return;
                            var secondSink=new AtomicInteger();
                            var denied=assertThrows(PlatformException.class,()->w.writeRawPayload(new OutputStream() { public void write(int n) { secondSink.incrementAndGet(); } }));
                            assertEquals("window writer active",denied.getMessage());assertEquals(0,secondSink.get());
                            try(var g=new com.fasterxml.jackson.core.JsonFactory().createGenerator(OutputStream.nullOutputStream())) { assertThrows(PlatformException.class,()->w.writePayload(g)); }
                        }
                    });
                    try(var g=new com.fasterxml.jackson.core.util.JsonGeneratorDelegate(new com.fasterxml.jackson.core.JsonFactory().createGenerator(OutputStream.nullOutputStream())) {
                        @Override public void writeString(String value)throws IOException { assertThrows(PlatformException.class,()->w.writeRawPayload(OutputStream.nullOutputStream()));super.writeString(value); }
                    }) { w.writePayload(g); }
                    assertThrows(IOException.class,()->w.writeRawPayload(new OutputStream() { public void write(int b)throws IOException { throw new IOException("raw sentinel"); } }));
                    w.writeRawPayload(OutputStream.nullOutputStream());
                    Files.writeString(run.resolve("raw-writer.properties"),"bothDirectionsRejectedBeforeSink=true\nrawExceptionThenSequential=true\n");
                });
            } finally { session.close();assertTrue(session.awaitClosed(Duration.ofSeconds(3)));assertEquals(0,PortalEventsPacketBudget.SHARED.reservedBytes()); }
        }
    }
    static void bounds(Path run)throws Exception {
        for(String type:List.of(" "," "+"界".repeat(31),"𠀀".repeat(32),"x\rboom","x\nboom","x\0boom","", "𠀀".repeat(33))) {
            Path vector=Files.createDirectory(run.resolve("type-"+UUID.randomUUID()));byte[] raw=utf("null");
            try(var f=new Fixture();var peer=new Peer(f,vector,m->payload(m,raw,type),"normal",raw);var tls=peer.tls()) {
                var session=new PortalRunEventsHttp(tls).begin(f.gateway,f.source,f.run,0);
                try { session.lookupMetadata();
                    if(type.isEmpty()) { var calls=new AtomicBoolean();assertThrows(PlatformException.class,()->session.consumeVerified(w->calls.set(true)));assertFalse(calls.get());continue; }
                    session.consumeVerified(w->{
                    var frame=new PortalRunEventsSse.Frame();
                    boolean bad=type.isEmpty()||type.codePointCount(0,type.length())>32||type.indexOf('\r')>=0||type.indexOf('\n')>=0||type.indexOf('\0')>=0;
                    if(bad)assertThrows(PlatformException.class,()->frame.event(w));
                    else { frame.event(w);assertEquals("id: 1\nevent: "+type+"\ndata: null\n\n",new String(frame.bytes,0,frame.count,StandardCharsets.UTF_8)); }
                }); } finally { session.close();assertTrue(session.awaitClosed(Duration.ofSeconds(3))); }
            }
        }
        byte[] max=utf("\""+"x".repeat(262142)+"\"");
        Path vector=Files.createDirectory(run.resolve("payload-max"));
        try(var f=new Fixture();var peer=new Peer(f,vector,m->{
            ((ObjectNode)m.get("eventIdentity")).put("seq",Long.MAX_VALUE);((ObjectNode)m.get("state")).put("event_seq",Long.MAX_VALUE);((ObjectNode)m.get("window")).put("watermark",Long.MAX_VALUE);
            try { ((ObjectNode)selected(m,"stateScope").get("material")).put("payloadSha256",PortalEventsMaterialVerifier.canonicalSha(JSON.writeValueAsBytes(m.get("state")))); }catch(IOException e) { throw new UncheckedIOException(e); }
            payload(m,max,"𠀀".repeat(32));
        },"normal",max);var tls=peer.tls()) {
            var session=new PortalRunEventsHttp(tls).begin(f.gateway,f.source,f.run,0);
            try { session.lookupMetadata();session.consumeVerified(w->{ var frame=new PortalRunEventsSse.Frame();frame.event(w);assertEquals(270374,frame.bytes.length);assertEquals(262312,frame.count); }); }
            finally { session.close();assertTrue(session.awaitClosed(Duration.ofSeconds(3))); }
        }
        byte[] plus=utf("\""+"x".repeat(262143)+"\"");
        Path rejected=Files.createDirectory(run.resolve("payload-plus-one"));
        try(var f=new Fixture();var peer=new Peer(f,rejected,m->{},"normal",plus);var tls=peer.tls()) {
            var session=new PortalRunEventsHttp(tls).begin(f.gateway,f.source,f.run,0);
            try { session.lookupMetadata();var called=new AtomicBoolean();assertThrows(PlatformException.class,()->session.consumeVerified(w->called.set(true)));assertFalse(called.get()); }
            finally { session.close();assertTrue(session.awaitClosed(Duration.ofSeconds(3))); }
        }
        assertEquals(0,PortalEventsPacketBudget.SHARED.reservedBytes());
    }
    public static void main(String[] args) throws Exception {
        Path run=Path.of(args[1]);
        switch(args[0]) {
            case "raw" -> raw(run);
            case "raw-writer" -> rawWriter(run);
            case "bounds" -> bounds(run);
            case "legacy-single" -> singleWriter(run);
            case "legacy-reentrant" -> reentrantWriter(run);
            case "legacy-retention" -> retention(run);
            default -> PortalRunEventsSseLifecycleTest.scenario(args[0],run);
        }
    }
}
