package cn.agentplatform.app.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.app.AppApplication;
import cn.agentplatform.common.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.*;
import java.net.http.*;
import java.util.*;

@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
@SpringBootTest(
        classes = AppApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DraftMetadataHttpTest {
    @LocalServerPort int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        var admin =
                new org.springframework.jdbc.core.JdbcTemplate(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:25432/postgres",
                                "agent_v12",
                                System.getenv("AGENT_DB_PASSWORD")));
        if (admin.queryForObject(
                        "select count(*) from pg_database where"
                            + " datname='agent_platform_test_input_test'",
                        Integer.class)
                == 0) admin.execute("create database agent_platform_test_input_test");
        p.add(
                "spring.datasource.url",
                () -> "jdbc:postgresql://127.0.0.1:25432/agent_platform_test_input_test");
    }

    HttpResponse<String> request(
            String method, String path, Object body, Actor actor, boolean internal)
            throws Exception {
        var b =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header(
                                "Authorization",
                                "Bearer "
                                        + (internal
                                                ? ServiceTokens.issue(actor)
                                                : ServiceTokens.userToken(actor, 120)))
                        .header("Content-Type", "application/json");
        return HttpClient.newHttpClient()
                .send(
                        b.method(
                                        method,
                                        body == null
                                                ? HttpRequest.BodyPublishers.noBody()
                                                : HttpRequest.BodyPublishers.ofString(
                                                        Json.write(body)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void realDraftIdentityIsTenantScopedAndRequiresServiceAndEditor() throws Exception {
        var actor =
                new Actor("draft-meta-" + UUID.randomUUID(), "editor", Set.of("EDITOR"), Map.of());
        var created =
                request(
                        "POST",
                        "/admin/apps",
                        Map.of("code", "demo", "name", "Demo", "type", "CHAT"),
                        actor,
                        false);
        assertEquals(200, created.statusCode(), created.body());
        long id = Json.read(created.body()).path("data").path("draftId").asLong();
        assertTrue(id > 0);
        String path = "/internal/app-drafts/" + id;
        var found = request("GET", path, null, actor, true);
        assertEquals(200, found.statusCode(), found.body());
        assertEquals("demo", Json.read(found.body()).path("data").path("appCode").asText());
        assertEquals(403, request("GET", path, null, actor, false).statusCode());
        assertEquals(
                404,
                request(
                                "GET",
                                path,
                                null,
                                new Actor("other", actor.principalId(), actor.roles(), Map.of()),
                                true)
                        .statusCode());
        assertEquals(
                403,
                request(
                                "GET",
                                path,
                                null,
                                new Actor(
                                        actor.tenantId(),
                                        actor.principalId(),
                                        Set.of("ADMIN"),
                                        Map.of()),
                                true)
                        .statusCode());
        assertEquals(
                404,
                request("GET", "/internal/app-drafts/9223372036854775807", null, actor, true)
                        .statusCode());
    }
}
