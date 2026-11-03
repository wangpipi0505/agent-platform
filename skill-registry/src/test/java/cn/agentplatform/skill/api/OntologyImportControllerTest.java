package cn.agentplatform.skill.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.application.OntologyManifestImports;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** R09/JOINT-005：解析catch只覆盖请求体读取；业务/存储错误不得伪称MANIFEST_PARSE。 */
class OntologyImportControllerTest {
    private final OntologyManifestImports imports = mock(OntologyManifestImports.class);
    private final OntologyImportController controller = new OntologyImportController(imports);

    private static MockHttpServletRequest body(String content) {
        var request = new MockHttpServletRequest("POST", "/admin/skills/import-ontology-manifests");
        request.setContent(content.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void 坏JSON仍是解析错误且不触业务() {
        var ex = assertThrows(PlatformException.class, () -> controller.create(body("{not-json")));
        assertEquals(400, ex.status());
        assertEquals("MANIFEST_PARSE", ex.code());
        verifyNoInteractions(imports);
    }

    @Test
    void 顶层null字面量按解析错误400拒绝且不触业务() {
        var ex = assertThrows(PlatformException.class, () -> controller.create(body("null")));
        assertEquals(400, ex.status());
        assertEquals("MANIFEST_PARSE", ex.code());
        verifyNoInteractions(imports);
    }

    @Test
    void 超限请求先拒绝且不触业务() {
        var request = body("{\"manifests\":[]}" + " ".repeat(3_000_000));
        var ex = assertThrows(PlatformException.class, () -> controller.create(request));
        assertEquals(413, ex.status());
        assertEquals("MANIFEST_REQUEST_SIZE", ex.code());
        verifyNoInteractions(imports);
    }

    @Test
    void 重复导入业务冲突按原码透出() {
        when(imports.importManifests(any()))
                .thenThrow(new PlatformException(409, "SKILL_CODE_CONFLICT", "同租户已存在同编码技能；重复导入请使用新编码"));
        var ex = assertThrows(PlatformException.class, () -> controller.create(body("{\"manifests\":[]}")));
        assertEquals(409, ex.status());
        assertEquals("SKILL_CODE_CONFLICT", ex.code());
    }

    @Test
    void 存储故障原样透出不改判解析错误() {
        when(imports.importManifests(any()))
                .thenThrow(new DataIntegrityViolationException("constraint", new SQLException("duplicate key", "23505")));
        assertThrows(DataIntegrityViolationException.class, () -> controller.create(body("{\"manifests\":[]}")));
    }

    @Test
    void 成功路径包ApiResponse() {
        when(imports.importManifests(any())).thenReturn(Map.of("drafts", List.of()));
        var response = controller.create(body("{\"manifests\":[]}"));
        assertInstanceOf(ApiResponse.class, response);
        assertEquals("OK", ((ApiResponse<?>) response).code());
        verify(imports).importManifests(any());
    }
}
