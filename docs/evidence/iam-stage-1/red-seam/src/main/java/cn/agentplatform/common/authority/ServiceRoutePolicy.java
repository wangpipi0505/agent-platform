package cn.agentplatform.common.authority;
public final class ServiceRoutePolicy {
    public ServiceCall require(String caller, String target, String method, String template) {
        return new ServiceCall(target, "skill.metadata.read", null);
    }
}
