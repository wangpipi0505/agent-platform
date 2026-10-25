package cn.agentplatform.common.authority;
import cn.agentplatform.common.Actor;
import java.time.Instant;
import java.util.*;

public interface CurrentAuthorityClient {
 ServiceTicket client(ServiceCall call);
 ServiceTicket exchange(String subjectToken,ServiceCall call);
 ServiceTicket resume(DelegationRef delegation,ServiceCall call);
 ServiceTicket forward(VerifiedAuthority authority,ServiceCall call);
 VerifiedAuthority authorize(String serviceTicket,ServiceCall call);
 default HttpCurrentAuthorityClient.RetainedAuthorization authorizeRetained(String serviceTicket,ServiceCall call){
  return HttpCurrentAuthorityClient.RetainedAuthorization.currentOnly(authorize(serviceTicket,call));
 }
 WorkloadAuthority authorizeWorkload(String serviceTicket,ServiceCall call);
 VerifiedAuthority approver(DelegationRef delegation,SubjectKey approver,ResourceBinding approval);
}
