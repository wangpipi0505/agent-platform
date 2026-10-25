package cn.agentplatform.common.authority;
/** A null resource denotes only a catalog capability; receivers must bind concrete resources. */
public record ServiceCall(String target, String action, ResourceBinding resource) {
    public ServiceCall {
        if (target == null || target.isBlank() || action == null || action.isBlank())
            throw new IllegalArgumentException("SERVICE_CALL_REQUIRED");
    }
}
