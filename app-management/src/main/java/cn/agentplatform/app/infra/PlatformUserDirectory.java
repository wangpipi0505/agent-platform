package cn.agentplatform.app.infra;

import cn.agentplatform.common.PlatformException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class PlatformUserDirectory {
    private final RestClient directory;
    private final String tenant;
    private final String key;

    public PlatformUserDirectory(@Value("${AGENT_AUTHORITY_IDENTITY_URL:}") String url,
                                 @Value("${AGENT_AUTHORITY_IDENTITY_TENANT:local-v12}") String tenant,
                                 @Value("${AGENT_PLATFORM_USER_SEARCH_KEY_REF:}") String reference) {
        this.tenant = tenant;
        this.key = reference.matches("env:[A-Za-z_][A-Za-z0-9_]{0,127}")
                ? System.getenv(reference.substring(4)) : null;
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.directory = url.isBlank() ? null : RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    public List<User> search(String tenantId, String query) {
        requireConfigured(tenantId);
        if (query == null || query.isBlank() || query.length() > 64)
            throw new PlatformException(400, "INVALID_USER_SEARCH", "请输入 1 至 64 个字符的用户名");
        try {
            JsonNode response = directory.get().uri(builder -> builder.path("/system/user/list")
                            .queryParam("userName", "{query}").queryParam("pageNum", 1).queryParam("pageSize", 20)
                            .build(query.strip()))
                    .header("X-API-Key", key).retrieve().body(JsonNode.class);
            if (response == null || response.path("code").asInt() != 200 || !response.path("rows").isArray())
                throw unavailable();
            var users = new ArrayList<User>();
            for (var row : response.path("rows")) {
                String subject = row.path("userName").textValue();
                String status = row.path("status").textValue();
                if (subject == null || subject.isBlank() || status == null) throw unavailable();
                users.add(new User(subject, row.path("nickName").asText(subject), "0".equals(status)));
                if (users.size() == 20) break;
            }
            return List.copyOf(users);
        } catch (RestClientException error) {
            throw unavailable();
        }
    }

    public void requireActive(String tenantId, String subject) {
        requireConfigured(tenantId);
        if (subject == null || subject.isBlank() || subject.length() > 128
                || subject.equals(".") || subject.equals("..") || subject.indexOf('/') >= 0
                || subject.indexOf('\\') >= 0 || !subject.equals(subject.strip())
                || subject.codePoints().anyMatch(Character::isISOControl))
            throw new PlatformException(400, "INVALID_PLATFORM_SUBJECT", "授权主体标识无效");
        try {
            JsonNode response = directory.get().uri("/system/apikeys/identity/{subject}", subject)
                    .header("X-API-Key", key).retrieve().body(JsonNode.class);
            if (response == null || !response.path("code").isIntegralNumber()) throw unavailable();
            if (response.path("code").asInt() != 200) {
                throw new PlatformException(409, "PLATFORM_SUBJECT_UNAVAILABLE", "当前用户不可授权");
            }
            JsonNode user = response.path("data");
            if (!subject.equals(user.path("userName").textValue()) || !"0".equals(user.path("status").textValue()))
                throw new PlatformException(409, "PLATFORM_SUBJECT_UNAVAILABLE", "当前用户不可授权");
        } catch (RestClientException error) {
            throw unavailable();
        }
    }

    private void requireConfigured(String tenantId) {
        if (directory == null || key == null || key.isBlank() || !tenant.equals(tenantId)) throw unavailable();
    }

    private static PlatformException unavailable() {
        return new PlatformException(503, "PLATFORM_USER_DIRECTORY_UNAVAILABLE", "用户目录暂不可用");
    }

    public record User(String subject, String displayName, boolean active) {}
}
