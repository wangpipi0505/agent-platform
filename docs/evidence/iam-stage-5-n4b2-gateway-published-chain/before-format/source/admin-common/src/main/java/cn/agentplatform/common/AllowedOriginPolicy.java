package cn.agentplatform.common;

/** 网关从权威凭据白名单判断动态来源；其他服务仅允许显式配置的门户来源。 */
@FunctionalInterface
public interface AllowedOriginPolicy {
    boolean allowed(String origin);
}
