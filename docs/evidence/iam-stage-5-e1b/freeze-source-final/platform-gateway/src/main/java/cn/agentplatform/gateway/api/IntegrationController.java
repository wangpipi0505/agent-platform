package cn.agentplatform.gateway.api;

import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.gateway.application.IntegrationAccess;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.web.bind.annotation.*;

@RestController
public class IntegrationController {
    private final IntegrationAccess access;

    public IntegrationController(IntegrationAccess access) {
        this.access = access;
    }

    @PostMapping("/embed/ticket")
    public Object ticket(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestBody JsonNode body) {
        return ApiResponse.ok(access.ticket(authorization, body, origin));
    }
}
