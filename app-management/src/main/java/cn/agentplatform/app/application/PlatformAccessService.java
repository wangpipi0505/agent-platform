package cn.agentplatform.app.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.authority.PortalTaskRoutes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Service
public class PlatformAccessService {
    private static final String ACTIVE_GRANTS = """
            from app.platform_user_role g
            left join app.role_capability c on c.role=g.platform_role and c.enabled
            left join app.capability_page p on p.capability=c.capability and p.enabled
            where g.tenant_id=? and g.subject=? and g.status='ACTIVE'
              and (g.expires_at is null or g.expires_at>statement_timestamp())
            """;

    public static final String VIEW = "agent.view";
    public static final String BUILD = "agent.build";
    public static final String PUBLISH = "agent.publish";
    public static final String GRANT = "auth.grant";

    private final JdbcTemplate db;

    public PlatformAccessService(JdbcTemplate db) {
        this.db = db;
    }

    public SessionAccess session(Actor identity) {
        var rows = access(identity);
        var roles = new TreeSet<String>();
        var globalRoles = new TreeSet<String>();
        var capabilities = new TreeSet<String>();
        var pages = new TreeSet<String>();
        for (var row : rows) {
            roles.add(row.role());
            if (row.applications().isEmpty()) globalRoles.add(row.role());
            if (row.capability() == null || (!row.capability().startsWith("agent.") && !row.applications().isEmpty()))
                continue;
            capabilities.add(row.capability());
            if (row.page() != null) pages.add(row.page());
        }
        return new SessionAccess(identity.tenantId(), identity.principalId(), roles, globalRoles, capabilities, pages, scope(rows, VIEW));
    }

    public ApplicationScope scope(Actor identity, String capability) {
        return scope(access(identity), capability);
    }

    private static ApplicationScope scope(List<AccessRow> rows, String capability) {
        var apps = new TreeSet<String>();
        for (var row : rows) {
            if (!capability.equals(row.capability())) continue;
            if (row.applications().isEmpty()) return new ApplicationScope(true, Set.of());
            apps.addAll(row.applications());
        }
        return new ApplicationScope(false, apps);
    }

    public record OwnedTaskDecision(String appCode, String permission, boolean reasonRequired,
                                    boolean canConfirm, boolean canExecute) {}

    public OwnedTaskDecision requireOwnedTask(Actor identity, String permission, String appCode) {
        var rows = access(identity);
        boolean execute = scope(rows, PortalTaskRoutes.EXECUTE).allows(appCode);
        boolean operate = scope(rows, PortalTaskRoutes.OPERATE).allows(appCode);
        boolean reasonRequired = !execute && operate;
        boolean allowedAsOperator = PortalTaskRoutes.EXECUTE.equals(permission) && reasonRequired;
        if (!scope(rows, VIEW).allows(appCode) || (!allowedAsOperator && !scope(rows, permission).allows(appCode)))
            throw new PlatformException(403, "PLATFORM_CAPABILITY_DENIED", "当前平台授权不允许此操作");
        return new OwnedTaskDecision(appCode, permission, reasonRequired,
                scope(rows, PortalTaskRoutes.CONFIRM).allows(appCode), execute || operate);
    }

    public void require(Actor identity, String capability, String appCode) {
        if (!scope(identity, capability).allows(appCode)) {
            throw new PlatformException(403, "PLATFORM_CAPABILITY_DENIED", "当前平台授权不允许此操作");
        }
    }

    private List<AccessRow> access(Actor identity) {
        return db.query("select g.platform_role,g.app_scope::text,c.capability,p.page_route " + ACTIVE_GRANTS,
                (row, index) -> new AccessRow(row.getString(1), scopeCodes(row.getString(2)),
                        row.getString(3), row.getString(4)), identity.tenantId(), identity.principalId());
    }

    private static List<String> scopeCodes(String value) {
        var codes = Json.read(value, String[].class);
        return List.of(codes);
    }

    private record AccessRow(String role, List<String> applications, String capability, String page) {}

    public record SessionAccess(String tenantId, String principalId, Set<String> roles, Set<String> globalRoles,
                                Set<String> capabilities, Set<String> pages, ApplicationScope viewScope) {
        public SessionAccess {
            roles = Set.copyOf(roles);
            globalRoles = Set.copyOf(globalRoles);
            capabilities = Set.copyOf(capabilities);
            pages = Set.copyOf(pages);
        }
    }

    public record ApplicationScope(boolean all, Set<String> codes) {
        public ApplicationScope {
            codes = Set.copyOf(codes);
        }

        public boolean allows(String code) {
            return all || (code != null && codes.contains(code));
        }
    }
}
