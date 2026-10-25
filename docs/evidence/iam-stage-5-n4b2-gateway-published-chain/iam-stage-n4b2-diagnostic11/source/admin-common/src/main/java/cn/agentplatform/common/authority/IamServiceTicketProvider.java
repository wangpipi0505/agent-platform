package cn.agentplatform.common.authority;

import java.time.Clock;
import java.util.Objects;

public final class IamServiceTicketProvider implements ServiceTicketProvider {
    private final Clock clock;
    private final CurrentAuthorityClient currentAuthority;
    public IamServiceTicketProvider(Clock clock,CurrentAuthorityClient currentAuthority){
        this.clock=Objects.requireNonNull(clock);this.currentAuthority=Objects.requireNonNull(currentAuthority);
    }
    @Override public ServiceTicket issue(VerifiedAuthority authority,ServiceCall call){
        AuthorityProtocol.requireCurrent(authority,clock);
        return currentAuthority.forward(authority,call);
    }
}
