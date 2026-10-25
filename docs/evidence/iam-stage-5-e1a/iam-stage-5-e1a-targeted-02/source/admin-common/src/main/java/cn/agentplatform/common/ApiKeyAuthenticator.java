package cn.agentplatform.common;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;

/** 仅网关提供实现，长期ApiKey不会进入其他业务服务。 */
@FunctionalInterface
public interface ApiKeyAuthenticator {
    Authentication authenticate(HttpServletRequest request);
}
