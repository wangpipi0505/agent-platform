package cn.agentplatform.common.authority;
/** A concrete, versioned resource. A route template is never a resource binding. */
public record ResourceBinding(String kind, String id, String versionDigest) {
    public ResourceBinding {
        if (kind == null || kind.isBlank() || id == null || id.isBlank()
                || versionDigest == null || versionDigest.isBlank())
            throw new IllegalArgumentException("RESOURCE_BINDING_REQUIRED");
    }
}
