package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

/** Bounded HTTP outside local transactions; short service tickets retain the original actor. */
@Component
public class TestInputClient {
    private final RestClient http;
    private final String app, monitor;

    public TestInputClient(
            @Value("${agent.services.app:http://127.0.0.1:18081}") String app,
            @Value("${agent.services.monitor:http://127.0.0.1:18085}") String monitor) {
        this.app = app;
        this.monitor = monitor;
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(10000);
        http = RestClient.builder().requestFactory(f).build();
    }

    public JsonNode draft(long id, Actor actor) {
        return call("GET", app + "/internal/app-drafts/" + id, null, actor);
    }

    public JsonNode promote(JsonNode payload, Actor actor) {
        return call("POST", monitor + "/internal/datasets/promotions", payload, actor);
    }

    private JsonNode call(String method, String url, Object payload, Actor actor) {
        try {
            var request =
                    http.method(org.springframework.http.HttpMethod.valueOf(method))
                            .uri(url)
                            .header("Authorization", "Bearer " + ServiceTokens.issue(actor));
            if (payload != null)
                request.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body(payload);
            JsonNode result = Json.read(request.retrieve().body(String.class));
            if (!"OK".equals(result.path("code").asText()))
                throw new PlatformException(502, "TEST_INPUT_DEPENDENCY", "依赖服务返回无效结果");
            return result.path("data");
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (SetHolder.CLIENT_ERRORS.contains(status))
                throw new PlatformException(
                        status, "TEST_INPUT_DEPENDENCY_REJECTED", "草稿或数据集请求被拒绝，请刷新后重试");
            throw new PlatformException(502, "TEST_INPUT_DEPENDENCY", "依赖服务暂不可用，请使用相同requestId重试");
        } catch (RestClientException e) {
            throw new PlatformException(502, "TEST_INPUT_DEPENDENCY", "依赖服务响应中断，请使用相同requestId重试");
        }
    }

    private static final class SetHolder {
        static final java.util.Set<Integer> CLIENT_ERRORS =
                java.util.Set.of(400, 403, 404, 409, 412, 413);
    }
}
