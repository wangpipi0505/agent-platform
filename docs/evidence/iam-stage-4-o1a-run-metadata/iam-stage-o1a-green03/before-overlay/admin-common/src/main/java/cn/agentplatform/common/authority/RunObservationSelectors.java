package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import java.util.*;

/** A versioned subject/path query; its digest describes no persisted run payload. */
public final class RunObservationSelectors {
    public static final String KIND="run-observation-selector-v1", ACTION="run.observation.metadata.read";
    private RunObservationSelectors() {}
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
        if(!kind&&!action)return;
        if(!kind||!action||!"engine".equals(call.target())||!"gateway".equals(caller)
                ||!"delegated".equals(mode)||!"PORTAL".equals(source)||!"PORTAL".equals(origin))throw AuthorityProtocol.denied();
    }
    private static boolean subject(String value){return value!=null&&!value.isBlank()&&value.length()<=128;}
}
