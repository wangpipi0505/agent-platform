package cn.agentplatform.common.authority;
import cn.agentplatform.common.Actor;
import java.time.Instant;
import java.util.*;

public record VerifiedAuthority(Actor actor,String caller,ServiceCall call,DelegationRef delegation,String authorizationVersion,Instant checkedAt,Instant expiresAt,String decisionId,OperationBinding operation) {}
