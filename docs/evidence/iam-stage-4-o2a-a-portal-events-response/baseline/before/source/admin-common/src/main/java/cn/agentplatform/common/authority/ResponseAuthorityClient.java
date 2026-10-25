package cn.agentplatform.common.authority;
import cn.agentplatform.common.Actor;
import java.time.Instant;
import java.util.*;

public interface ResponseAuthorityClient { ResponseLease open(VerifiedAuthority authority,ResponseBinding binding); ResponseLease check(ResponseLease lease); }
