package cn.agentplatform.app.api;

import cn.agentplatform.app.application.IntegrationService;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.IntegrationCredentialCalls;
import cn.agentplatform.common.authority.CurrentAuthorityClient;
import cn.agentplatform.common.authority.ServiceAuthorization;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class IntegrationController {
    private final IntegrationService service;
    private final boolean iam;
    private final ObjectProvider<CurrentAuthorityClient> authority;

    public IntegrationController(IntegrationService service, @Value("${agent.security.mode:dev-hmac}") String mode,
            ObjectProvider<CurrentAuthorityClient> authority) {
        this.service = service;
        this.iam = "iam".equals(mode);
        this.authority = authority;
    }

    @GetMapping("/admin/apps/{code}/integration")
    public Object list(@PathVariable String code) {
        Actor.current().requireRole("ADMIN");
        return ApiResponse.ok(service.list(code));
    }

    @PostMapping("/admin/apps/{code}/integration/credentials")
    public Object issue(@PathVariable String code, @RequestBody Map<String, Object> b) {
        return ApiResponse.ok(service.issue(code, b));
    }

    @PostMapping("/admin/apps/{code}/integration/credentials/{biz}/revoke")
    public Object revoke(@PathVariable String code, @PathVariable String biz) {
        return ApiResponse.ok(service.revoke(code, biz));
    }

    @PutMapping("/admin/apps/{code}/integration/credentials/{biz}")
    public Object policy(
            @PathVariable String code,
            @PathVariable String biz,
            @RequestBody Map<String, Object> b) {
        return ApiResponse.ok(service.policy(code, biz, b));
    }

    @GetMapping(IntegrationCredentialCalls.ORIGINS)
    public Object origins(@RequestParam String origin, HttpServletRequest request) {
        if (iam) {
            ServiceAuthorization.requireWorkloadRequest(request, "gateway", IntegrationCredentialCalls.origin(origin));
            return ApiResponse.ok(service.checkOrigin(origin));
        }
        return ApiResponse.ok(service.origins(origin));
    }

    @PostMapping(IntegrationCredentialCalls.VERIFY)
    public Object verify(@PathVariable long id, @RequestBody ApiKeyVerification body, HttpServletRequest request) {
        ServiceAuthorization.requireWorkloadRequest(request, "gateway", IntegrationCredentialCalls.verify(id));
        return ApiResponse.ok(service.verifyApiKey(id, body.secret()));
    }

    public record ApiKeyVerification(String secret) {
        @Override public String toString() { return "ApiKeyVerification[redacted]"; }
    }

    @PostMapping(IntegrationCredentialCalls.TICKETS)
    public Object issueSource(@PathVariable long id, @RequestBody SourceRequest body, HttpServletRequest request) {
        ServiceAuthorization.requireWorkloadRequest(request, "gateway", IntegrationCredentialCalls.issue(id));
        var issuer = authority.getIfAvailable();
        if (issuer == null) throw new PlatformException(503, "INTEGRATION_IDENTITY_PROVIDER_UNAVAILABLE", "集成出票暂不可用");
        var claims = service.sourceClaims(id, body.secret(), body.claims(), body.origin());
        var ticket = issuer.issueIntegration(claims);
        return ApiResponse.ok(Map.of("ticket", ticket.value(), "expiresAt", ticket.expiresAt()));
    }

    public record SourceRequest(String secret, JsonNode claims, String origin) {
        @Override public String toString() { return "SourceRequest[redacted]"; }
    }

    @GetMapping("/internal/integration-credentials/{id}")
    public Object authenticate(@PathVariable long id) {
        return ApiResponse.ok(service.authenticate(id));
    }

    @GetMapping("/internal/integration/{code}/{biz}")
    public Object get(@PathVariable String code, @PathVariable String biz) {
        Actor.current().requireRole("SERVICE");
        return ApiResponse.ok(service.get(code, biz, true));
    }
}
