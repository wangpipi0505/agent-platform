package cn.agentplatform.gateway.infra;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.agentplatform.gateway.infra.PortalEventsMaterialAuthorityTest.*;
import static cn.agentplatform.gateway.infra.PortalRunEventsSseLifecycleTest.*;

/** Real signed TLS peer and Tomcat completion; no manual finisher before the refund assertion. */
public class PortalRunEventsSseResetCleanupTest {
    @Test void terminalResetAutomaticallyRefundsWhenReaderAbortOverlapsCompletion() throws Exception {
        Path run=Files.createDirectories(Path.of(System.getProperty("reset.cleanup.evidence")))
                .resolve("reset-"+UUID.randomUUID());Files.createDirectory(run);
        var command=new ArrayList<String>();command.add(Path.of(System.getProperty("java.home"),"bin/java.exe").toString());
        command.addAll(List.of("-Djdk.net.unixdomain.tmpdir=V:/.local/tmp","-Djdk.tls.maxHandshakeMessageSize=32768",
                "-Djdk.tls.maxCertificateChainLength=4","-Dcom.sun.security.enableAIAcaIssuers=false",
                "-Djdk.tls.server.newSessionTicketCount=0","-Djdk.tls.server.enableStatusRequestExtension=false",
                "-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                PortalRunEventsSseResetCleanupTest.class.getName(),run.toString()));
        Files.write(run.resolve("command.args"),command,StandardCharsets.UTF_8);
        Process child=new ProcessBuilder(command).directory(root().toFile()).redirectErrorStream(true).start();
        Files.writeString(run.resolve("launch.properties"),"pid="+child.pid()+"\ncwd="+root()+"\n");
        var failure=new AtomicReference<Throwable>();
        Thread collector=Thread.ofPlatform().daemon().start(()->{
            try(var in=child.getInputStream();var out=Files.newOutputStream(run.resolve("child-native.log"))) {
                byte[] b=new byte[4096];int total=0,n;
                while((n=in.read(b))!=-1) { if((total+=n)>131072)throw new IOException("bounded child output");out.write(b,0,n); }
            } catch(Throwable t) { failure.set(t);child.destroyForcibly(); }
        });
        try {
            assertTrue(child.waitFor(60,TimeUnit.SECONDS),"owned child deadline");collector.join(3000);
            assertFalse(collector.isAlive());assertNull(failure.get());
            // A diagnostic decode cannot replace the actual child assertion with a charset failure.
            String diagnostic=new String(Files.readAllBytes(run.resolve("child-native.log")),StandardCharsets.UTF_8);
            Files.writeString(run.resolve("child-diagnostic-utf8.log"),diagnostic);
            assertEquals(0,child.exitValue(),diagnostic);
        } finally {
            if(child.isAlive())child.destroyForcibly();boolean waited=child.waitFor(3,TimeUnit.SECONDS);collector.join(3000);
            Files.writeString(run.resolve("exit.properties"),"pid="+child.pid()+"\nwaitFor="+waited+"\nexit="+(waited?child.exitValue():"running")+"\nalive="+child.isAlive()+"\ncollectorJoinReturned=true\ncollectorAlive="+collector.isAlive()+"\n");
            assertTrue(waited);assertFalse(collector.isAlive());
        }
    }
    public static void main(String[] args)throws Exception { automatic(Path.of(args[0])); }
    static void automatic(Path run)throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var callback=new AtomicReference<Thread>();var original=new AtomicReference<org.apache.tomcat.util.net.SocketWrapperBase<?>>();
        var serverRef=new AtomicReference<Server>();
        try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{
                ((ObjectNode)m.get("window")).put("status","FAILED");((ObjectNode)m.get("state")).put("status","FAILED");
            },"reset",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,(phase,value)->{
                if(phase.equals("completion-entry")) {
                    callback.set(Thread.currentThread());original.set(serverRef.get().protocol.currentSocketForTest());entered.countDown();
                    try { if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("bounded completion observation"); }
                    catch(InterruptedException e) { Thread.currentThread().interrupt();throw new AssertionError(e); }
                }
            })) {
            serverRef.set(server);
            try(var socket=server.request(f.run)) {
                try {
                    var handle=server.handle();Object state=field(handle,"state");Thread reader=(Thread)field(state,"worker");
                    assertTrue(entered.await(5,TimeUnit.SECONDS),"actual container completion entered");
                    // This finite observer delay only selects a legitimate overlap. It ends BEFORE
                    // onComplete starts its join budget; it does not consume that budget artificially.
                    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                    while(!inAbort(reader)&&System.nanoTime()<end)Thread.sleep(5);
                    snapshot(run.resolve("held.properties"),state,server.session.get(),reader,callback.get(),original.get());
                    assertTrue(inAbort(reader),"reader reached original socket abort while actual completion owns it");
                    release.countDown();
                    byte[] response=socket.getInputStream().readAllBytes();
                    String text=new String(response,StandardCharsets.UTF_8);
                    boolean valid=text.startsWith("HTTP/1.1 200")&&text.contains("event: stream_reset")&&text.contains("CURSOR_EXPIRED")&&text.contains("FAILED");
                    Files.writeString(run.resolve("response.properties"),"bytes="+response.length+"\nvalidTerminalReset="+valid+"\nafter="+handle.after()+"\nclientClosed=false\n");
                    assertTrue(valid,"real public terminal reset response");assertEquals(0,handle.after());
                    reader.join(6000);assertFalse(reader.isAlive(),"actual reader returned");
                    end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
                    while(PortalEventsPacketBudget.SHARED.owners()!=0&&System.nanoTime()<end)Thread.sleep(10);
                    snapshot(run.resolve("automatic-before-teardown.properties"),state,server.session.get(),reader,callback.get(),original.get());
                    Files.write(run.resolve("automatic-trace.txt"),server.trace,StandardCharsets.UTF_8);
                    assertEquals(1,traceCount(server.trace,"completion-entry"));
                    assertEquals(0,PortalEventsPacketBudget.SHARED.owners(),"automatic reset completion must refund before client close, manual awaitClosed or fixture teardown");
                    assertEquals(3145728L,PortalEventsPacketBudget.SHARED.reservedBytes());assertTrue(handle.detachedForTest());
                    assertEquals(0,tlsSlots());
                } finally { release.countDown(); }
            }
        } finally {
            release.countDown();Files.writeString(run.resolve("after-teardown.properties"),"owners="+PortalEventsPacketBudget.SHARED.owners()+"\nbytes="+PortalEventsPacketBudget.SHARED.reservedBytes()+"\n");
        }
    }
    static boolean inAbort(Thread thread) {
        return Arrays.stream(thread.getStackTrace()).anyMatch(f->f.getClassName().endsWith("PortalRunEventsSse$RequestSlot")&&f.getMethodName().equals("abort"));
    }
    static int tlsSlots()throws Exception {
        var lock=PortalEventsTls.class.getDeclaredField("SLOTS_LOCK");lock.setAccessible(true);
        var slots=PortalEventsTls.class.getDeclaredField("SLOTS");slots.setAccessible(true);
        synchronized(lock.get(null)) { return (int)Arrays.stream((Object[])slots.get(null)).filter(Objects::nonNull).count(); }
    }
    static void snapshot(Path file,Object state,PortalRunEventsHttp.Session session,Thread reader,Thread callback,org.apache.tomcat.util.net.SocketWrapperBase<?> socket)throws Exception {
        var lines=new ArrayList<String>();lines.add("owners="+PortalEventsPacketBudget.SHARED.owners());lines.add("bytes="+PortalEventsPacketBudget.SHARED.reservedBytes());
        lines.add("tlsSlots="+tlsSlots());lines.add("socketLock.readerQueued="+socket.getLock().hasQueuedThread(reader));
        synchronized(field(state,"cell")) { for(String n:List.of("stopping","abortIssued","completed","finished","finalizing","heavy","actions"))lines.add("state."+n+"="+field(state,n)); }
        synchronized(session) { for(String n:List.of("active","closing","closed","transportClean","cleanupActive","transportStops"))lines.add("session."+n+"="+field(session,n)); }
        for(var entry:Map.of("reader",reader,"actualCompletionCallback",callback).entrySet()) {
            Thread t=entry.getValue();lines.add(entry.getKey()+"="+t.threadId()+":"+t.getName()+":"+t.getState()+":alive="+t.isAlive());
            for(var frame:t.getStackTrace())lines.add(entry.getKey()+".stack="+frame);
        }
        Files.write(file,lines,StandardCharsets.UTF_8);
    }
}
