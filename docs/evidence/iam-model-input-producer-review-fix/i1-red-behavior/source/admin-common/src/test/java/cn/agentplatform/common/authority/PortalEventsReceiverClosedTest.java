package cn.agentplatform.common.authority;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** C migrates the all-closed stage assertion. Missing handlers and aliases are verified by the
 * real engine HTTP tests; this common test owns only the bidirectional fixed purpose rule. */
class PortalEventsReceiverClosedTest {
    private static final String RUN="r_"+"a".repeat(32),SHA=Digest.sha256("accepted");
    @Test void fixedPortalEventsPurposeAndStatusRemainPairedInBothDirections(){
        for(String action:List.of(RunObservationSelectors.EVENTS_ACTION,RunObservationSelectors.STATUS_ACTION))
            RunObservationSelectors.requirePurpose(new ServiceCall("engine",action,RunObservationSelectors.events(RUN,SHA)),"gateway","delegated","PORTAL","PORTAL");
        RunObservationSelectors.requirePurpose(new ServiceCall("engine",RunObservationSelectors.ACTION,RunObservationSelectors.metadata(new SubjectKey("tenant-a","alice"),RUN)),"gateway","delegated","PORTAL","PORTAL");
    }
    @Test void wrongKindActionCallerModeSourceOriginTargetAndExecutionCannotBorrowEventsPurpose(){
        var resource=RunObservationSelectors.events(RUN,SHA);
        for(var call:List.of(new ServiceCall("engine",RunObservationSelectors.EVENTS_ACTION,new ResourceBinding("run",RUN,SHA)),new ServiceCall("engine",RunObservationSelectors.ACTION,resource),new ServiceCall("engine","proxy.engine.post.RunController.run",resource),new ServiceCall("app",RunObservationSelectors.EVENTS_ACTION,resource)))
            assertThatThrownBy(()->RunObservationSelectors.requirePurpose(call,"gateway","delegated","PORTAL","PORTAL")).isInstanceOf(PlatformException.class);
        var call=new ServiceCall("engine",RunObservationSelectors.EVENTS_ACTION,resource);
        for(String[] route:List.of(new String[]{"app","delegated","PORTAL","PORTAL"},new String[]{"gateway","workload","PORTAL","PORTAL"},new String[]{"gateway","delegated","DELEGATED","PORTAL"},new String[]{"gateway","delegated","PORTAL","INTEGRATION"}))
            assertThatThrownBy(()->RunObservationSelectors.requirePurpose(call,route[0],route[1],route[2],route[3])).isInstanceOf(PlatformException.class);
    }
}
