package cn.agentplatform.common.authority;
import cn.agentplatform.common.Actor;
import java.time.Instant;
import java.util.*;

public record MaterialScopeV1(int schemaVersion,MaterialRef material,SubjectKey owner,String sourceDecisionId,String sourceAuthorizationVersion,String initialCeilingSha256,Set<String> requiredRoles,Map<String,Set<String>> requiredEntitlements,Set<String> requiredDataLabels,List<String> parentScopeSha256,String scopeSha256) {
 public MaterialScopeV1 {
  requiredRoles=requiredRoles==null?null:Set.copyOf(requiredRoles);
  if(requiredEntitlements!=null){var copy=new HashMap<String,Set<String>>();requiredEntitlements.forEach((k,v)->copy.put(k,Set.copyOf(v)));requiredEntitlements=Map.copyOf(copy);}
  requiredDataLabels=requiredDataLabels==null?null:Set.copyOf(requiredDataLabels);
  parentScopeSha256=parentScopeSha256==null?null:List.copyOf(parentScopeSha256);
 }
}
