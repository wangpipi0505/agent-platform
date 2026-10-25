package cn.agentplatform.common.authority;

import java.util.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Normal G exchange and E receiver; no signing API or caller-constructed successful claims. */
public final class PortalChildEventsAuthorityFixture implements AutoCloseable {
    public final RunEventReaderAuthorityFixture auth;
    public final GatewayPortalEventsResponse parent;
    public final String ticket;
    public final ServiceCall call;
    public PortalChildEventsAuthorityFixture(RunEventReaderAuthorityFixture auth,SubjectKey owner,String run,String sha,long cursor){
        this.auth=auth;auth.iam.registerObservation(owner,run,sha);call=new ServiceCall("engine",RunObservationSelectors.EVENTS_ACTION,RunObservationSelectors.events(run,sha));
        String source=auth.iam.portal(120);
        try(var opening=auth.gateway.beginGatewayPortalEvents(source,run,cursor)){
            parent=auth.gateway.openGatewayPortalEvents(opening,call.resource(),()->{});
        }
        ticket=auth.gateway.exchangePortalEventsChild(source,parent).ticket().value();
    }
    public PortalEventsResponse open(){
        var initial=auth.engine.authorizeRetained(ticket,call);
        try(var frame=AuthorityContext.bindRetained(initial,()->auth.engine.authorizeRetained(ticket,call))){return auth.engine.openPortalEventsChild(initial,parent.currentCapture().initialCursor(),()->{});}
    }
    public static JwtDecoder decoder(RunEventReaderAuthorityFixture auth){return new TrustedJwtDecoder(auth.iam.config("engine"),auth.iam.clock(),true);}
    public void revoke(){auth.iam.engineEventsRevoked=true;}
    public void close(){parent.close();}
}
