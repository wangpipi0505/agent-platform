package cn.agentplatform.skill.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;

/** 跨平台只读消费共用通路：精确目的地、逐次身份回查、API key；绝不发送AP内部Bearer。 */
@Component
public class OntologySemanticTransport {
    private static final Pattern PATH = Pattern.compile("^/onto/releases/([0-9a-fA-F-]{36})/(formal-source|semantic/(identity|facts/resolve|facts|definitions/query|decisions|conditions/evaluate|objects/query|objects/join))$");
    private final URI base;
    private final OntologyConsumeIdentity identities;
    private final RestClient client;

    @org.springframework.beans.factory.annotation.Autowired
    public OntologySemanticTransport(OntologyConsumeIdentity identities,
            @Value("${agent.services.ontology:http://127.0.0.1:18182}") String baseUrl) {
        this(identities, baseUrl, httpClient());
    }

    /** 测试只替换HTTP传输，不替换主体选择、回查验证或精确路由判断。 */
    OntologySemanticTransport(OntologyConsumeIdentity identities, String baseUrl, RestClient client) {
        this.identities = identities;
        this.client = client;
        base = URI.create(baseUrl.replaceAll("/+$", ""));
        if (base.getHost() == null || base.getRawUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null || !java.util.Set.of("http", "https").contains(base.getScheme())
                || !(base.getRawPath().isEmpty() || base.getRawPath().equals("/")))
            throw new IllegalArgumentException("ontology地址必须为明确的http(s) origin");
    }

    private static RestClient httpClient() {
        var factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(java.net.HttpURLConnection connection, String method)
                    throws java.io.IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        return RestClient.builder().requestFactory(factory).build();
    }

    public JsonNode get(String url) { return request("GET", url, null); }
    public JsonNode post(String url, Object body) { return request("POST", url, body); }

    private JsonNode request(String method, String url, Object body) {
        URI target = URI.create(url);
        var match = PATH.matcher(target.getRawPath());
        if (!sameOrigin(base, target) || target.getRawUserInfo() != null || target.getRawFragment() != null
                || !match.matches() || !target.getPath().equals(target.getRawPath())) throw denied();
        String operation = match.group(2);
        if ("identity".equals(operation) || "semantic/identity".equals(operation)
                || !method.equals("formal-source".equals(operation) ? "GET" : "POST")
                || target.getRawQuery() != null && !("formal-source".equals(operation)
                    && target.getRawQuery().matches("spaceCode=[A-Za-z0-9._-]{1,64}"))) throw denied();
        UUID release = UUID.fromString(match.group(1));
        Actor actor = Actor.current();
        String key = identities.key(actor, release);
        JsonNode identity = send("GET", base + "/onto/releases/" + release + "/semantic/identity", null, key);
        identities.verify(actor, release, identity);
        return send(method, url, body, key);
    }

    private JsonNode send(String method, String url, Object body, String key) {
        try {
            var request = client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(url)
                    .header("X-API-Key", key);
            if (body != null) request.body(body);
            String raw = request.retrieve().onStatus(status -> status.is3xxRedirection(), (req, reply) -> {
                throw unavailable();
            }).body(String.class);
            JsonNode response = Json.read(raw);
            if (!"OK".equals(response.path("code").asText()) || !response.has("data")) throw unavailable();
            return response.path("data");
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().is4xxClientError()) {
                JsonNode error;
                try { error = Json.read(failure.getResponseBodyAsString()); }
                catch (Exception malformed) { throw unavailable(); }
                if (!error.path("code").asText().isBlank())
                    throw new PlatformException(failure.getStatusCode().value(), error.path("code").asText(),
                            error.path("message").asText("本体消费被拒绝"));
            }
            throw unavailable();
        } catch (PlatformException rejected) { throw rejected; }
        catch (Exception unavailable) { throw unavailable(); }
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost()) && port(left) == port(right);
    }
    private static int port(URI uri) { return uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort(); }
    private static PlatformException denied() { return new PlatformException(403, "ONTOLOGY_ROUTE_DENIED", "本体消费目的地或操作未登记"); }
    private static PlatformException unavailable() { return new PlatformException(503, "SEMANTIC_SOURCE_UNAVAILABLE", "本体消费来源暂不可用"); }
}
