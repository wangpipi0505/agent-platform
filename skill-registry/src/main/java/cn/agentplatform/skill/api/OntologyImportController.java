package cn.agentplatform.skill.api;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.application.OntologyManifestImports;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/** OR-S01：本体发布manifest导入入口。整批DRAFT草稿，发布走既有四道门。 */
@RestController
public class OntologyImportController {
    private final OntologyManifestImports imports;

    public OntologyImportController(OntologyManifestImports imports) {
        this.imports = imports;
    }

    @PostMapping("/admin/skills/import-ontology-manifests")
    public Object create(HttpServletRequest request) {
        // manifest本体可能较大（含Schema），沿用OpenAPI导入的2MiB请求体上限防任意分配。
        int max = 2_097_152;
        Map<String, Object> value;
        try {
            if (request.getContentLengthLong() > max)
                throw new PlatformException(413, "MANIFEST_REQUEST_SIZE", "导入请求最大2MiB");
            var bytes = request.getInputStream().readNBytes(max + 1);
            if (bytes.length > max)
                throw new PlatformException(413, "MANIFEST_REQUEST_SIZE", "导入请求最大2MiB");
            value = Json.mapper().readValue(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), Map.class);
            if (value == null)
                throw new PlatformException(400, "MANIFEST_PARSE", "导入请求不是合法JSON对象");
        } catch (PlatformException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PlatformException(400, "MANIFEST_PARSE", "导入请求不是合法JSON对象");
        }
        // R09/JOINT-005：业务调用必须在解析catch之外；重复导入等业务/存储错误不得伪称MANIFEST_PARSE。
        return ApiResponse.ok(imports.importManifests(value));
    }
}
