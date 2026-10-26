package cn.agentplatform.model.infra;

import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.model.api.ModelController;
import cn.agentplatform.model.application.FixturePolicy;

import org.springframework.core.MethodParameter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AP-S06 状态语义：管理面供应商/条目回包必须携带 source=FIXTURE|MANAGED，前端据此把演示
 * 行渲染成可区分标识，而不是把 fixture 误读成“健康可用”。
 *
 * <p>以 ResponseBodyAdvice 挂在 ModelController 回包上（而非改写仓储层），是因为供应商/条目
 * 读取路径集中在该控制器；只改写包含 code+baseUrl 的供应商形状与包含 provider 子对象的条目
 * 形状，其余回包（创建结果、统计、调用）原样透传。</p>
 */
@RestControllerAdvice
public class FixtureViewMarker implements ResponseBodyAdvice<Object> {
    private final FixturePolicy policy;

    public FixtureViewMarker(FixturePolicy policy) {
        this.policy = policy;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class converterType) {
        return ModelController.class.equals(returnType.getContainingClass());
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            org.springframework.http.MediaType selectedContentType,
            Class selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body instanceof ApiResponse<?> api) return annotate(api);
        return body;
    }

    /** 测试与回包共用同一转换：ApiResponse 内的供应商/条目形状补 source 标记。 */
    public ApiResponse<?> annotate(ApiResponse<?> body) {
        if (body == null || body.data() == null) return body;
        return new ApiResponse<>(body.code(), body.message(), mark(body.data()), body.traceId());
    }

    private Object mark(Object data) {
        if (data instanceof List<?> list) return list.stream().map(this::mark).toList();
        if (data instanceof Map<?, ?> map) return markMap(map);
        return data;
    }

    private Object markMap(Map<?, ?> raw) {
        var result = new java.util.LinkedHashMap<String, Object>();
        raw.forEach((k, v) -> result.put(String.valueOf(k), v));
        Object provider = result.get("provider");
        if (provider instanceof Map<?, ?> nested) {
            // 条目视图：来源跟随其供应商；两层都带 source，避免调用方二次拼接。
            var markedProvider = new java.util.LinkedHashMap<String, Object>();
            nested.forEach((k, v) -> markedProvider.put(String.valueOf(k), v));
            String code = Objects.toString(markedProvider.get("code"), "");
            String source = fixture(code) ? "FIXTURE" : "MANAGED";
            markedProvider.put("source", source);
            result.put("provider", markedProvider);
            result.put("source", source);
            return result;
        }
        if (result.containsKey("code") && result.containsKey("baseUrl")) {
            result.put("source", fixture(Objects.toString(result.get("code"), "")) ? "FIXTURE" : "MANAGED");
        }
        return result;
    }

    private boolean fixture(String code) {
        return FixturePolicy.isFixtureProviderCode(code);
    }
}
