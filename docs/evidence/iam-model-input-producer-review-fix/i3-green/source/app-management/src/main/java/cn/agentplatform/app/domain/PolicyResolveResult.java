package cn.agentplatform.app.domain;
import java.util.*;
public record PolicyResolveResult(int schemaVersion,long credentialId,String tenantId,String appCode,
    String bizSystemCode,String userId,String principalId,boolean active,boolean subjectAllowed,
    Set<String> roleCeiling,Map<String,Set<String>> attributeCeiling,Set<String> dataLabels,
    Set<String> allowedOrigins,String policyVersion,String policyDigest,String nonce) {}
