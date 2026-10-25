from pathlib import Path
base=Path('admin-common/src/main/java/cn/agentplatform/common/authority')
types={
'DelegationRef':'public record DelegationRef(String value, SubjectKey subject, Instant expiresAt) {}',
'OperationBinding':'public record OperationBinding(String operationId,String sourceKind,String originKind,Instant issuedAt,Instant expiresAt,String grantRef,SubjectKey subject,ResourceBinding resource,String ceilingSha256) {}',
'VerifiedAuthority':'public record VerifiedAuthority(Actor actor,String caller,ServiceCall call,DelegationRef delegation,String authorizationVersion,Instant checkedAt,Instant expiresAt,String decisionId,OperationBinding operation) {}',
'WorkloadAuthority':'public record WorkloadAuthority(String caller,ServiceCall call,String decisionId,Instant checkedAt,Instant expiresAt) {}',
'ServiceTicket':'public record ServiceTicket(String value,Instant expiresAt,OperationBinding operation) { @Override public String toString(){return "ServiceTicket[redacted]";} }',
'CurrentAuthorityClient':'''public interface CurrentAuthorityClient {
 ServiceTicket client(ServiceCall call);
 ServiceTicket exchange(String subjectToken,ServiceCall call);
 ServiceTicket resume(DelegationRef delegation,ServiceCall call);
 ServiceTicket forward(VerifiedAuthority authority,ServiceCall call);
 VerifiedAuthority authorize(String serviceTicket,ServiceCall call);
 WorkloadAuthority authorizeWorkload(String serviceTicket,ServiceCall call);
 VerifiedAuthority approver(DelegationRef delegation,SubjectKey approver,ResourceBinding approval);
}''',
'ServiceTicketProvider':'public interface ServiceTicketProvider { ServiceTicket issue(VerifiedAuthority authority,ServiceCall call); }',
'ResponseBinding':'public record ResponseBinding(String responseId,String kind,ResourceBinding resource,String requestSha256,Instant hardDeadline) {}',
'ResponseLease':'public record ResponseLease(ResponseBinding binding,String sourceKind,String continuationRef,String openingDecisionId,Instant checkedUntil,Instant sourceExpiresAt,ResponseMaterialPermit completionPermit) { @Override public String toString(){return "ResponseLease[redacted]";} }',
'ResponseAuthorityClient':'public interface ResponseAuthorityClient { ResponseLease open(VerifiedAuthority authority,ResponseBinding binding); ResponseLease check(ResponseLease lease); }',
'ResponseMaterial':'public record ResponseMaterial(Object payload,MaterialScopeV1 scope,String responseId) {}',
'MaterialRef':'public record MaterialRef(String producer,String kind,String id,String version,String payloadSha256) {}',
'MaterialScopeV1':'''public record MaterialScopeV1(int schemaVersion,MaterialRef material,SubjectKey owner,String sourceDecisionId,String sourceAuthorizationVersion,String initialCeilingSha256,Set<String> requiredRoles,Map<String,Set<String>> requiredEntitlements,Set<String> requiredDataLabels,List<String> parentScopeSha256,String scopeSha256) {
 public MaterialScopeV1 {
  requiredRoles=requiredRoles==null?null:Set.copyOf(requiredRoles);
  if(requiredEntitlements!=null){var copy=new HashMap<String,Set<String>>();requiredEntitlements.forEach((k,v)->copy.put(k,Set.copyOf(v)));requiredEntitlements=Map.copyOf(copy);}
  requiredDataLabels=requiredDataLabels==null?null:Set.copyOf(requiredDataLabels);
  parentScopeSha256=parentScopeSha256==null?null:List.copyOf(parentScopeSha256);
 }
}''',
'AuthorizedMaterial':'public record AuthorizedMaterial(Object payload,MaterialScopeV1 scope) {}',
}
for name,body in types.items():
 (base/(name+'.java')).write_text('package cn.agentplatform.common.authority;\nimport cn.agentplatform.common.Actor;\nimport java.time.Instant;\nimport java.util.*;\n\n'+body+'\n',encoding='utf-8')
