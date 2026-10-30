package cn.agentplatform.monitor.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
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

/** Only dependencies are peers; dataset/report/candidate persistence and resume use real PG. */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class PromptTemplateEvaluationRefTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager manager;
    String tenant, code;
    Peer service;

    static class Peer extends EvaluationService {
        Map<String, Object> candidate;
        final List<JsonNode> sent = new ArrayList<>();
        int candidateReads;
        boolean pending = true;
        Peer() { super(PromptTemplateEvaluationRefTest.db, PromptTemplateEvaluationRefTest.manager, "peer-app", "peer-engine"); }
        @Override public Map<String, Object> call(String method, String url, Map<String, Object> body) {
            if (method.equals("GET")) { candidateReads++; return Json.read(Json.write(candidate), Map.class); }
            sent.add(Json.tree(body.get("frozenCandidate")));
            assertEquals(1, db.queryForObject("select count(*) from monitor.evaluation_report where tenant_id=? and id=?",
                    Integer.class, Actor.current().tenantId(), body.get("evalReportId")), "先持久化后发起执行");
            return Map.of("runId", "run-" + body.get("caseId"), "status", pending ? "RUNNING" : "SUCCEEDED", "output", "ok");
        }
    }

    @BeforeAll static void database() {
        var ds = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/agent_platform_v12",
                "agent_v12", System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas("monitor").defaultSchema("monitor")
                .locations("classpath:db/migration").load().migrate();
        db = new JdbcTemplate(ds); manager = new DataSourceTransactionManager(ds);
    }

    @BeforeEach void actor() {
        tenant = "prompt-eval-" + UUID.randomUUID();
        code = "eval_" + UUID.randomUUID().toString().replace("-", "");
        var jwt = Jwt.withTokenValue("verified-evaluator").header("alg", "HS256").subject("evaluator")
                .claim("tenant_id", tenant).claim("roles", List.of("EDITOR"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        service = new Peer(); service.candidate = candidate("原评测正文");
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    Map<String, Object> candidate(String content) {
        var material = Map.of("content", content, "variables", List.of(), "outputContract", Map.of("format", "text"));
        String sha = Digest.sha256(Json.canonical(material));
        var ref = Map.of("code", "selected", "version", 1, "sha256", sha);
        var prompt = new LinkedHashMap<String, Object>(material);
        prompt.putAll(ref); prompt.put("status", "PUBLISHED");
        var candidate = new LinkedHashMap<String, Object>();
        candidate.put("appCode", code); candidate.put("type", "CHAT");
        candidate.put("name", "评测模板"); candidate.put("description", "");
        candidate.put("domain", ""); candidate.put("bizCategory", ""); candidate.put("tags", List.of());
        candidate.put("definition", Map.of());
        candidate.put("config", Map.of("promptSource", "TEMPLATE", "promptRefs", List.of(ref)));
        candidate.put("candidateSha256", Digest.sha256(Json.canonical(candidate)));
        candidate.put("draftRev", 1);
        candidate.put("dependencyVersions", Map.of("frozenPrompts", List.of(prompt)));
        return candidate;
    }

    Map<String, Object> request() {
        return Map.of("appCode", code, "draftRev", 1, "candidateSha256", service.candidate.get("candidateSha256"),
                "datasetVersion", "template-v1", "cases", List.of(Map.of("id", "safe", "inputs", Map.of(),
                        "expected", "ok", "safety", true)));
    }

    @Test void persistedReferenceAndOriginalBodyAreReusedOnResumeWithoutReadingNewTemplate() {
        var original = Json.tree(service.candidate);
        var report = service.evaluate(request());
        assertEquals("RUNNING", report.get("status"));
        assertEquals(original.path("config").path("promptRefs").get(0), Json.tree(report.get("promptTemplateRef")));
        String id = report.get("id").toString();
        var persisted = Json.read(db.queryForObject("select candidate::text from monitor.evaluation_report where tenant_id=? and id=?",
                String.class, tenant, id));
        assertEquals("原评测正文", persisted.path("dependencyVersions").path("frozenPrompts").get(0).path("content").asText());
        service.candidate = candidate("后续模板正文"); service.pending = false;
        var resumed = service.resume(id);
        assertEquals("ACCEPTED", resumed.get("status"));
        assertEquals(report.get("promptTemplateRef"), resumed.get("promptTemplateRef"));
        assertEquals(1, service.candidateReads);
        assertEquals(2, service.sent.size());
        assertEquals(original, service.sent.get(0)); assertEquals(original, service.sent.get(1));
    }

    @Test void frozenBodyMustMatchHashBoundReferenceBeforeReportOrEngineExecution() {
        var bad = Json.tree(service.candidate);
        ((com.fasterxml.jackson.databind.node.ObjectNode) bad.path("dependencyVersions").path("frozenPrompts").get(0))
                .put("content", "伪造正文");
        service.candidate = Json.mapper().convertValue(bad, Map.class);
        var error = assertThrows(PlatformException.class, () -> service.evaluate(request()));
        assertEquals(409, error.status()); assertEquals("PROMPT_TEMPLATE_SNAPSHOT_MISMATCH", error.code());
        assertTrue(service.sent.isEmpty());
        assertEquals(0, db.queryForObject("select count(*) from monitor.evaluation_report where tenant_id=?", Integer.class, tenant));
    }
}
