package cn.agentplatform.skill.api;

import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.application.OnboardingSkillImports;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/** 内部 app 能力；没有公共管理入口或来自 gateway 的直接调用能力。 */
@RestController
public class OnboardingSkillImportsController {
    private final OnboardingSkillImports imports;

    public OnboardingSkillImportsController(OnboardingSkillImports imports) {
        this.imports = imports;
    }

    @PostMapping("/internal/onboarding-skill-imports")
    public Object create(HttpServletRequest request) {
        return ApiResponse.ok(imports.create(read(request)));
    }

    @GetMapping("/internal/onboarding-skill-imports/{sessionId}")
    public Object get(@PathVariable String sessionId, @RequestParam String planSha256) {
        return ApiResponse.ok(imports.get(sessionId, planSha256));
    }

    private Map<String, Object> read(HttpServletRequest request) {
        int max = 2_097_152;
        if (request.getContentLengthLong() > max) throw tooLarge();
        try {
            byte[] bytes = request.getInputStream().readNBytes(max + 1);
            if (bytes.length > max) throw tooLarge();
            Map<String, Object> value = Json.mapper().readerFor(Map.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readValue(bytes);
            if (value == null) throw new PlatformException(400, "ONBOARDING_IMPORT_INPUT", "导入请求必须是JSON对象");
            return value;
        } catch (IOException failure) {
            throw new PlatformException(400, "ONBOARDING_IMPORT_INPUT", "导入请求不是合法JSON对象");
        }
    }

    private PlatformException tooLarge() {
        return new PlatformException(413, "OPENAPI_REQUEST_SIZE", "导入请求最大2MiB，文档最大1MiB");
    }
}
