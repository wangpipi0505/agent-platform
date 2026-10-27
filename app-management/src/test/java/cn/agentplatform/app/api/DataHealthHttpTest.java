package cn.agentplatform.app.api;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.app.application.DataHealthService;
import cn.agentplatform.app.infra.DataHealthIndicator;
import cn.agentplatform.common.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.*;
import java.net.http.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T20.1 / PLAT-20：/health/data 鉴权面证据。挂生产同一条 SecurityConfiguration 门户链
 * （dev-hmac 模式、JWT roles claim），验证：匿名 401 可读 JSON、非 ADMIN 403、ADMIN 200 可见
 * 库名/Flyway 版本/演示标记；/actuator/health 保持 show-details=never，不泄露行数明细。
 *
 * <p>不使用 AppApplication 全量上下文：既有环境里全量扫描会把 test-jar 的 fixture Receiver
 * 配置一并扫入（authorityProperties 冲突，既有 Y-4 失败之一），此处只装配本卡相关 bean，
 * 安全链与鉴权语义与生产一致。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
@SpringBootTest(
        classes = DataHealthHttpTest.Receiver.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.port=0",
            "server.address=127.0.0.1",
            "agent.security.mode=dev-hmac",
            "agent.security.serviceId=app",
            "agent.dev-auth=true"
        })
class DataHealthHttpTest {
    private static final String DB = "agent_platform_health_data_v12";

    @LocalServerPort int port;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry p) {
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:25432/postgres",
                                "agent_v12",
                                System.getenv("AGENT_DB_PASSWORD")));
        if (!Boolean.TRUE.equals(
                admin.queryForObject(
                        "select exists(select 1 from pg_database where datname=?)",
                        Boolean.class,
                        DB)))
            admin.execute("CREATE DATABASE " + DB);
        p.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:25432/" + DB);
        // 演示/真实环境从既有环境标记进入健康详情
        p.add("AGENT_DEMO_MODE", () -> "true");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({
        SecurityConfiguration.class,
        PlatformErrors.class,
        DataHealthController.class,
        DataHealthService.class,
        DataHealthIndicator.class
    })
    static class Receiver {}

    HttpResponse<String> get(String path, Actor actor) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (actor != null) builder.header("Authorization", "Bearer " + ServiceTokens.userToken(actor, 120));
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anonymousDataRequestIsReadable4xx() throws Exception {
        var response = get("/health/data", null);
        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("SYS-AUTH-401"), "未授权必须是可读 JSON 信封: " + response.body());
    }

    @Test
    void nonAdminDataRequestIsForbiddenWithReadableCode() throws Exception {
        var editor = new Actor("health-data-http", "editor", Set.of("EDITOR"), Map.of());
        var response = get("/health/data", editor);
        assertEquals(403, response.statusCode());
        assertTrue(response.body().contains("SYS-APP-4031"), "非 ADMIN 必须可读 403: " + response.body());
    }

    @Test
    void adminReadsDataHealthWithDemoMarkerFlywayVersionAndDbName() throws Exception {
        var admin = new Actor("health-data-http", "admin", Set.of("ADMIN"), Map.of());
        var response = get("/health/data", admin);
        assertEquals(200, response.statusCode(), response.body());
        var data = Json.read(response.body()).path("data");
        assertEquals("DEMO", data.path("environment").asText());
        assertTrue(data.path("demoMode").asBoolean(), "AGENT_DEMO_MODE 必须进入健康详情");
        assertEquals(DB, data.path("database").asText());
        assertTrue(data.path("flywayVersion").asInt() >= 106, "Flyway 已应用版本必须可见");
        assertTrue(data.path("dataSourceType").asText("").startsWith("PostgreSQL"));
        assertTrue(data.path("rowCounts").has("app.application"), "关键表行数（取整）必须存在");
    }

    @Test
    void actuatorHealthStaysUpWithoutDetailLeak() throws Exception {
        var response = get("/actuator/health", null);
        assertEquals(200, response.statusCode());
        var body = Json.read(response.body());
        assertEquals("UP", body.path("status").asText());
        // show-details=never：行数/库名等明细不得出现在公开健康端点，只能经 /health/data 读出
        assertFalse(body.has("rowCounts") || body.has("components"), "健康端点不得泄露数据面明细");
    }
}
