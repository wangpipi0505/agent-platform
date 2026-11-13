package cn.agentplatform.engine.infra;

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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * A3-4（台账 §7.3）：跨会话记忆 CRUD/状态/读写策略——A09 子集验收。
 *
 * <p>验收映射（A09：验私有显式保存、授权共享读、共享写限制、两版本策略及撤销后旧版任务
 * 也退出检索）：①私有显式保存→ACTIVE 即进列表/单条（列表不含正文）；②共享读须策略
 * readScopes 允许（会话钉住版本 vs 当前生效版两口径），USER 写 SHARED 一律 403；③PATCH
 * expectedVersion CAS 409 MEMORY_VERSION_CONFLICT；④DRAFT 审批走 PATCH status 增量
 * （私有=来源用户、共享=ADMIN）；⑤删除先置 REVOKED 立即退出列表与 retrieve 检索且幂等；
 * ⑥检索顺序=权限过滤→scope/标签/文本相关性→topK 截取（含有效期过滤）；⑦requestId 幂等
 * （同参重放原条目/异参 409 REQUEST_REPLAY）；⑧网关目录行五条登记（运行期
 * RouteCoverageVerifier 强制覆盖，缺行启动即失败）。
 *
 * <p>夹具一律 h34_% 前缀双向清理（记忆按 tenant_id 前缀清理——requestId 派生的确定性
 * memory_id 是摘要值，无法前缀匹配）；照 SessionListEndpointTest 的 standalone MockMvc
 * 先例（真实 Controller+服务+库）。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class MemoryApiTest {

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

    /** 双向清理：仅 h34_% 夹具（tenant/workspace 双前缀收口，覆盖 API 派生 ID 与直插行）。 */
    @BeforeEach
    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        db.update("delete from engine.memory_entry where tenant_id like 'h34-t-%'");
        db.update("delete from engine.chat_session where tenant_id like 'h34-t-%'");
        db.update("delete from engine.task where tenant_id like 'h34-t-%'");
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

    private Memories memories() {
        return new Memories(store, new MemoryRepository(store));
    }

    /** 资产管理口径（无 sessionId）用桩注入"当前生效发布快照"，验证当前版策略读取路径。 */
    static final class PublishedStubMemories extends Memories {
        private final JsonNode snapshot;

        PublishedStubMemories(TraceStore store, MemoryRepository repository, JsonNode snapshot) {
            super(store, repository);
            this.snapshot = snapshot;
        }

        @Override
        protected JsonNode publishedSnapshot(String code, Actor actor) {
            return snapshot;
        }
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
                    case "PATCH" -> patch(url).contentType(MediaType.APPLICATION_JSON)
                            .content(body == null ? "{}" : body);
                    case "DELETE" -> delete(url).contentType(MediaType.APPLICATION_JSON)
                            .content(body == null ? "{}" : body);
                    default -> throw new IllegalArgumentException(method);
                };
        var response = mvc.perform(builder).andReturn().getResponse();
        assertEquals(expected, response.getStatus(), response.getContentAsString());
        return Json.read(response.getContentAsString());
    }

    /** 会话夹具：snapshot 即会话创建时固化的发布快照（会话钉住版本的 policy 载体）。 */
    private String session(String tenant, String principal, String code, String snapshotJson) {
        String id = "h34_s_" + UUID.randomUUID().toString().replace("-", "");
        db.update(
                "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot)"
                    + " values(?,?,?,?,?::jsonb)",
                id,
                tenant,
                principal,
                code,
                snapshotJson);
        return id;
    }

    /** 任务夹具（source_task_id FK→task；摘要列 char(64) 形状 CHECK 须喂合法十六进制）。 */
    private String task(String tenant, String principal, String code) {
        String id = "h34_task_" + UUID.randomUUID().toString().replace("-", "");
        String digest = Digest.sha256("h34-" + id);
        db.update(
                "insert into engine.task(public_id,tenant_id,principal_id,app_code,"
                    + "config_release_id,config_digest,mode,goal,input_ref,input_digest,state,"
                    + "request_id,request_digest)"
                    + " values(?,?,?,?,'h34-rel',?,'AGENT','h34 目标','h34-in',?,"
                    + "'QUEUED',?,?)",
                id,
                tenant,
                principal,
                code,
                digest,
                digest,
                "h34-req-" + id,
                digest);
        return id;
    }

    /** 记忆直插夹具（检索排序/有效期用；ID 带 h34_ 前缀便于 tenant 前缀外的补充定位）。 */
    private void memory(
            String tenant,
            String code,
            String scope,
            String owner,
            String content,
            String tagsJson,
            String status,
            Instant createdAt,
            Instant expiresAt) {
        db.update(
                "insert into engine.memory_entry(memory_id,tenant_id,workspace_code,scope,owner_id,"
                    + "kind,content,tags,source_kind,version,status,created_at,expires_at)"
                    + " values(?,?,?,?,?,'EXPERIENCE',?,?::jsonb,'USER_EXPLICIT',1,?,?,?)",
                "h34_mem_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24),
                tenant,
                code,
                scope,
                owner,
                content,
                tagsJson,
                status,
                java.sql.Timestamp.from(createdAt),
                expiresAt == null ? null : java.sql.Timestamp.from(expiresAt));
    }

    // ------------------------------------------------------------------
    // A09-①：私有显式保存→ACTIVE 即生效；列表不含正文；单条读正文
    // ------------------------------------------------------------------

    @Test
    void explicitPrivateSaveIsImmediatelyActiveAndListOmitsContent() throws Exception {
        String tenant = "h34-t-" + UUID.randomUUID(), code = "h34-app-" + UUID.randomUUID().toString().substring(0, 8);
        identity(tenant, "alice", "USER");
        // 会话钉住版本用空快照（无 memoryPolicy→仅 PRIVATE 收窄口径）。
        String sessionId = session(tenant, "alice", code, "{}");
        String taskPublicId = task(tenant, "alice", code);
        var mvc = mvc(memories());
        var created =
                call(
                        mvc,
                        "POST",
                        "/v1/apps/" + code + "/memories",
                        Json.write(
                                Map.of(
                                        "requestId", "h34-req-save-1",
                                        "content", "观测序列规划时云量阈值默认取 30%",
                                        "kind", "PREFERENCE",
                                        "scope", "PRIVATE",
                                        "tags", List.of("观测偏好"),
                                        "sessionId", sessionId,
                                        "sourceTaskId", taskPublicId)),
                        200);
        var detail = created.path("data");
        assertEquals("ACTIVE", detail.path("status").asText(), "用户显式保存→ACTIVE 即生效");
        assertEquals(1, detail.path("version").asInt(), "首版为 1");
        assertEquals("PRIVATE", detail.path("scope").asText());
        assertEquals("观测偏好", detail.path("tags").get(0).asText());
        String memoryId = detail.path("memoryId").asText();
        assertFalse(memoryId.isBlank());

        // 列表：先权限过滤再检索；列表项不含正文，字段形状照接口稿 §5.4。
        var list = call(mvc, "GET", "/v1/apps/" + code + "/memories?sessionId=" + sessionId, null, 200)
                .path("data");
        assertEquals(1, list.path("total").asInt());
        var item = list.path("memories").get(0);
        assertEquals(memoryId, item.path("memoryId").asText());
        for (String field : List.of("memoryId", "kind", "scope", "tags", "version", "status", "sourceTaskId"))
            assertTrue(item.has(field), "列表缺字段 " + field + ": " + item);
        assertFalse(item.has("content"), "列表不得携带正文（接口稿 §5.4：正文经单条接口读取）");
        assertEquals(taskPublicId, item.path("sourceTaskId").asText(), "sourceTaskId 对外为任务公共标识");

        // 单条：归属核验后返回含正文。
        var single = call(mvc, "GET", "/v1/apps/" + code + "/memories/" + memoryId + "?sessionId=" + sessionId, null, 200)
                .path("data");
        assertEquals("观测序列规划时云量阈值默认取 30%", single.path("content").asText());
        assertFalse(single.has("expiresAt"), "未设置有效期时省略该键");

        // 入参校验：缺 requestId/空正文 400。
        assertEquals(
                "MEMORY_REQUEST_ID_INVALID",
                call(mvc, "POST", "/v1/apps/" + code + "/memories", "{\"content\":\"x\",\"kind\":\"PREFERENCE\"}", 400)
                        .path("code")
                        .asText());
        assertEquals(
                "MEMORY_CONTENT_REQUIRED",
                call(mvc, "POST", "/v1/apps/" + code + "/memories", "{\"requestId\":\"h34-r2\",\"content\":\" \",\"kind\":\"PREFERENCE\"}", 400)
                        .path("code")
                        .asText());
    }

    // ------------------------------------------------------------------
    // A09-②：授权共享读（策略 readScopes）/共享写限制（USER 403 SCOPE_WRITE_DENIED）
    // ------------------------------------------------------------------

    @Test
    void sharedReadRequiresPolicyAndUserSharedWriteIsDenied() throws Exception {
        String tenant = "h34-t-" + UUID.randomUUID(), code = "h34-app-" + UUID.randomUUID().toString().substring(0, 8);
        // 两个会话钉住两个版本：sShared（授权共享读）与 sPrivate（仅 PRIVATE）——两版本策略口径。
        String sharedPolicy =
                Json.write(
                        Map.of(
                                "memoryPolicy",
                                Map.of(
                                        "readScopes", List.of("PRIVATE", "SHARED"),
                                        "writeScopes", List.of("PRIVATE", "SHARED"))));
        String privateOnly =
                Json.write(
                        Map.of(
                                "memoryPolicy",
                                Map.of(
                                        "readScopes", List.of("PRIVATE"),
                                        "writeScopes", List.of("PRIVATE"))));
        identity(tenant, "admin", "ADMIN");
        String adminSession = session(tenant, "admin", code, sharedPolicy);
        var adminMvc = mvc(memories());
        var shared =
                call(
                        adminMvc,
                        "POST",
                        "/v1/apps/" + code + "/memories",
                        Json.write(
                                Map.of(
                                        "requestId", "h34-req-shared-1",
                                        "content", "工作区共享：差异报告默认附置信度差值列",
                                        "kind", "EXPERIENCE",
                                        "scope", "SHARED",
                                        "tags", List.of("差异报告"),
                                        "sessionId", adminSession)),
                        200);
        String sharedId = shared.path("data").path("memoryId").asText();

        identity(tenant, "alice", "USER");
        String aliceSharedSession = session(tenant, "alice", code, sharedPolicy);
        String alicePrivateSession = session(tenant, "alice", code, privateOnly);
        var mvc = mvc(memories());

        // USER 写 SHARED 一律拒绝（策略即便允许，USER 角色不可写共享层）。
        assertEquals(
                "SCOPE_WRITE_DENIED",
                call(
                        mvc,
                        "POST",
                        "/v1/apps/" + code + "/memories",
                        Json.write(
                                Map.of(
                                        "requestId", "h34-req-shared-user",
                                        "content", "尝试写共享",
                                        "kind", "PREFERENCE",
                                        "scope", "SHARED",
                                        "sessionId", aliceSharedSession)),
                        403)
                        .path("code")
                        .asText());

        // 授权会话（readScopes 含 SHARED）：列表与单条可见共享条目。
        var authorized = call(mvc, "GET", "/v1/apps/" + code + "/memories?sessionId=" + aliceSharedSession, null, 200)
                .path("data");
        assertEquals(1, authorized.path("total").asInt(), "授权会话可见他人共享条目");
        assertEquals(sharedId, authorized.path("memories").get(0).path("memoryId").asText());
        var single = call(mvc, "GET", "/v1/apps/" + code + "/memories/" + sharedId + "?sessionId=" + aliceSharedSession, null, 200)
                .path("data");
        assertEquals("工作区共享：差异报告默认附置信度差值列", single.path("content").asText());

        // 未授权会话（readScopes 仅 PRIVATE）：列表过滤掉共享条目；单条 403 并留拒绝审计
        //（接口稿 §6：存在但无权读取返回 403）。
        assertEquals(0, call(mvc, "GET", "/v1/apps/" + code + "/memories?sessionId=" + alicePrivateSession, null, 200)
                .path("data")
                .path("total")
                .asInt());
        assertEquals(
                "MEMORY_FORBIDDEN",
                call(mvc, "GET", "/v1/apps/" + code + "/memories/" + sharedId + "?sessionId=" + alicePrivateSession, null, 403)
                        .path("code")
                        .asText());

        // ADMIN 写 SHARED 但策略收窄（writeScopes 仅 PRIVATE）→同样 403（策略∩角色双闸）。
        identity(tenant, "admin", "ADMIN");
        String narrowedSession = session(tenant, "admin", code, privateOnly);
        assertEquals(
                "SCOPE_WRITE_DENIED",
                call(
                        adminMvc,
                        "POST",
                        "/v1/apps/" + code + "/memories",
                        Json.write(
                                Map.of(
                                        "requestId", "h34-req-shared-narrow",
                                        "content", "策略收窄后的共享写入",
                                        "kind", "PREFERENCE",
                                        "scope", "SHARED",
                                        "sessionId", narrowedSession)),
                        403)
                        .path("code")
                        .asText());
    }

    // ------------------------------------------------------------------
    // A09-③：PATCH expectedVersion CAS——过期 409 MEMORY_VERSION_CONFLICT
    // ------------------------------------------------------------------

    @Test
    void patchUsesExpectedVersionCasWithConflictOnStaleVersion() throws Exception {
        String tenant = "h34-t-" + UUID.randomUUID(), code = "h34-app-" + UUID.randomUUID().toString().substring(0, 8);
        identity(tenant, "alice", "USER");
        var mvc = mvc(memories());
        String memoryId =
                call(
                        mvc,
                        "POST",
                        "/v1/apps/" + code + "/memories",
                        Json.write(
                                Map.of(
                                        "requestId", "h34-req-cas",
                                        "content", "原始正文",
                                        "kind", "PREFERENCE",
                                        "tags", List.of("原始"))),
                        200)
                        .path("data")
                        .path("memoryId")
                        .asText();

        // 缺 expectedVersion 400；过期版本 409 MEMORY_VERSION_CONFLICT。
        assertEquals(
                "MEMORY_EXPECTED_VERSION_INVALID",
                call(mvc, "PATCH", "/v1/apps/" + code + "/memories/" + memoryId, "{\"content\":\"新正文\"}", 400)
                        .path("code")
                        .asText());
        assertEquals(
                "MEMORY_VERSION_CONFLICT",
                call(
                        mvc,
                        "PATCH",
                        "/v1/apps/" + code + "/memories/" + memoryId,
                        "{\"content\":\"过期更新\",\"expectedVersion\":5}",
                        409)
                        .path("code")
                        .asText());

        // 正确版本：content+tags 更新成功，version 推进到 2。
        var updated =
                call(
                        mvc,
                        "PATCH",
                        "/v1/apps/" + code + "/memories/" + memoryId,
                        Json.write(
                                Map.of("content", "更新后正文", "tags", List.of("更新"), "expectedVersion", 1)),
                        200)
                        .path("data");
        assertEquals(2, updated.path("version").asInt());
        assertEquals("更新后正文", updated.path("content").asText());
        assertEquals("ACTIVE", updated.path("status").asText(), "内容更新不改状态");

        // 基于旧版本 1 的第二次更新必须冲突（CAS 保护并发）。
        assertEquals(
                "MEMORY_VERSION_CONFLICT",
                call(
                        mvc,
                        "PATCH",
                        "/v1/apps/" + code + "/memories/" + memoryId,
                        "{\"content\":\"再更新\",\"expectedVersion\":1}",
                        409)
                        .path("code")
                        .asText());
    }

    // ------------------------------------------------------------------
    // A09-④：DRAFT 审批走 PATCH status 增量——私有=来源用户、共享=ADMIN；拒绝→REVOKED
    // ------------------------------------------------------------------

    @Test
    void draftApprovalViaPatchStatusActivatesOrRejects() throws Exception {
        String tenant = "h34-t-" + UUID.randomUUID(), code = "h34-app-" + UUID.randomUUID().toString().substring(0, 8);
        var service = memories();
        var alice = new Actor(tenant, "alice", Set.of("USER"), Map.of());
        // 引擎内部提取入口（非 HTTP）：模型提取按 writeScopes 写 DRAFT（默认策略仅 PRIVATE）。
        var draft =
                service.extractDraft(
                        code, alice, "EXPERIENCE", "PRIVATE", "提取草稿：低照度帧段单独标注", List.of("提取草稿"), null, null);
        String draftId = String.valueOf(draft.get("memoryId"));
        assertEquals("DRAFT", draft.get("status"));
        identity(tenant, "alice", "USER");
        var mvc = mvc(service);

        // DRAFT 不进默认（ACTIVE）列表，status=DRAFT 过滤可见（确认中心待办形状）。
        assertEquals(0, call(mvc, "GET", "/v1/apps/" + code + "/memories", null, 200).path("data").path("total").asInt());
        assertEquals(
                1,
                call(mvc, "GET", "/v1/apps/" + code + "/memories?status=DRAFT", null, 200)
                        .path("data")
                        .path("total")
                        .asInt());

        // 确认→ACTIVE：进默认列表，version 推进。
        var approved =
                call(
                        mvc,
                        "PATCH",
                        "/v1/apps/" + code + "/memories/" + draftId,
                        "{\"status\":\"ACTIVE\",\"expectedVersion\":1}",
                        200)
                        .path("data");
        assertEquals("ACTIVE", approved.path("status").asText());
        assertEquals(2, approved.path("version").asInt());
        assertEquals(1, call(mvc, "GET", "/v1/apps/" + code + "/memories", null, 200).path("data").path("total").asInt());

        // 拒绝→REVOKED：立即退出检索（单条 404）。
        var rejected =
                service.extractDraft(code, alice, "EXPERIENCE", "PRIVATE", "另一条待拒绝草稿", List.of(), null, null);
        assertEquals(
                "REVOKED",
                call(
                        mvc,
                        "PATCH",
                        "/v1/apps/" + code + "/memories/" + rejected.get("memoryId"),
                        "{\"status\":\"REVOKED\",\"expectedVersion\":1}",
                        200)
                        .path("data")
                        .path("status")
                        .asText());
        assertEquals(
                "MEMORY_NOT_FOUND",
                call(mvc, "GET", "/v1/apps/" + code + "/memories/" + rejected.get("memoryId"), null, 404)
                        .path("code")
                        .asText());

        // 共享草稿由 ADMIN 确认：USER 审批共享层 403 MEMORY_STATUS_FORBIDDEN，ADMIN 放行。
        var adminActor = new Actor(tenant, "admin", Set.of("ADMIN"), Map.of());
        var sharedPolicy =
                new Memories.MemoryPolicy(Set.of("PRIVATE", "SHARED"), Set.of("PRIVATE", "SHARED"), Set.of(), 5);
        var sharedDraft =
                service.extractDraft(
                        code, adminActor, "PREFERENCE", "SHARED", "共享草稿：输出统一中文摘要", List.of(), null, sharedPolicy);
        String sharedDraftId = String.valueOf(sharedDraft.get("memoryId"));
        identity(tenant, "alice", "USER");
        assertEquals(
                "MEMORY_STATUS_FORBIDDEN",
                call(
                        mvc,
                        "PATCH",
                        "/v1/apps/" + code + "/memories/" + sharedDraftId,
                        "{\"status\":\"ACTIVE\",\"expectedVersion\":1}",
                        403)
                        .path("code")
                        .asText());
        identity(tenant, "admin", "ADMIN");
        assertEquals(
                "ACTIVE",
                call(
                        mvc,
                        "PATCH",
                        "/v1/apps/" + code + "/memories/" + sharedDraftId,
                        "{\"status\":\"ACTIVE\",\"expectedVersion\":1}",
                        200)
                        .path("data")
                        .path("status")
                        .asText());
    }

    // ------------------------------------------------------------------
    // A09-⑤：删除先置 REVOKED 立即退出列表与检索；幂等；旧版任务下一次检索不再引用
    // ------------------------------------------------------------------

    @Test
    void deleteRevokesExitsListAndRetrievalAndIsIdempotent() throws Exception {
        String tenant = "h34-t-" + UUID.randomUUID(), code = "h34-app-" + UUID.randomUUID().toString().substring(0, 8);
        identity(tenant, "alice", "USER");
        var service = memories();
        var mvc = mvc(service);
        String memoryId =
                call(
                        mvc,
                        "POST",
                        "/v1/apps/" + code + "/memories",
                        Json.write(Map.of("requestId", "h34-req-del", "content", "将删除的观测偏好", "kind", "PREFERENCE")),
                        200)
                        .path("data")
                        .path("memoryId")
                        .asText();

        // 缺 requestId 400（接口稿 DELETE 请求体 {requestId}）。
        assertEquals(
                "MEMORY_DELETE_REQUEST_INVALID",
                call(mvc, "DELETE", "/v1/apps/" + code + "/memories/" + memoryId, "{}", 400)
                        .path("code")
                        .asText());

        // 删除→REVOKED；列表与 retrieve 检索即刻退出；单条 404。
        var deleted =
                call(mvc, "DELETE", "/v1/apps/" + code + "/memories/" + memoryId, "{\"requestId\":\"h34-del-1\"}", 200)
                        .path("data");
        assertEquals("REVOKED", deleted.path("status").asText());
        assertEquals(memoryId, deleted.path("memoryId").asText());
        assertEquals(0, call(mvc, "GET", "/v1/apps/" + code + "/memories", null, 200).path("data").path("total").asInt());
        assertEquals(
                "MEMORY_NOT_FOUND",
                call(mvc, "GET", "/v1/apps/" + code + "/memories/" + memoryId, null, 404).path("code").asText());
        var actor = new Actor(tenant, "alice", Set.of("USER"), Map.of());
        assertTrue(
                service.retrieve(code, actor, "观测偏好", Memories.MemoryPolicy.restricted(), 10).isEmpty(),
                "REVOKED 立即退出任务检索（旧版任务下一次检索不再引用）");

        // 幂等：重复 DELETE 返回原终态；未知条目 404。
        var replay =
                call(mvc, "DELETE", "/v1/apps/" + code + "/memories/" + memoryId, "{\"requestId\":\"h34-del-2\"}", 200)
                        .path("data");
        assertEquals("REVOKED", replay.path("status").asText());
        assertEquals(
                "MEMORY_NOT_FOUND",
                call(mvc, "DELETE", "/v1/apps/" + code + "/memories/h34_missing", "{\"requestId\":\"h34-del-3\"}", 404)
                        .path("code")
                        .asText());
    }

    // ------------------------------------------------------------------
    // A09-⑥：检索顺序=权限过滤→scope/标签/文本相关性→topK（含有效期过滤）
    // ------------------------------------------------------------------

    @Test
    void retrieveFiltersByPermissionThenOrdersByRelevanceThenTruncates() throws Exception {
        String tenant = "h34-t-" + UUID.randomUUID(), code = "h34-app-" + UUID.randomUUID().toString().substring(0, 8);
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        // 本人 PRIVATE（标签+正文双命中）→ 本人 PRIVATE（无命中）→ 他人 PRIVATE（被权限过滤）→
        // 共享（策略授权后可见）→ 已过期（退出检索）。
        memory(tenant, code, "PRIVATE", "alice", "冬季观测窗口优先早于22点", "[\"观测\"]", "ACTIVE", base.plusSeconds(1), null);
        memory(tenant, code, "PRIVATE", "alice", "导出格式默认CSV", "[]", "ACTIVE", base.plusSeconds(2), null);
        memory(tenant, code, "PRIVATE", "bob", "bob 的私有观测偏好", "[\"观测\"]", "ACTIVE", base.plusSeconds(3), null);
        memory(tenant, code, "SHARED", "admin", "工作区共享观测经验", "[\"观测\"]", "ACTIVE", base.plusSeconds(4), null);
        memory(tenant, code, "PRIVATE", "alice", "过期观测偏好不应出现", "[\"观测\"]", "ACTIVE", base.plusSeconds(5),
                base.minusSeconds(3600));
        var actor = new Actor(tenant, "alice", Set.of("USER"), Map.of());
        var policy =
                Memories.MemoryPolicy.from(
                        Json.read(
                                "{\"memoryPolicy\":{\"readScopes\":[\"PRIVATE\",\"SHARED\"],\"writeScopes\":[\"PRIVATE\"]}}"));
        var result = memories().retrieve(code, actor, "观测", policy, 100);
        var ids = new ArrayList<String>();
        for (var item : result) ids.add(String.valueOf(item.get("memoryId")));
        // 权限先行：bob 私有条目与过期条目无论相关性多高都不出现。
        assertEquals(3, ids.size(), "检索结果=" + ids);
        // scope 排序：本人 PRIVATE 优先于 SHARED；无命中条目靠后。
        assertEquals("冬季观测窗口优先早于22点", result.get(0).get("content"), "PRIVATE 双命中第一");
        assertEquals("导出格式默认CSV", result.get(1).get("content"), "PRIVATE 零命中次之（scope 先于相关性）");
        assertEquals("SHARED", result.get(2).get("scope"), "SHARED 命中但 scope 位次靠后");
        for (var item : result)
            assertTrue(item.containsKey("version"), "检索结果必须携带 version（装配材料清单审计）");

        // 预算截取：topK=1 只留第一。
        assertEquals(1, memories().retrieve(code, actor, "观测", policy, 1).size());

        // 策略收窄（仅 PRIVATE）：共享条目整体退出检索。
        var narrowed =
                Memories.MemoryPolicy.from(
                        Json.read("{\"memoryPolicy\":{\"readScopes\":[\"PRIVATE\"],\"writeScopes\":[\"PRIVATE\"]}}"));
        var narrowedResult = memories().retrieve(code, actor, "观测", narrowed, 100);
        assertEquals(2, narrowedResult.size(), "策略收窄后共享条目退出：n=" + narrowedResult.size());
        for (var item : narrowedResult) assertEquals("PRIVATE", item.get("scope"));
    }

    // ------------------------------------------------------------------
    // A09-⑦：requestId 幂等——同参重放原条目/异参 409 REQUEST_REPLAY
    // ------------------------------------------------------------------

    @Test
    void requestIdReplayReturnsOriginalAndConflictsOnDifferentParams() throws Exception {
        String tenant = "h34-t-" + UUID.randomUUID(), code = "h34-app-" + UUID.randomUUID().toString().substring(0, 8);
        identity(tenant, "alice", "USER");
        var mvc = mvc(memories());
        String body =
                Json.write(
                        Map.of(
                                "requestId", "h34-req-idem",
                                "content", "幂等保存正文",
                                "kind", "PREFERENCE",
                                "tags", List.of("幂等")));
        var first = call(mvc, "POST", "/v1/apps/" + code + "/memories", body, 200).path("data");
        var replay = call(mvc, "POST", "/v1/apps/" + code + "/memories", body, 200).path("data");
        assertEquals(first.path("memoryId").asText(), replay.path("memoryId").asText(), "同号同参重放原条目");
        assertEquals(1, call(mvc, "GET", "/v1/apps/" + code + "/memories", null, 200).path("data").path("total").asInt(),
                "重放不产生新条目");

        // 同号异参：409 REQUEST_REPLAY。
        assertEquals(
                "REQUEST_REPLAY",
                call(
                        mvc,
                        "POST",
                        "/v1/apps/" + code + "/memories",
                        Json.write(Map.of("requestId", "h34-req-idem", "content", "异参正文", "kind", "PREFERENCE")),
                        409)
                        .path("code")
                        .asText());
    }

    // ------------------------------------------------------------------
    // 列表分页与过滤：page/pageSize 校验、tags OR 语义、q 命中正文、scope 过滤
    // ------------------------------------------------------------------

    @Test
    void listPaginationValidationAndFilterSemantics() throws Exception {
        String tenant = "h34-t-" + UUID.randomUUID(), code = "h34-app-" + UUID.randomUUID().toString().substring(0, 8);
        identity(tenant, "alice", "USER");
        var mvc = mvc(memories());
        call(mvc, "POST", "/v1/apps/" + code + "/memories",
                Json.write(Map.of("requestId", "h34-p1", "content", "甲条目", "kind", "PREFERENCE", "tags", List.of("标签甲"))), 200);
        call(mvc, "POST", "/v1/apps/" + code + "/memories",
                Json.write(Map.of("requestId", "h34-p2", "content", "乙条目", "kind", "EXPERIENCE", "tags", List.of("标签乙"))), 200);
        call(mvc, "POST", "/v1/apps/" + code + "/memories",
                Json.write(Map.of("requestId", "h34-p3", "content", "丙条目提及甲关键词", "kind", "EXPERIENCE")), 200);

        // 非法分页 400；pageSize 上限 100。
        assertEquals("MEMORY_PAGE_INVALID", call(mvc, "GET", "/v1/apps/" + code + "/memories?page=0", null, 400).path("code").asText());
        assertEquals("MEMORY_PAGE_INVALID", call(mvc, "GET", "/v1/apps/" + code + "/memories?pageSize=101", null, 400).path("code").asText());
        assertEquals("MEMORY_PAGE_INVALID", call(mvc, "GET", "/v1/apps/" + code + "/memories?pageSize=abc", null, 400).path("code").asText());
        assertEquals("MEMORY_STATUS_FILTER_INVALID", call(mvc, "GET", "/v1/apps/" + code + "/memories?status=REVOKED", null, 400).path("code").asText());

        var page = call(mvc, "GET", "/v1/apps/" + code + "/memories?page=2&pageSize=2", null, 200).path("data");
        assertEquals(3, page.path("total").asInt());
        assertEquals(2, page.path("page").asInt());
        assertEquals(1, page.path("memories").size(), "第 2 页只剩 1 条");

        // tags 过滤 OR 语义（任一命中即入选）。MockMvc 对 %XX 转义按 ISO-8859-1 解码，
        // 中文参数与上方 q 一致直接以原字符放进模板。
        var byTags = call(mvc, "GET", "/v1/apps/" + code + "/memories?tags=标签甲,标签乙", null, 200)
                .path("data");
        assertEquals(2, byTags.path("total").asInt(), "OR 语义命中两条");
        // q 命中正文或标签。
        assertEquals(2, call(mvc, "GET", "/v1/apps/" + code + "/memories?q=甲", null, 200).path("data").path("total").asInt(),
                "q=甲 命中『甲条目』与『丙条目提及甲关键词』");
        // kind 无过滤参数——scope 过滤默认全集（本用例全为 PRIVATE）。
        assertEquals(3, call(mvc, "GET", "/v1/apps/" + code + "/memories?scope=PRIVATE", null, 200).path("data").path("total").asInt());
    }

    // ------------------------------------------------------------------
    // 网关/authority 登记（交付物 2）：五条路径目录行必须存在（照 S2-1 先例断言）
    // ------------------------------------------------------------------

    @Test
    void gatewayCatalogRegistersAllFiveMemoryRoutes() throws Exception {
        record Expected(String method, String template, String action) {}
        var expected =
                List.of(
                        new Expected("GET", "/v1/apps/{code}/memories", "proxy.engine.get.MemoryController.list"),
                        new Expected("POST", "/v1/apps/{code}/memories", "proxy.engine.post.MemoryController.create"),
                        new Expected("GET", "/v1/apps/{code}/memories/{memoryId}", "proxy.engine.get.MemoryController.get"),
                        new Expected("PATCH", "/v1/apps/{code}/memories/{memoryId}", "proxy.engine.patch.MemoryController.patch"),
                        new Expected("DELETE", "/v1/apps/{code}/memories/{memoryId}", "proxy.engine.delete.MemoryController.delete"));
        try (var input = getClass().getResourceAsStream("/agent-service-authority.json")) {
            assertNotNull(input, "agent-service-authority.json 必须在测试类路径上（admin-common 依赖）");
            var catalog = Json.read(new String(input.readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
            for (var row : expected) {
                var matches = new ArrayList<JsonNode>();
                for (var entry : catalog) {
                    if (row.method().equals(entry.path("method").asText())
                            && row.template().equals(entry.path("template").asText())
                            && "engine".equals(entry.path("target").asText())
                            && "gateway".equals(entry.path("caller").asText())
                            && "delegated".equals(entry.path("mode").asText())) {
                        matches.add(entry);
                        assertEquals(row.action(), entry.path("action").asText(), "委派动作必须指向本端点");
                        assertFalse(entry.path("integrationAllowed").asBoolean(false), "记忆端点不开放集成票据面");
                    }
                }
                assertEquals(1, matches.size(), row.method() + " " + row.template() + " 必须恰登记一行");
            }
        }
    }
}
