package cn.agentplatform.common.authority;

import java.util.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Same-package fixture bridge to the real trusted decoder and actual signed authority HTTP. */
public final class PortalRunEventsAuthorityFixture {
    private PortalRunEventsAuthorityFixture() {}
    public static JwtDecoder decoder(RunEventReaderAuthorityFixture auth) {
        return new TrustedJwtDecoder(auth.iam.config("engine"),auth.iam.clock(),true);
    }
    public static String ticket(RunEventReaderAuthorityFixture auth,SubjectKey owner,String run,String sha) {
        auth.iam.registerObservation(owner,run,sha);
        return auth.gateway.exchange(auth.iam.portal(120),new ServiceCall("engine",RunObservationSelectors.EVENTS_ACTION,RunObservationSelectors.events(run,sha))).value();
    }
    public static void onEvents(RunEventReaderAuthorityFixture auth,java.util.function.Consumer<String> hook){auth.iam.observeEventsRequest=hook;}
    public static void shortEventsTicket(RunEventReaderAuthorityFixture auth){auth.iam.ticketMutation(c->{if(RunObservationSelectors.EVENTS_ACTION.equals(c.get("action")))c.put("exp",auth.iam.clock().instant().plusSeconds(2).getEpochSecond());});}
}
