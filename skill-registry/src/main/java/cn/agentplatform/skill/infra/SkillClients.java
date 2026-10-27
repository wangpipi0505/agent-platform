package cn.agentplatform.skill.infra;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Locale;
import java.util.Map;

@Component
public class SkillClients {
    private final RestClient client;
    /**
     * G-8c 签发缝。默认 LEGACY：与改动前逐字相同（ServiceTokens 的 HS256 自签），构造签名不变，
     * 手工 new 的夹具不注入也不改行为；只有容器把它换成 {@code AUTHORITY} 的实例才走授权模式。
     */
    private ServiceTickets serviceTickets = new ServiceTickets(ServiceTickets.LEGACY, null, null, null);

    /** 容器注入（设值注入，避免改动被多处夹具依赖的构造签名）。 */
    @Autowired
    void setServiceTickets(ServiceTickets serviceTickets) {
        this.serviceTickets = serviceTickets;
    }

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
        // 票据先于传输签发：签发失败（LEGACY 的 403 AUTHORITY_CONTEXT_MISSING、AUTHORITY 的未绑定
        // 授权或目录未登记）是"当前没有可信授权"（Y-3/Y-8 根因），不是下游不可用；折成 502 会让
        // 运维误判依赖、重试策略也失去依据。因此签发放在传输 try 之外，授权/配置失败一律原样上抛。
        String ticket = serviceTickets.ticket(actor, "skill", method, url);
        String response;
        try {
            response =
                    method.equals("GET")
                            ? client.get()
                                    .uri(url)
                                    .header("Authorization", "Bearer " + ticket)
                                    .retrieve()
                                    .body(String.class)
                            : client.post()
                                    .uri(url)
                                    .header("Authorization", "Bearer " + ticket)
                                    .body(body)
                                    .retrieve()
                                    .body(String.class);
        } catch (RestClientResponseException ex) {
            // 4xx 可能携带下游业务信封，按原码抛出；5xx 不解析，维持依赖不可用。
            throw downstreamError(ex);
        } catch (Exception ex) {
            // 连接失败/超时（ResourceAccessException 等）：下游确实不可用，保持既有 502 语义。
            throw unavailable();
        }
        JsonNode result = null;
        try {
            result = Json.read(response);
        } catch (Exception ex) {
            result = null;
        }
        if (result == null) {
            // 2xx 空正文/非 JSON 属协议破坏；既有语义是 502，不伪装成 400 业务错误。
            throw unavailable();
        }
        return unwrap(result);
    }

    /** 信封 {code,message,data?} 的 code 是下游业务错误码，必须保留原码与映射后的状态。 */
    private static JsonNode unwrap(JsonNode result) {
        if (result.has("code")) {
            String code = result.path("code").asText();
            // code 为空/非文本：不是可回显的业务码，按协议破坏处理（与改动前此类畸形信封的 502 一致）。
            if (code.isBlank()) throw unavailable();
            if (!"OK".equals(code)) throw new PlatformException(statusOf(code), code, messageOf(result));
        }
        return result.has("data") ? result.get("data") : result;
    }

    /**
     * 4xx 解析响应体：能解析出 {code,message} 就按业务错误抛出（保留原码）；解析不出（正文不是
     * JSON、没有 code 字段、code 为 OK）则视为协议不一致的依赖故障，仍返回 502。
     */
    private static PlatformException downstreamError(RestClientResponseException ex) {
        if (ex.getStatusCode().is4xxClientError()) {
            JsonNode payload = parseOrNull(ex.getResponseBodyAsString());
            if (payload != null && payload.hasNonNull("code")) {
                String code = payload.path("code").asText();
                if (!"OK".equals(code) && !code.isBlank())
                    return new PlatformException(statusOf(code), code, messageOf(payload));
            }
        }
        return unavailable();
    }

    private static JsonNode parseOrNull(String body) {
        try {
            return Json.read(body);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String messageOf(JsonNode payload) {
        String message = payload.path("message").asText("");
        return message.isBlank() ? "依赖返回失败" : message;
    }

    /** 卡片 statusOf 表：*NOT_FOUND*→404，授权类→403，冲突类→409，其余业务错误→400。 */
    private static int statusOf(String code) {
        String normalized = code.toUpperCase(Locale.ROOT);
        if (normalized.contains("NOT_FOUND")) return 404;
        if (normalized.contains("AUTH") || normalized.contains("SCOPE") || normalized.contains("PERMISSION"))
            return 403;
        if (normalized.contains("CONFLICT") || normalized.contains("CHANGED") || normalized.contains("EXPIRED"))
            return 409;
        return 400;
    }

    private static PlatformException unavailable() {
        return new PlatformException(502, "DEPENDENCY_UNAVAILABLE", "依赖服务暂不可用，请稍后重试");
    }
}
