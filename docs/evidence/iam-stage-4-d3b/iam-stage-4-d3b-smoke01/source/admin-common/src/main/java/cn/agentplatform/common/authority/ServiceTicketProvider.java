package cn.agentplatform.common.authority;
import cn.agentplatform.common.Actor;
import java.time.Instant;
import java.util.*;

public interface ServiceTicketProvider { ServiceTicket issue(VerifiedAuthority authority,ServiceCall call); }
