package cn.agentplatform.skill.api;

import cn.agentplatform.skill.infra.ArtifactStore;

import cn.agentplatform.common.PlatformException;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/** 每次下载均重新校验已验JWT归属；流代理避免预签名URL向UI暴露bucket/object key。 */
@RestController
public class ArtifactController {
    private final ArtifactStore artifacts;

    public ArtifactController(ArtifactStore artifacts) {
        this.artifacts = artifacts;
    }

    /** 文件输入先落受控产物，技能调用只传 artifactId，不在请求体里塞 base64。 */
    @PostMapping(value = "/admin/artifacts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty())
            throw new PlatformException(400, "ARTIFACT_EMPTY", "请选择非空文件");
        try (var source = file.getInputStream()) {
            return artifacts.upload(file.getContentType(), file.getSize(), source);
        } catch (IOException ex) {
            throw new PlatformException(400, "ARTIFACT_READ_FAILED", "文件读取失败，请重试");
        }
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
                // R08：扩展名只取服务端可信contentType映射，主体仍为artifactId，不拼接外部文件名。
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + id + extension(download.contentType()) + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(download.source()));
    }

    private static String extension(String contentType) {
        var base = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT).split(";", 2)[0].trim();
        return switch (base) {
            case "application/json" -> ".json";
            case "text/csv", "application/csv" -> ".csv";
            default -> ".bin";
        };
    }
}
