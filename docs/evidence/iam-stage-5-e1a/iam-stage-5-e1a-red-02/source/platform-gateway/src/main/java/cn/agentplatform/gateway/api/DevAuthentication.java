package cn.agentplatform.gateway.api;

import cn.agentplatform.common.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 只在显式本地开发配置下启用，角色由服务配置固定，绝不接受客户端自报权限。 */
@RestController
@ConditionalOnProperty(name = "agent.dev-auth", havingValue = "true")
public class DevAuthentication {
    @PostMapping("/auth/dev-token")
    public Object token(HttpServletRequest request) {
        String host = request.getServerName();
        if (!Set.of("127.0.0.1", "localhost", "::1").contains(host))
            throw new PlatformException(403, "DEV_AUTH_LOCAL_ONLY", "开发身份仅供本机使用");
        Actor actor =
                new Actor(
                        "local-v12",
                        "developer-v12",
                        Set.of("ADMIN", "EDITOR", "APPROVER"),
                        // 独立测试范围，仅由显式loopback开发入口固定签发；客户端不能自报扩大。
                        Map.of("region", Set.of("LOCAL")));
        return ApiResponse.ok(
                Map.of(
                        "accessToken",
                        ServiceTokens.userToken(actor, 3600),
                        "tokenType",
                        "Bearer",
                        "expiresIn",
                        3600,
                        "user",
                        Map.of(
                                "tenantId",
                                actor.tenantId(),
                                "principalId",
                                actor.principalId(),
                                "name",
                                "本地开发者",
                                "roles",
                                actor.roles())));
    }
}
