package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.api.ChatSessionController;
import cn.agentplatform.engine.application.ChatSessions;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * R-16（台账 §7.6b）：会话 SSE 限流/背压——慢消费者有界缓冲断开+审计行、单主体连接上限、
 * 50 连接内存平稳、限流配置默认值在案。
 *
 * <p>验收映射（卡面三条）：①慢消费者场景不 OOM——未读积压超 {@code agent.sse.max-buffered-events}
 * （测试内反射调小）→连接被主动断开 + 记一条 SESSION_SSE_SLOW_CONSUMER_DISCONNECT 审计行；
 * ②并发 50 连接低成本压测——50 个 SSE 连接拉同一会话流，全部出帧、进程堆增量有界（宽松
 * 门槛的堆采样断言，如实登记：这是低成本模拟，非真实负载压测），且第 51 个连接被
 * 429 SESSION_SSE_CONNECTION_LIMIT 拒绝（默认上限同时验证）；③配置默认值在 application.yml
 * 在案（max-buffered-events=2000 / max-connections-per-principal=50）。
 *
 * <p>背压策略现状（照 T2-11 任务 SSE 同构先例，本卡补齐缺口）：PG（事实源）即缓冲、进程内
 * 无无界队列（每批 ≤200 事件）、onCompletion/onTimeout/onError 收口、断开不取消 turn——
 * H1-9 交付已有；本卡新增=积压上限配置化（原硬编码 2000）、慢消费者断开审计行、单主体
 * 连接上限（429+审计）。端点行为经 standalone MockMvc 真实 Controller 调用（先例
 * SessionEventsEndpointTest）。
 *
 * <p>夹具一律 h16_% 前缀双向清理，不触碰共享测试库中其他测试的行。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class SessionSseBackpressureTest {

    static JdbcTemplate db;
    static TraceStore store;
    static final String TENANT = "h16-t";

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

    /** 双向清理：仅 h16_% 夹具；活动 SSE 泵先在原服务实例上收口（泵集合按实例持有）。 */
    @BeforeEach
    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        for (ChatSessions svc : createdServices)
            for (String sid : sessions) {
                try {
                    svc.closeSessionStreams(sid);
                } catch (Exception alreadyGone) {
                    // 收口失败不影响清理链（泵线程为 daemon，随测试进程收口）。
                }
            }
        createdServices.clear();
        sessions.clear();
        db.execute("ALTER TABLE engine.session_event DISABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.session_event where session_id like 'h16_%'");
        db.execute("ALTER TABLE engine.session_event ENABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.chat_turn where session_id like 'h16_%'");
        db.update("delete from engine.chat_session where public_id like 'h16_%'");
        db.update("delete from engine.run where public_id like 'h16_%'");
    }

    /** 本测试创建的会话号与服务实例（@AfterEach 泵收口清单——泵集合按 ChatSessions 实例持有）。 */
    final List<String> sessions = new ArrayList<>();
    final List<ChatSessions> createdServices = new ArrayList<>();

    private void identity(String tenant, String principal, List<String> roles) {
        var jwt =
                Jwt.withTokenValue("verified-internal-peer")
                        .header("alg", "HS256")
                        .subject(principal)
                        .claim("tenant_id", tenant)
                        .claim("roles", roles)
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(180))
                        .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private ChatSessions service() {
        var svc = new ChatSessions(store, null, null);
        createdServices.add(svc);
        return svc;
    }

    private MockMvc mvc(ChatSessions service) {
        return MockMvcBuilders.standaloneSetup(new ChatSessionController(service))
                .setControllerAdvice(new PlatformErrors())
                .build();
    }

    private String session(String tenant, String principal, String code) {
        String id = "h16_" + UUID.randomUUID().toString().replace("-", "");
        db.update(
                "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot)"
                        + " values(?,?,?,?,'{}'::jsonb)",
                id,
                tenant,
                principal,
                code);
        sessions.add(id);
        return id;
    }

    /** 直接落事件行（append-only 触发器只禁 UPDATE/DELETE，INSERT 合法）；seq 由夹具显式给定。 */
    private void event(String sessionId, int seq, String type, String dataJson) {
        db.update(
                "insert into engine.session_event(session_id,seq,type,data) values(?,?,?,?::jsonb)",
                sessionId,
                seq,
                type,
                dataJson);
    }

    private static void setConfig(ChatSessions service, String field, int value) throws Exception {
        var f = ChatSessions.class.getDeclaredField(field);
        f.setAccessible(true);
        f.setInt(service, value);
    }

    private static int config(ChatSessions service, String field) throws Exception {
        var f = ChatSessions.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.getInt(service);
    }

    /** 轮询等待 SSE 内容出现指定片段（泵线程异步写帧）。 */
    private static String awaitContent(MvcResult result, String mustContain) throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        String content = "";
        while (System.currentTimeMillis() < deadline) {
            content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (content.contains(mustContain)) return content;
            Thread.sleep(20);
        }
        fail("SSE 内容未出现 `" + mustContain + "`: " + content);
        return content;
    }

    /** 轮询等待指定会话的活动泵全部收口（断开后 streams 集合排空）。注意：本探测会主动收口
     * 尚未自然断开的泵——审计行断言必须先于本探测（泵内 warn 先于 close，探测先行会抢跑断开）。 */
    private static void awaitStreamsClosed(ChatSessions service, String sid) throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            if (service.closeSessionStreams(sid) == 0) return;
            Thread.sleep(20);
        }
        fail("会话 " + sid + " 的 SSE 泵未在期限内收口");
    }

    private static void awaitAuditLine(AuditCapture appender, String marker, String... mustContain)
            throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (audited(appender, marker, mustContain)) return;
            Thread.sleep(20);
        }
        fail("审计行未出现 `" + marker + "`: " + appender.list);
    }

    private static final class AuditCapture
            extends ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>
            implements AutoCloseable {
        private final ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ChatSessions.class);
        private final ch.qos.logback.classic.Level previous = logger.getLevel();

        private AuditCapture() {
            list = new java.util.concurrent.CopyOnWriteArrayList<>();
            logger.setLevel(ch.qos.logback.classic.Level.WARN);
            start();
            logger.addAppender(this);
        }

        @Override
        public void close() {
            logger.detachAppender(this);
            logger.setLevel(previous);
            stop();
        }
    }

    private static boolean audited(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender,
            String marker,
            String... mustContain) {
        return appender.list.stream()
                .anyMatch(line -> {
                    String text = line.getFormattedMessage();
                    if (!text.contains(marker)) return false;
                    for (String fragment : mustContain) if (!text.contains(fragment)) return false;
                    return true;
                });
    }

    /** System.gc 后堆使用量采样（低成本模拟；R-16 卡面允许低成本内存断言）。 */
    private static long usedHeapAfterGc() throws Exception {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(50);
        }
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    // ------------------------------------------------------------------
    // ① 慢消费者：未读积压超上限→主动断开+审计行
    // ------------------------------------------------------------------

    /**
     * 积压超限断开：积压上限反射调小为 5，预插 210 条事件（>泵单批 200+上限 5）——首批发送
     * 200 帧后未读积压=10 > 5，泵主动断开并恰好记一条 SLOW_CONSUMER_DISCONNECT 审计行（携带
     * 租户/主体/会话号/积压量/上限）；断开响应 200、泵收口排空、不触碰会话与轮次数据。
     */
    @Test
    void slowConsumerDisconnectedWithAuditWhenBacklogExceedsLimit() throws Exception {
        String code = "h16-app";
        identity(TENANT, "alice", List.of("USER"));
        String sid = session(TENANT, "alice", code);
        for (int seq = 1; seq <= 210; seq++) event(sid, seq, "user/message", "{\"n\":" + seq + "}");
        var service = service();
        setConfig(service, "maxBufferedEvents", 5);
        var mvc = mvc(service);
        String url = "/v1/apps/" + code + "/sessions/" + sid + "/events/stream";
        MvcResult first = null;
        try (var appender = new AuditCapture()) {
        first = mvc.perform(get(url)).andExpect(request().asyncStarted()).andReturn();
        // 首批 ≤200 帧先出（有界发送）；id:200 出现说明第一批已落。
        awaitContent(first, "id:200");
        // 断开判定：未读积压 210-200=10 > 5 → 泵主动收口。审计行由泵线程在 close 前写入——
        // 直接轮询审计行（先于"泵收口"探测，后者会主动 close 抢跑断开分支）。
        awaitAuditLine(
                appender,
                "AUDIT SESSION_SSE_SLOW_CONSUMER_DISCONNECT",
                "tenant=" + TENANT,
                "principal=alice",
                "sessionId=" + sid,
                "unreadBacklog=10",
                "limit=5");
        awaitStreamsClosed(service, sid);
        }
        assertEquals(
                200, mvc.perform(asyncDispatch(first)).andReturn().getResponse().getStatus(),
                "断开是正常收口（响应 200），客户端以 Last-Event-ID 重连续读");
        // 断开只停泵，不触碰会话/轮次/事件事实（SSE 只是观察通道）。
        assertEquals(
                210, db.queryForObject("select count(*) from engine.session_event where session_id=?", Integer.class, sid),
                "断开不删除事件事实");
        assertEquals(
                0, db.queryForObject("select count(*) from engine.chat_turn where session_id=?", Integer.class, sid),
                "断开不写轮次（不取消 turn）");
    }

    // ------------------------------------------------------------------
    // ② 单主体连接上限：429 拒绝+审计行；跨主体不受影响
    // ------------------------------------------------------------------

    /**
     * 连接上限（反射调小为 2）：同主体第 3 条连接 429 SESSION_SSE_CONNECTION_LIMIT+审计行；
     * 另一主体同租户不受影响（per-principal 非全局）；既有连接不受拒绝影响（仍出帧）。
     */
    @Test
    void connectionCapPerPrincipalRejectsWith429AndAuditWithoutTouchingExistingStreams() throws Exception {
        String code = "h16-app";
        identity(TENANT, "alice", List.of("USER"));
        String sid = session(TENANT, "alice", code);
        event(sid, 1, "user/message", "{\"n\":1}");
        var service = service();
        setConfig(service, "maxConnectionsPerPrincipal", 2);
        var mvc = mvc(service);
        String url = "/v1/apps/" + code + "/sessions/" + sid + "/events/stream";
        var first = mvc.perform(get(url)).andExpect(request().asyncStarted()).andReturn();
        var second = mvc.perform(get(url)).andExpect(request().asyncStarted()).andReturn();
        // 第 3 条：429+审计。
        try (var appender = new AuditCapture()) {
            var third = mvc.perform(get(url)).andReturn().getResponse();
            assertEquals(429, third.getStatus(), third.getContentAsString(StandardCharsets.UTF_8));
            assertEquals(
                    "SESSION_SSE_CONNECTION_LIMIT",
                    Json.read(third.getContentAsString(StandardCharsets.UTF_8)).path("code").asText());
            // 另一主体（bob）同租户不受影响：自己的会话照常受理（per-principal 非全局）。
            identity(TENANT, "bob", List.of("USER"));
            String bobSid = session(TENANT, "bob", code);
            event(bobSid, 1, "user/message", "{\"n\":1}");
            var bobStream =
                    mvc.perform(get("/v1/apps/" + code + "/sessions/" + bobSid + "/events/stream"))
                            .andExpect(request().asyncStarted())
                            .andReturn();
            awaitContent(bobStream, "id:1");
            assertTrue(
                    audited(
                            appender,
                            "AUDIT SESSION_SSE_CONNECTION_LIMIT",
                            "tenant=" + TENANT,
                            "principal=alice",
                            "sessionId=" + sid,
                            "active=2",
                            "limit=2"),
                    "超限拒绝必须记审计行: " + appender.list);
        }
        // 既有连接不受拒绝影响：仍正常出帧。
        awaitContent(first, "id:1");
        awaitContent(second, "id:1");
        assertEquals(2, service.closeSessionStreams(sid), "收口恰关闭两条既有连接");
    }

    // ------------------------------------------------------------------
    // ③ 并发 50 连接低成本压测：全出帧、堆增量有界、默认上限即 50（第 51 个 429）
    // ------------------------------------------------------------------

    /**
     * 50 个 SSE 连接拉同一会话流（卡面低成本模拟口径）：全部出帧、堆增量有界（<256MB 宽松
     * 门槛——泵进程内每批 ≤200 且 PG 即缓冲，连接本身不缓冲事件）；默认上限 50 下第 51 个
     * 连接被 429 拒绝（默认值同时验证）。如实登记：堆采样为低成本近似，非真实负载压测。
     */
    @Test
    void fiftyConcurrentConnectionsStayBoundedInMemoryAndCapRejects51st() throws Exception {
        String code = "h16-app";
        identity(TENANT, "alice", List.of("USER"));
        String sid = session(TENANT, "alice", code);
        for (int seq = 1; seq <= 3; seq++) event(sid, seq, "user/message", "{\"n\":" + seq + "}");
        var service = service();
        assertEquals(2000, config(service, "maxBufferedEvents"), "默认积压上限=2000（yml 同值）");
        assertEquals(50, config(service, "maxConnectionsPerPrincipal"), "默认连接上限=50（yml 同值）");
        var mvc = mvc(service);
        String url = "/v1/apps/" + code + "/sessions/" + sid + "/events/stream";
        // 预热：先开 1 条连接出帧再收口，排除首连类/JIT 一次性分配对基线的扰动。
        var warmup = mvc.perform(get(url)).andExpect(request().asyncStarted()).andReturn();
        awaitContent(warmup, "id:3");
        service.closeSessionStreams(sid);
        awaitStreamsClosed(service, sid);
        long baseline = usedHeapAfterGc();
        // 50 条并发连接拉同一会话流。
        var results = new ArrayList<MvcResult>();
        for (int i = 0; i < 50; i++)
            results.add(mvc.perform(get(url)).andExpect(request().asyncStarted()).andReturn());
        for (var result : results) awaitContent(result, "id:3");
        long after = usedHeapAfterGc();
        assertTrue(
                after - baseline < 256L * 1024 * 1024,
                "50 连接堆增量应有界: baseline=" + baseline + " after=" + after);
        // 默认上限=50：第 51 个连接被 429 拒绝。
        try (var appender = new AuditCapture()) {
            var rejected = mvc.perform(get(url)).andReturn().getResponse();
            assertEquals(429, rejected.getStatus(), rejected.getContentAsString(StandardCharsets.UTF_8));
            assertEquals(
                    "SESSION_SSE_CONNECTION_LIMIT",
                    Json.read(rejected.getContentAsString(StandardCharsets.UTF_8)).path("code").asText());
            assertTrue(
                    audited(appender, "AUDIT SESSION_SSE_CONNECTION_LIMIT", "active=50", "limit=50"),
                    "默认上限拒绝也记审计行");
        }
        assertEquals(50, service.closeSessionStreams(sid), "一次收口全部 50 条连接");
        awaitStreamsClosed(service, sid);
    }

    // ------------------------------------------------------------------
    // ④ 配置默认值在 application.yml 在案
    // ------------------------------------------------------------------

    /** 限流配置默认值在案：yml 两键存在且默认 2000/50；fresh 实例字段初值与 yml 默认一致。 */
    @Test
    void backpressureDefaultsAreDeclaredInApplicationYml() throws Exception {
        String yml;
        try (var input = getClass().getResourceAsStream("/application.yml")) {
            assertNotNull(input, "engine application.yml 必须在测试类路径上");
            yml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(yml.contains("max-buffered-events:"), "yml 必须声明 max-buffered-events");
        assertTrue(yml.contains("max-connections-per-principal:"), "yml 必须声明 max-connections-per-principal");
        assertTrue(yml.contains("${AGENT_SSE_MAX_BUFFERED_EVENTS:2000}"), "积压上限默认 2000 在案");
        assertTrue(yml.contains("${AGENT_SSE_MAX_CONNECTIONS_PER_PRINCIPAL:50}"), "连接上限默认 50 在案");
        // fresh 实例（非 Spring 注入）字段初值=默认值，与 yml 默认一致。
        var fresh = service();
        assertEquals(2000, config(fresh, "maxBufferedEvents"));
        assertEquals(50, config(fresh, "maxConnectionsPerPrincipal"));
    }
}
