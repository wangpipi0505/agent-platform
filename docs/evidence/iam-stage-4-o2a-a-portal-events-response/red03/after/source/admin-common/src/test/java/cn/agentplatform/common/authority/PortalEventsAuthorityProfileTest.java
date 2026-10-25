package cn.agentplatform.common.authority;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Trusted package receiver seam plus actual authority/JWKS HTTP; no engine HTTP or PG claim. */
class PortalEventsAuthorityProfileTest {
    static final String RUN = "r_" + "a".repeat(32);
    static final String SHA = Digest.sha256("accepted-events");
    static final SubjectKey OWNER = new SubjectKey("o2a-common", "alice");

    static void setup(LoopbackAuthority iam) {
        iam.portalActor(new Actor(OWNER.tenantId(), OWNER.principalId(), Set.of("EDITOR"),
                Map.of("region", Set.of("A", "B"), "dataLabels", Set.of("initial"))));
        iam.registerObservation(OWNER, RUN, SHA);
    }

    static ServiceCall call() {
        return new ServiceCall("engine", "proxy.engine.get.RunController.events",
                RunObservationSelectors.status(RUN, SHA));
    }

    static PortalEventsBinding binding(HttpCurrentAuthorityClient.RetainedAuthorization initial,
            LoopbackAuthority iam, String response) {
        var resource = initial.current().call().resource();
        return new PortalEventsBinding(response, resource, 0,
                PortalEventsBinding.observationRequestSha256(OWNER,
                        initial.requireOriginAuthenticationId(), resource, 0),
                iam.clock.instant().plusSeconds(30));
    }

    @Test
    void realSignedEventsAdmissionOpensIndependentFreshResponseAndCrossesThreads() throws Exception {
        try (var iam = new LoopbackAuthority()) {
            setup(iam);
            try (var gateway = iam.client("gateway"); var engine = iam.client("engine")) {
            String ticket = gateway.exchange(iam.portal(120), call()).value();
            var initial = engine.authorizeRetained(ticket, call());
            var cancelled = new AtomicInteger();
            PortalEventsResponse response;
            try (var frame = AuthorityContext.bindRetained(initial, () -> engine.authorizeRetained(ticket, call()))) {
                response = engine.openPortalEvents(initial, binding(initial, iam, "real-open"),
                        cancelled::incrementAndGet);
            }
            try (response; var worker = Executors.newSingleThreadExecutor()) {
                assertThat(worker.submit(() -> {
                    var capture = response.currentCapture();
                    response.requireLive(capture);
                    return capture.subject();
                }).get(3, TimeUnit.SECONDS)).isEqualTo(OWNER);
                engine.checkPortalEvents(response);
                assertThat(iam.observedOperations()).contains("portal_events_open", "portal_events_check");
            }
            assertThat(cancelled).hasValue(1);
            }
        }
    }

    private static final class Session implements AutoCloseable {
        final LoopbackAuthority iam;
        final HttpCurrentAuthorityClient gateway, engine;
        final String ticket;

        Session() throws Exception { this(120, null); }

        Session(long sourceSeconds, Consumer<Map<String, Object>> ticketMutation) throws Exception {
            iam = new LoopbackAuthority();
            setup(iam);
            gateway = iam.client("gateway");
            engine = iam.client("engine");
            if (ticketMutation != null) iam.changeTicket = ticketMutation;
            ticket = gateway.exchange(iam.portal(sourceSeconds), call()).value();
        }

        HttpCurrentAuthorityClient.RetainedAuthorization initial() {
            return engine.authorizeRetained(ticket, call());
        }

        PortalEventsResponse open(String id, Runnable cancel) {
            var initial = initial();
            try (var frame = AuthorityContext.bindRetained(initial, this::initial)) {
                return engine.openPortalEvents(initial, binding(initial, iam, id), cancel);
            }
        }

        @Override
        public void close() {
            engine.close();
            gateway.close();
            iam.close();
        }
    }

    private static void denied(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(PlatformException.class);
    }

    private static void status(Runnable action, int expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(PlatformException.class,
                failure -> assertThat(failure.status()).isEqualTo(expected));
    }

