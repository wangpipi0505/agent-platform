package cn.agentplatform.common.authority;
import cn.agentplatform.common.Actor;
import java.util.*;
import org.springframework.security.oauth2.jwt.Jwt;
/** Temporary compiling seam, replaced after observing the behavioral assertion failure. */
public final class PortalClaimMapper {
    public record Mapped(Actor actor, Set<String> scopes) {}
    public PortalClaimMapper(AuthorityProperties.Portal portal) {}
    public Mapped map(Jwt jwt) {
        return new Mapped(new Actor(jwt.getClaimAsString("tenant_id"),jwt.getSubject(),
            new HashSet<>(jwt.getClaimAsStringList("roles")),Map.of()),Set.of());
    }
}
