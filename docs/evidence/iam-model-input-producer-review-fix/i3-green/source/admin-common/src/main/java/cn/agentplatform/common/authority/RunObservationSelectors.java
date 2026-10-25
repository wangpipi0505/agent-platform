package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import java.util.*;

/** A versioned subject/path query; its digest describes no persisted run payload. */
public final class RunObservationSelectors {
    public static final String KIND="run-observation-selector-v1", ACTION="run.observation.metadata.read";
    public static final String STATUS_ACTION="proxy.engine.get.RunController.status";
    public static final String EVENTS_ACTION = "proxy.engine.get.RunController.events";
    static final String GATEWAY_EVENTS_ACTION="gateway.run.events.observe";
    /** Private fixed G purpose; deliberately absent from public route and selector admission. */
    static void requireGatewayPortalEventsCall(ServiceCall call,String caller,String mode,String source,String origin){
        if(call==null||call.resource()==null||!"gateway".equals(call.target())||!"gateway".equals(caller)
                ||!GATEWAY_EVENTS_ACTION.equals(call.action())||!"delegated".equals(mode)
                ||!"PORTAL".equals(source)||!"PORTAL".equals(origin))throw AuthorityProtocol.denied();
        AuthorityProtocol.same(events(call.resource().id(),call.resource().versionDigest()),call.resource());
    }
    private RunObservationSelectors() {}
    public static ResourceBinding status(String run,String acceptedSha){
        if(run==null||!run.matches("r_[0-9a-f]{32}")||acceptedSha==null||!acceptedSha.matches("[0-9a-f]{64}"))
            throw new PlatformException(403,"RUN_AUTHORITY_DENIED","运行观察资源无效");
        return new ResourceBinding("run-snapshot",run,acceptedSha);
    }
    public static ResourceBinding events(String run, String acceptedSha) {
        return status(run, acceptedSha);
    }

    /** Fixed response profile and mapped receiver share the same strict EVENTS purpose. */
    static void requirePortalEventsResponseCall(ServiceCall call, String caller, String mode,
            String source, String origin) {
        if (call == null || call.resource() == null || !"engine".equals(call.target())
                || !"gateway".equals(caller) || !EVENTS_ACTION.equals(call.action())
                || !"delegated".equals(mode) || !"PORTAL".equals(source) || !"PORTAL".equals(origin)) {
            throw AuthorityProtocol.denied();
        }
        AuthorityProtocol.same(events(call.resource().id(), call.resource().versionDigest()), call.resource());
    }
    public static ResourceBinding metadata(SubjectKey verified,String publicRunId){
        if(verified==null||!subject(verified.tenantId())||!subject(verified.principalId())
                ||publicRunId==null||!publicRunId.matches("r_[0-9a-f]{32}"))
            throw new PlatformException(400,"INVALID_RUN_OBSERVATION_SELECTOR","运行观察查询标识无效");
        var descriptor=Map.of("schema","run-observation-selector.v1","tenant_id",verified.tenantId(),
                "principal_id",verified.principalId(),"run_id",publicRunId,"projection","run-observation-metadata.v1");
        return new ResourceBinding(KIND,Json.canonical(List.of("run-observation-selector.v1",verified.tenantId(),
                verified.principalId(),publicRunId,"run-observation-metadata.v1")),Digest.sha256(Json.canonical(descriptor)));
    }
    /** Both directions are checked before online authorization, including workload and old routes. */
    public static void requirePurpose(ServiceCall call,String caller,String mode,String source,String origin){
        if(call==null||call.resource()==null)throw AuthorityProtocol.denied();
        boolean kind=KIND.equals(call.resource().kind()),action=ACTION.equals(call.action());
        boolean snapshot="run-snapshot".equals(call.resource().kind()),status=STATUS_ACTION.equals(call.action()),events=EVENTS_ACTION.equals(call.action());
        if(snapshot||status||events){
            if(!snapshot||(!status&&!events)||!"engine".equals(call.target())||!"gateway".equals(caller)
                    ||!"delegated".equals(mode)||!"PORTAL".equals(source)||!"PORTAL".equals(origin)
                    ||!status(call.resource().id(),call.resource().versionDigest()).equals(call.resource()))throw AuthorityProtocol.denied();
            return;
        }
        if(!kind&&!action)return;
        if(!kind||!action||!"engine".equals(call.target())||!"gateway".equals(caller)
                ||!"delegated".equals(mode)||!"PORTAL".equals(source)||!"PORTAL".equals(origin))throw AuthorityProtocol.denied();
    }
    private static boolean subject(String value){return value!=null&&!value.isBlank()&&value.length()<=128;}
}
