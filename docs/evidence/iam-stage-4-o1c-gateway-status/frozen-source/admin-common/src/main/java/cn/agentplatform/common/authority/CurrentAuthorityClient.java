package cn.agentplatform.common.authority;
import cn.agentplatform.common.Actor;
import java.time.Instant;
import java.util.*;

public interface CurrentAuthorityClient {
 /** Fixed gateway capability. Ordinary authorize/currentOnly implementations cannot project a session. */
 default HttpCurrentAuthorityClient.PortalSessionAuthorization portalSession(String originalPortalToken){
  throw new cn.agentplatform.common.PlatformException(503,"PORTAL_SESSION_UNAVAILABLE","门户会话能力尚未就绪");
 }
 ServiceTicket client(ServiceCall call);
 ServiceTicket exchange(String subjectToken,ServiceCall call);
 /** Only the IAM gateway's original portal exchange can mint this short-lived local proof. */
 default HttpCurrentAuthorityClient.GatewayRunAuthorization exchangeGatewayPublishedRun(String originalPortalToken,ResourceBinding publishedRoot){
  throw AuthorityProtocol.denied();
 }
 default HttpCurrentAuthorityClient.GatewayObservationQueryAuthorization exchangeGatewayRunObservationQuery(String originalPortalToken,SubjectKey subject,String publicRunId){throw AuthorityProtocol.denied();}
 default HttpCurrentAuthorityClient.GatewayStatusAuthorization exchangeGatewayRunStatus(String originalPortalToken,ResourceBinding runSnapshot){throw AuthorityProtocol.denied();}
 ServiceTicket resume(DelegationRef delegation,ServiceCall call);
 ServiceTicket forward(VerifiedAuthority authority,ServiceCall call);
 VerifiedAuthority authorize(String serviceTicket,ServiceCall call);
 default HttpCurrentAuthorityClient.RetainedAuthorization authorizeRetained(String serviceTicket,ServiceCall call){
  return HttpCurrentAuthorityClient.RetainedAuthorization.currentOnly(authorize(serviceTicket,call));
 }
 WorkloadAuthority authorizeWorkload(String serviceTicket,ServiceCall call);
 VerifiedAuthority approver(DelegationRef delegation,SubjectKey approver,ResourceBinding approval);
}
