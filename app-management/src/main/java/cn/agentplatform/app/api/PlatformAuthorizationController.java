package cn.agentplatform.app.api;

import cn.agentplatform.app.application.PlatformAccessService;
import cn.agentplatform.app.application.PlatformGrantService;
import cn.agentplatform.app.infra.PlatformUserDirectory;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.common.authority.AuthorityContext;
import cn.agentplatform.common.authority.PlatformBuildRoutes;
import cn.agentplatform.common.authority.SubjectKey;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
public class PlatformAuthorizationController {
    private final PlatformAccessService access;
    private final PlatformGrantService grants;
    private final PlatformUserDirectory directory;

    public PlatformAuthorizationController(PlatformAccessService access, PlatformGrantService grants,
                                           PlatformUserDirectory directory) {
        this.access = access;
        this.grants = grants;
        this.directory = directory;
    }

    @GetMapping("/internal/platform-authorization/session")
    public Object session() {
        var identity = Actor.current();
        var subject = new SubjectKey(identity.tenantId(), identity.principalId());
        AuthorityContext.requireFor(identity, PlatformBuildRoutes.resource(subject));
        return ApiResponse.ok(access.session(identity));
    }

    @GetMapping("/admin/platform-authorization/users")
    public Object users(@RequestParam String q) {
        var actor = Actor.current();
        access.require(actor, PlatformAccessService.GRANT, null);
        return ApiResponse.ok(directory.search(actor.tenantId(), q));
    }

    @GetMapping("/admin/platform-authorization/grants")
    public Object grants(@RequestParam String subject) {
        return ApiResponse.ok(grants.grants(Actor.current(), subject));
    }

    @PutMapping("/admin/platform-authorization/grants")
    public Object save(@RequestBody GrantChange change) {
        var actor = Actor.current();
        access.require(actor, PlatformAccessService.GRANT, null);
        directory.requireActive(actor.tenantId(), change.subject());
        grants.save(actor, change.subject(), change.role(), change.appScope(), change.expiresAt());
        return ApiResponse.ok(Map.of("saved", true));
    }

    @DeleteMapping("/admin/platform-authorization/grants")
    public Object revoke(@RequestBody GrantKey grant) {
        grants.revoke(Actor.current(), grant.subject(), grant.role());
        return ApiResponse.ok(Map.of("revoked", true));
    }

    @GetMapping("/admin/platform-authorization/audit")
    public Object audit(@RequestParam String subject) {
        return ApiResponse.ok(grants.audit(Actor.current(), subject));
    }

    @GetMapping("/admin/platform-authorization/configuration-audit")
    public Object configurationAudit() {
        return ApiResponse.ok(grants.configurationAudit(Actor.current()));
    }

    public record GrantChange(String subject, String role, List<String> appScope, Instant expiresAt) {}
    public record GrantKey(String subject, String role) {}
}
