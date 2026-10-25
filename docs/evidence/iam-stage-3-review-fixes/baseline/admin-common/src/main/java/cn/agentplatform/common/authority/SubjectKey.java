package cn.agentplatform.common.authority;
/** Tenant and principal are a single identity; neither may be filled from another ceiling. */
public record SubjectKey(String tenantId, String principalId) {
    public SubjectKey {
        if (tenantId == null || tenantId.isBlank() || principalId == null || principalId.isBlank())
            throw new IllegalArgumentException("SUBJECT_BINDING_REQUIRED");
    }
}
