package cn.agentplatform.common.authority;
import cn.agentplatform.common.Actor;
import java.time.Instant;
import java.util.*;

public record OperationBinding(String operationId,String sourceKind,String originKind,Instant issuedAt,Instant expiresAt,String grantRef,SubjectKey subject,ResourceBinding resource,String ceilingSha256) {
    @Override public String toString(){return "OperationBinding[id="+operationId+",source="+sourceKind+"]";}
}
