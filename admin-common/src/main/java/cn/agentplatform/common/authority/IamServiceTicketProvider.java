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
        // 目录只授予 target/action 能力；具体资源只能沿用当前已验操作，不能从出站参数伪造。
        var bound=call.resource()==null
                ?new ServiceCall(call.target(),call.action(),authority.operation().resource()):call;
        return currentAuthority.forward(authority,bound);
    }
}
