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
 * 反例：开关开了（enabled=true）但 <b>active profile 不是 test</b> 时，控制器同样不注册
 * （{@code @Profile("test")} 拒绝装配）——非 test 环境启动即拒绝注册，请求 404。
 * 这钉住"双条件缺一不可"的第二支：不存在"生产配置里塞一个开关值就能签测试票"的路径。
 */
@ActiveProfiles("not-test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                AuthorityTestSupport.P_ISSUER,
                AuthorityTestSupport.P_KID,
                AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY,
                AuthorityTestSupport.P_APP,
                "agent.authority.test-ticket.enabled=true",
                "agent.authority.identity.url=http://127.0.0.1",
                "agent.authority.identity.subject-lookup-key-ref=env:AGENT_R38_TEST_LOOKUP_KEY"})
class TestTicketProfileGateTest {

    @Autowired
    private TestRestTemplate rest;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // @ActiveProfiles("not-test") 显式接管 profile，这里只配身份档案供对照。
        registry.add("agent.authority.identity.issuer", () -> AuthorityTestSupport.ISSUER);
        registry.add("agent.authority.identity.audience", () -> "agent-platform-portal");
        registry.add("agent.authority.identity.client-id", () -> AuthorityTestSupport.GATEWAY);
    }

    @Test
    void endpointIsNotRegisteredOutsideTestProfile() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = rest.postForEntity(
                "http://127.0.0.1:" + port + "/test/tickets",
                new HttpEntity<>("{\"tenantId\":\"demo\",\"subject\":\"tester-1\","
                        + "\"roles\":[\"USER\"],\"attributes\":{\"region\":\"A\",\"dataLabels\":[],"
                        + "\"bizSystemCode\":\"B\",\"userId\":\"u\"}}", headers), String.class);
        assertThat(response.getStatusCode().value()).as("body: %s", response.getBody()).isEqualTo(404);
    }
}
