package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.api.MemoryController;
import cn.agentplatform.engine.application.Memories;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * R-18（A3-4 memoryPolicy 接 manifest，台账 §7.6b）：manifest 的 memoryPolicy 变更后
 * 读写路径行为随之变化。读取链=chat_session 钉住的发布快照 → config.memoryPolicy（A3-8
 * manifest 形状，与 ResourceManifestService.collectMemory 的 MEMORY_POLICY 条目同源）——
 * Memories.MemoryPolicy.from 三级回退补接 config 层后，改 manifest memoryPolicy 即改
 * 会话钉住策略。写入路径行为变化：writeScopes 不含 SHARED 时 ADMIN 写 SHARED 403
 * SCOPE_WRITE_DENIED，放开后 200；readScopes 同理决定 USER 对共享条目的读取（403/200）。
 * 角色红线不受策略影响：USER 写 SHARED 恒 403（策略放行不越角色门）。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class MemoryManifestPolicyTest {

    static JdbcTemplate db;
    static TraceStore store;

    @BeforeAll
    static void connect() {
        var source =
                new DriverManagerDataSource(
                        EngineTestDatabase.url(),
                        EngineTestDatabase.user(),
                        EngineTestDatabase.password());
        org.flywaydb.core.Flyway.configure()
                .dataSource(source)
                .schemas("engine")
                .defaultSchema("engine")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(source);
        store = new TraceStore(db, new DataSourceTransactionManager(source));
    }

    @BeforeEach
    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        db.update("delete from engine.memory_entry where tenant_id like 'h36-t-%'");
        db.update("delete from engine.chat_session where tenant_id like 'h36-t-%'");
    }

    private void identity(String tenant, String principal, String... roles) {
        var jwt =
                Jwt.withTokenValue("verified-internal-peer")
                        .header("alg", "HS256")
                        .subject(principal)
                        .claim("tenant_id", tenant)
                        .claim("roles", List.of(roles))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(180))
                        .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private MockMvc mvc(Memories service) {
        return MockMvcBuilders.standaloneSetup(new MemoryController(service))
                .setControllerAdvice(new PlatformErrors())
                .build();
    }

    private JsonNode call(MockMvc mvc, String method, String url, String body, int expected)
            throws Exception {
        var builder =
                switch (method) {
                    case "GET" -> get(url);
                    case "POST" -> post(url).contentType(MediaType.APPLICATION_JSON)
                            .content(body == null ? "{}" : body);
                    default -> throw new IllegalArgumentException(method);
                };
        var response = mvc.perform(builder).andReturn().getResponse();
        assertEquals(expected, response.getStatus(), response.getContentAsString());
        return Json.read(response.getContentAsString());
    }

    /**
     * 会话夹具：snapshot=会话创建时固化的发布快照（发布形状——manifest 在 config 下），
     * memoryPolicy 挂在 config.memoryPolicy（A3-8 manifest 四分区形状）。
     */
    private String session(String tenant, String principal, String code, String readScopes, String writeScopes) {
        String id = "h36_s_" + UUID.randomUUID().toString().replace("-", "");
        String snapshot =
                "{\"appCode\":\"" + code + "\",\"type\":\"CHAT\",\"versionNo\":1,\"config\":"
                        + "{\"memoryPolicy\":{\"readScopes\":" + readScopes
                        + ",\"writeScopes\":" + writeScopes + "}}}";
        db.update(
                "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot)"
                        + " values(?,?,?,?,?::jsonb)",
                id,
                tenant,
                principal,
                code,
                snapshot);
        return id;
    }

    /** 共享条目夹具（ADMIN 所有；检索资格 ACTIVE）。 */
    private void sharedMemory(String tenant, String code, String owner, String content) {
        db.update(
                "insert into engine.memory_entry(memory_id,tenant_id,workspace_code,scope,owner_id,"
                        + "kind,content,tags,source_kind,version,status,created_at)"
                        + " values(?,?,?,'SHARED',?,'EXPERIENCE',?,'[]'::jsonb,'USER_EXPLICIT',1,'ACTIVE',now())",
                "h36_mem_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24),
                tenant,
                code,
                owner,
                content);
    }

    @Test
    void manifestMemoryPolicyChangeFlipsWriteAndReadPathBehavior() throws Exception {
        String tenant = "h36-t-" + UUID.randomUUID(), code = "h36-app-" + UUID.randomUUID().toString().substring(0, 8);
        identity(tenant, "admin", "ADMIN");
        // 收窄口径：manifest memoryPolicy.readScopes/writeScopes 均仅 PRIVATE。
        String adminNarrow = session(tenant, "admin", code, "[\"PRIVATE\"]", "[\"PRIVATE\"]");
        sharedMemory(tenant, code, "admin", "工作区共享观测经验（差异报告默认附置信度差值列）");
        var memories = new Memories(store, new MemoryRepository(store));
        var adminMvc = mvc(memories);

        // 写入路径随 manifest 收窄：writeScopes 不含 SHARED → ADMIN 写 SHARED 也 403。
        var denied =
                call(
                        adminMvc,
                        "POST",
                        "/v1/apps/" + code + "/memories",
                        Json.write(
                                Map.of(
                                        "requestId", "h36-req-narrow",
                                        "content", "收窄口径下的共享写入",
                                        "kind", "EXPERIENCE",
                                        "scope", "SHARED",
                                        "sessionId", adminNarrow)),
                        403);
        assertEquals("SCOPE_WRITE_DENIED", denied.path("code").asText(), "writeScopes 不含 SHARED=拒绝");

        // 放开口径：manifest memoryPolicy.readScopes/writeScopes 放开 SHARED。
        String adminOpen = session(tenant, "admin", code, "[\"PRIVATE\",\"SHARED\"]", "[\"PRIVATE\",\"SHARED\"]");
        var allowed =
                call(
                        adminMvc,
                        "POST",
                        "/v1/apps/" + code + "/memories",
                        Json.write(
                                Map.of(
                                        "requestId", "h36-req-open",
                                        "content", "放开口径下的共享写入",
                                        "kind", "EXPERIENCE",
                                        "scope", "SHARED",
                                        "sessionId", adminOpen)),
                        200);
        assertEquals("SHARED", allowed.path("data").path("scope").asText(), "manifest 放开后 ADMIN 写 SHARED=200");
        String sharedId = allowed.path("data").path("memoryId").asText();

        // 读取路径随 manifest readScopes 变化：USER 携收窄会话读共享=403；携放开会话=200。
        identity(tenant, "alice", "USER");
        String aliceNarrow = session(tenant, "alice", code, "[\"PRIVATE\"]", "[\"PRIVATE\"]");
        String aliceOpen = session(tenant, "alice", code, "[\"PRIVATE\",\"SHARED\"]", "[\"PRIVATE\"]");
        var userMvc = mvc(memories);
        assertEquals(
                "MEMORY_FORBIDDEN",
                call(userMvc, "GET", "/v1/apps/" + code + "/memories/" + sharedId + "?sessionId=" + aliceNarrow, null, 403)
                        .path("code")
                        .asText(),
                "readScopes 仅 PRIVATE：共享条目对 USER 不可读");
        assertEquals(
                "放开口径下的共享写入",
                call(userMvc, "GET", "/v1/apps/" + code + "/memories/" + sharedId + "?sessionId=" + aliceOpen, null, 200)
                        .path("data")
                        .path("content")
                        .asText(),
                "readScopes 放开 SHARED：同一共享条目变为可读");

        // 角色红线不受策略放行影响：USER 携放开会话写 SHARED 仍 403（SCOPE_WRITE_DENIED）。
        assertEquals(
                "SCOPE_WRITE_DENIED",
                call(
                        userMvc,
                        "POST",
                        "/v1/apps/" + code + "/memories",
                        Json.write(
                                Map.of(
                                        "requestId", "h36-req-user-shared",
                                        "content", "USER 尝试共享写入",
                                        "kind", "PREFERENCE",
                                        "scope", "SHARED",
                                        "sessionId", aliceOpen)),
                        403)
                        .path("code")
                        .asText(),
                "USER 恒不写 SHARED（策略放行不越角色门）");

        // 收窄/放开的 policy 解析同链自查（config 层回退形状）。
        var narrow =
                Memories.MemoryPolicy.from(
                        Json.read("{\"config\":{\"memoryPolicy\":{\"readScopes\":[\"PRIVATE\"]}}}"));
        assertTrue(narrow.readScopes().equals(Set.of("PRIVATE")) && !narrow.canRead("SHARED"));
        var open =
                Memories.MemoryPolicy.from(
                        Json.read("{\"config\":{\"memoryPolicy\":{\"readScopes\":[\"PRIVATE\",\"SHARED\"]}}}"));
        assertTrue(open.canRead("SHARED"), "config.memoryPolicy 三级回退生效");
    }
}
