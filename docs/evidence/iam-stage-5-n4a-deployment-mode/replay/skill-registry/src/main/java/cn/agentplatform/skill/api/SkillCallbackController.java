package cn.agentplatform.skill.api;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.application.SkillCallbacks;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
public class SkillCallbackController {
    private final SkillCallbacks callbacks;

    public SkillCallbackController(SkillCallbacks callbacks) {
        this.callbacks = callbacks;
    }

    @PostMapping("/internal/skill-jobs/{callId}/callback")
    public Object receive(@PathVariable long callId, HttpServletRequest request)
            throws IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null
                || !(auth.getPrincipal() instanceof Jwt jwt)
                || !"service".equals(jwt.getClaimAsString("token_use"))
                || !jwt.getAudience().contains("agent-platform-internal")
                || !Actor.current().roles().contains("SERVICE"))
            throw new PlatformException(403, "CALLBACK_SERVICE_REQUIRED", "回调需要可信服务票据");
        return ApiResponse.ok(
                callbacks.receive(
                        callId,
                        request.getInputStream(),
                        request.getHeader("X-Callback-Timestamp"),
                        request.getHeader("X-Callback-Signature")));
    }
}
