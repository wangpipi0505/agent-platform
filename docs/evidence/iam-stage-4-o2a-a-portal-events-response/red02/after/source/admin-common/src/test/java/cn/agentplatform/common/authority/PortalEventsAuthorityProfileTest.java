package cn.agentplatform.common.authority;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

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
}
