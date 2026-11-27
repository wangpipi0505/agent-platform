package cn.agentplatform.engine.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.authority.AuthorityContext;
import cn.agentplatform.common.authority.CurrentAuthorityClient;
import cn.agentplatform.common.authority.PlatformAccessClient;
import cn.agentplatform.common.authority.PortalRuntimeRoutes;
import cn.agentplatform.common.authority.SubjectKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agent.security.mode", havingValue = "iam")
public final class PortalRuntimeAccess {
    private final CurrentAuthorityClient authority;
    private final PlatformAccessClient app;

    public PortalRuntimeAccess(CurrentAuthorityClient authority, @Value("${agent.services.app}") String appUrl) {
        this.authority = authority;
        this.app = new PlatformAccessClient(appUrl);
    }

    public Grant current() {
        var current = AuthorityContext.current();
        if (!"gateway".equals(current.caller()) || !"PORTAL".equals(current.operation().originKind()))
            throw new PlatformException(403, "SYS-AUTH-403", "此入口仅接受门户授权");
        var actor = current.actor();
        var call = PortalRuntimeRoutes.accessCall(current.call().resource());
        var ticket = authority.forward(current, call);
        var raw = app.runtime(ticket, new SubjectKey(actor.tenantId(), actor.principalId()));
        if (!raw.isObject()) throw unavailable();
        var scopes = new LinkedHashMap<String, Scope>();
        raw.fields().forEachRemaining(field -> {
            var value = field.getValue();
            if (!value.path("all").isBoolean() || !value.path("codes").isArray()) throw unavailable();
            var codes = new TreeSet<String>();
            for (var code : value.path("codes")) {
                if (!code.isTextual() || !code.asText().matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))
                    throw unavailable();
                codes.add(code.textValue());
            }
            scopes.put(field.getKey(), new Scope(value.path("all").booleanValue(), codes));
        });
        return new Grant(scopes);
    }

    public record Scope(boolean all, Set<String> codes) {
        public static final Scope ALL = new Scope(true, Set.of());
        public static final Scope NONE = new Scope(false, Set.of());

        public Scope { codes = Set.copyOf(codes); }

        public boolean allows(String appCode) { return all || (appCode != null && codes.contains(appCode)); }
        public boolean empty() { return !all && codes.isEmpty(); }

        // Callers supply constants for column names. Application codes are always SQL parameters.
        public String predicate(String column, List<Object> args) {
            if (all) return "true";
            if (codes.isEmpty()) return "false";
            args.addAll(codes);
            return column + " in (" + String.join(",", Collections.nCopies(codes.size(), "?")) + ")";
        }
    }

    public record Grant(Map<String, Scope> roles) {
        public Grant { roles = Map.copyOf(roles); }

        public Scope view() { return scope(roles.keySet()); }

        public Scope scope(Set<String> names) {
            var codes = new TreeSet<String>();
            for (var role : names) {
                var current = roles.get(role);
                if (current == null) continue;
                if (current.all()) return Scope.ALL;
                codes.addAll(current.codes());
            }
            return new Scope(false, codes);
        }

        public Set<String> rolesFor(String appCode) {
            var names = new TreeSet<String>();
            roles.forEach((name, scope) -> {
                if (scope.allows(appCode)) names.add(name);
            });
            return Set.copyOf(names);
        }

        public Actor actorFor(Actor actor, String appCode) {
            return new Actor(actor.tenantId(), actor.principalId(), rolesFor(appCode), actor.attributes());
        }

        public void requireView(String appCode) {
            if (!view().allows(appCode))
                throw new PlatformException(403, "PLATFORM_CAPABILITY_DENIED", "当前平台授权不允许访问此应用");
        }
    }

    private static PlatformException unavailable() {
        return new PlatformException(503, "PLATFORM_ACCESS_UNAVAILABLE", "平台授权暂不可用");
    }
}