    private static MaterialScopeV1 material(HttpCurrentAuthorityClient.RetainedAuthorization initial,
            Set<String> roles, Set<String> regions) {
        var current = initial.current();
        var ref = new MaterialRef("engine", "event", RUN, SHA + ":1", Digest.sha256("actual-row-projection"));
        var raw = new MaterialScopeV1(1, ref, OWNER, "historical-material-decision", "historical-v0",
                current.operation().ceilingSha256(), roles, Map.of("region", regions), Set.of("initial"), List.of(), "");
        return new MaterialScopeV1(1, ref, OWNER, raw.sourceDecisionId(), raw.sourceAuthorizationVersion(),
                raw.initialCeilingSha256(), raw.requiredRoles(), raw.requiredEntitlements(), raw.requiredDataLabels(),
                raw.parentScopeSha256(), MaterialAuthorization.scopeDigest(raw));
    }

    @Test
    void signedFreshRolesAttributesAndVersionReplaceOpeningCoverageAndOldGeneration() throws Exception {
        try (var session = new Session()) {
            var initial = session.initial();
            try (var frame = AuthorityContext.bindRetained(initial, session::initial);
                    var response = session.engine.openPortalEvents(initial, binding(initial, session.iam, "fresh"), () -> {})) {
                var original = response.currentCapture();
                var editorB = material(initial, Set.of("EDITOR"), Set.of("B"));
                original.requireMaterial(editorB);
                session.iam.currentRoles = Set.of();
                session.iam.eventsCurrentAttributes = Map.of("region", Set.of("A"), "dataLabels", Set.of("initial", "new"));
                session.iam.version = "fresh-v2";
                session.engine.checkPortalEvents(response);
                denied(() -> original.requireMaterial(editorB));
                var fresh = response.currentCapture();
                denied(() -> fresh.requireMaterial(editorB));
                fresh.requireMaterial(material(initial, Set.of(), Set.of("A")));
                assertThat(session.iam.requests.stream().filter(r -> r.path("operation").asText().equals("portal_events_check")))
                        .allSatisfy(request -> assertThat(request.has("service_ticket")).isFalse());
            }
        }
    }

    @Test
    void localExpiryDetectedByCaptureIsTerminalAcrossWallRollback() throws Exception {
        try (var session = new Session(); var response = session.open("rollback", () -> {})) {
            var captured = response.currentCapture();
            session.iam.clock.advance(6);
            denied(captured::subject);
            session.iam.clock.advance(-6);
            denied(captured::subject);
        }
    }

    @Test
    void realFirstAnchorSurvivesRefreshAndItsExpiredOldCurrentDoesNotSupplyPermission() throws Exception {
        try (var session = new Session()) {
            var first = session.initial();
            var start = session.iam.clock.instant();
            try (var frame = AuthorityContext.bindRetained(first, session::initial)) {
                session.iam.clock.advance(3);
                var refreshed = AuthorityContext.requireInitialFor(first.current().actor(), call().resource());
                session.iam.clock.advance(3);
                refreshed = AuthorityContext.requireInitialFor(refreshed.current().actor(), call().resource());
                var late = refreshed;
                assertThat(first.current().expiresAt()).isBefore(session.iam.clock.instant());
                var remoteFuture = new PortalEventsBinding("anchor", call().resource(), 0,
                        PortalEventsBinding.observationRequestSha256(OWNER, late.requireOriginAuthenticationId(),
                                call().resource(), 0), start.plusSeconds(60));
                try (var response = session.engine.openPortalEvents(late, remoteFuture, () -> {})) {
                    for (int i = 0; i < 16; i++) {
                        session.iam.clock.advance(3);
                        session.engine.checkPortalEvents(response);
                    }
                    assertThat(session.iam.clock.instant()).isEqualTo(start.plusSeconds(54));
                    response.currentCapture();
                    session.iam.clock.advance(2);
                    denied(response::currentCapture);
                    session.iam.clock.advance(-2);
                    denied(response::currentCapture);
                }
            }
        }
    }

    @Test
    void admissionJwtOnlyExpiryCanContinueOriginalResponseButCannotOpenAnotherFrame() throws Exception {
        try (var session = new Session(120, claims -> claims.put("exp", sessionClock(claims) + 2))) {
            try (var response = session.open("jwt-only", () -> {})) {
                session.iam.clock.advance(3);
                session.engine.checkPortalEvents(response);
                assertThat(response.currentCapture().resource()).isEqualTo(call().resource());
                assertThatThrownBy(session::initial).isInstanceOfAny(PlatformException.class,
                        org.springframework.security.oauth2.jwt.JwtException.class);
            }
        }
    }

    private static long sessionClock(Map<String, Object> claims) {
        return ((Number) claims.get("iat")).longValue();
    }

