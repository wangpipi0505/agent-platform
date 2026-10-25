package cn.agentplatform.gateway.infra;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.agentplatform.gateway.infra.PortalEventsMaterialAuthorityTest.*;
import static cn.agentplatform.gateway.infra.PortalRunEventsSseLifecycleTest.*;

/** The signed private peer isolates lifecycle ownership; it is not the joined E/PG proof. */
public class PortalRunEventsSseAutomaticCleanupTest {
    @Test void actualCompletionAutomaticallyRefundsAfterTransportHelperReturns() throws Exception {
        Path run=Files.createDirectories(Path.of(System.getProperty("cleanup.fix.evidence")))
                .resolve("automatic-"+UUID.randomUUID());Files.createDirectory(run);
        var command=new ArrayList<String>();command.add(Path.of(System.getProperty("java.home"),"bin/java.exe").toString());
        command.addAll(List.of("-Djdk.net.unixdomain.tmpdir=V:/.local/tmp","-Djdk.tls.maxHandshakeMessageSize=32768",
                "-Djdk.tls.maxCertificateChainLength=4","-Dcom.sun.security.enableAIAcaIssuers=false",
                "-Djdk.tls.server.newSessionTicketCount=0","-Djdk.tls.server.enableStatusRequestExtension=false",
                "-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                PortalRunEventsSseAutomaticCleanupTest.class.getName(),run.toString()));
        Process child=new ProcessBuilder(command).directory(root().toFile()).redirectErrorStream(true).start();
        Files.writeString(run.resolve("launch.properties"),"pid="+child.pid()+"\ncwd="+root()+"\n");
        var collectorFailure=new AtomicReference<Throwable>();
        Thread collector=Thread.ofPlatform().daemon().start(()->{
            try(var in=child.getInputStream();var out=Files.newOutputStream(run.resolve("child-native.log"))) {
                byte[] b=new byte[4096];int total=0,n;
                while((n=in.read(b))!=-1) { if((total+=n)>131072)throw new IOException("bounded child output");out.write(b,0,n); }
            } catch(Throwable failure) { collectorFailure.set(failure);child.destroyForcibly(); }
        });
        try {
            assertTrue(child.waitFor(60,TimeUnit.SECONDS),"owned child deadline");collector.join(3000);
            assertFalse(collector.isAlive());assertNull(collectorFailure.get());
            String diagnostic=new String(Files.readAllBytes(run.resolve("child-native.log")),
                    Charset.forName(System.getProperty("native.encoding")));
            Files.writeString(run.resolve("child-diagnostic-utf8.log"),diagnostic);
            assertEquals(0,child.exitValue(),diagnostic);
        } finally {
            if(child.isAlive())child.destroyForcibly();boolean waited=child.waitFor(3,TimeUnit.SECONDS);collector.join(3000);
            Files.writeString(run.resolve("exit.properties"),"pid="+child.pid()+"\nwaitFor="+waited+"\nexit="+child.exitValue()+"\nalive="+child.isAlive()+"\ncollectorJoinReturned=true\ncollectorAlive="+collector.isAlive()+"\n");
            assertTrue(waited);assertFalse(collector.isAlive());
        }
    }
    public static void main(String[] args)throws Exception { automatic(Path.of(args[0])); }
    static void automatic(Path run)throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var completionEntered=new CountDownLatch(1);
        var helper=new AtomicReference<Thread>();var completion=new AtomicReference<Thread>();
        byte[] payload=("\""+"x".repeat(262142)+"\"").getBytes(StandardCharsets.UTF_8);
        try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"eof",payload);var tls=peer.tls();
            var server=new Server(run,f,tls,(phase,value)->{
                if(phase.equals("completion-entry")) { completion.set(Thread.currentThread());completionEntered.countDown(); }
            });var socket=server.request(f.run)) {
            Thread stopper=null;
            try {
                assertTrue(server.waitTrace("ready-false",5000));var handle=server.handle();var session=server.session.get();
                Object state=field(handle,"state");Thread reader=(Thread)field(state,"worker");
                session.transportCheckpointForTest(phase->{
                    if(phase.equals("cleanup-tail")) {
                        helper.set(Thread.currentThread());entered.countDown();
                        try { if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("bounded actual helper barrier"); }
                        catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new AssertionError(failure); }
                    }
                });
                // close() stops the real original public socket; no awaitClosed/manual finisher is called here.
                stopper=Thread.ofPlatform().name("automatic-original-socket-stop").start(handle::close);
                assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(completionEntered.await(5,TimeUnit.SECONDS));
                // Hold an actual transport helper beyond the competing onComplete finite wait.
                long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(2300);
                while(System.nanoTime()<until)Thread.sleep(10);
                snapshot(run.resolve("held.properties"),state,session,reader,helper.get(),completion.get());
                assertTrue(helper.get().isAlive());assertEquals(1,PortalEventsPacketBudget.SHARED.owners());
                assertTrue(PortalEventsPacketBudget.SHARED.reservedBytes()>3145728L);
                release.countDown();reader.join(6000);stopper.join(6000);
                assertFalse(reader.isAlive(),"actual reader return");assertFalse(stopper.isAlive(),"actual original socket stop return");
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
                while(PortalEventsPacketBudget.SHARED.owners()!=0&&System.nanoTime()<end)Thread.sleep(10);
                snapshot(run.resolve("automatic-before-teardown.properties"),state,session,reader,helper.get(),completion.get());
                Files.write(run.resolve("automatic-trace.txt"),server.trace,StandardCharsets.UTF_8);
                assertEquals(0,PortalEventsPacketBudget.SHARED.owners(),"automatic completion must refund before fixture teardown or manual awaitClosed");
                assertEquals(3145728L,PortalEventsPacketBudget.SHARED.reservedBytes());assertTrue(handle.detachedForTest());
            } finally { release.countDown();if(stopper!=null)stopper.join(6000); }
        } finally {
            Files.writeString(run.resolve("after-teardown.properties"),"owners="+PortalEventsPacketBudget.SHARED.owners()+"\nbytes="+PortalEventsPacketBudget.SHARED.reservedBytes()+"\n");
        }
    }
    static void snapshot(Path file,Object state,PortalRunEventsHttp.Session session,Thread reader,Thread helper,Thread completion)throws Exception {
        var lines=new ArrayList<String>();
        lines.add("owners="+PortalEventsPacketBudget.SHARED.owners());lines.add("bytes="+PortalEventsPacketBudget.SHARED.reservedBytes());
        synchronized(field(state,"cell")) { for(String name:List.of("completed","finished","finalizing","heavy","actions"))lines.add("state."+name+"="+field(state,name)); }
        synchronized(session) {
            for(String name:List.of("active","closing","closed","transportClean","cleanupActive","transportStops"))lines.add("session."+name+"="+field(session,name));
            Object lease=field(session,"sseLease");lines.add("lease.released="+(lease==null?"absent":field(lease,"released")));
        }
        for(var entry:Map.of("reader",reader,"helper",helper,"completion",completion).entrySet()) {
            Thread t=entry.getValue();lines.add(entry.getKey()+"="+t.threadId()+":"+t.getName()+":"+t.getState()+":alive="+t.isAlive());
            for(var frame:t.getStackTrace())lines.add(entry.getKey()+".stack="+frame);
        }
        Files.write(file,lines,StandardCharsets.UTF_8);
    }
}
