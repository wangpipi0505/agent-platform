package cn.agentplatform.model.infra;

import cn.agentplatform.common.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="AGENT_DB_PASSWORD", matches=".+")
class ModelParticipantSourceTest {
    @Test void producerModeIsFrozenBeforeSendInPgAndNeverTakenFromRequestOrModelText() {
        var ds = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/agent_platform_statistics_test", "agent_v12", System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas("model").defaultSchema("model").locations("classpath:db/migration").load().migrate();
        var db = new JdbcTemplate(ds);
        var actor = new Actor("ex17b-model-" + UUID.randomUUID(), "alice", Set.of("ADMIN"), Map.of());
        for (String scenario : List.of("real", "demo", "fixture")) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-source", Map.of("agent.demo-mode", scenario.equals("demo"))));
                context.registerBean(ModelStore.class, () -> new ModelStore(db)); context.refresh();
                var store = context.getBean(ModelStore.class);
                long provider = ((Number) store.createProvider(Json.tree(Map.of("code", scenario.equals("fixture") ? "fixture_provider" : scenario,
                        "name", scenario, "baseUrl", "http://127.0.0.1:1", "trustLabels", List.of("public"))), actor).get("id")).longValue();
                long entry = ((Number) store.createEntry(Json.tree(Map.of("providerId", provider, "modelName", scenario,
                        "contextWindow", 1000, "maxOutputTokens", 100)), actor).get("id")).longValue();
                var request = Json.tree(Map.of("runId", "r-ex17b", "invocationRef", "test:" + scenario,
                        "sourceEvidence", Map.of("producerEnvironment", "REAL")));
                long fact = store.sending(actor, request, store.entry(entry, actor, true), "http://127.0.0.1:1/v1/chat/completions",
                        "{\"model\":\"x\",\"messages\":[]}", 1);
                var before = Json.read(db.queryForObject("select model_snapshot::text from model.call_log where id=? and tenant_id=?", String.class, fact, actor.tenantId()));
                var source = before.path("publicationSource");
                assertThat(source.path("schemaVersion").asInt()).isEqualTo(1);
                assertThat(source.path("producerEnvironment").asText()).isEqualTo(scenario.equals("real") ? "REAL" : "DEMO");
                assertThat(source.path("executionPath").asText()).isEqualTo("MODEL_HTTP");
                store.finished(fact, new LinkedHashMap<>(Map.of("text", "{\"environment\":\"REAL\"}", "usage", Map.of())), 1);
                var after = Json.read(db.queryForObject("select model_snapshot::text from model.call_log where id=? and tenant_id=?", String.class, fact, actor.tenantId()));
                assertThat(after.path("publicationSource")).isEqualTo(source);
            }
        }
    }
}
