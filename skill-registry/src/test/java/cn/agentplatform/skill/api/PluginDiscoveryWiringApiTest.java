package cn.agentplatform.skill.api;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.ServiceTokens;
import cn.agentplatform.skill.SkillApplication;
import cn.agentplatform.skill.application.PluginGovernance;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * R-6 插件发现执行器接线——API 级验收（台账 §7.6 R-6）。
 *
 * <p>真实 Spring 上下文（{@link SkillApplication} 全量装配，@Scheduled 生效）+ 专用库
 * agent_platform_r6_discovery_test（drop/create，不触碰 dev 库与其他验证库）+ 真实 HTTP 面
 * （RANDOM_PORT + ServiceTokens HS256 用户票据，dev-hmac 部署模式与生产安全链一致）。
 * 测试全程不直调执行器：触发只走 POST /admin/plugin-sources/{id}/discover 入口，
 * 状态推进只靠（a）接线后的 trigger 异步工作线程（b）真实 @Scheduled pulse 兜底。
 *
 * <ul>
 *   <li>HTTP 触发闭环：202 QUEUED 受理→异步执行→轮询 SUCCEEDED，差异落库、last_discovered_at 回写；</li>
 *   <li>@Scheduled pulse 装配：无人派发的遗弃 QUEUED 行在调度间隔内被消费到终态（唯一证明
 *       调度在位的口径——测试不经执行器任何方法）；</li>
 *   <li>遗弃 RUNNING 接管观测：ghost 租约失效行先被轮询确定性观测到 RUNNING，再由 pulse
 *       接管到 SUCCEEDED（QUEUED→RUNNING→SUCCEEDED 全程可查询）；</li>
 *   <li>契约保持：停用来源 409 PLUGIN_SOURCE_DISABLED 经新接线入口原样透传。</li>
 * </ul>
 *
 * <p>诚实口径：pulse 间隔经 agent.discovery.pulse-ms 调短为 2s（生产默认 5000ms）；demo 桩扫描
 * 毫秒级完成，HTTP 触发路径的瞬时 RUNNING 以 started_at 非空 + 后续终态机制性证明（轮询顺带
 * 记录是否恰好观测到），遗弃行的 RUNNING 以 2s 租约窗口确定性观测。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