    @Test
    void currentOnlyMissingOldClosedAndOtherOwnerFramesCannotMintAndEveryFrameClaimsOnce() throws Exception {
        try (var session = new Session(); var otherOwner = session.iam.client("engine")) {
            var initial = session.initial();
            var binding = binding(initial, session.iam, "frame");
            denied(() -> session.engine.openPortalEvents(initial, binding, () -> {}));
            try (var frame = AuthorityContext.bind(initial.current())) {
                denied(() -> session.engine.openPortalEvents(
                        HttpCurrentAuthorityClient.RetainedAuthorization.currentOnly(initial.current()), binding, () -> {}));
            }
            try (var frame = AuthorityContext.bindRetained(initial, session::initial)) {
                denied(() -> otherOwner.openPortalEvents(initial, binding, () -> {}));
                try (var response = session.engine.openPortalEvents(initial, binding, () -> {})) {
                    denied(() -> session.engine.openPortalEvents(initial, binding, () -> {}));
                }
            }
            denied(() -> session.engine.openPortalEvents(initial, binding, () -> {}));
            var current = session.initial();
            try (var frame = AuthorityContext.bindRetained(current, session::initial)) {
                session.iam.clock.advance(1);
                AuthorityContext.requireInitialFor(current.current().actor(), call().resource());
                denied(() -> session.engine.openPortalEvents(current, binding, () -> {}));
            }
            var fresh = session.initial();
            try (var frame = AuthorityContext.bindRetained(fresh, session::initial)) {
                session.iam.unavailable = true;
                status(() -> session.engine.openPortalEvents(fresh, binding, () -> {}), 503);
                session.iam.unavailable = false;
                int requests = session.iam.authorityCalls();
                denied(() -> session.engine.openPortalEvents(fresh, binding, () -> {}));
                assertThat(session.iam.authorityCalls()).isEqualTo(requests);
            }
        }
    }

    @Test
    void signedProfileBindingAndCryptographicFailuresNeverMintOrRelabelInternal401() throws Exception {
        try (var session = new Session()) {
            var cases = new java.util.LinkedHashMap<String, Consumer<Map<String, Object>>>();
            for (String field : List.of("nonce", "request_sha256", "client_id", "target", "action", "sub",
                    "subject_caller", "grant_ref", "authorization_version", "origin_authentication_id", "ticket_jti", "profile")) {
                cases.put(field, claims -> claims.put(field, "wrong"));
            }
            cases.put("authorization-version-missing", claims -> claims.put("authorization_version", ""));
            cases.remove("authorization_version"); // A different nonblank fresh version is a valid new decision.
            cases.put("resource", claims -> claims.put("resource", AuthorityProtocol.resource(
                    RunObservationSelectors.status("r_" + "b".repeat(32), SHA))));
            cases.put("grant-expiry", claims -> claims.put("grant_expires_at", session.iam.clock.instant().plusSeconds(299).getEpochSecond()));
            cases.put("roles", claims -> claims.put("roles", Set.of("ADMIN")));
            cases.put("attributes", claims -> claims.put("attributes", Map.of("region", Set.of("C"), "dataLabels", Set.of("initial"))));
            cases.put("checked-until", claims -> claims.put("checked_until", session.iam.clock.instant().plusSeconds(6).getEpochSecond()));
            cases.put("completion", claims -> claims.put("completion_permit", "not-a-completion-profile"));
            cases.put("parent", claims -> claims.put("parent_response_id", "fake-parent"));
            cases.put("immutable-response", claims -> {
                var immutable = (ObjectNode) Json.tree(claims.get("immutable"));
                ((ObjectNode) immutable.get("binding")).put("responseId", "other-response");
                claims.put("immutable", immutable);
            });
            cases.put("float-cursor", claims -> {
                var immutable = (ObjectNode) Json.tree(claims.get("immutable"));
                ((ObjectNode) immutable.get("binding")).put("effectiveCursor", 0.5);
                claims.put("immutable", immutable);
            });
            cases.put("overflow-cursor", claims -> {
                var immutable = (ObjectNode) Json.tree(claims.get("immutable"));
                ((ObjectNode) immutable.get("binding")).put("effectiveCursor", new java.math.BigInteger("9223372036854775808"));
                claims.put("immutable", immutable);
            });
            for (var entry : cases.entrySet()) {
                var initial = session.initial();
                try (var frame = AuthorityContext.bindRetained(initial, session::initial)) {
                    session.iam.changeDecision = entry.getValue();
                    denied(() -> session.engine.openPortalEvents(initial, binding(initial, session.iam,
                            "bad-" + entry.getKey()), () -> {}));
                } finally { session.iam.changeDecision = claims -> {}; }
            }
            var initial = session.initial();
            try (var frame = AuthorityContext.bindRetained(initial, session::initial)) {
                session.iam.badSignature = true;
                status(() -> session.engine.openPortalEvents(initial, binding(initial, session.iam, "bad-signature"), () -> {}), 503);
            }
        }
    }

