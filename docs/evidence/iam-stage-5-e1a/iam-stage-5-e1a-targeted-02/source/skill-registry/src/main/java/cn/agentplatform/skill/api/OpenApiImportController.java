package cn.agentplatform.skill.api;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.application.OpenApiImports;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class OpenApiImportController {
    private final OpenApiImports imports;

    public OpenApiImportController(OpenApiImports imports) {
        this.imports = imports;
    }

    @PostMapping("/admin/skills/import-openapi/preview")
    public Object preview(HttpServletRequest request) {
        return ApiResponse.ok(imports.preview(read(request)));
    }

    @PostMapping("/admin/skills/import-openapi")
    public Object create(HttpServletRequest request) {
        return ApiResponse.ok(publicView(imports.create(read(request))));
    }

    private Map<String, Object> read(HttpServletRequest request) {
        // 同时限制Content-Length和分块流，避免在业务文档上限检查之前分配任意大的请求对象。
        int max = 2_097_152;
        if (request.getContentLengthLong() > max)
            throw new PlatformException(413, "OPENAPI_REQUEST_SIZE", "导入请求最大2MiB，文档最大1MiB");
        try {
            var bytes = request.getInputStream().readNBytes(max + 1);
            if (bytes.length > max)
                throw new PlatformException(413, "OPENAPI_REQUEST_SIZE", "导入请求最大2MiB，文档最大1MiB");
            Map<String, Object> value =
                    Json.mapper()
                            .readerFor(Map.class)
                            .with(
                                    com.fasterxml.jackson.databind.DeserializationFeature
                                            .FAIL_ON_TRAILING_TOKENS)
                            .readValue(bytes);
            if (value == null) throw new PlatformException(400, "OPENAPI_REQUEST", "导入请求必须为JSON对象");
            return value;
        } catch (java.io.IOException e) {
            throw new PlatformException(400, "OPENAPI_REQUEST", "请求读取失败，请重新上传文档");
        }
    }

    /** 认证引用只在管理员输入时使用，管理回包延续技能API的凭据隐藏约定。 */
    private Object publicView(Object value) {
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            map.forEach(
                    (key, item) -> {
                        if (key.equals("authRef"))
                            out.put("authConfigured", item != null && !item.toString().isBlank());
                        else out.put(key.toString(), publicView(item));
                    });
            return out;
        }
        if (value instanceof Collection<?> items)
            return items.stream().map(this::publicView).toList();
        return value;
    }
}
