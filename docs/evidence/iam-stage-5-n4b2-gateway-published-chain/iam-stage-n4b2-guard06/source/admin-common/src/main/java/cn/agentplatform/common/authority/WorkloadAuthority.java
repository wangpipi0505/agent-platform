package cn.agentplatform.common.authority;
import cn.agentplatform.common.Actor;
import java.time.Instant;
import java.util.*;

public record WorkloadAuthority(String caller,ServiceCall call,String decisionId,Instant checkedAt,Instant expiresAt) {}
