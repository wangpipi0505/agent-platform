package cn.agentplatform.app.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.agentplatform.app.api.AppController;
import cn.agentplatform.app.domain.AppRules;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.AuthorityProperties;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 已发布模板的内容与发布摘要必须一致：内容被改、摘要缺失或格式非法都不能被当成正常版本读出。
 * 校验落在唯一读方法上，内部端点与 admin 读端点因此同受保护；草稿写入路径不受影响。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class TemplateDigestTest {
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    private static final String TABLE = "app.prompt_template_version";
    private static JdbcTemplate db;
    private static TemplateService templates;
    private MockMvc mvc;
    private String tenant;
    private String code;

    @BeforeAll
    static void database() {
        PublishTransactionTest.database();
        db = PublishTransactionTest.db;
        templates = new TemplateService(db);
        // 上一轮异常退出可能留下关闭的不可变触发器；测试库只读自愈，避免故障注入污染后续用例。
        db.execute("alter table " + TABLE + " enable trigger immutable_template_version");
    }

    @BeforeEach
    void setup() {
        tenant = "digest-" + UUID.randomUUID();
        code = "digest_" + UUID.randomUUID().toString().replace("-", "");
        PublishTransactionTest.actor(tenant);
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new AppController(
                                        PublishTransactionTest.apps,
                                        templates,
                                        new AuthorityProperties(
                                                "test", "app", null, null, null, null, null, false,
                                                null)))
                        .setControllerAdvice(new PlatformErrors())
                        .build();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private Map<String, Object> templateBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "摘要校验模板");
        body.put("content", "你好 {{name}}，请总结 {{name}}。");
        body.put("variables", List.of(Map.of("name", "name", "type", "string")));
        body.put("outputContract", Map.of("format", "text"));
        return body;
    }

    private Map<String, Object> createTemplate() {
        return templates.create(templateBody());
    }

    private Map<String, Object> publishedTemplate() {
        createTemplate();
        return templates.publish(code, 1);
    }

    /** 读回字段必须能复算出同一摘要：这是写路径与校验路径共用同一规范化材料的证据。 */
    private static String recompute(Map<String, Object> version) {
        return Digest.sha256(
                AppRules.canonical(
                        Map.of(
                                "content", version.get("content"),
                                "variables", version.get("variables"),
                                "outputContract", version.get("outputContract"))));
    }

    private String storedSha() {
        return db.queryForObject(
                "select sha256 from " + TABLE + " where tenant_id=? and template_code=? and"
                        + " version_no=1",
                String.class,
                tenant,
                code);
    }

    /**
     * 已发布行受不可变触发器保护；只有在隔离测试库上临时关闭触发器才能复现"库中内容被改"的故障注入。
     * finally 必须恢复，避免把测试库留成可改已发布版本的状态。
     */
    private void tamperContent(String content) {
        db.execute("alter table " + TABLE + " disable trigger immutable_template_version");
        try {
            assertEquals(
                    1,
                    db.update(
                            "update " + TABLE + " set content=? where tenant_id=? and"
                                    + " template_code=? and version_no=1",
                            content,
                            tenant,
                            code),
                    "故障注入必须命中已发布行");
        } finally {
            db.execute("alter table " + TABLE + " enable trigger immutable_template_version");
        }
    }

    @Test
    void tamperedPublishedContentIsRejectedOnRead() {
        assertEquals("PUBLISHED", publishedTemplate().get("status"), "夹具必须先落到已发布状态");
        tamperContent("被篡改的正文");

        var failure =
                assertThrows(
                        PlatformException.class,
                        () -> templates.version(code, 1),
                        "已发布内容与摘要不一致时不得照常返回版本");
        assertEquals(409, failure.status());
        assertEquals("TEMPLATE_DIGEST_MISMATCH", failure.code());
        assertTrue(
                failure.getMessage().contains("v1"),
                "错误信息必须点明版本号，便于定位是哪个版本被改: " + failure.getMessage());
    }

    @Test
    void untamperedPublishedVersionStillReadsBackWithSameDigest() {
        assertEquals("PUBLISHED", publishedTemplate().get("status"));
        String stored = storedSha();
        assertNotNull(stored);
        assertTrue(SHA.matcher(stored).matches(), "落库摘要必须是 64 位小写 hex: " + stored);

        var read = templates.version(code, 1);
        assertEquals(stored, read.get("sha256"), "读回的摘要必须就是发布时写入的摘要");
        assertEquals(stored, recompute(read), "读回字段必须能复算出同一摘要，否则校验会误伤正常版本");
        assertEquals("PUBLISHED", read.get("status"));
    }

    @Test
    void missingOrMalformedDigestIsRejectedWithReason() {
        createTemplate();
        // 草稿行不受不可变触发器约束，可直接覆盖成历史坏数据。
        db.update(
                "update " + TABLE + " set sha256=? where tenant_id=? and template_code=? and"
                        + " version_no=1",
                "",
                tenant,
                code);
        var missing =
                assertThrows(
                        PlatformException.class,
                        () -> templates.version(code, 1),
                        "摘要缺失时不得照常返回版本");
        assertEquals(409, missing.status());
        assertEquals("TEMPLATE_DIGEST_MISMATCH", missing.code());
        assertTrue(
                missing.getMessage().contains("摘要缺失"),
                "错误信息必须说明摘要缺失: " + missing.getMessage());
        assertTrue(missing.getMessage().contains("v1"), "错误信息必须点明版本号: " + missing.getMessage());

        db.update(
                "update " + TABLE + " set sha256=? where tenant_id=? and template_code=? and"
                        + " version_no=1",
                "Z".repeat(64),
                tenant,
                code);
        var malformed =
                assertThrows(
                        PlatformException.class,
                        () -> templates.version(code, 1),
                        "非小写 hex 摘要不得照常返回版本");
        assertEquals(409, malformed.status());
        assertEquals("TEMPLATE_DIGEST_MISMATCH", malformed.code());
        assertTrue(
                malformed.getMessage().contains("摘要缺失"),
                "非 64 位小写 hex 与缺失同码同类别: " + malformed.getMessage());
    }

    @Test
    void internalReadEndpointRejectsTamperedVersion() throws Exception {
        publishedTemplate();
        tamperContent("被篡改的正文");

        String response =
                mvc.perform(
                                get(
                                        "/internal/prompt-templates/{code}/versions/{version}",
                                        code,
                                        1))
                        .andExpect(status().isConflict())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertTrue(response.contains("TEMPLATE_DIGEST_MISMATCH"), response);
        assertTrue(response.contains("v1"), response);
    }
}