    @Test
    void independentlySignedContinuationCannotChangeUseIssuerOrSourceRelations() throws Exception {
        for (String field : List.of("token_use", "iss", "immutable", "parent_response_id")) {
            try (var session = new Session()) {
                session.iam.changeEventsContinuation = proof -> proof.put(field, "wrong");
                denied(() -> session.open("bad-continuation", () -> {}));
            }
        }
    }

    @Test
    void anotherResponseRunOrOwnerCannotConsumeCaptureAndClosedCaptureChecksItself() throws Exception {
        try (var session = new Session(); var other = session.iam.client("engine")) {
            var initial = session.initial();
            var scope = material(initial, Set.of("EDITOR"), Set.of("A"));
            try (var first = session.open("first", () -> {}); var second = session.open("second", () -> {})) {
                var captured = first.currentCapture();
                denied(() -> second.requireLive(captured));
                denied(() -> other.checkPortalEvents(first));
                captured.requireMaterial(scope);
                first.close();
                denied(() -> captured.requireMaterial(scope));
                second.currentCapture().requireMaterial(scope);
            }
        }
    }

    @Test
    void idleWatchdogRenewsAndStopsUnavailableWithoutAnyDataConsumer() throws Exception {
        try (var session = new Session()) {
            var cancelled = new CountDownLatch(1);
            try (var response = session.open("idle", cancelled::countDown)) {
                session.iam.unavailable = true;
                session.iam.clock.advance(3);
                assertThat(cancelled.await(3, TimeUnit.SECONDS)).isTrue();
                status(response::currentCapture, 503);
            }
        }
    }

    @Test
    void singleFlightLateRenewCannotResurrectClosedHolderAndOwnedWorkersTerminate() throws Exception {
        var session = new Session();
        var arrived = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        Object runtime;
        try (session; var response = session.open("race", cancelled::countDown);
                var callers = Executors.newFixedThreadPool(4)) {
            var runtimeField = HttpCurrentAuthorityClient.class.getDeclaredField("eventsRuntime");
            runtimeField.setAccessible(true);
            runtime = runtimeField.get(session.engine);
            session.iam.observeEventsRequest = operation -> {
                if (operation.equals("portal_events_check")) {
                    arrived.countDown();
                    try { assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                }
            };
            List<Future<?>> checks = new ArrayList<>();
            for (int i = 0; i < 4; i++) checks.add(callers.submit(() -> denied(() -> session.engine.checkPortalEvents(response))));
            assertThat(arrived.await(2, TimeUnit.SECONDS)).isTrue();
            var captured = response.currentCapture();
            response.close();
            denied(captured::subject);
            release.countDown();
            for (var check : checks) check.get(3, TimeUnit.SECONDS);
            assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(session.iam.observedOperations().stream().filter("portal_events_check"::equals).count()).isEqualTo(1);
        } finally { release.countDown(); }
        for (String field : List.of("workers", "scheduler")) {
            var workerField = runtime.getClass().getDeclaredField(field);
            workerField.setAccessible(true);
            assertThat(((java.util.concurrent.ExecutorService) workerField.get(runtime)).isTerminated()).isTrue();
        }
        System.out.println("O2A_OWNED_WORKERS_TERMINATED=true");
    }

    @Test
    void opaqueConstructorsAndDefaultClosedAdapterCannotBeUsedAsFactories() {
        assertThat(PortalEventsResponse.Capture.class.getDeclaredConstructors())
                .allSatisfy(constructor -> assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue());
        assertThat(HttpCurrentAuthorityClient.PortalEventsEvidence.class.getDeclaredConstructors())
                .allSatisfy(constructor -> assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue());
        var legacy = new ResponseAuthorityClient() {
            public ResponseLease open(VerifiedAuthority current, ResponseBinding binding) { throw new UnsupportedOperationException(); }
            public ResponseLease check(ResponseLease response) { throw new UnsupportedOperationException(); }
        };
        status(() -> legacy.openPortalEvents(null, null, () -> {}), 503);
        status(() -> legacy.checkPortalEvents(null), 503);
    }
}
