package cn.agentplatform.common.authority;
import cn.agentplatform.common.Actor;
import java.time.Instant;
import java.util.*;

public record DelegationRef(String value, SubjectKey subject, Instant expiresAt) {
    @Override public String toString(){return "DelegationRef[redacted]";}
}
