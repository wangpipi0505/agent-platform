package cn.agentplatform.engine.application;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.infra.RunAuthorityStore;
import java.time.*;
public final class RunAuthority {
 public RunAuthority(RunAuthorityStore store,HttpCurrentAuthorityClient client,Clock clock,Duration backoff){}
 public VerifiedAuthority current(long runId,ServiceCall call){throw new UnsupportedOperationException("E1B_CURRENT_NOT_IMPLEMENTED");}
}
