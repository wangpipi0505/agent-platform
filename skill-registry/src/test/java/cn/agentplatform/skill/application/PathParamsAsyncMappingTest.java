package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.infra.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PLAT-06（T06）：SUBMIT_QUERY 声明式路径映射与 catch 降级修正。
 *
 * <p>T06.1：reconciliation.pathParams 声明"平台字段→远端占位符"，保存期校验占位符与映射一一对应，
 * 缺映射返回 400 ASYNC_PATH_PARAM_MISSING（带字段路径与修复建议）；旧 {jobId}/{idempotencyKey}
 * 字符串替换继续可用；空 httpMapping 对 SUBMIT_QUERY 视为缺省。
 *
 * <p>T06.2：非 REMOTE_FAILED 的 4xx 异常不再一律折叠成 UNKNOWN——远端对查询的明确拒绝（401/403）
 * 按原语义映射为 NO_PERMISSION 可读失败；异步协议拒绝（ASYNC_* 400）按原码透出而不是
 * OUTPUT_INVALID/UNKNOWN；仍属不确定的查询失败（404/5xx）保持 UNKNOWN 但消息带 HTTP 状态与
 * 远端业务码，cause 保留。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class PathParamsAsyncMappingTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager tx;
    String tenant, code, key;
    long call;
    HttpServer server;
    SkillCatalog catalog;
    SkillClients clients;
    SkillExecution executor;
    Map<String, Object> request;
    final AtomicInteger submits = new AtomicInteger(), queries = new AtomicInteger();
    volatile String state = "RUNNING";
    volatile int queryStatus = 200;
    volatile String queryCode = null;
    volatile String queryMessage = null;
    volatile String lastQueryPath = "";
    volatile String lastQueryQuery = "";
    /** 远端把任务叫 taskId、把调用方密钥叫 requestKey：占位符名与平台字段名完全解耦的真实形态。 */
    volatile boolean remoteNaming = true;

    @BeforeAll
    static void database() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_skill_async_test",
                        "agent_v12",
                        System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure()
                .dataSource(ds)
                .schemas("skill")
                .defaultSchema("skill")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(ds);
        tx = new DataSourceTransactionManager(ds);
    }

    @BeforeEach
    void setup() throws Exception {
        tenant = "pathparam_" + UUID.randomUUID();
        code = "pathparam";
        key = "key/" + UUID.randomUUID();
        call = Math.abs(UUID.randomUUID().getMostSignificantBits());
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("pathparam-owner")
                        .claim("tenant_id", tenant)
                        .claim("roles", List.of("ADMIN", "EDITOR", "SERVICE"))
                        .claim("attributes", Map.of("region", List.of("A")))
                        .claim("token_use", "service")
                        .audience(List.of("agent-platform-internal"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(600))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        submits.set(0);
        queries.set(0);
        state = "RUNNING";
        queryStatus = 200;
        queryCode = null;
        queryMessage = null;
        lastQueryPath = "";
        lastQueryQuery = "";
        remoteNaming = true;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/submit",
                exchange -> {
                    submits.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    Map<String, Object> acknowledgement = new LinkedHashMap<>();
                    if (remoteNaming) {
                        acknowledgement.put("taskId", "task-1");
                        acknowledgement.put("requestKey", key);
                    } else {
                        acknowledgement.put("jobId", "job-1");
                        acknowledgement.put("idempotencyKey", key);
                    }
                    byte[] body =
                            Json.write(acknowledgement).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(202, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/tasks",
                exchange -> {
                    queries.incrementAndGet();
                    lastQueryPath = exchange.getRequestURI().getPath();
                    lastQueryQuery = exchange.getRequestURI().getQuery();
                    exchange.getRequestBody().readAllBytes();
                    byte[] body;
                    if (queryStatus != 200) {
                        var errorBody = new LinkedHashMap<String, Object>();
                        if (queryCode != null) errorBody.put("code", queryCode);
                        if (queryMessage != null) errorBody.put("message", queryMessage);
                        body = Json.write(errorBody).getBytes(StandardCharsets.UTF_8);
                    } else {
                        Map<String, Object> poll = new LinkedHashMap<>();
                        if (remoteNaming) {
                            poll.put("taskId", "task-1");
                            poll.put("requestKey", key);
                        } else {
                            poll.put("jobId", "job-1");
                            poll.put("idempotencyKey", key);
                        }
                        poll.put("status", state);
                        poll.put("result", Map.of("value", 7));
                        body = Json.write(poll).getBytes(StandardCharsets.UTF_8);
                    }
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(queryStatus, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        clients = mock(SkillClients.class);
        catalog = new SkillCatalog(db, tx, clients, "http://unused");
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        server.stop(0);
        // 与 AsyncResultEndpointFailureTest 同口径：事实与绑定按租户清掉；对账流水有外键，先清；
        // 已发布技能版本受 protect_published_contract 触发器保护且租户唯一，留给库内不清理（不跨用例可见）。
        db.update("delete from skill.reconciliation_note where tenant_id=?", tenant);
        db.update("delete from skill.execution_fact where tenant_id=?", tenant);
        db.update("delete from skill.external_job_binding where tenant_id=?", tenant);
    }

    /** 提交响应与查询响应都回显调用方幂等键（远端字段名 requestKey 语义等价，echo 走声明路径）。 */
    Map<String, Object> contract() {
        var c = new LinkedHashMap<String, Object>();
        c.put("code", code);
        c.put("name", "pathparam");
        c.put("type", "HTTP");
        c.put("description", "声明式路径映射查询任务结果");
        c.put("endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/submit");
        c.put("invocationKind", "SUBMIT_QUERY");
        c.put("jobIdPath", "$.taskId");
        c.put("inputSchema", Map.of("type", "object"));
        c.put("outputSchema", Map.of("type", "object", "required", List.of("value")));
        c.put("timeoutMs", 5000);
        c.put(
                "execution",
                Map.of(
                        "readonly",
                        true,
                        "sideEffect",
                        false,
                        "idempotent",
                        true,
                        "cancellable",
                        false));
        c.put(
                "guard",
                Map.of("roles", List.of("SERVICE"), "attributes", Map.of("region", List.of("A"))));
        var reconciliation = new LinkedHashMap<String, Object>();
        reconciliation.put(
                "endpoint",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/tasks/{remoteTaskId}");
        reconciliation.put("method", "GET");
        reconciliation.put("donePath", "$.status");
        reconciliation.put("doneValue", "DONE");
        reconciliation.put("idempotencyKeyPath", "$.requestKey");
        c.put("reconciliation", reconciliation);
        return c;
    }

    long createAndPublish(Map<String, Object> body) {
        long version = ((Number) catalog.create(body).get("skillVersionId")).longValue();
        db.update("update skill.skill_version set status='PUBLISHED' where id=?", version);
        return version;
    }

    void prepareRequestAndExecutor(long version) {
        request =
                new LinkedHashMap<>(
                        Map.of(
                                "callId",
                                call,
                                "runId",
                                "r_pathparam",
                                "nodeInstanceId",
                                123,
                                "attemptId",
                                456,
                                "skillVersionId",
                                version,
                                "arguments",
                                Map.of("region", "A"),
                                "idempotencyKey",
                                key,
                                "leaseOwner",
                                "worker"));
        var authority =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "claimed",
                                true,
                                "status",
                                "UNKNOWN",
                                "skill_version_id",
                                version,
                                "skill_code",
                                code,
                                "args_digest",
                                Digest.sha256(Json.canonical(request.get("arguments"))),
                                "node_instance_id",
                                123,
                                "runId",
                                "r_pathparam",
                                "trigger_type",
                                "TASK",
                                "idempotency_key",
                                key,
                                "mock_snapshot",
                                Map.of()));
        authority.put("attempt_id", 456);
        authority.put("skill_contract_sha256", catalog.versionId(version).get("sha256"));
        when(clients.get(anyString()))
                .thenAnswer(
                        i ->
                                Json.tree(
                                        i.<String>getArgument(0).contains("/admin/runs/")
                                                ? Map.of(
                                                        "runId",
                                                        "r_pathparam",
                                                        "status",
                                                        "RUNNING",
                                                        "nodes",
                                                        List.of(
                                                                Map.of(
                                                                        "nodeInstanceId",
                                                                        123,
                                                                        "status",
                                                                        "RUNNING")))
                                                : authority));
        when(clients.post(anyString(), any()))
                .thenAnswer(
                        i -> {
                            String url = i.getArgument(0);
                            return Json.tree(
                                    url.endsWith("/claim")
                                            ? authority
                                            : url.endsWith("/submitted")
                                                    ? Map.of("submitted", true)
                                                    : Map.of("accepted", true));
                        });
        executor =
                new SkillExecution(
                        db,
                        tx,
                        catalog,
                        new SkillAdapters(
                                new ArtifactStore(db),
                                new AlgorithmAdapter(db),
                                new HttpAsyncAdapter(db)),
                        clients,
                        "http://engine.test");
    }

    /** T06.1 验收①：只用 {remoteTaskId} + pathParams 的契约能通过保存，提交与真实对账占位符替换正确。 */
    @Test
    void pathParamsContractSavesSubmitsAndReconcilesWithSubstitution() {
        var body = contract();
        @SuppressWarnings("unchecked")
        var reconciliation = (Map<String, Object>) body.get("reconciliation");
        reconciliation.put(
                "pathParams", Map.of("remoteTaskId", Map.of("from", "externalJobId")));
        prepareRequestAndExecutor(createAndPublish(body));
        assertEquals("UNKNOWN", executor.execute(code, request).get("status"));
        assertEquals(1, submits.get());
        state = "DONE";
        var recovered = executor.reconcile(call);
        assertEquals("SUCCEEDED", recovered.get("status"), Json.write(recovered));
        assertEquals(
                7, Json.tree(recovered.get("result")).path("structured").path("value").asInt());
        // 远端实际收到的查询路径由声明式映射构造：占位符 remoteTaskId ← 平台字段 externalJobId。
        assertEquals("/tasks/task-1", lastQueryPath, "对账查询必须用声明式映射替换占位符");
        assertEquals(1, submits.get());
    }

    /** T06.1 验收①补充：两个占位符分别来自不同平台字段，且可落在查询串位置。 */
    @Test
    void pathParamsCoverBothPlatformFieldsAcrossPathAndQuery() {
        var body = contract();
        @SuppressWarnings("unchecked")
        var reconciliation = (Map<String, Object>) body.get("reconciliation");
        reconciliation.put(
                "endpoint",
                "http://127.0.0.1:"
                        + server.getAddress().getPort()
                        + "/tasks/{remoteTaskId}?caller={callKey}");
        reconciliation.put(
                "pathParams",
                Map.of(
                        "remoteTaskId", Map.of("from", "externalJobId"),
                        "callKey", Map.of("from", "idempotencyKey")));
        prepareRequestAndExecutor(createAndPublish(body));
        assertEquals("UNKNOWN", executor.execute(code, request).get("status"));
        state = "DONE";
        var recovered = executor.reconcile(call);
        assertEquals("SUCCEEDED", recovered.get("status"), Json.write(recovered));
        assertEquals("/tasks/task-1", lastQueryPath);
        assertEquals("caller=" + key, lastQueryQuery, "查询串占位符必须替换为原幂等键原文");
        assertEquals(1, submits.get());
    }

    /** T06.1 验收②：占位符缺映射 → 400 ASYNC_PATH_PARAM_MISSING，报错带字段路径与修复建议。 */
    @Test
    void missingMappingRejectedWithFieldPathAndSuggestion() {
        var body = contract();
        var ex =
                assertThrows(
                        PlatformException.class,
                        () -> catalog.create(body),
                        "{remoteTaskId} 无 pathParams 映射必须在保存期拒绝");
        assertEquals(400, ex.status());
        assertEquals("ASYNC_PATH_PARAM_MISSING", ex.code());
        assertTrue(
                ex.getMessage().contains("reconciliation.pathParams.remoteTaskId"),
                "报错必须带字段路径: " + ex.getMessage());
        assertTrue(
                ex.getMessage().contains("externalJobId"),
                "报错必须带修复建议（平台字段取值）: " + ex.getMessage());
    }

    /** T06.1 验收⑥：pathParams 声明了端点未使用的占位符 → 一一对应校验拒绝。 */
    @Test
    void unmappedPathParamsEntryRejected() {
        var body = contract();
        @SuppressWarnings("unchecked")
        var reconciliation = (Map<String, Object>) body.get("reconciliation");
        reconciliation.put(
                "pathParams",
                Map.of(
                        "remoteTaskId", Map.of("from", "externalJobId"),
                        "unusedKey", Map.of("from", "idempotencyKey")));
        var ex = assertThrows(PlatformException.class, () -> catalog.create(body));
        assertEquals(400, ex.status());
        assertTrue(
                ex.getMessage().contains("reconciliation.pathParams.unusedKey"),
                "报错必须定位到多余映射的字段路径: " + ex.getMessage());
    }

    /** T06.1 验收③：旧 {jobId} 契约零回归（同链路提交与对账）。 */
    @Test
    void legacyJobIdPlaceholderStillSavesSubmitsAndReconciles() {
        remoteNaming = false;
        var body = contract();
        body.put("jobIdPath", "$.jobId");
        body.put("idempotencyKeyPath", "$.idempotencyKey");
        @SuppressWarnings("unchecked")
        var reconciliation = (Map<String, Object>) body.get("reconciliation");
        reconciliation.put(
                "endpoint",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/tasks/{jobId}");
        reconciliation.put("idempotencyKeyPath", "$.idempotencyKey");
        prepareRequestAndExecutor(createAndPublish(body));
        assertEquals("UNKNOWN", executor.execute(code, request).get("status"));
        state = "DONE";
        var recovered = executor.reconcile(call);
        assertEquals("SUCCEEDED", recovered.get("status"), Json.write(recovered));
        assertEquals("/tasks/job-1", lastQueryPath);
        assertEquals(1, submits.get());
    }

    /** T06.1 验收④：空 httpMapping 对 SUBMIT_QUERY 视为缺省，保存通过且不落 httpMapping 键。 */
    @Test
    void emptyHttpMappingTreatedAsAbsentForSubmitQuery() {
        var body = contract();
        @SuppressWarnings("unchecked")
        var reconciliation = (Map<String, Object>) body.get("reconciliation");
        reconciliation.put(
                "pathParams", Map.of("remoteTaskId", Map.of("from", "externalJobId")));
        body.put("httpMapping", Map.of());
        long version = createAndPublish(body);
        assertNull(
                catalog.versionId(version).get("httpMapping"),
                "空 httpMapping 必须在保存时按缺省移除");
    }

    /**
     * T06.2：异步协议拒绝（ASYNC_* 400）不再被 SkillExecution 兜底 catch 折叠成
     * OUTPUT_INVALID/UNKNOWN——它是"本次调用为何没有运行"的结论，必须按原码原状态透出。
     */
    @Test
    void asyncProtocolRejectionKeepsItsCodeInsteadOfFoldingToUnknown() {
        // 用旧 {jobId} 端点让契约能通过注册期（注册期校验不是本用例对象），再在草稿态注入
        // multipart 声明模拟存量/绕过契约后发布：multipart 拒绝发生在任何出站请求之前，
        // 被测对象是执行器兜底 catch 的折叠行为（发布行受触发器保护，必须先改契约再置 PUBLISHED）。
        remoteNaming = false;
        var body = contract();
        body.put("jobIdPath", "$.jobId");
        @SuppressWarnings("unchecked")
        var reconciliation = (Map<String, Object>) body.get("reconciliation");
        reconciliation.put(
                "endpoint",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/tasks/{jobId}");
        reconciliation.remove("idempotencyKeyPath");
        long version = ((Number) catalog.create(body).get("skillVersionId")).longValue();
        var mutated = new LinkedHashMap<String, Object>(body);
        mutated.put(
                "httpMapping",
                Map.of(
                        "multipart",
                        Map.of(
                                "fields",
                                List.of(Map.of("name", "payloadType", "from", "payloadType")))));
        String sha = Digest.sha256(Json.canonical(mutated));
        db.update(
                "update skill.skill_version set contract=?::jsonb,sha256=? where id=?",
                Json.write(mutated),
                sha,
                version);
        db.update("update skill.skill_version set status='PUBLISHED' where id=?", version);
        prepareRequestAndExecutor(version);
        var ex =
                assertThrows(
                        PlatformException.class,
                        () -> executor.execute(code, request),
                        "ASYNC_* 400 必须原样透出，而不是折叠成 FAILED/OUTPUT_INVALID 信封");
        assertEquals(400, ex.status());
        assertEquals("ASYNC_MULTIPART_UNSUPPORTED", ex.code());
    }

    /**
     * T06.2 验收⑤：远端对查询的明确拒绝（403）不再折叠成 UNKNOWN——按原语义映射为 NO_PERMISSION
     * 可读失败，携带远端业务码与原文；外部任务绑定行保持 SUBMITTED（查询失败≠外部任务失败）。
     */
    @Test
    void queryAuthRejectionSurfacesNoPermissionInsteadOfUnknown() {
        var body = contract();
        @SuppressWarnings("unchecked")
        var reconciliation = (Map<String, Object>) body.get("reconciliation");
        reconciliation.put(
                "pathParams", Map.of("remoteTaskId", Map.of("from", "externalJobId")));
        prepareRequestAndExecutor(createAndPublish(body));
        assertEquals("UNKNOWN", executor.execute(code, request).get("status"));
        queryStatus = 403;
        queryCode = "AUTH_EXPIRED";
        queryMessage = "token expired";
        var result = executor.reconcile(call);
        assertEquals(
                "FAILED",
                result.get("status"),
                "查询被 403 明确拒绝时不得折叠成 UNKNOWN 无限轮询: " + Json.write(result));
        assertEquals("NO_PERMISSION", result.get("errorSemantic"));
        JsonNode error = Json.tree(result.get("result")).path("error");
        assertEquals("NO_PERMISSION", error.path("semantic").asText());
        assertEquals("AUTH_EXPIRED", error.path("code").asText(), "远端业务码必须透传");
        assertTrue(
                error.path("detail").path("raw").asText().contains("token expired"),
                "远端原文必须保留: " + error.path("detail").path("raw").asText());
        // 查询失败不是外部任务的终态：绑定行不得被改写成 FAILED。
        assertEquals(
                "SUBMITTED",
                db.queryForObject(
                        "select status from skill.external_job_binding where tenant_id=?"
                                + " and call_id=?",
                        String.class,
                        tenant,
                        call));
    }

    /**
     * T06.2：仍属不确定的查询失败（404，任务可能尚未可见）保持 UNKNOWN 继续对账，但原因必须
     * 可读——携带 HTTP 状态，不再是无法定位的"异步查询未返回确认"。
     */
    @Test
    void queryNotFoundStaysUnknownWithReadableStatus() {
        var body = contract();
        @SuppressWarnings("unchecked")
        var reconciliation = (Map<String, Object>) body.get("reconciliation");
        reconciliation.put(
                "pathParams", Map.of("remoteTaskId", Map.of("from", "externalJobId")));
        prepareRequestAndExecutor(createAndPublish(body));
        queryStatus = 404;
        queryCode = "TASK_NOT_VISIBLE";
        var result = executor.execute(code, request);
        assertEquals("UNKNOWN", result.get("status"), "404 属任务暂不可见，必须继续走对账");
        String reason = Json.tree(result.get("result")).path("error").path("reason").asText();
        assertTrue(reason.contains("HTTP 404"), "原因必须携带HTTP状态: " + reason);
    }
}