@SpringBootTest(
        classes = SkillApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.port=0",
            "server.address=127.0.0.1",
            "agent.security.mode=dev-hmac",
            "agent.security.serviceId=skill",
            "agent.dev-auth=true",
            // R-6 验收口径：调度兜底间隔在测试下调短（生产默认 5000），遗弃行消费可观测。
            "agent.discovery.pulse-ms=2000"
        })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PluginDiscoveryWiringApiTest {
    private static final String DATABASE = "agent_platform_r6_discovery_test";
    private static final String HOST = "jdbc:postgresql://127.0.0.1:25432/" + DATABASE;
    private static final String TENANT = "r6-discovery-api-tests";
    private static final long HTTP_DEADLINE_MS = 30_000;

    @LocalServerPort int port;

    @Autowired PluginGovernance governance;
    JdbcTemplate db;

    private final HttpClient http = HttpClient.newHttpClient();
    private Actor admin;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        // 专用库（任务纪律）：FORCE 清掉遗留连接后重建，绝不触碰 dev 库与其他验证库。
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:25432/postgres",
                                "agent_v12",
                                System.getenv("AGENT_DB_PASSWORD")));
        admin.execute("drop database if exists " + DATABASE + " with (force)");
        admin.execute("create database " + DATABASE);
        p.add("spring.datasource.url", () -> HOST);
        p.add(
                "spring.datasource.username",
                () -> System.getenv().getOrDefault("AGENT_DB_USER", "agent_v12"));
        p.add("spring.datasource.password", () -> System.getenv("AGENT_DB_PASSWORD"));
        // 25432 验证实例 max_connections 有限：小池避免缓存的 Spring 上下文占满连接槽。
        p.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        // 与 DataHealthHttpTest 同约定：jwt-secret 显式落属性，环境缺失时用固定测试值，
        // 避免嵌套占位符无法解析；ServiceTokens.secretBytes 走同一环境变量对齐。
        String key =
                System.getenv().getOrDefault(
                        "AGENT_JWT_SECRET", "r6-discovery-wiring-independent-key-0123456789abcdef");
        System.setProperty("agent.jwt.secret", key);
        p.add("agent.security.jwt-secret", () -> key);
    }

    @AfterAll
    static void clearSystemKey() {
        System.clearProperty("agent.jwt.secret");
    }

    @BeforeEach
    void prepareAdminActorAndDb() {
        db =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                HOST,
                                System.getenv().getOrDefault("AGENT_DB_USER", "agent_v12"),
                                System.getenv("AGENT_DB_PASSWORD")));
        admin =
                new Actor(
                        TENANT,
                        "r6-api-admin-" + UUID.randomUUID().toString().substring(0, 8),
                        Set.of("ADMIN"),
                        Map.of());
        // 数据面 bean 调用（遗弃行构造）在测试线程上进行：与既有 PG 测试同法置入已验签身份。
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject(admin.principalId())
                        .claim("tenant_id", TENANT)
                        .claim("roles", List.of("ADMIN"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    // ---------------------------------------------------------------- 场景

    @Test
    @Order(1)
    void httpTriggeredDiscoveryRunsFromQueuedToSucceeded() throws Exception {
        long sourceId = registerSourceViaApi("http");
        // 202 受理契约：QUEUED 投影原样（R-6 只换执行通路，HTTP 契约不变）
        var accepted = request("POST", "/admin/plugin-sources/" + sourceId + "/discover", null);
        assertEquals(202, accepted.statusCode(), accepted.body());
        assertEquals("OK", Json.read(accepted.body()).path("code").asText());
        String discoveryId = Json.read(accepted.body()).path("data").path("discoveryId").asText();
        assertTrue(discoveryId.matches("disc_[0-9a-f]{32}"), accepted.body());
        assertEquals("QUEUED", Json.read(accepted.body()).path("data").path("status").asText());
        assertEquals(1, Json.read(accepted.body()).path("data").path("batchNo").asInt());

        // 轮询查询（API 面）直至终态：QUEUED→RUNNING→SUCCEEDED 由 trigger 异步工作线程推进
        Terminal terminal =
                pollToTerminal(sourceId, discoveryId, "异步工作线程（接线后 trigger）应把发现推进到终态");
        assertEquals(
                "SUCCEEDED", terminal.status(), "demo 桩扫描应发布成功，观测序列=" + terminal.observed());
        assertNotNull(terminal.startedAt(), "QUEUED→RUNNING 认领腿已发生（started_at 非空）");
        // QUEUED 腿以 202 受理体为准（上断言）；demo 桩毫秒级完成，瞬时 RUNNING 是否被轮询
        // 撞见属机会性证据，只记录不断言（见类注释诚实口径）。

        // 差异落库：目录消费 recordDiscoveredVersion（demo-<sourceId> 入库）
        var discovery = Json.read(discoveryBody(sourceId, discoveryId));
        assertEquals(
                "demo-" + sourceId,
                discovery.path("data").path("changes").path("added").get(0).path("plugin").asText());
        // 来源回写 last_discovered_at（列表投影可查询）
        var sources = Json.read(request("GET", "/admin/plugin-sources", null).body());
        JsonNode found = null;
        for (JsonNode item : sources.path("data"))
            if (item.path("id").asLong() == sourceId) found = item;
        assertNotNull(found, "来源 " + sourceId + " 不在列表投影中");
        assertNotNull(found.path("lastDiscoveredAt").asText(null), "成功发布回写 last_discovered_at");
    }

    @Test
    @Order(2)
    void scheduledPulseConsumesOrphanQueuedRowWithoutAnyTestDrivenExecution() throws Exception {
        long sourceId = registerSourceViaApi("pulse-queued");
        // 遗弃 QUEUED 行：只走数据面受理（不派发、不直调执行器），唯一消费者是 @Scheduled pulse
        String discoveryId = governance.triggerDiscovery(sourceId).get("discoveryId").toString();
        assertEquals("QUEUED", statusOf(discoveryId), "数据面受理后为 QUEUED（无派发）");

        // 测试零驱动：等待调度兜底消费。本行只能由真实 @Scheduled pulse 认领执行。
        Terminal terminal =
                pollToTerminal(sourceId, discoveryId, "@Scheduled pulse 应接管无人派发的遗弃 QUEUED 行");
        assertEquals(
                "SUCCEEDED", terminal.status(), "pulse 消费遗弃行应发布成功，观测序列=" + terminal.observed());
        assertNotNull(terminal.startedAt(), "pulse 认领（QUEUED→RUNNING）已发生");
        assertEquals(
                "demo-" + sourceId,
                Json.read(discoveryBody(sourceId, discoveryId))
                        .path("data")
                        .path("changes")
                        .path("added")
                        .get(0)
                        .path("plugin")
                        .asText(),
                "pulse 路径同样完成目录合并");
    }

    @Test
    @Order(3)
    void scheduledPulseTakeoverObservesRunningThenSucceeds() throws Exception {
        long sourceId = registerSourceViaApi("pulse-takeover");
        // 模拟崩溃实例：ghost 认领（数据面 markRunning），2s 短租约后失联
        String discoveryId = claimAsGhost(sourceId);
        // 确定性观测 RUNNING（租约 2s 窗口；pulse 只认领 QUEUED 或租约失效行，不会提前动它）
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean observedRunning = false;
        while (System.nanoTime() < deadline) {
            if ("RUNNING".equals(statusOf(discoveryId))) {
                observedRunning = true;
                break;
            }
            Thread.sleep(20);
        }
        assertTrue(observedRunning, "ghost 租约期内 RUNNING 确定性可查询");

        // 租约失效后由真实 @Scheduled pulse 接管（代次推进）→ SUCCEEDED
        Terminal terminal =
                pollToTerminal(sourceId, discoveryId, "租约失效后 @Scheduled pulse 应接管遗弃 RUNNING 行");
        assertEquals("SUCCEEDED", terminal.status(), "接管后发布成功，观测序列=" + terminal.observed());
        long generation =
                db.queryForObject(
                        "select claim_generation from skill.plugin_discovery where discovery_id=?",
                        Long.class,
                        discoveryId);
        assertTrue(generation >= 2, "接管推进认领代次（ghost=1 → pulse≥2），实际=" + generation);
    }

    @Test
    @Order(4)
    void disabledSourceContractPreservedThroughWiredEntrypoint() throws Exception {
        long sourceId = registerSourceViaApi("disabled");
        governance.disableSource(sourceId);
        var blocked = request("POST", "/admin/plugin-sources/" + sourceId + "/discover", null);
        assertEquals(409, blocked.statusCode(), blocked.body());
        assertEquals(
                "PLUGIN_SOURCE_DISABLED",
                Json.read(blocked.body()).path("code").asText(),
                "停用来源经新接线入口原样透传数据面 409 拦截");
    }

    // ---------------------------------------------------------------- 工具

    /** 轮询 API 查询发现状态直至终态；顺带记录观测序列供证据登记。 */
    private Terminal pollToTerminal(long sourceId, String discoveryId, String message)
            throws Exception {
        long start = System.nanoTime();
        var observed = new ArrayList<String>();
        long deadline = start + TimeUnit.MILLISECONDS.toNanos(HTTP_DEADLINE_MS);
        String body = "";
        while (System.nanoTime() < deadline) {
            body = discoveryBody(sourceId, discoveryId);
            String status = Json.read(body).path("data").path("status").asText();
            if (observed.isEmpty() || !observed.getLast().equals(status)) observed.add(status);
            if ("SUCCEEDED".equals(status) || "FAILED".equals(status)) {
                System.out.println(
                        "[R-6 API] discovery="
                                + discoveryId
                                + " statuses="
                                + String.join("→", observed)
                                + " elapsedMs="
                                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                return new Terminal(status, Json.read(body).path("data").path("startedAt").asText(null), observed);
            }
            Thread.sleep(25);
        }
        fail(
                message
                        + "：超时 "
                        + HTTP_DEADLINE_MS
                        + "ms，最后观测="
                        + String.join("→", observed)
                        + " body="
                        + body);
        return null;
    }

    /** ghost 认领（数据面写入口，模拟崩溃实例持有者）。pulse 恰在两步之间抢先认领的概率极小，重试新批次。 */
    private String claimAsGhost(long sourceId) {
        PlatformException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            String discoveryId = governance.triggerDiscovery(sourceId).get("discoveryId").toString();
            try {
                governance.markRunning(discoveryId, "ghost", Instant.now().plusSeconds(2));
                return discoveryId;
            } catch (PlatformException lostRace) {
                last = lostRace; // 行已被 pulse 先行认领：换新批次重试
            }
        }
        throw new AssertionError("ghost 认领连续被 pulse 抢占（调度间隔异常？）", last);
    }

    private String statusOf(String discoveryId) {
        return db.queryForObject(
                "select status from skill.plugin_discovery where discovery_id=?",
                String.class,
                discoveryId);
    }

    private String discoveryBody(long sourceId, String discoveryId) throws Exception {
        var response =
                request(
                        "GET",
                        "/admin/plugin-sources/" + sourceId + "/discoveries/" + discoveryId,
                        null);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private long registerSourceViaApi(String stem) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put(
                "code",
                stem + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        body.put("name", "R-6 接线验收来源");
        body.put("sourceType", "DIRECTORY");
        body.put("location", "file:///srv/plugins-r6");
        var created = request("POST", "/admin/plugin-sources", body);
        assertEquals(200, created.statusCode(), created.body());
        var node = Json.read(created.body());
        assertEquals("ACTIVE", node.path("data").path("status").asText());
        return node.path("data").path("id").asLong();
    }

    /** 真实 HTTP 面：RANDOM_PORT + HS256 用户票据（dev-hmac 部署模式，与生产安全链一致）。 */
    private HttpResponse<String> request(String method, String path, Object body) throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Authorization", "Bearer " + ServiceTokens.userToken(admin, 300))
                        .header("Content-Type", "application/json");
        return http.send(
                builder.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(Json.write(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** 终态投影 + 观测序列。 */
    private record Terminal(String status, String startedAt, List<String> observed) {}
}
