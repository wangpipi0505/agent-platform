package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.domain.SkillDigest;
import cn.agentplatform.skill.infra.SkillClients;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.*;

/**
 * A3-2 技能双轨链路验证（skill V321；库稿 §4.8、接口稿 §5.6、主方案 §6.1）：
 * GUIDE 登记（SKILL.md 形状五字段契约 + kind 过滤）、发布链路复用（证据门禁一致）、
 * TOOL 契约 businessIntentInput 发布预检双分支（副作用未声明/显式 null→拒绝发布并定位字段；
 * 声明→过；非副作用免检）与注册期放宽矩阵。专用库 agent_platform_v321_test。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class SkillGuideDualTrackPostgresTest {
    private static final String DATABASE = "agent_platform_v321_test";
    private static final String HOST = "jdbc:postgresql://127.0.0.1:25432/" + DATABASE;
    private static final String TENANT = "skill-v321-tests";

    private static JdbcTemplate db;
    private static DataSourceTransactionManager manager;

    private SkillCatalog catalog;
    private String code;

    @BeforeAll
    static void migrateDedicatedDatabase() {
        String password = System.getenv("AGENT_DB_PASSWORD");
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:25432/postgres", "agent_v12", password));
        if (admin.queryForObject(
                        "select count(*) from pg_database where datname=?", Integer.class, DATABASE)
                == 0) admin.execute("create database " + DATABASE);
        var ds = new DriverManagerDataSource(HOST, "agent_v12", password);
        new JdbcTemplate(ds).execute("drop schema if exists skill cascade");
        Flyway.configure()
                .dataSource(ds)
                .schemas("skill")
                .defaultSchema("skill")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
    }

    @BeforeEach
    void prepareActorAndCatalog() {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("skill-tester")
                        .claim("tenant_id", TENANT)
                        .claim("roles", List.of("ADMIN", "EDITOR", "SERVICE"))
                        .claim("attributes", Map.of("region", List.of("A")))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        catalog = new SkillCatalog(db, manager, mock(SkillClients.class), "http://unused");
        code = "g_" + UUID.randomUUID().toString().replace("-", "");
    }

    private Map<String, Object> guideBody() {
        var body = new LinkedHashMap<String, Object>();
        body.put("code", code);
        body.put("name", "团队协作规范");
        body.put("kind", "GUIDE");
        body.put("description", "when the team asks for collaboration norms");
        body.put("whenToUse", "协作类请求");
        body.put("modelInvocable", true);
        body.put("content", "---\nname: team-norms\n---\n按以下规范协作……");
        return body;
    }

    /** 只读 TOOL 契约：守卫属性齐备，可直接通过发布预检（除证据门禁外）。 */
    private Map<String, Object> readonlyBody(String skillCode) {
        var body = new LinkedHashMap<String, Object>();
        body.put("code", skillCode);
        body.put("name", "查询技能");
        body.put("type", "HTTP");
        body.put("endpoint", "http://127.0.0.1:1/readonly");
        body.put("description", "只读查询");
        body.put(
                "inputSchema",
                Map.of("type", "object", "properties", Map.of("region", Map.of("type", "string"))));
        body.put("outputSchema", Map.of("type", "object"));
        body.put(
                "execution",
                Map.of("readonly", true, "sideEffect", false, "idempotent", true, "cancellable", false));
        body.put("guard", Map.of("attributes", Map.of("region", List.of("LOCAL"))));
        body.put("timeoutMs", 3000);
        return body;
    }

    /** 副作用 TOOL 契约：confirmRoles 齐备；approval 形状由调用方决定。 */
    private Map<String, Object> sideEffectBody(String skillCode, Map<String, Object> approval) {
        var body = new LinkedHashMap<String, Object>();
        body.put("code", skillCode);
        body.put("name", "写技能");
        body.put("type", "HTTP");
        body.put("endpoint", "http://127.0.0.1:1/write");
        body.put("description", "受控写入");
        body.put(
                "inputSchema",
                Map.of("type", "object", "properties", Map.of("intent", Map.of("type", "string"))));
        body.put("outputSchema", Map.of("type", "object"));
        var execution = new LinkedHashMap<String, Object>();
        execution.put("readonly", false);
        execution.put("sideEffect", true);
        execution.put("idempotent", true);
        execution.put("cancellable", false);
        execution.put("confirmRoles", List.of("APPROVER"));
        if (approval != null) execution.put("approval", approval);
        body.put("execution", execution);
        body.put("guard", Map.of("attributes", Map.of("region", List.of("LOCAL"))));
        body.put("timeoutMs", 3000);
        return body;
    }

    /** 按既有先例直插通过证据（连接测试+选型验收），把发布门禁收敛到被测分支。 */
    private void grantEvidence(String skillCode) {
        var created = catalog.version(skillCode, 1);
        long id = ((Number) created.get("skillVersionId")).longValue();
        db.update(
                "insert into skill.connection_test(tenant_id,skill_version_id,candidate_sha256,passed,report,semantic_digest)"
                        + " values(?,?,?,true,'{}',?)",
                TENANT,
                id,
                created.get("sha256"),
                created.get("semanticDigest"));
        db.update(
                "insert into"
                    + " skill.acceptance(id,tenant_id,skill_version_id,candidate_sha256,passed,hit_rate,report,semantic_digest)"
                    + " values(?,?,?,?,true,1,'{}',?)",
                "sa_" + UUID.randomUUID().toString().replace("-", ""),
                TENANT,
                id,
                created.get("sha256"),
                created.get("semanticDigest"));
    }

    // ---------------------------------------------------------------- GUIDE 登记

    @Test
    void guideRegistrationStoresSkillMdContractShapeAndFiltersByKind() {
        var created = catalog.create(guideBody());
        assertEquals("GUIDE", created.get("kind"), "公共视图必须携带 kind");
        // GUIDE 不建新表：正文与 frontmatter 整体进 skill_version.contract（约定五字段形状）。
        var row =
                db.queryForMap(
                        "select s.kind, v.contract, v.sha256 from skill.skill s"
                            + " join skill.skill_version v on v.tenant_id=s.tenant_id and"
                            + " v.skill_code=s.code where s.tenant_id=? and s.code=?",
                        TENANT,
                        code);
        assertEquals("GUIDE", row.get("kind"));
        var contract = Json.read(row.get("contract").toString(), Map.class);
        assertEquals(
                Set.of("name", "description", "whenToUse", "modelInvocable", "content"),
                contract.keySet(),
                "GUIDE 契约必须是 SKILL.md 五字段形状");
        assertEquals("团队协作规范", contract.get("name"));
        assertEquals(Boolean.TRUE, contract.get("modelInvocable"));
        assertTrue(((String) contract.get("content")).contains("按以下规范协作"));
        // sha256 沿用现有机制：整份契约规范化字节摘要。
        assertEquals(
                Digest.sha256(Json.canonical(contract)),
                row.get("sha256").toString(),
                "GUIDE 版本 sha256 必须覆盖整份契约");
        assertEquals(
                SkillDigest.VERSION,
                ((Number)
                                catalog.version(code, 1).get("digestVersion"))
                        .intValue(),
                "版本/摘要链路对 GUIDE 全复用");

        // GET /admin/skills?kind= 过滤（接口稿 §5.6）。
        assertTrue(
                catalog.list("GUIDE").stream().anyMatch(s -> code.equals(s.get("code"))),
                "kind=GUIDE 过滤必须命中");
        assertTrue(
                catalog.list("TOOL").stream().noneMatch(s -> code.equals(s.get("code"))),
                "kind=TOOL 过滤不得混入 GUIDE");
        assertTrue(
                catalog.list(null).stream().anyMatch(s -> code.equals(s.get("code"))),
                "不过滤返回全部");
        var bad =
                assertThrows(
                        PlatformException.class, () -> catalog.list("MODULE"), "kind 过滤越界必须拒绝");
        assertEquals(400, bad.status());
    }

    @Test
    void guideContractValidationRejectsMissingOrMalformedFields() {
        for (String[] missing : new String[][] {{"name"}, {"description"}, {"content"}}) {
            var body = guideBody();
            body.remove(missing[0]);
            var failure =
                    assertThrows(
                            PlatformException.class,
                            () -> catalog.create(body),
                            "GUIDE 缺 " + missing[0] + " 必须拒绝");
            assertEquals(400, failure.status());
            assertEquals("INVALID_SKILL", failure.code());
        }
        var blankContent = guideBody();
        blankContent.put("content", "   ");
        assertThrows(PlatformException.class, () -> catalog.create(blankContent), "content 空白必须拒绝");
        var nonBoolean = guideBody();
        nonBoolean.put("modelInvocable", "true");
        assertThrows(
                PlatformException.class,
                () -> catalog.create(nonBoolean),
                "modelInvocable 必须显式布尔");
        var toolField = guideBody();
        toolField.put("endpoint", "http://127.0.0.1:1/x");
        var mixed =
                assertThrows(
                        PlatformException.class,
                        () -> catalog.create(toolField),
                        "TOOL 专属字段混入 GUIDE 必须拒绝（双轨形状互不渗透）");
        assertTrue(mixed.getMessage().contains("endpoint"), "错误必须定位到混入字段");
        var badKind = guideBody();
        badKind.put("kind", "PROMPT");
        assertThrows(PlatformException.class, () -> catalog.create(badKind), "kind 越界必须拒绝");
    }

    @Test
    void guideDraftSaveReplacesWholeDocumentAndPublishReusesEvidenceChain() {
        catalog.create(guideBody());
        var updated = guideBody();
        updated.put("content", "---\nname: team-norms v2\n---\n修订后的协作规范……");
        var saved = catalog.save(code, updated);
        assertEquals(1, ((Number) saved.get("versionNo")).intValue(), "草稿原地保存复用既有版本机制");
        var contract = (Map<?, ?>) saved.get("definition");
        assertEquals(
                "---\nname: team-norms v2\n---\n修订后的协作规范……",
                contract.get("content"),
                "GUIDE 草稿按整份文档替换");

        // GUIDE 沿用现有发布链路：证据门禁与指针交换一致（接口稿 §5.6/主方案 §6.1）。
        var unpublished =
                assertThrows(PlatformException.class, () -> catalog.publish(code, 1));
        assertEquals("CONNECTION_TEST_REQUIRED", unpublished.code(), "GUIDE 发布同样要过证据门禁");
        grantEvidence(code);
        var published = catalog.publish(code, 1);
        assertEquals("PUBLISHED", published.get("status"));
        assertEquals(
                "PUBLISHED",
                db.queryForObject(
                        "select s.status from skill.skill s where s.tenant_id=? and s.code=?",
                        String.class,
                        TENANT,
                        code),
                "发布指针交换后技能主行状态为 PUBLISHED");
        // 统计响应区分 kind（接口稿 §5.6）。
        var stats = new SkillStatistics(db, manager).query(code, null, null, "day", null);
        assertEquals("GUIDE", stats.get("kind"), "统计响应必须区分 kind");
    }

    // ---------------------------------------------------------------- 意图输入预检

    @Test
    void sideEffectWithoutBusinessIntentInputIsRejectedAtPublish() {
        String writeCode = "w_" + UUID.randomUUID().toString().replace("-", "");
        // 注册期接受未声明（首期不把要求前移到登记），发布预检拒绝——错误定位到字段路径。
        var created = catalog.create(sideEffectBody(writeCode, null));
        assertFalse(
                created.get("definition").toString().contains("businessIntentInput"),
                "未声明时不落键");
        var failure =
                assertThrows(
                        PlatformException.class,
                        () -> catalog.publish(writeCode, 1),
                        "副作用技能未声明意图输入必须拒绝发布");
        assertEquals(409, failure.status());
        assertEquals("BUSINESS_INTENT_INPUT_REQUIRED", failure.code());
        assertTrue(
                failure.getMessage().contains("execution.approval.businessIntentInput"),
                "错误必须定位到字段路径: " + failure.getMessage());
    }

    @Test
    void sideEffectWithDeclaredBusinessIntentInputPassesPrecheckAndPublishes() {
        String writeCode = "w_" + UUID.randomUUID().toString().replace("-", "");
        var body =
                sideEffectBody(
                        writeCode,
                        Map.of("businessIntentInput", "businessIntentId"));
        var created = catalog.create(body);
        // 注册期放宽：SINGLE_STEP 副作用技能声明意图输入字段合法，契约保留该声明。
        var contract = (Map<?, ?>) created.get("definition");
        var execution = (Map<?, ?>) contract.get("execution");
        var approval = (Map<?, ?>) execution.get("approval");
        assertEquals("businessIntentId", approval.get("businessIntentInput"), "声明必须原样进入冻结契约");
        grantEvidence(writeCode);
        var published = catalog.publish(writeCode, 1);
        assertEquals("PUBLISHED", published.get("status"), "声明非 null 字段名→预检通过并正常发布");
    }

    @Test
    void explicitNullBusinessIntentInputIsRejectedAtRegistrationAndPublish() {
        String writeCode = "w_" + UUID.randomUUID().toString().replace("-", "");
        // 注册期：显式 null 沿用现有校验拒绝（评审七轮 03：null 分支移出首期）。
        var body =
                sideEffectBody(
                        writeCode,
                        Collections.singletonMap("businessIntentInput", null));
        var registration =
                assertThrows(
                        PlatformException.class,
                        () -> catalog.create(body),
                        "显式 null 必须在注册期拒绝");
        assertEquals("BUSINESS_INTENT_INPUT_INVALID", registration.code());
        assertEquals(400, registration.status());
        // 发布口：手工写入含显式 null 的草稿契约（sha 故意不同步），预检仍拒绝并定位字段。
        catalog.create(sideEffectBody(writeCode, Map.of("businessIntentInput", "businessIntentId")));
        db.update(
                "update skill.skill_version set contract=?::jsonb where tenant_id=? and skill_code=? and version_no=1",
                "{\"type\":\"HTTP\",\"description\":\"受控写入\",\"endpoint\":\"http://127.0.0.1:1/write\","
                        + "\"execution\":{\"readonly\":false,\"sideEffect\":true,\"idempotent\":true,"
                        + "\"cancellable\":false,\"confirmRoles\":[\"APPROVER\"],"
                        + "\"approval\":{\"businessIntentInput\":null}},"
                        + "\"guard\":{\"attributes\":{},\"roles\":[]}}",
                TENANT,
                writeCode);
        var publish =
                assertThrows(
                        PlatformException.class,
                        () -> catalog.publish(writeCode, 1),
                        "显式 null 契约必须在发布口拒绝");
        assertEquals("BUSINESS_INTENT_INPUT_REQUIRED", publish.code());
        assertTrue(publish.getMessage().contains("execution.approval.businessIntentInput"));
    }

    @Test
    void nonSideEffectSkillsAreExemptFromIntentPrecheck() {
        String readCode = "r_" + UUID.randomUUID().toString().replace("-", "");
        catalog.create(readonlyBody(readCode));
        grantEvidence(readCode);
        // 只读技能无副作用，不检意图输入，正常走完发布链路。
        assertEquals("PUBLISHED", catalog.publish(readCode, 1).get("status"));
        var stats = new SkillStatistics(db, manager).query(readCode, null, null, "day", null);
        assertEquals("TOOL", stats.get("kind"), "TOOL 统计响应区分 kind");
    }

    @Test
    void registrationMatrixForIntentDeclarationShapes() {
        String badName = "w_" + UUID.randomUUID().toString().replace("-", "");
        // 非副作用技能声明意图输入：无语义，拒绝。
        var readonlyWithIntent = readonlyBody(badName);
        readonlyWithIntent.put(
                "execution",
                Map.of(
                        "readonly", true,
                        "sideEffect", false,
                        "idempotent", true,
                        "cancellable", false,
                        "approval", Map.of("businessIntentInput", "businessIntentId")));
        var invalid =
                assertThrows(
                        PlatformException.class,
                        () -> catalog.create(readonlyWithIntent),
                        "非副作用技能声明意图输入必须拒绝");
        assertEquals("BUSINESS_INTENT_INPUT_INVALID", invalid.code());
        // 字段名形状越界：拒绝（只允许 [A-Za-z_][A-Za-z0-9_]{0,63}）。
        String malformed = "w_" + UUID.randomUUID().toString().replace("-", "");
        var badShape =
                sideEffectBody(
                        malformed, Map.of("businessIntentInput", "1bad-field"));
        assertThrows(
                PlatformException.class,
                () -> catalog.create(badShape),
                "意图输入字段名形状越界必须拒绝");
        // 两段审批路径声明意图输入：既有行为保持（ SkillPrepareExecuteTest 兼容）。
        String twoPhase = "w_" + UUID.randomUUID().toString().replace("-", "");
        var twoPhaseBody =
                sideEffectBody(
                        twoPhase,
                        Map.of(
                                "mode",
                                "PREPARE_EXECUTE",
                                "risk",
                                "STANDARD",
                                "prepare",
                                Map.of("endpoint", "http://127.0.0.1:1/prepare"),
                                "businessIntentInput",
                                "businessIntentId"));
        var created = catalog.create(twoPhaseBody);
        assertEquals("DRAFT", created.get("status"));
        var definition = (Map<?, ?>) created.get("definition");
        assertNotNull(((Map<?, ?>) definition.get("execution")).get("approval"), "两段审批声明保持既有形状");
    }
}
