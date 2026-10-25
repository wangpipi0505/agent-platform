package cn.agentplatform.common;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.*;

/** 身份只能来自SecurityFilterChain已经验签的JWT，不采信body、query或自报身份头。 */
public record Actor(
        String tenantId,
        String principalId,
        Set<String> roles,
        Map<String, Set<String>> attributes) {
    public Actor {
        if (tenantId == null || tenantId.isBlank() || principalId == null || principalId.isBlank())
            throw new PlatformException(401, "SYS-AUTH-401", "身份缺少租户或用户");
        roles = Set.copyOf(roles == null ? Set.of() : roles);
        Map<String, Set<String>> safe = new HashMap<>();
        if (attributes != null) attributes.forEach((k, v) -> safe.put(k, Set.copyOf(v)));
        attributes = Map.copyOf(safe);
    }

    public static Actor current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt))
            throw new PlatformException(401, "SYS-AUTH-401", "请先登录");
        // A machine client cannot be projected onto a tenant/user, even if a future caller
        // accidentally supplies user-shaped claims beside its verified workload ticket.
        if ("workload".equals(jwt.getClaims().get("authority_mode")))
            throw new PlatformException(403, "SYS-AUTH-403", "机器能力不能映射为用户身份");
        if ("delegated".equals(jwt.getClaimAsString("authority_mode"))) {
            var current = cn.agentplatform.common.authority.AuthorityContext.current().actor();
            if (!current.tenantId().equals(jwt.getClaimAsString("tenant_id"))
                    || !current.principalId().equals(jwt.getSubject()))
                throw new PlatformException(403, "SYS-AUTH-403", "当前授权主体不一致");
            return current;
        }
        Set<String> roles = new HashSet<>();
        Object roleClaim = jwt.getClaims().get("roles");
        if (roleClaim instanceof Collection<?> list)
            list.forEach(r -> roles.add(String.valueOf(r)));
        if ("RS256".equals(jwt.getHeaders().get("alg")))
            roles.removeAll(Set.of("SERVICE", "GATEWAY_AUTH"));
        Map<String, Set<String>> attrs = new HashMap<>();
        Object attrClaim = jwt.getClaims().get("attributes");
        if (attrClaim instanceof Map<?, ?> map)
            map.forEach(
                    (k, v) -> {
                        Set<String> values = new HashSet<>();
                        if (v instanceof Collection<?> list)
                            list.forEach(x -> values.add(String.valueOf(x)));
                        else if (v != null) values.add(String.valueOf(v));
                        attrs.put(String.valueOf(k), values);
                    });
        return new Actor(jwt.getClaimAsString("tenant_id"), jwt.getSubject(), roles, attrs);
    }

    public void requireRole(String role) {
        if (!roles.contains("ADMIN") && !roles.contains(role))
            throw new PlatformException(403, "SYS-APP-4031", "当前账号没有此操作权限");
    }
}
