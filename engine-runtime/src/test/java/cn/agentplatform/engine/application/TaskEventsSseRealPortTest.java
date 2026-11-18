package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformErrors;
import cn.agentplatform.engine.api.TaskController;
import cn.agentplatform.engine.infra.EngineTestDatabase;
import cn.agentplatform.engine.infra.TraceStore;
import cn.agentplatform.engine.infra.Utf8StringMessageConvertersConfiguration;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * R-5（返工卡）：SSE 非 ASCII 字节级验证——真实端口（Tomcat 真启动，非 MockMvc）逐字节比对。
 *
 * <p>台账发现：TaskEventsSseTest:149 自认 MockMvc 下非 ASCII 变 '?'，全仓此前无 UTF-8
 * StringHttpMessageConverter 注册。本测试类以 {@link Utf8StringMessageConvertersConfiguration}
 * （生产配置类，@Import 引入——验证的就是生产注册）+ 真实 Tomcat 回环端口起最小真实上下文，
 * 经原始 TCP 套接字抓 SSE 帧，断言中文任务标题/会话名（含四字节字符 🚀🎯）在 SSE 事件流中的
 * 帧字节与源 UTF-8 完全一致（byte-exact，非字符串等价）。
 *
 * <p>证据口径（如实登记三条）：
 *
 * <ul>
 *   <li><b>最小真实启动</b>：只装配 DispatcherServlet+真实 Controller/服务/库+身份过滤器
 *       （验签链路按 RunEventsHttpTest 先例以 fixture 过滤器代位）+生产 UTF-8 转换器配置；
 *       authority 目录/IAM 链路等与本卡无关装配不在其中。HTTP 层为真实 Tomcat+原始字节流；
 *       请求用 HTTP/1.0（响应不走 chunked，字节比对对象即 SSE 帧本体）。</li>
 *   <li><b>UTF-8 注册的生效证明</b>：断言真实上下文中 StringHttpMessageConverter 实例默认
 *       字符集=UTF-8（生产配置类生效）。SSE data 负载经 APPLICATION_JSON 写出——Spring 对
 *       JSON 兼容媒体类型本就按 UTF-8（框架规则）；配置类把<b>默认字符集</b>（text/plain、
 *       无显式媒体类型的 String 写出、standalone MockMvc 等 ISO-8859-1 疑难路径）显式钉在
 *       UTF-8，二者互为补强。</li>
 *   <li><b>JSON 对照路径</b>（非卡面范围，如实登记）：@ResponseBody JSON（Jackson 字节生成器
 *       直写）对增补平面字符按合法 JSON 转义 \uD83C\uDFAF 输出——语义无损（解析回原字符，
 *       本类对照用例断言回读相等）；该行为由 jackson-core 决定（2.19.0/2.21.4 实测一致），
 *       与字符集/转换器注册无关，SSE 路径（StringHttpMessageConverter 字符串写出）不受其影响。</li>
 * </ul>
 *
 * <p>夹具一律 h212_ 前缀双向清理（照 TaskEventsSseTest 先例；SSE 泵线程由 Tasks
 * {@code @PreDestroy} 随上下文收口，测试内另显式 closeTaskStreams）。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class TaskEventsSseRealPortTest {

    static JdbcTemplate db;

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
    }

    /** 双向清理：仅 h212_% 夹具，不触碰共享测试库中其他测试的行。 */
    @BeforeEach
    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        db.update("delete from engine.task_event where task_id in (select id from engine.task where public_id like 'h212_%')");
        db.update("delete from engine.task_step where task_id in (select id from engine.task where public_id like 'h212_%')");
        db.update("delete from engine.task where public_id like 'h212_%'");
    }

    /**
     * 最小真实启动配置（证据口径见类 javadoc）：真实 Tomcat 回环随机端口 + 身份过滤器 + 真实
     * Controller/服务/库 + 生产 UTF-8 转换器配置（@Import 生产类——验证对象就是它）。
     */
    @TestConfiguration
    @EnableWebMvc
    @Import(Utf8StringMessageConvertersConfiguration.class)
    static class SseUtf8Fixture implements WebMvcConfigurer {
        static final String TENANT = "h212-t";
        static final String OWNER = "h212-alice";

        @Bean
        TomcatServletWebServerFactory servletFactory() {
            var factory = new TomcatServletWebServerFactory(0);
            factory.setAddress(InetAddress.getLoopbackAddress());
            factory.addInitializers(
                    servlet -> {
                        var filter =
                                servlet.addFilter(
                                        "fixture-identity",
                                        new OncePerRequestFilter() {
                                            @Override
                                            protected void doFilterInternal(
                                                    HttpServletRequest request,
                                                    HttpServletResponse response,
                                                    FilterChain chain)
                                                    throws ServletException, IOException {
                                                identity(TENANT, OWNER);
                                                try {
                                                    chain.doFilter(request, response);
                                                } finally {
                                                    SecurityContextHolder.clearContext();
                                                }
                                            }
                                        });
                        filter.setAsyncSupported(true);
                        filter.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
                    });
            return factory;
        }

        static void identity(String tenant, String principal) {
            var jwt =
                    Jwt.withTokenValue("verified-internal-peer")
                            .header("alg", "HS256")
                            .subject(principal)
                            .claim("tenant_id", tenant)
                            .claim("roles", List.of("USER"))
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(180))
                            .build();
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        }

        @Bean
        ServletRegistrationBean<DispatcherServlet> dispatcher(
                org.springframework.web.context.WebApplicationContext context) {
            var registration = new ServletRegistrationBean<>(new DispatcherServlet(context), "/");
            registration.setAsyncSupported(true);
            return registration;
        }

        @Bean
        TraceStore store() {
            var source =
                    new DriverManagerDataSource(
                            EngineTestDatabase.url(),
                            EngineTestDatabase.user(),
                            EngineTestDatabase.password());
            return new TraceStore(
                    new JdbcTemplate(source), new DataSourceTransactionManager(source));
        }

        @Bean
        Tasks tasks(TraceStore store) throws Exception {
            var tasks = new Tasks(store);
            // 心跳改短照 TaskEventsSseTest 反射先例（同类包内字段可见，直接赋值等价）。
            var field = Tasks.class.getDeclaredField("taskHeartbeatMillis");
            field.setAccessible(true);
            field.setLong(tasks, 500);
            return tasks;
        }

        @Bean
        TaskController taskController(Tasks tasks, TraceStore store) {
            return new TaskController(tasks, new Plans(store), new TaskReconciliation(store));
        }

        @Bean
        PlatformErrors errors() {
            return new PlatformErrors();
        }

        @Override
        public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
            configurer.setDefaultTimeout(60000);
        }
    }

    /** 任务夹具（h212_ 前缀）：中文标题任务。 */
    private long task(String goal) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        db.update(
                "insert into engine.task(public_id,tenant_id,principal_id,app_code,config_release_id,"
                    + "config_digest,mode,purpose,goal,input_ref,input_digest,state,request_id,request_digest)"
                    + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "h212_" + suffix,
                SseUtf8Fixture.TENANT,
                SseUtf8Fixture.OWNER,
                "h212-app",
                "unpinned-h212",
                Digest.sha256("h212-config:" + suffix),
                "AGENT",
                "EXECUTE",
                goal,
                "h212-input/" + suffix,
                Digest.sha256("h212-input:" + suffix),
                "RUNNING",
                "h212-req-" + suffix,
                Digest.sha256("h212-request:" + suffix));
        return db.queryForObject(
                "select id from engine.task where public_id=?", Long.class, "h212_" + suffix);
    }

    /** 逐字节查找：needle 在 haystack 中首次出现的下标（不含则 -1）——字节级比对核心。 */
    private static int indexOfBytes(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++)
                if (haystack[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    /**
     * 真实端口字节级验证（本卡主测）：生产 UTF-8 转换器在真实上下文生效；中文任务标题/会话名
     * （含四字节 emoji）在 SSE 事件流中的帧字节与源 UTF-8 完全一致；帧结构（id/event/data）齐备；
     * Content-Type 为 text/event-stream。
     */
    @Test
    void sseFramesCarryNonAsciiPayloadByteExactOverRealPort() throws Exception {
        long taskId = task("中文任务标题：差异报告🚀——会话名【规划会话🎯】");
        // 事件负载：中文 + 四字节 emoji（🚀 U+1F680 / 🎯 U+1F3AF）——经 DB jsonb 落库（PG 为事实源）。
        // 期望帧字节以泵出帧的同一投影口径计算：Json.read(jsonb 文本)→Json.write（紧凑化）。
        db.update(
                "insert into engine.task_event(task_id,seq,type,payload,state_version) values(?,?,?,?::jsonb,1)",
                taskId,
                1,
                "STEP_STARTED",
                "{\"stepId\":\"坐标探测\",\"标题\":\"中文任务🚀标题\",\"会话名\":\"规划会话🎯\",\"note\":\"mix ascii\"}");
        String payloadText =
                db.queryForObject(
                        "select payload::text from engine.task_event where task_id=? and seq=?",
                        String.class,
                        taskId,
                        1);
        String compactPayloadJson =
                Json.write(Json.read(payloadText, JsonNode.class));
        assertTrue(
                new String(compactPayloadJson.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
                        .contains("🚀"),
                "源负载含四字节 emoji（jsonb 原样）");

        try (var context =
                new AnnotationConfigServletWebServerApplicationContext(SseUtf8Fixture.class)) {
            int port = context.getWebServer().getPort();
            var tasks = context.getBean(Tasks.class);
            // 生产 UTF-8 转换器生效证明：真实上下文中的 String 转换器默认字符集=UTF-8。
            var converters =
                    context.getBean(
                                    org.springframework.web.servlet.mvc.method.annotation
                                            .RequestMappingHandlerAdapter.class)
                            .getMessageConverters();
            var stringConverter =
                    converters.stream()
                            .filter(c -> c instanceof StringHttpMessageConverter)
                            .map(c -> (StringHttpMessageConverter) c)
                            .findFirst()
                            .orElseThrow();
            assertEquals(
                    StandardCharsets.UTF_8,
                    stringConverter.getDefaultCharset(),
                    "生产 Utf8StringMessageConvertersConfiguration 在真实上下文生效");
            String expectedFrame =
                    "id:1\nevent:STEP_STARTED\ndata:" + compactPayloadJson + "\n\n";
            byte[] expectedBytes = expectedFrame.getBytes(StandardCharsets.UTF_8);
            byte[] headerEndBytes = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            var buffer = new ByteArrayOutputStream();
            try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                socket.setSoTimeout(2000);
                // HTTP/1.0：响应不走 chunked 传输编码（帧字节流无 chunk-size 行穿插）。
                socket.getOutputStream()
                        .write(
                                ("GET /v1/tasks/" + taskId + "/events/stream HTTP/1.0\r\n\r\n")
                                        .getBytes(StandardCharsets.US_ASCII));
                var in = socket.getInputStream();
                long deadline = System.currentTimeMillis() + 15000;
                int headerEndAt = -1;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        int value = in.read();
                        if (value < 0) break;
                        buffer.write(value);
                    } catch (java.net.SocketTimeoutException quiet) {
                        // 泵线程逐帧异步写出——窗口内未到继续等。
                    }
                    byte[] raw = buffer.toByteArray();
                    if (headerEndAt < 0) headerEndAt = indexOfBytes(raw, headerEndBytes);
                    if (headerEndAt >= 0 && indexOfBytes(raw, expectedBytes) >= 0) break;
                }
            } finally {
                // 收口本任务泵线程（断开只停泵不取消任务——SSE 观察通道语义）。
                tasks.closeTaskStreams(taskId);
            }
            byte[] raw = buffer.toByteArray();
            assertTrue(raw.length > 0, "真实端口必须有响应字节");
            String head = new String(raw, 0, Math.min(raw.length, 512), StandardCharsets.ISO_8859_1);
            assertTrue(head.contains("200"), "HTTP 200: " + head);
            assertTrue(
                    head.toLowerCase(java.util.Locale.ROOT).contains("content-type: text/event-stream"),
                    "SSE 媒体类型: " + head);
            int frameAt = indexOfBytes(raw, expectedBytes);
            assertTrue(
                    frameAt >= 0,
                    "SSE 帧字节与源 UTF-8 不一致（逐字节比对失败）: "
                            + new String(raw, StandardCharsets.UTF_8));
            // 四字节 emoji 原样字节存在（UTF-8 四字节序列，非 '?' 0x3F 替换、非 \uD83C 转义）。
            byte[] rocket = "🚀".getBytes(StandardCharsets.UTF_8);
            assertTrue(indexOfBytes(raw, rocket) >= 0, "SSE 帧内四字节 emoji 必须原样 UTF-8 字节");
            assertFalse(
                    new String(raw, StandardCharsets.UTF_8).contains("\\uD83C"),
                    "SSE 帧不出现增补平面转义（字符串路径原样写出）");
        }
    }

    /**
     * JSON 对照路径（非卡面范围，证据口径第三条）：真实端口事件增量 JSON 语义无损回读——
     * 中文与四字节 emoji 经 Jackson 字节生成器的合法 JSON 转义（\uD83C\uDFAF）解析回原字符
     * （jackson-core 2.19.0/2.21.4 行为一致，与字符集/转换器注册无关，类 javadoc 如实登记）。
     */
    @Test
    void eventIncrementJsonPathRoundTripsNonAsciiSemantically() throws Exception {
        long taskId = task("中文标题对照：正常事件增量");
        db.update(
                "insert into engine.task_event(task_id,seq,type,payload,state_version) values(?,?,?,?::jsonb,1)",
                taskId,
                1,
                "STEP_STARTED",
                "{\"标题\":\"增量对照🎯\"}");
        try (var context =
                new AnnotationConfigServletWebServerApplicationContext(SseUtf8Fixture.class)) {
            int port = context.getWebServer().getPort();
            byte[] request =
                    ("GET /v1/tasks/" + taskId + "/events HTTP/1.0\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII);
            byte[] response;
            try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write(request);
                var out = new ByteArrayOutputStream();
                var in = socket.getInputStream();
                int value;
                while ((value = in.read()) >= 0) out.write(value);
                response = out.toByteArray();
            }
            String text = new String(response, StandardCharsets.UTF_8);
            int bodyAt = text.indexOf("\r\n\r\n");
            assertTrue(bodyAt >= 0, "响应必须有头体分隔");
            assertTrue(text.substring(0, bodyAt).contains("200"), "HTTP 200: " + text);
            var data = Json.read(text.substring(bodyAt + 4), JsonNode.class).path("data");
            assertEquals(1, data.path("events").size());
            // 语义无损：转义回读=原字符（JSON 合法转义与原始 UTF-8 字节等价）。
            assertEquals(
                    "增量对照🎯",
                    data.path("events").get(0).path("data").path("标题").asText(),
                    "JSON 路径中文+四字节 emoji 语义无损回读");
        }
    }
}
