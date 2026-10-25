package cn.agentplatform.common.authority;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AuthorityPolicyTest {
    @Test void routeCannotBorrowCallerOrMethod() {
        var policy = new ServiceRoutePolicy();
        assertThat(policy.require("engine", "skill", "GET", "/internal/skills/{code}").action())
                .isEqualTo("skill.metadata.read");
        assertThatThrownBy(() -> policy.require("monitor", "skill", "GET", "/internal/skills/{code}"))
                .isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> policy.require("engine", "skill", "POST", "/internal/skills/{code}"))
                .isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> policy.require("engine", "unknown", "GET", "/internal/skills/{code}"))
                .isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> policy.require("engine", "skill", "GET", "/internal/skills/payroll"))
                .isInstanceOf(PlatformException.class);
    }

    @Test void adminCannotGrantServiceCapability() {
        var jwt = Jwt.withTokenValue("test").header("alg", "test").subject("admin")
                .claim("tenant_id", "t").claim("roles", List.of("ADMIN")).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            Actor.current().requireRole("EDITOR");
            assertThatThrownBy(() -> new ServiceRoutePolicy().require("monitor", "engine", "POST", "/internal/tool-calls/{id}/submitted"))
                    .isInstanceOf(PlatformException.class);
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test void cannotRestoreRemovedRegion() {
        assertThat(ClaimCeiling.intersect(Set.of("A", "B"), Set.of("A"))).containsExactly("A");
        assertThat(ClaimCeiling.intersect(Set.of("A"), Set.of())).isEmpty();
        assertThat(ClaimCeiling.intersect(null, Set.of("A"))).isEmpty();
        assertThatThrownBy(() -> ClaimCeiling.intersect(Set.of("A"), Set.of("A")).add("B"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void labelsRemainObligationsAndMissingPermissionDoesNotAddAuthority() {
        var result = ClaimCeiling.attributes(
                Map.of("region", Set.of("A", "B"), "dataLabels", Set.of("SECRET"), "userId", Set.of("u"), "bizSystemCode", Set.of("b"), "unknown", Set.of("x")),
                Map.of("dataLabels", Set.of("INTERNAL"), "userId", Set.of("u"), "bizSystemCode", Set.of("b")));
        assertThat(result.get("region")).isEmpty();
        assertThat(result.get("dataLabels")).containsExactlyInAnyOrder("SECRET", "INTERNAL");
        assertThat(result).doesNotContainKey("unknown");
        assertThatThrownBy(() -> result.put("region", Set.of("B"))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.get("dataLabels").clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(ClaimCeiling.attributes(Map.of("dataLabels", Set.of("SECRET")), Map.of()).get("dataLabels"))
                .containsExactly("SECRET");
    }

    @Test void ownershipMustMatchAndCannotBeCompletedFromOneSide() {
        for (String key : List.of("bizSystemCode", "userId")) {
            assertThatThrownBy(() -> ClaimCeiling.attributes(Map.of(key, Set.of("a")), Map.of(key, Set.of("b"))))
                    .isInstanceOf(PlatformException.class);
            assertThatThrownBy(() -> ClaimCeiling.attributes(Map.of(), Map.of(key, Set.of("a"))))
                    .isInstanceOf(PlatformException.class);
            assertThatThrownBy(() -> ClaimCeiling.attributes(Map.of(key, Set.of("a", "b")), Map.of(key, Set.of("a", "b"))))
                    .isInstanceOf(PlatformException.class);
        }
        var key = new SubjectKey("tenant", "principal");
        assertThat(ClaimCeiling.subject(key, key)).isEqualTo(key);
        assertThatThrownBy(() -> ClaimCeiling.subject(key, new SubjectKey("other", "principal")))
                .isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> ClaimCeiling.subject(key, null)).isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> new SubjectKey("", "p")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceBinding("run", "1", "")).isInstanceOf(IllegalArgumentException.class);
    }
}
