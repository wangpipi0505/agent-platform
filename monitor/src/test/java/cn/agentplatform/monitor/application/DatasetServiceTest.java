package cn.agentplatform.monitor.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.monitor.api.DatasetController;

import com.fasterxml.jackson.databind.JsonNode;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.*;

/**
 * 提升链路在 monitor 侧的断言保存契约：assertion 与 id/inputs/expected/safety 同权进入用例白名单，
 * 保存期用 EvaluationAssertions.requireValid 校验并按 canonical 形状冻结，读接口原样回读。
 *
 * <p>不启动 Spring 上下文：直接复刻生产 DatasetController -> DatasetService -> PostgreSQL 的调用，
 * 既覆盖 HTTP 响应包装形状，又避免与既有 DatasetHttpTest 的类身份夹具互相污染。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class DatasetServiceTest {
    /** body 中完全省略 assertion：历史/默认语义，等价于 EXACT。 */
    private static final Object OMIT = new Object();

    static JdbcTemplate db;
    static DatasetService service;
    static DatasetController controller;
    String code;
    String version;

    @BeforeAll
    static void prepare() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12",
                        "agent_v12",
                        System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure()
                .dataSource(ds)
                .schemas("monitor")
                .defaultSchema("monitor")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(ds);
        service = new DatasetService(db, new DataSourceTransactionManager(ds));
        controller = new DatasetController(service);
    }

    @BeforeEach
    void actor() {
        var jwt =
                Jwt.withTokenValue("dataset-verified")
                        .header("alg", "HS256")
                        .subject("promoter")
                        .claim("tenant_id", "monitor-tests")
                        // promote 要求 EDITOR+SERVICE（内部调用范围），get 只要求 EDITOR。
                        .claim("roles", List.of("EDITOR", "SERVICE"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        code = "promote_" + UUID.randomUUID().toString().replace("-", "");
        version = "v1";
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void promotionKeepsContainsAssertionAndReadsItBack() {
        var saved = promote(false, "ok", "CONTAINS");

        assertEquals("OK", saved.code());
        var cases = readBackCases();
        assertEquals("CONTAINS", cases.get(0).path("assertion").asText());
    }

    @Test
    void numericToleranceIsStoredAsCanonicalModeAndToleranceObject() {
        var saved = promote(false, 0.5, Map.of("mode", "NUMERIC_TOLERANCE", "tolerance", 0.05));

        assertEquals("OK", saved.code());
        assertEquals(
                Json.tree(Map.of("mode", "NUMERIC_TOLERANCE", "tolerance", 0.05)),
                readBackCases().get(0).path("assertion"));
    }

    @Test
    void rejectsSafetyCaseThatWeakensEqualityWithoutWritingTheDataset() {
        var error =
                assertThrows(
                        PlatformException.class, () -> promote(true, "ok", "CONTAINS"));

        assertEquals(400, error.status());
        assertEquals("EVALUATION_ASSERTION_SAFETY_CONSTRAINT", error.code());
        assertEquals(0, storedRows(), "校验失败不得落库");
    }

    @Test
    void rejectsExtraAssertionParameterWithoutWritingTheDataset() {
        var error =
                assertThrows(
                        PlatformException.class,
                        () -> promote(false, "x", Map.of("mode", "REGEX", "pattern", "x")));

        assertEquals(400, error.status());
        assertEquals("EVALUATION_ASSERTION_INVALID", error.code());
        assertTrue(error.getMessage().contains("pattern"), error.getMessage());
        assertEquals(0, storedRows(), "校验失败不得落库");
    }

    @Test
    void omittedAssertionStaysAbsentSoEvaluationInterpretsExact() {
        var saved = promote(false, "ok", OMIT);

        assertEquals("OK", saved.code());
        assertFalse(
                readBackCases().get(0).has("assertion"),
                "缺失 assertion 不得被写成 EXACT 字段，否则改变历史数据集字节与语义");
    }

    @SuppressWarnings("unchecked")
    private ApiResponse<Map<String, Object>> promote(
            boolean safety, Object expected, Object assertion) {
        var c = new LinkedHashMap<String, Object>();
        c.put("id", "case-1");
        c.put("inputs", Map.of("q", "hi"));
        c.put("expected", expected);
        c.put("safety", safety);
        if (assertion != OMIT) c.put("assertion", assertion);
        var body =
                Json.tree(
                        Map.of(
                                "appCode",
                                code,
                                "datasetVersion",
                                version,
                                "requestId",
                                "req-" + code,
                                "source",
                                Map.of(
                                        "inputId",
                                        1,
                                        "revision",
                                        1,
                                        "appDraftId",
                                        1,
                                        "inputsSha256",
                                        Digest.sha256(Json.canonical(Map.of("q", "hi")))),
                                "cases",
                                List.of(c)));
        return (ApiResponse<Map<String, Object>>) controller.promote(body);
    }

    /** 走 GET /admin/evaluation-datasets/{app}/{version} 同一入口回读，而不是直接查库。 */
    @SuppressWarnings("unchecked")
    private List<JsonNode> readBackCases() {
        var response = (ApiResponse<Map<String, Object>>) controller.get(code, version);
        assertEquals("OK", response.code());
        var raw = (JsonNode) response.data().get("cases");
        assertEquals(1, raw.size());
        return List.of(raw.get(0));
    }

    private int storedRows() {
        return db.queryForObject(
                "select count(*) from monitor.dataset where tenant_id=? and app_code=?",
                Integer.class,
                "monitor-tests",
                code);
    }
}
