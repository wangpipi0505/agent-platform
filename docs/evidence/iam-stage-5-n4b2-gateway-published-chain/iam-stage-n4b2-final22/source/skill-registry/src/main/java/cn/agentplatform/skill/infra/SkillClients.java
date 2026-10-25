package cn.agentplatform.skill.infra;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class SkillClients {
    private final RestClient client;

    public SkillClients() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(60000);
        client = RestClient.builder().requestFactory(factory).build();
    }

    public JsonNode get(String url) {
        return request("GET", url, Map.of(), Actor.current());
    }

    public JsonNode post(String url, Object body) {
        return request("POST", url, body, Actor.current());
    }

    public JsonNode post(String url, Object body, Actor actor) {
        return request("POST", url, body, actor);
    }

    private JsonNode request(String method, String url, Object body, Actor actor) {
        try {
            String response =
                    method.equals("GET")
                            ? client.get()
                                    .uri(url)
                                    .header("Authorization", "Bearer " + ServiceTokens.issue(actor))
                                    .retrieve()
                                    .body(String.class)
                            : client.post()
                                    .uri(url)
                                    .header("Authorization", "Bearer " + ServiceTokens.issue(actor))
                                    .body(body)
                                    .retrieve()
                                    .body(String.class);
            JsonNode result = Json.read(response);
            if (result.has("code") && !result.path("code").asText().equals("OK"))
                throw new IllegalArgumentException("依赖返回失败");
            return result.has("data") ? result.get("data") : result;
        } catch (Exception ex) {
            throw new PlatformException(502, "DEPENDENCY_UNAVAILABLE", "依赖服务暂不可用，请稍后重试");
        }
    }
}
