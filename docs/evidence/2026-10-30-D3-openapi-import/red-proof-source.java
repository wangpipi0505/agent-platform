package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 反证运行（scratch，仅存在于 target/red-proof，不提交）：初版选型假设 operation20
 * （GET /api/v1/space/visibility/task-results）可转换，对真实规格运行后转红，用来证明
 * "该操作被转换器拒绝"是真实事实，而不是终版测试迁就实现后编出来的说法。
 */
class SpaceGroundOpenApiImportRedProofTest {
    @Test
    void visibilityTaskResultsOperationIsSupported() throws Exception {
        var jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject("p")
                .claim("tenant_id", "t").claim("roles", List.of("EDITOR")).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            String doc = Files.readString(
                    Path.of("G:/5-YG/space-ground-visible/docs/openapi/openapi.json"),
                    StandardCharsets.UTF_8);
            var preview = new OpenApiImports(mock(SkillCatalog.class))
                    .preview(Map.of("document", doc));
            var ops = (List<Map<String, Object>>) preview.get("operations");
            var op = ops.stream()
                    .filter(o -> "operation20".equals(o.get("operationId")))
                    .findFirst().orElseThrow();
            assertEquals(true, op.get("supported"), "diagnostics=" + op.get("diagnostics"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
