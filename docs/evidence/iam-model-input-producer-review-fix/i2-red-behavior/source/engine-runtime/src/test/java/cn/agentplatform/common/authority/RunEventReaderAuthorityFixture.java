package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Test-only trusted frame seam; uses real E/JWKS/JWT/current HTTP, never opens an EVENTS route. */
public final class RunEventReaderAuthorityFixture implements AutoCloseable {
    public final LoopbackAuthority iam = new LoopbackAuthority();
    public final HttpCurrentAuthorityClient gateway, engine;
    public static final ResourceBinding ROOT = new ResourceBinding("app-version", "app-a@1", Digest.sha256("o2a-b-candidate"));
    public String lastOpeningOrigin;
    public RunEventReaderAuthorityFixture() throws Exception { actor("tenant-a", "alice", Set.of("A", "B")); gateway=iam.client("gateway");engine=iam.client("engine"); }
    public void actor(String tenant, String principal, Set<String> regions) {
        iam.portalActor(new Actor(tenant, principal, Set.of("EDITOR"), Map.of("region", regions, "dataLabels", Set.of("initial"))));
    }
    public <T> T admitted(Function<HttpCurrentAuthorityClient.RetainedAuthorization,T> work) {
        var call = new ServiceCall("engine", "proxy.engine.post.RunController.run", ROOT);
        String ticket = gateway.exchange(iam.portal(120), call).value();
        var initial = engine.authorizeRetained(ticket, call);
        lastOpeningOrigin=initial.requireOriginAuthenticationId();
        try (var frame = AuthorityContext.bindRetained(initial, () -> engine.authorizeRetained(ticket, call))) {
            return work.apply(initial);
        }
    }
    public PortalEventsResponse open(SubjectKey owner, String run, String snapshot, long cursor) {
        iam.registerObservation(owner, run, snapshot);
        var resource = RunObservationSelectors.status(run, snapshot);
        var call = new ServiceCall("engine", "proxy.engine.get.RunController.events", resource);
        String ticket = gateway.exchange(iam.portal(120), call).value();
        var initial = engine.authorizeRetained(ticket, call);
        lastOpeningOrigin=initial.requireOriginAuthenticationId();
        var binding = new PortalEventsBinding("b-"+UUID.randomUUID(), resource, cursor,
                PortalEventsBinding.observationRequestSha256(owner, initial.requireOriginAuthenticationId(), resource, cursor),
                iam.clock().instant().plusSeconds(30));
        try (var frame = AuthorityContext.bindRetained(initial, () -> engine.authorizeRetained(ticket, call))) {
            return engine.openPortalEvents(initial, binding, () -> {});
        }
    }
    public void advance(long seconds) { iam.advanceClock(Duration.ofSeconds(seconds)); }
    public void renew(PortalEventsResponse response) { engine.checkPortalEvents(response); }
    public void narrow() { iam.eventsCurrentAttributes = Map.of("region", Set.of("A"), "dataLabels", Set.of("initial")); }
    public void resetCoverage() { iam.eventsCurrentAttributes=null; }
    public void close() { engine.close(); gateway.close(); iam.close(); }
}
