package cn.agentplatform.skill.api;

import cn.agentplatform.skill.infra.ArtifactStore;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 每次下载均重新校验已验JWT归属；流代理避免预签名URL向UI暴露bucket/object key。 */
@RestController
public class ArtifactController {
    private final ArtifactStore artifacts;

    public ArtifactController(ArtifactStore artifacts) {
        this.artifacts = artifacts;
    }

    @GetMapping("/admin/artifacts/{id}/download")
    public ResponseEntity<InputStreamResource> portal(@PathVariable String id) {
        return response(id, null);
    }

    @GetMapping("/v1/runs/{runId}/artifacts/{id}/download")
    public ResponseEntity<InputStreamResource> external(
            @PathVariable String runId, @PathVariable String id) {
        return response(id, runId);
    }

    private ResponseEntity<InputStreamResource> response(String id, String runId) {
        var download = artifacts.download(id, runId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.bytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + id + ".json\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(download.source()));
    }
}
