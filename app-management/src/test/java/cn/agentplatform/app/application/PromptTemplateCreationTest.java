package cn.agentplatform.app.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;

/** Real PG drafts/candidates: the browser chooses a version; the server owns its digest/body. */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class PromptTemplateCreationTest {
    static JdbcTemplate db;
    static AppService apps;
    static TemplateService templates;
    String tenant, template;

    @BeforeAll static void database() {
        PublishTransactionTest.database();
        db = PublishTransactionTest.db;
        apps = PublishTransactionTest.apps;
        templates = new TemplateService(db);
    }

    @BeforeEach void actor() {
        tenant = "prompt-create-" + UUID.randomUUID();
        template = "tpl_" + UUID.randomUUID().toString().replace("-", "");
        PublishTransactionTest.actor(tenant);
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    Map<String, Object> body(String content) {
        return Map.of("code", template, "name", "精确模板", "content", content,
                "variables", List.of(), "outputContract", Map.of("format", "text"));
    }

    Map<String, Object> create(String code, Map<String, Object> ref) {
        var body = new LinkedHashMap<String, Object>();
        body.put("code", code); body.put("name", "模板应用"); body.put("type", "CHAT");
        if (ref != null) body.put("promptTemplateRef", ref);
        return apps.create(body);
    }

    @Test void explicitPublishedVersionPinsServerShaAndCandidateBody() {
        templates.create(body("原模板正文"));
        var published = templates.publish(template, 1);
        var created = create("selected", Map.of("code", template, "version", 1));
        var config = Json.tree(created.get("config"));
        assertEquals("TEMPLATE", config.path("promptSource").asText());
        assertEquals(1, config.path("promptRefs").size());
        var ref = config.path("promptRefs").get(0);
        assertEquals(template, ref.path("code").asText());
        assertEquals(1, ref.path("version").asInt());
        assertEquals(published.get("sha256"), ref.path("sha256").asText());
        var frozen = Json.tree(apps.frozenCandidate("selected", 1,
                created.get("candidateSha256").toString()));
        var prompt = frozen.path("dependencyVersions").path("frozenPrompts").get(0);
        assertNotNull(prompt, "候选必须持久化可核验的原模板正文");
        assertEquals("原模板正文", prompt.path("content").asText());
        assertEquals(ref.path("sha256"), prompt.path("sha256"));
        assertEquals(created.get("candidateSha256"), frozen.path("candidateSha256").asText());
    }

    @Test void unpublishedFractionalSpoofedOrOtherTenantReferenceCannotCreateDraft() {
        templates.create(body("草稿正文"));
        assertThrows(PlatformException.class,
                () -> create("unpublished", Map.of("code", template, "version", 1)));
        templates.publish(template, 1);
        assertThrows(PlatformException.class,
                () -> create("fractional", Map.of("code", template, "version", 1.5)));
        assertThrows(PlatformException.class,
                () -> create("spoof", Map.of("code", template, "version", 1,
                        "sha256", "a".repeat(64), "content", "客户端正文")));
        PublishTransactionTest.actor(tenant + "-other");
        assertThrows(PlatformException.class,
                () -> create("foreign", Map.of("code", template, "version", 1)));
        assertEquals(0, db.queryForObject("select count(*) from app.application where tenant_id in (?,?)",
                Integer.class, tenant, tenant + "-other"));
    }

    @Test void newerTemplateDoesNotChangeSelectedVersionAndLegacyInlineIsNotSwitched() {
        templates.create(body("v1原文")); templates.publish(template, 1);
        var selected = create("selected", Map.of("code", template, "version", 1));
        var before = apps.frozenCandidate("selected", 1, selected.get("candidateSha256").toString());
        templates.revise(template, body("v2新原文")); templates.publish(template, 2);
        assertEquals(Json.tree(before).path("dependencyVersions").path("frozenPrompts"),
                Json.tree(apps.frozenCandidate("selected", 1, selected.get("candidateSha256").toString()))
                        .path("dependencyVersions").path("frozenPrompts"));
        var legacy = create("legacy", null);
        assertTrue(Json.tree(legacy.get("config")).isEmpty(), "不自动挑选任何模板");
        var saved = apps.save("legacy", Map.of("draftRev", 1,
                "config", Map.of("systemPrompt", "历史内联正文")), false);
        assertEquals(Map.of("systemPrompt", "历史内联正文"), saved.get("config"));
        assertFalse(Json.tree(saved.get("config")).has("promptSource"));
    }
}
