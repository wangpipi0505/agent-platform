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
                var cancellation = new CountDownLatch(1);
                PortalEventsResponse response;
                try (var frame = AuthorityContext.bindRetained(initial, () -> engine.authorizeRetained(ticket, call()))) {
                    response = engine.openPortalEvents(initial, binding(initial, iam, "real-open"), () -> {
                        cancelled.incrementAndGet();
                        cancellation.countDown();
                    });
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
                assertThat(cancellation.await(2, TimeUnit.SECONDS)).isTrue();
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
            try {
                var runtime = field(engine, "eventsRuntime");
                if (runtime != null) {
                    assertThat(workers(runtime).isTerminated()).isTrue();
                    assertThat(scheduler(runtime).isTerminated()).isTrue();
                }
            } catch (Exception failure) { throw new AssertionError(failure); }
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
            for (String field : List.of("iss", "token_use", "nonce", "request_sha256", "client_id", "target", "action", "sub",
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

    @Test
    void queryStatusAndExecutionRetainedCannotBorrowEventsResponseProfile() throws Exception {
        try (var session = new Session()) {
            for (var wrong : List.of(
                    new ServiceCall("engine", RunObservationSelectors.STATUS_ACTION, call().resource()),
                    new ServiceCall("engine", RunObservationSelectors.ACTION, RunObservationSelectors.metadata(OWNER, RUN)),
                    new ServiceCall("engine", "proxy.engine.post.RunController.run", call().resource()))) {
                String ticket = session.gateway.exchange(session.iam.portal(120), wrong).value();
                var initial = session.engine.authorizeRetained(ticket, wrong);
                try (var frame = AuthorityContext.bindRetained(initial,
                        () -> session.engine.authorizeRetained(ticket, wrong))) {
                    var binding = new PortalEventsBinding("wrong-purpose", call().resource(), 0,
                            PortalEventsBinding.observationRequestSha256(OWNER, initial.requireOriginAuthenticationId(),
                                    call().resource(), 0), session.iam.clock.instant().plusSeconds(30));
                    int requests = session.iam.authorityCalls();
                    denied(() -> session.engine.openPortalEvents(initial, binding, () -> {}));
                    assertThat(session.iam.authorityCalls()).isEqualTo(requests);
                }
            }
        }
    }

    @Test
    void renewalIndependentlyChecksSignedRequestSourceOperationAndCurrent() throws Exception {
        try (var session = new Session()) {
            for (String field : List.of("nonce", "request_sha256", "sub", "grant_ref", "origin_authentication_id",
                    "operation", "immutable", "checked_until", "current")) {
                try (var response = session.open("renew-bad-" + field, () -> {})) {
                    var previous = response.currentCapture();
                    session.iam.changeDecision = claims -> claims.put(field, field.equals("current") ? false : "wrong");
                    denied(() -> session.engine.checkPortalEvents(response));
                    denied(previous::subject);
                } finally { session.iam.changeDecision = claims -> {}; }
            }
        }
    }

    @Test
    void actualDifferentRunCaptureIsNotExchangeableEvenForSamePrincipalAndOwner() throws Exception {
        try (var session = new Session(); var first = session.open("same-subject-first", () -> {})) {
            String run = "r_" + "b".repeat(32);
            session.iam.registerObservation(OWNER, run, SHA);
            var call = new ServiceCall("engine", RunObservationSelectors.EVENTS_ACTION,
                    RunObservationSelectors.events(run, SHA));
            String ticket = session.gateway.exchange(session.iam.portal(120), call).value();
            var initial = session.engine.authorizeRetained(ticket, call);
            var binding = new PortalEventsBinding("same-subject-other-run", call.resource(), 0,
                    PortalEventsBinding.observationRequestSha256(OWNER, initial.requireOriginAuthenticationId(),
                            call.resource(), 0), session.iam.clock.instant().plusSeconds(30));
            try (var frame = AuthorityContext.bindRetained(initial,
                    () -> session.engine.authorizeRetained(ticket, call));
                    var second = session.engine.openPortalEvents(initial, binding, () -> {})) {
                denied(() -> first.requireLive(second.currentCapture()));
                denied(() -> second.requireLive(first.currentCapture()));
                assertThat(second.currentCapture().resource().id()).isEqualTo(run);
            }
        }
    }

    @Test
    void idleRevocationAndElapsedHardBoundariesStopWithoutAConsumerAndNeverResumeExecution() throws Exception {
        // The long jumps cross several deadlines; operation <= grant prevents an independent grant-first case.
        for (String cause : List.of("revoked", "past-source", "past-all-boundaries")) {
            try (var session = new Session()) {
                var cancelled = new CountDownLatch(1);
                try (var response = session.open("idle-" + cause, cancelled::countDown)) {
                    if (cause.equals("revoked")) {
                        session.iam.revoked = true;
                        session.iam.clock.advance(3);
                    } else {
                        session.iam.clock.advance(cause.equals("past-source") ? 121 : 301);
                    }
                    assertThat(cancelled.await(3, TimeUnit.SECONDS)).isTrue();
                    status(response::currentCapture, 403);
                    assertThat(session.iam.observedOperations()).doesNotContain("resume", "forward", "response_open", "response_check");
                }
            }
        }
    }

    @Test
    void shorterPublicDeadlineAndRealElapsedTimeRemainHardAfterWallRollback() throws Exception {
        try (var session = new Session()) {
            var initial = session.initial();
            var binding = new PortalEventsBinding("short-hard", call().resource(), Long.MAX_VALUE,
                    PortalEventsBinding.observationRequestSha256(OWNER, initial.requireOriginAuthenticationId(),
                            call().resource(), Long.MAX_VALUE), session.iam.clock.instant().plusSeconds(1));
            try (var frame = AuthorityContext.bindRetained(initial, session::initial);
                    var response = session.engine.openPortalEvents(initial, binding, () -> {})) {
                session.iam.clock.advance(-10);
                Thread.sleep(1100);
                denied(response::currentCapture);
            }
        }
    }

    @Test
    void boundedSlotsAndBlockedCancellationKeepTheirPhysicalOwnershipUntilWorkersReturn() throws Exception {
        try (var session = new Session()) {
            var initial = session.initial();
            var entered = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            var responses = new ArrayList<PortalEventsResponse>();
            try {
                for (int i = 0; i < 64; i++) {
                    try (var frame = AuthorityContext.bindRetained(initial, session::initial)) {
                        responses.add(session.engine.openPortalEvents(initial,
                                binding(initial, session.iam, "capacity-" + i), () -> {
                                    entered.countDown();
                                    try { release.await(3, TimeUnit.SECONDS); }
                                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                                }));
                    }
                }
                int requests = session.iam.authorityCalls();
                try (var frame = AuthorityContext.bindRetained(initial, session::initial)) {
                    status(() -> session.engine.openPortalEvents(initial,
                            binding(initial, session.iam, "capacity-over"), () -> {}), 429);
                }
                assertThat(session.iam.authorityCalls()).isEqualTo(requests);
                for (var response : responses) response.close();
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                var runtimeField = HttpCurrentAuthorityClient.class.getDeclaredField("eventsRuntime");
                runtimeField.setAccessible(true);
                var runtime = runtimeField.get(session.engine);
                var slotsField = runtime.getClass().getDeclaredField("slots");
                slotsField.setAccessible(true);
                assertThat((Set<?>) slotsField.get(runtime)).hasSize(64);
                var workersField = runtime.getClass().getDeclaredField("workers");
                workersField.setAccessible(true);
                var workers = (java.util.concurrent.ThreadPoolExecutor) workersField.get(runtime);
                assertThat(workers.getPoolSize()).isEqualTo(2);
                assertThat(workers.getQueue().size()).isLessThanOrEqualTo(64);
                release.countDown();
            } finally {
                release.countDown();
                for (var response : responses) response.close();
            }
        }
    }

    @Test
    void retainedNeverCrossesThreadsAndOpeningRechecksOriginalJwtAfterRealSignedOpen() throws Exception {
        try (var session = new Session(120, claims -> claims.put("exp", sessionClock(claims) + 2));
                var worker = Executors.newSingleThreadExecutor()) {
            var initial = session.initial();
            var binding = binding(initial, session.iam, "post-open-jwt");
            var cancelled = new CountDownLatch(1);
            try (var frame = AuthorityContext.bindRetained(initial, session::initial)) {
                worker.submit(() -> denied(() -> session.engine.openPortalEvents(initial, binding, () -> {})))
                        .get(2, TimeUnit.SECONDS);
                session.iam.observeEventsRequest = operation -> {
                    if (operation.equals("portal_events_open")) session.iam.clock.advance(3);
                };
                denied(() -> session.engine.openPortalEvents(initial, binding, cancelled::countDown));
                assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void lateOpeningAfterManyRealRefreshesCannotRestartFirstFiftyFiveSeconds() throws Exception {
        try (var session = new Session()) {
            var initial = session.initial();
            try (var frame = AuthorityContext.bindRetained(initial, session::initial)) {
                var fresh = initial;
                for (int i = 0; i < 18; i++) {
                    session.iam.clock.advance(3);
                    fresh = AuthorityContext.requireInitialFor(fresh.current().actor(), call().resource());
                }
                session.iam.clock.advance(2);
                var late = fresh;
                var binding = binding(late, session.iam, "too-late-open");
                int requests = session.iam.authorityCalls();
                status(() -> session.engine.openPortalEvents(late, binding, () -> {}), 403);
                assertThat(session.iam.authorityCalls()).isEqualTo(requests);
            }
        }
    }

    @Test
    void signedWrongEnvelopeTypeIsUnavailableAndQueuedOpeningTimeoutCancelsWithoutNetwork() throws Exception {
        try (var session = new Session()) {
            session.iam.eventsDecisionType = "JWT";
            status(() -> session.open("wrong-type", () -> {}), 503);
            session.iam.eventsDecisionType = "agent-authority-decision+jwt";
            var blocked = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            Runnable cancellation = () -> {
                blocked.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            };
            try (var first = session.open("queue-first", cancellation);
                    var second = session.open("queue-second", cancellation)) {
                first.close();
                second.close();
                assertThat(blocked.await(2, TimeUnit.SECONDS)).isTrue();
                var initial = session.initial();
                var queued = new PortalEventsBinding("queued-timeout", call().resource(), 0,
                        PortalEventsBinding.observationRequestSha256(OWNER, initial.requireOriginAuthenticationId(),
                                call().resource(), 0), session.iam.clock.instant().plusSeconds(1));
                var queuedCancelled = new CountDownLatch(1);
                int requests = session.iam.authorityCalls();
                try (var frame = AuthorityContext.bindRetained(initial, session::initial)) {
                    denied(() -> session.engine.openPortalEvents(initial, queued, queuedCancelled::countDown));
                }
                assertThat(session.iam.authorityCalls()).isEqualTo(requests);
                release.countDown();
                assertThat(queuedCancelled.await(2, TimeUnit.SECONDS)).isTrue();
            } finally { release.countDown(); }
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private static void until(java.util.function.BooleanSupplier condition) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.onSpinWait();
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static java.util.concurrent.ScheduledThreadPoolExecutor scheduler(Object runtime) throws Exception {
        return (java.util.concurrent.ScheduledThreadPoolExecutor) field(runtime, "scheduler");
    }

    private static java.util.concurrent.ThreadPoolExecutor workers(Object runtime) throws Exception {
        return (java.util.concurrent.ThreadPoolExecutor) field(runtime, "workers");
    }

    private static Object quietRuntime(Session session) throws Exception {
        var runtime = field(session.engine, "eventsRuntime");
        // These tests drive the exact transition; prevent an unrelated periodic tick from racing it.
        for (var timer : scheduler(runtime).getQueue()) {
            if (timer instanceof java.util.concurrent.RunnableScheduledFuture<?> scheduled && scheduled.isPeriodic()) {
                scheduled.cancel(false);
            }
        }
        return runtime;
    }

    private static int slotCount(Object runtime) throws Exception {
        synchronized (runtime) { return ((Set<?>) field(runtime, "slots")).size(); }
    }

    private static void awaitReleased(Object runtime) throws Exception {
        // Slot removal is after the callback's actual finally; Future.done alone is insufficient.
        until(() -> {
            try { return slotCount(runtime) == 0; }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
    }

    private static void blockWorkers(Object runtime, CountDownLatch release) throws Exception {
        var entered = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) workers(runtime).execute(() -> {
            entered.countDown();
            try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        await(entered);
    }

    private static void assertCancellationOutsideLocks(Object response) {
        try {
            var slot = field(field(response, "evidence"), "slot");
            for (var lock : List.of(response, slot, field(slot, "runtime"), field(slot, "cancellation"))) {
                assertThat(Thread.holdsLock(lock)).isFalse();
            }
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    @Test
    void clientCloseDeliversCancellationWhileHolderStopIsPublishedButNotSubmitted() throws Exception {
        try (var session = new Session(); var callers = Executors.newFixedThreadPool(2)) {
            var published = new CountDownLatch(1);
            var resumeStop = new CountDownLatch(1);
            var callbackEntered = new CountDownLatch(1);
            var callbackExit = new CountDownLatch(1);
            var calls = new AtomicInteger();
            var responseLock = new java.util.concurrent.atomic.AtomicReference<Object>();
            var response = session.open("stop-close-gap", () -> {
                assertCancellationOutsideLocks(responseLock.get());
                calls.incrementAndGet(); callbackEntered.countDown(); await(callbackExit);
            });
            responseLock.set(response);
            var runtime = quietRuntime(session);
            var pool = workers(runtime);
            session.engine.eventsLifecycle = point -> {
                if (point.equals("stop-published")) { published.countDown(); await(resumeStop); }
            };
            try {
                var holderClose = callers.submit(() -> {
                    try { response.close(); return (Throwable) null; }
                    catch (Throwable failure) { return failure; }
                });
                await(published);
                var ownerClose = callers.submit(session.engine::close);
                until(pool::isShutdown);
                resumeStop.countDown();
                assertThat(holderClose.get(2, TimeUnit.SECONDS)).isNull();
                await(callbackEntered);
                assertThat(calls).hasValue(1);
                assertThat(slotCount(runtime)).isEqualTo(1);
                callbackExit.countDown();
                ownerClose.get(3, TimeUnit.SECONDS);
                assertThat(slotCount(runtime)).isZero();
                assertThat(pool.isTerminated()).isTrue();
                assertThat(scheduler(runtime).isTerminated()).isTrue();
                denied(response::currentCapture);
            } finally { resumeStop.countDown(); callbackExit.countDown(); responseLock.set(null); }
        }
    }

    @Test
    void rejectedCancellationRunsOutsideLifecycleLocksAndRetainsSlotUntilActualReturn() throws Exception {
        try (var session = new Session(); var caller = Executors.newSingleThreadExecutor()) {
            var releaseWorkers = new CountDownLatch(1);
            var callbackEntered = new CountDownLatch(1);
            var callbackExit = new CountDownLatch(1);
            var count = new AtomicInteger();
            var fillerExits = new AtomicInteger();
            var responseLock = new java.util.concurrent.atomic.AtomicReference<Object>();
            var response = session.open("rejected-cancel", () -> {
                assertCancellationOutsideLocks(responseLock.get());
                count.incrementAndGet(); callbackEntered.countDown(); await(callbackExit);
            });
            responseLock.set(response);
            var runtime = quietRuntime(session);
            try {
                blockWorkers(runtime, releaseWorkers);
                for (int i = 0; i < 64; i++) workers(runtime).execute(fillerExits::incrementAndGet);
                var closing = caller.submit(() -> {
                    try { response.close(); return (Throwable) null; }
                    catch (Throwable failure) { return failure; }
                });
                await(callbackEntered);
                assertThat(slotCount(runtime)).isEqualTo(1);
                assertThat(closing.isDone()).isFalse();
                callbackExit.countDown();
                assertThat(closing.get(2, TimeUnit.SECONDS)).isNull();
                awaitReleased(runtime);
                assertThat(count).hasValue(1);
                releaseWorkers.countDown();
                var pool = workers(runtime);
                until(() -> fillerExits.get() == 64 && pool.getActiveCount() == 0);
            } finally { releaseWorkers.countDown(); callbackExit.countDown(); responseLock.set(null); }
        }
    }

    @Test
    void expiryBeforeRenewalPublicationLeavesNoReservationAndCapacityIsReusable() throws Exception {
        try (var session = new Session(); var caller = Executors.newSingleThreadExecutor()) {
            var response = session.open("unpublished-expiry", () -> {});
            var runtime = quietRuntime(session);
            var deadline = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            session.engine.eventsLifecycle = point -> {
                if (point.equals("renew-deadline")) { deadline.countDown(); await(resume); }
            };
            try {
                var check = caller.submit(() -> denied(() -> session.engine.checkPortalEvents(response)));
                await(deadline);
                session.iam.clock.advance(6);
                resume.countDown();
                check.get(2, TimeUnit.SECONDS);
                response.close();
                awaitReleased(runtime);
                assertThat(session.iam.observedOperations()).doesNotContain("portal_events_check");
                session.engine.eventsLifecycle = point -> {};
                try (var next = session.open("capacity-reused", () -> {})) {
                    assertThat(slotCount(runtime)).isEqualTo(1);
                    next.currentCapture();
                }
                awaitReleased(runtime);
            } finally { resume.countDown(); response.close(); }
        }
    }

    @Test
    void saturatedWatchdogDefersCancellationWithoutBlockingOtherDeadlines() throws Exception {
        try (var session = new Session()) {
            var releaseWorkers = new CountDownLatch(1);
            var releaseCallback = new CountDownLatch(1);
            var callbackEntered = new CountDownLatch(1);
            var callbackThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
            var calls = new AtomicInteger();
            var fillerExits = new AtomicInteger();
            var response = session.open("watchdog-rejection", () -> {
                calls.incrementAndGet();
                callbackThread.set(Thread.currentThread());
                callbackEntered.countDown(); await(releaseCallback);
            });
            var runtime = quietRuntime(session);
            var pool = workers(runtime);
            var timer = scheduler(runtime);
            var watchdog = timer.submit(Thread::currentThread).get(2, TimeUnit.SECONDS);
            try {
                blockWorkers(runtime, releaseWorkers);
                for (int i = 0; i < 64; i++) pool.execute(fillerExits::incrementAndGet);
                timer.execute(response::close);
                var nextDeadline = new CountDownLatch(1);
                timer.execute(nextDeadline::countDown);
                assertThat(nextDeadline.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(callbackEntered.getCount()).isEqualTo(1);
                assertThat(slotCount(runtime)).isEqualTo(1);
                var slot = ((Set<?>) field(runtime, "slots")).iterator().next();
                var cancellation = field(slot, "cancellation");
                synchronized (cancellation) { assertThat(field(cancellation, "submitted")).isEqualTo(false); }
                releaseWorkers.countDown();
                until(() -> pool.getQueue().isEmpty());
                var tick = runtime.getClass().getDeclaredMethod("tick");
                tick.setAccessible(true);
                timer.submit(() -> {
                    try { tick.invoke(runtime); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                }).get(2, TimeUnit.SECONDS);
                await(callbackEntered);
                assertThat(callbackThread.get()).isNotSameAs(watchdog);
                assertThat(slotCount(runtime)).isEqualTo(1);
                releaseCallback.countDown();
                awaitReleased(runtime);
                assertThat(calls).hasValue(1);
                assertThat(fillerExits).hasValue(64);
                until(() -> pool.getActiveCount() == 0);
            } finally { releaseWorkers.countDown(); releaseCallback.countDown(); }
        }
    }

    @Test
    void cancelledPublishedRenewalCannotRegisterTimerOrQueueAfterItsSlotWasReleased() throws Exception {
        try (var session = new Session(); var caller = Executors.newSingleThreadExecutor()) {
            var response = session.open("cancel-before-execute", () -> {});
            var captured = response.currentCapture();
            var runtime = quietRuntime(session);
            var published = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            var releaseWorkers = new CountDownLatch(1);
            session.engine.eventsLifecycle = point -> {
                if (point.equals("renew-published")) { published.countDown(); await(resume); }
            };
            try {
                var renewal = caller.submit(() -> response.renew(session.engine));
                await(published);
                var task = (HttpCurrentAuthorityClient.EventsTask<?>) field(response, "pending");
                var slot = field(task, "slot");
                response.close();
                awaitReleased(runtime);
                blockWorkers(runtime, releaseWorkers);
                resume.countDown();
                assertThat(renewal.get(2, TimeUnit.SECONDS)).isSameAs(task);
                assertThat(List.of(workers(runtime).getQueue().size(), scheduler(runtime).getQueue().size()))
                        .containsExactly(0, 0);
                assertThat(field(slot, "work")).isEqualTo(0);
                assertThat(((CountDownLatch) field(task, "exited")).getCount()).isZero();
                task.cancel(true);
                assertThat(field(slot, "work")).isEqualTo(0);
                assertThat(session.iam.observedOperations()).doesNotContain("portal_events_check");
                denied(captured::subject);
                denied(response::currentCapture);
            } finally { resume.countDown(); releaseWorkers.countDown(); response.close(); }
        }
    }

    @Test
    void cancellationDuringTimerHandoffRemovesBothTimerAndQueuedWorkerExactlyOnce() throws Exception {
        try (var session = new Session(); var callers = Executors.newFixedThreadPool(2)) {
            var response = session.open("timer-handoff", () -> {});
            var captured = response.currentCapture();
            var runtime = quietRuntime(session);
            var registered = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            var cancelEntered = new CountDownLatch(1);
            var cancellingThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
            var releaseWorkers = new CountDownLatch(1);
            session.engine.eventsLifecycle = point -> {
                if (point.equals("timer-created")) { registered.countDown(); await(resume); }
            };
            try {
                blockWorkers(runtime, releaseWorkers);
                var renewal = callers.submit(() -> response.renew(session.engine));
                await(registered);
                var task = (HttpCurrentAuthorityClient.EventsTask<?>) field(response, "pending");
                var slot = field(task, "slot");
                var cancel = callers.submit(() -> {
                    cancellingThread.set(Thread.currentThread()); cancelEntered.countDown(); task.cancel(true);
                });
                await(cancelEntered);
                // The old implementation finishes here; a coordinated submit holds the task monitor.
                until(() -> cancel.isDone() || cancellingThread.get().getState() == Thread.State.BLOCKED);
                resume.countDown();
                renewal.get(2, TimeUnit.SECONDS);
                cancel.get(2, TimeUnit.SECONDS);
                assertThat(List.of(workers(runtime).getQueue().size(), scheduler(runtime).getQueue().size()))
                        .containsExactly(0, 0);
                assertThat(field(slot, "work")).isEqualTo(0);
                task.cancel(true);
                assertThat(field(slot, "work")).isEqualTo(0);
                assertThat(session.iam.observedOperations()).doesNotContain("portal_events_check");
                response.close();
                releaseWorkers.countDown();
                awaitReleased(runtime);
                denied(captured::subject);
                denied(response::currentCapture);
            } finally { resume.countDown(); releaseWorkers.countDown(); response.close(); }
        }
    }

    @Test
    void historicalExecutionCeilingIsProvenanceAndDoesNotHaveToEqualNewObservationCeiling() throws Exception {
        try (var session = new Session(); var response = session.open("historical-floor", () -> {})) {
            var base = material(session.initial(), Set.of("EDITOR"), Set.of("A"));
            // Value-only common policy test: B must authenticate this historical floor from actual PG.
            // A new observation grant cannot rewrite or assert equality with the original execution floor.
            var historical = new MaterialScopeV1(1, base.material(), base.owner(), base.sourceDecisionId(),
                    base.sourceAuthorizationVersion(), Digest.sha256("different-historical-execution-ceiling"),
                    base.requiredRoles(), base.requiredEntitlements(), base.requiredDataLabels(),
                    base.parentScopeSha256(), "");
            var scope = new MaterialScopeV1(1, historical.material(), historical.owner(), historical.sourceDecisionId(),
                    historical.sourceAuthorizationVersion(), historical.initialCeilingSha256(), historical.requiredRoles(),
                    historical.requiredEntitlements(), historical.requiredDataLabels(), historical.parentScopeSha256(),
                    MaterialAuthorization.scopeDigest(historical));
            response.currentCapture().requireMaterial(scope);
        }
    }
}
