package cn.agentplatform.gateway.infra;

import cn.agentplatform.common.authority.*;
import cn.agentplatform.gateway.application.RunObservationEventsMetadataTest.Fixture;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static cn.agentplatform.gateway.infra.PortalRunEventsPrivateHttpTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * EX-46 ①：私有相位不能因为 checked 窗口（assertionSeconds≈5s）到期就结束。
 *
 * <p>客户端契约把续期面收在 {@code checkGatewayPortalEvents} 上（只刷新 checked_until，硬窗
 * hard_deadline 不变，child 票不可换）。这里验证 gateway 在剩余 ≤10s 时主动驱动续期，而不是
 * 等客户端 watchdog 的 3s 定时器，也不是静默降级回公共流。</p>
 *
 * <p>TLS 需要启动期 JVM 声明（{@code jdk.tls.maxHandshakeMessageSize} 等），surefire 进程里
 * 没有这些声明，所以按本模块既有做法在自养子 JVM 里跑真实连接。</p>
 */
public class PortalRunEventsPrivateRenewalTest {

    @Test
    void thePrivateStreamDrivesItsOwnGatewayWindowRenewal() throws Exception {
        spawn("renewal");
    }

    public static void main(String[] args) throws Exception {
        scenario(Path.of(args[1]));
        System.out.println("F2B2_GROUP_OK=" + args[0]);
    }

    static void scenario(Path run) throws Exception {
        try (var f = new Fixture(); var peer = new Peer(response(200, MIME, packets()), "normal");
             var tls = tls(peer, run)) {
            var renewals = new AtomicInteger();
            var authority = adapter(f, (proxy, method, args) -> {
                if (method.getName().equals("checkGatewayPortalEvents")) renewals.incrementAndGet();
                return invoke(f, method, args);
            }, true);
            var session = new PortalRunEventsHttp(tls).begin(authority, f.source, RUN, 7);
            session.lookupMetadata();
            var delivered = new AtomicInteger();
            var worker = Executors.newSingleThreadExecutor();
            var future = worker.submit(() -> session.consumePrivate(packet -> delivered.incrementAndGet()));
            try {
                // checked 窗口只有 5s：body 一进来剩余就已经 ≤10s，必须立刻续期一次。
                // 等“权威侧真的收到 check”而不是代理自增计数：代理是先自增再转发，
                // 只等计数会在转发完成前返回（单独跑绿、与其它类同跑时偶发 0）。
                until(() -> Collections.frequency(f.iam.observedOperations(), "portal_gateway_events_check") > 0, 5000);
                assertTrue(renewals.get() > 0, "gateway 必须在 checked 窗口耗尽前驱动 portal_gateway_events_check");
                assertEquals(1, Collections.frequency(f.iam.observedOperations(), "portal_gateway_events_check"),
                        "续期必须真的走到权威的 G 窗口检查分支");
                Files.writeString(run.resolve("summary.properties"),
                        "gatewayRenewals=" + renewals.get() + "\ndelivered=" + delivered.get() + "\n");
            } finally {
                peer.release.countDown();
                try { future.get(5, TimeUnit.SECONDS); } catch (Exception ignored) { }
                closed(session);
                worker.shutdownNow();
            }
        }
    }

    static void spawn(String group) throws Exception {
        Path run = Files.createDirectories(root().resolve(".local/iam-o2b-f2b2-private-renewal/" + group + "-" + UUID.randomUUID()));
        var words = new ArrayList<>(List.of("-Djdk.net.unixdomain.tmpdir=V:/.local/tmp",
                "-Djdk.tls.maxHandshakeMessageSize=32768", "-Djdk.tls.maxCertificateChainLength=4",
                "-Dcom.sun.security.enableAIAcaIssuers=false", "-Djdk.tls.server.newSessionTicketCount=0",
                "-Djdk.tls.server.enableStatusRequestExtension=false",
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                PortalRunEventsPrivateRenewalTest.class.getName(), group, run.toString()));
        Files.writeString(run.resolve("client.args"),
                String.join("\n", words.stream().map(s -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList()));
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
                    while ((n = in.read(b)) != -1) {
                        total += n;
                        if (total > 131072) throw new java.io.IOException("bounded child output");
                        out.write(b, 0, n);
                    }
                } catch (Throwable t) { failure.set(t); p.destroyForcibly(); }
            });
            assertTrue(p.waitFor(60, TimeUnit.SECONDS), "owned child deadline");
            collector.join(3000);
            assertFalse(collector.isAlive());
            assertNull(failure.get());
            assertEquals(0, p.exitValue(), new String(Files.readAllBytes(run.resolve("client.out")), StandardCharsets.UTF_8));
        } finally {
            if (p.isAlive()) p.destroyForcibly();
            assertTrue(p.waitFor(3, TimeUnit.SECONDS));
            if (collector != null) { collector.join(3000); assertFalse(collector.isAlive()); }
            Files.writeString(run.resolve("exit.properties"),
                    "pid=" + p.pid() + "\nexit=" + p.exitValue() + "\nalive=" + p.isAlive() + "\n");
        }
    }

    @FunctionalInterface interface CheckedCondition { boolean get() throws Exception; }

    static void until(CheckedCondition condition, long millis) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.get()) {
            if (System.nanoTime() - end >= 0) throw new AssertionError("bounded condition deadline");
            Thread.sleep(5);
        }
    }

    static CurrentAuthorityClient adapter(Fixture f, InvocationHandler handler, boolean both) {
        return (CurrentAuthorityClient) Proxy.newProxyInstance(CurrentAuthorityClient.class.getClassLoader(),
                both ? new Class<?>[]{CurrentAuthorityClient.class, ResponseAuthorityClient.class}
                        : new Class<?>[]{CurrentAuthorityClient.class}, handler);
    }

    static Object invoke(Fixture f, Method method, Object[] args) throws Throwable {
        try { return method.invoke(f.gateway, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
}
