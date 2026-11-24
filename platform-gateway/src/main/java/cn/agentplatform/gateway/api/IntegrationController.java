package cn.agentplatform.gateway.api;

import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.gateway.application.IntegrationAccess;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

@RestController
public class IntegrationController {
    private final IntegrationAccess access;
    private final boolean iam;
    private final boolean allowEmbed;

    public IntegrationController(IntegrationAccess access,
            @Value("${agent.security.mode:dev-hmac}") String mode,
            @Value("${agent.security.allow-embed:true}") boolean allowEmbed) {
        this.access = access;
        this.iam = "iam".equals(mode);
        this.allowEmbed = allowEmbed;
    }

    @PostMapping(path = "/embed/ticket", consumes = "application/json")
    public Object ticket(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        if (iam && !allowEmbed) throw new PlatformException(404, "NOT_FOUND", "接口不存在");
        if (iam && (!"/embed/ticket".equals(request.getRequestURI()) || request.getQueryString() != null
                || Collections.list(request.getHeaders("Authorization")).size() > 1
                || Collections.list(request.getHeaders("Origin")).size() > 1))
            throw new PlatformException(400, "INVALID_CLAIMS", "集成出票请求形状无效");
        byte[] bytes = request.getInputStream().readNBytes(16385);
        if (bytes.length > 16384) throw new PlatformException(400, "CLAIMS_TOO_LARGE", "身份声明超过安全上限");
        JsonNode body;
        try {
            body = Json.read(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException badJson) {
            throw new PlatformException(400, "INVALID_CLAIMS", "身份声明须为有效JSON");
        }
        return ApiResponse.ok(access.embedTicket(request.getHeader("Authorization"), body, request.getHeader("Origin")));
    }
}
