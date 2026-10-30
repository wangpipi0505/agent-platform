package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.infra.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 真签名 IAM HTTP + 有限业务 HTTP；同包仅复用现有 package-private retained-frame 测试缝。 */
class ReadOnlyToolCacheTest {
    private static final ResourceBinding RESOURCE = new ResourceBinding("run", "r-ex21a", Digest.sha256("ex21a-frozen"));

    // 首轮不直接引用新类：旧生产仍编译，缺缓存表现为真实多发一次 HTTP。
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = "cn.agentplatform.skill", useDefaultFilters = false, includeFilters = {
            @ComponentScan.Filter(type = FilterType.REGEX, pattern = "cn\\.agentplatform\\.skill\\.application\\.ReadOnlyToolCache"),
            @ComponentScan.Filter(type = FilterType.REGEX, pattern = "cn\\.agentplatform\\.skill\\.infra\\.IdentityMappingClient"),
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = SkillClients.class)})
    static class Wiring {
        @Bean SkillAdapters adapters() { return new SkillAdapters(mock(ArtifactStore.class), mock(AlgorithmAdapter.class)); }
    }

    @Test void hitStillRefreshesSignedCurrentAuthorityAndDeclaredMappingAndNeverSharesMutableOutput() throws Exception {
        try (var iam = new LoopbackAuthority(); var session = new Session(iam); var peer = new Peer(session.app)) {
            runner(session, peer, true).run(context -> {
                var adapter = context.getBean(SkillAdapters.class);
                var contract = contract(peer);
                contract.put("identity", Map.of("mode", "PLATFORM_SUBJECT", "headers", Map.of("X-User-Id", "principalId")));
                int before = iam.requestAttempts();
                var firstObservation = session.observe(adapter, contract, Map.of("x", 1, "y", 2));
                var first = firstObservation.raw();
                assertThat(firstObservation.source()).doesNotContainKey("readOnlyCacheHit");
                int afterFirst = iam.requestAttempts();
                assertThat(peer.sends).hasValue(1);
                ((ObjectNode) first.structured()).put("value", "caller-mutated");
                var reordered = new LinkedHashMap<String, Object>(); reordered.put("y", 2); reordered.put("x", 1);
                var secondObservation = session.observe(adapter, contract, reordered);
                var second = secondObservation.raw();
                assertThat(secondObservation.source()).containsEntry("readOnlyCacheHit", true);
                assertThat(peer.sends).hasValue(1);
                assertThat(second.structured().path("value").asText()).isEqualTo("fresh");
                assertThat(second.fileRefs()).isEmpty();
                assertThat(peer.mappingReads.get()).isGreaterThanOrEqualTo(2);
                // 每次都有真实 authorize；命中还必须重新走映射 HTTP，绝非读取旧 JWT roles。
                assertThat(afterFirst).isGreaterThan(before);
                assertThat(iam.requestAttempts()).isGreaterThan(afterFirst);
                assertThat(peer.lastSubject.get()).isEqualTo("alice");
                session.invoke(adapter, contract, Map.of("x", 9));
                assertThat(peer.sends).hasValue(2);
                var nextVersion = new LinkedHashMap<>(contract); nextVersion.put("skillVersionId", 2L);
                session.invoke(adapter, nextVersion, reordered);
                assertThat(peer.sends).hasValue(3);
                var nextDigest = new LinkedHashMap<>(contract); nextDigest.put("sha256", Digest.sha256("ex21a-contract-2"));
                session.invoke(adapter, nextDigest, reordered);
                assertThat(peer.sends).hasValue(4);
            });
        }
    }

    @Test void currentRevocationScopeAndMappingFailureRejectBeforeCachedDataOrBusinessDispatch() throws Exception {
        try (var iam = new LoopbackAuthority(); var session = new Session(iam); var peer = new Peer(session.app)) {
            runner(session, peer, true).run(context -> {
                var adapter = context.getBean(SkillAdapters.class); var contract = contract(peer);
                contract.put("identity", Map.of("mode", "PLATFORM_SUBJECT", "headers", Map.of("X-User-Id", "principalId")));
                session.invoke(adapter, contract, Map.of()); session.invoke(adapter, contract, Map.of());
                assertThat(peer.sends).hasValue(1);
                assertThatThrownBy(() -> session.invoke(adapter, contract, Map.of(), () -> iam.revoked(true)))
                        .isInstanceOfSatisfying(SkillAdapters.Failure.class, failure -> {
                            assertThat(failure.semantic).isEqualTo("NO_PERMISSION");
                            assertThat(failure.error("ex21a-trace").get("retryable")).isEqualTo(false);
                        });
                assertThat(peer.sends).hasValue(1); iam.revoked(false);
                peer.mappingMissing.set(true);
                assertThatThrownBy(() -> session.invoke(adapter, contract, Map.of()))
                        .isInstanceOfSatisfying(SkillAdapters.Failure.class, failure ->
                                assertThat(failure.code).isEqualTo("IDENTITY_MAPPING_NOT_FOUND"));
                assertThat(peer.sends).hasValue(1); peer.mappingMissing.set(false);
                assertThatThrownBy(() -> session.invoke(adapter, contract, Map.of(), () -> iam.currentRoles = Set.of()))
                        .isInstanceOfSatisfying(SkillAdapters.Failure.class, failure ->
                                assertThat(failure.semantic).isEqualTo("NO_PERMISSION"));
                assertThat(peer.sends).hasValue(1); iam.currentRoles = Set.of("EDITOR");
                assertThatThrownBy(() -> session.currentOnly(adapter, contract))
                        .isInstanceOfSatisfying(SkillAdapters.Failure.class, failure ->
                                assertThat(failure.code).isEqualTo("AUTHORITY_CONTEXT_MISSING"));
                assertThat(peer.sends).hasValue(1);
                // 同 tenant 的不同主体、不同 tenant、不同属性范围都不得读 alice 的旧结果。
                contract.remove("identity"); session.invoke(adapter, contract, Map.of());
                assertThat(peer.sends).hasValue(2);
                for (var actor : List.of(new Actor("tenant-a", "alice", Set.of("EDITOR"), Map.of("region", Set.of("B"), "dataLabels", Set.of("restricted"))),
                        new Actor("tenant-a", "bob", Set.of("EDITOR"), Map.of("region", Set.of("A"), "dataLabels", Set.of("restricted"))),
                        new Actor("tenant-b", "alice", Set.of("EDITOR"), Map.of("region", Set.of("A"), "dataLabels", Set.of("restricted"))))) {
                    session.newPortalSubject(actor); session.invoke(adapter, contract, Map.of());
                }
                assertThat(peer.sends).hasValue(5);
            });
        }
    }

    @Test void ttlEntryAndByteBudgetsAndBypassesKeepFailuresWritesCredentialsAndDisabledCallsUncached() throws Exception {
        try (var iam = new LoopbackAuthority(); var session = new Session(iam); var peer = new Peer(session.app)) {
            runner(session, peer, true).run(context -> {
                var adapter = context.getBean(SkillAdapters.class); var contract = contract(peer);
                session.invoke(adapter, contract, Map.of()); session.invoke(adapter, contract, Map.of());
                assertThat(peer.sends).hasValue(1);
                session.nanos.addAndGet(11_000_000L);
                session.invoke(adapter, contract, Map.of()); assertThat(peer.sends).hasValue(2);
                // 三个小条目的合计未超过总字节预算，条目数上限 2 必须逐出最旧者。
                for (String key : List.of("a", "b", "c", "a")) session.invoke(adapter, contract, Map.of("entry", key));
                assertThat(peer.sends).hasValue(6);
                // 两个中等条目各自可收，但合计超过 1400 bytes；A/B/A 应再发第三次。
                peer.value.set("v".repeat(500));
                for (String key : List.of("byte-a", "byte-b", "byte-a")) session.invoke(adapter, contract, Map.of("entry", key));
                assertThat(peer.sends).hasValue(9);
                peer.value.set("v".repeat(1500));
                session.invoke(adapter, contract, Map.of("large", true)); session.invoke(adapter, contract, Map.of("large", true));
                assertThat(peer.sends).hasValue(11); peer.value.set("fresh");
                peer.status.set(500);
                for (int i = 0; i < 2; i++) assertThatThrownBy(() -> session.invoke(adapter, contract, Map.of("fail", true)))
                        .isInstanceOf(SkillAdapters.Failure.class);
                assertThat(peer.sends).hasValue(13); peer.status.set(200);
                var write = new LinkedHashMap<>(contract); write.put("execution", Map.of("readonly", false, "sideEffect", true));
                session.invoke(adapter, write, Map.of()); session.invoke(adapter, write, Map.of());
                assertThat(peer.sends).hasValue(15);
                session.observe(adapter, contract, Map.of(), true); session.observe(adapter, contract, Map.of(), true);
                assertThat(peer.sends).hasValue(17);
                var credential = new LinkedHashMap<>(contract); credential.put("authScheme", "BEARER");
                // 不需要伪造可撤销凭据；当前无 authRef 的旧 BEARER 行为仍正常发请求，但不纳入缓存。
                session.invoke(adapter, credential, Map.of()); session.invoke(adapter, credential, Map.of());
                assertThat(peer.sends).hasValue(19);
            });
            int enabledSends = peer.sends.get();
            try (var disabledSession = new Session(iam)) {
                runner(disabledSession, peer, false).run(context -> {
                    var adapter = context.getBean(SkillAdapters.class);
                    disabledSession.invoke(adapter, contract(peer), Map.of()); disabledSession.invoke(adapter, contract(peer), Map.of());
                    assertThat(peer.sends.get()).isEqualTo(enabledSends + 2);
                });
            }
        }
    }

    private static ApplicationContextRunner runner(Session session, Peer peer, boolean enabled) {
        var runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class)
                .withBean(CurrentAuthorityClient.class, () -> session.skill)
                .withBean(LongSupplier.class, () -> session.nanos::get)
                .withBean(ServiceTickets.class, () -> new ServiceTickets(ServiceTickets.AUTHORITY,
                        new IamServiceTicketProvider(session.iam.clock(), session.skill), new ServiceRoutePolicy(), Map.of("app", peer.url())))
                .withPropertyValues("agent.services.app=" + peer.url(),
                        "agent.skill.read-only-cache.ttl-ms=10", "agent.skill.read-only-cache.max-entries=2",
                        "agent.skill.read-only-cache.max-total-bytes=1400", "agent.skill.read-only-cache.max-entry-bytes=900");
        return enabled ? runner.withPropertyValues("agent.skill.read-only-cache.enabled=true") : runner;
    }

    private static Map<String, Object> contract(Peer peer) {
        var result = new LinkedHashMap<String, Object>();
        result.put("type", "HTTP"); result.put("invocationKind", "SYNC"); result.put("status", "PUBLISHED");
        result.put("skillVersionId", 1L); result.put("sha256", Digest.sha256("ex21a-contract"));
        result.put("endpoint", peer.url() + "/business"); result.put("httpMethod", "POST"); result.put("authScheme", "NONE");
        result.put("timeoutMs", 2000); result.put("outputMaxBytes", 4096); result.put("outputSchema", Map.of("type", "object"));
        result.put("returnSpec", Map.of("modelSummary", Map.of("mode", "PATH", "path", "$.kind")));
        result.put("execution", Map.of("readonly", true, "sideEffect", false)); result.put("guard", Map.of("roles", List.of("EDITOR")));
        return result;
    }

    private static final class Session implements AutoCloseable {
        final LoopbackAuthority iam;
        final HttpCurrentAuthorityClient gateway, skill, app;
        final TrustedJwtDecoder decoder;
        final AtomicLong nanos = new AtomicLong();
        final ServiceCall call;
        String ticket;
        Session(LoopbackAuthority iam) {
            this.iam = iam; gateway = iam.client("gateway"); skill = iam.client("skill"); app = iam.client("app");
            decoder = new TrustedJwtDecoder(iam.config("skill"), iam.clock(), true);
            var route = new ServiceRoutePolicy().routes().stream().filter(row -> "gateway".equals(row.caller())
                    && "skill".equals(row.target()) && "delegated".equals(row.mode())).findFirst().orElseThrow();
            call = new ServiceCall("skill", route.action(), RESOURCE); ticket = gateway.exchange(iam.portal(), call).value();
        }
        void newPortalSubject(Actor actor) {
            iam.portalActor(actor);
            try (var currentPortal = iam.client("gateway")) { ticket = currentPortal.exchange(iam.portal(), call).value(); }
        }
        SkillAdapters.Observation observe(SkillAdapters adapter, Map<String, Object> contract, Map<String, Object> args) {
            return observe(adapter, contract, args, false);
        }
        SkillAdapters.Observation observe(SkillAdapters adapter, Map<String, Object> contract, Map<String, Object> args, boolean queryOnly) {
            var previous = SecurityContextHolder.getContext().getAuthentication();
            var initial = skill.authorizeRetained(ticket, call);
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(ticket)));
            try (var frame = AuthorityContext.bindRetained(initial, () -> skill.authorizeRetained(ticket, call))) {
                return adapter.observe(contract, args, UUID.randomUUID().toString(), 21, queryOnly, "r-ex21a", 1);
            } finally { SecurityContextHolder.getContext().setAuthentication(previous); }
        }
        SkillAdapters.Raw currentOnly(SkillAdapters adapter, Map<String, Object> contract) {
            var previous = SecurityContextHolder.getContext().getAuthentication();
            var initial = skill.authorizeRetained(ticket, call);
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(ticket)));
            try (var frame = AuthorityContext.bind(initial.current())) {
                return adapter.invoke(contract, Map.of(), "current-only", 21, false, "r-ex21a", 1);
            } finally { SecurityContextHolder.getContext().setAuthentication(previous); }
        }
        SkillAdapters.Raw invoke(SkillAdapters adapter, Map<String, Object> contract, Map<String, Object> args) {
            return invoke(adapter, contract, args, () -> {});
        }
        SkillAdapters.Raw invoke(SkillAdapters adapter, Map<String, Object> contract, Map<String, Object> args, Runnable afterInitial) {
            var previous = SecurityContextHolder.getContext().getAuthentication();
            var initial = skill.authorizeRetained(ticket, call);
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(ticket)));
            try (var frame = AuthorityContext.bindRetained(initial, () -> skill.authorizeRetained(ticket, call))) {
                afterInitial.run(); return adapter.invoke(contract, args, UUID.randomUUID().toString(), 21, false, "r-ex21a", 1);
            } finally { SecurityContextHolder.getContext().setAuthentication(previous); }
        }
        @Override public void close() { decoder.close(); app.close(); skill.close(); gateway.close(); }
    }

    private static final class Peer implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger sends = new AtomicInteger(), mappingReads = new AtomicInteger(), status = new AtomicInteger(200);
        final AtomicBoolean mappingMissing = new AtomicBoolean();
        final AtomicReference<String> value = new AtomicReference<>("fresh"), lastSubject = new AtomicReference<>();
        Peer(HttpCurrentAuthorityClient app) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes(); int responseStatus = status.get(); Object body;
                if (exchange.getRequestURI().getPath().equals("/internal/identity-mappings/current")) {
                    mappingReads.incrementAndGet(); var token = exchange.getRequestHeaders().getFirst("Authorization");
                    var verified = app.authorize(token.substring(7), new ServiceCall("app", "identity.mapping.current", RESOURCE));
                    var actor = verified.actor();
                    if (mappingMissing.get()) { responseStatus = 404; body = Map.of("code", "IDENTITY_MAPPING_NOT_FOUND", "message", "当前映射不存在"); }
                    else { responseStatus = 200; body = Map.of("code", "OK", "data", Map.of("tenantId", actor.tenantId(),
                            "principalId", actor.principalId(), "mappingKey", actor.subject().mappingKey(), "revision", 1, "subject", actor.subject())); }
                } else {
                    sends.incrementAndGet(); lastSubject.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
                    // 远端同名字段只是业务数据，不能成为本地命中事实。
                    body = Map.of("kind", "ok", "value", value.get(), "readOnlyCacheHit", true);
                }
                byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(responseStatus, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); } finally { exchange.close(); }
            }); server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }
}
