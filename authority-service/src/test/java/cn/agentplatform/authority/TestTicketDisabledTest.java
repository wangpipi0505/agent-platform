package cn.agentplatform.authority;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 反例：{@code agent.authority.test-ticket.enabled} 缺省（false）时，test profile 即便在，
 * 端点也不注册——请求落到 404，且既有端点（jwks）不受影响。
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                AuthorityTestSupport.P_ISSUER,
                AuthorityTestSupport.P_KID,
                AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY,
                AuthorityTestSupport.P_APP})
class TestTicketDisabledTest {

    @Autowired
    private TestRestTemplate rest;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        System.setProperty("spring.profiles.active", "test");
        registry.add("agent.authority.identity.issuer", () -> AuthorityTestSupport.ISSUER);
        registry.add("agent.authority.identity.audience", () -> "agent-platform-portal");
        registry.add("agent.authority.identity.client-id", () -> AuthorityTestSupport.GATEWAY);
        // 刻意不设置 agent.authority.test-ticket.enabled：默认 false。
    }

    @Test
    void endpointIsNotRegisteredWhenSwitchIsOff() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = rest.postForEntity(url("/test/tickets"),
                new HttpEntity<>("{\"tenantId\":\"demo\",\"subject\":\"tester-1\","
                        + "\"roles\":[\"USER\"],\"attributes\":{\"region\":\"A\",\"dataLabels\":[],"
                        + "\"bizSystemCode\":\"B\",\"userId\":\"u\"}}", headers), String.class);
        assertThat(response.getStatusCode().value()).as("body: %s", response.getBody()).isEqualTo(404);
    }

    @Test
    void existingEndpointsAreUnaffected() {
        var response = rest.getForEntity(url("/.well-known/jwks.json"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    private String url(String path) {
        // 本类不继承 AuthorityTestSupport（避免把 enabled=true 带进共享上下文），端口自取。
        return "http://127.0.0.1:" + port + path;
    }

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;
}
