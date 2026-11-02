package cn.agentplatform.skill.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.skill.infra.ArtifactStore;

import org.junit.jupiter.api.*;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** R08（SCN-013）：下载文件名按服务端可信contentType映射安全扩展名；主体保持artifactId，其余头不变。 */
class ArtifactDownloadNameTest {
    private ArtifactStore store;
    private ArtifactController controller;

    @BeforeEach
    void fresh() {
        store = mock(ArtifactStore.class);
        controller = new ArtifactController(store);
    }

    private ResponseEntity<InputStreamResource> download(String contentType, String runId) {
        when(store.download(eq("art_csv"), eq(runId)))
                .thenReturn(new ArtifactStore.Download(
                        new ByteArrayInputStream("a,b\n1,2".getBytes(StandardCharsets.UTF_8)),
                        7,
                        contentType,
                        "d".repeat(64)));
        return runId == null ? controller.portal("art_csv") : controller.external(runId, "art_csv");
    }

    private String disposition(String contentType) {
        return download(contentType, null).getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
    }

    @Test
    void csv与json与未知类型分别得到明确扩展名() {
        assertTrue(disposition("text/csv").endsWith("art_csv.csv\""));
        assertTrue(disposition("application/csv").endsWith("art_csv.csv\""));
        assertTrue(disposition("application/json").endsWith("art_csv.json\""));
        assertTrue(disposition("application/octet-stream").endsWith("art_csv.bin\""));
        // 带参数的contentType按基础类型映射，参数不进文件名。
        assertTrue(disposition("text/csv; charset=utf-8").endsWith("art_csv.csv\""));
    }

    @Test
    void 两条下载路由的头与授权归属参数保持() {
        for (String runId : new String[] {null, "r_external"}) {
            var response = download("text/csv", runId);
            assertEquals(200, response.getStatusCode().value());
            assertEquals(7, response.getHeaders().getContentLength());
            assertEquals("private, no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
            assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
            var disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
            assertTrue(disposition.startsWith("attachment;"));
            // 外部文件名不得拼进响应头：CSV不再叫JSON，也不出现任何外部名称。
            assertTrue(disposition.endsWith("art_csv.csv\""));
            assertFalse(disposition.contains("trajectory"));
            verify(store).download("art_csv", runId);
        }
    }
}
