package cn.agentplatform.common.authority;
import cn.agentplatform.common.Actor;
import java.time.Instant;
import java.util.*;

public record ResponseLease(ResponseBinding binding,String sourceKind,String continuationRef,String openingDecisionId,Instant checkedUntil,Instant sourceExpiresAt,ResponseMaterialPermit completionPermit) { @Override public String toString(){return "ResponseLease[redacted]";} }
