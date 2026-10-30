package cn.agentplatform.skill.application;

import cn.agentplatform.common.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="AGENT_DB_PASSWORD", matches=".+")
class SkillParticipantSourceTest {
    @Test void pendingAndReconciledSkillKeepProducerModeAndLegacyFactsCannotBeUpgradedFromRemoteJson() throws Exception {
        AsyncHttpExecutionTest.database();
        for (String scenario : List.of("real", "demo", "mock", "legacy")) {
            boolean demo = scenario.equals("demo");
            var fixture = new AsyncHttpExecutionTest(); fixture.setup();
            try (var context = new AnnotationConfigApplicationContext()) {
                fixture.prepareExecution();
                if (scenario.equals("mock")) {
                    fixture.authority.put("trigger_type", "DEBUG");
                    fixture.authority.put("mock_snapshot", Map.of("skillCode", fixture.code, "mode", "FIXED", "fixedResponse", Map.of("value", 7)));
                }
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-source", Map.of("agent.demo-mode", demo)));
                context.registerBean(SkillExecution.class, () -> fixture.executor); context.refresh();
                fixture.request.put("sourceEvidence", Map.of("producerEnvironment", "REAL"));
                fixture.actualResult = Map.of("value", 7, "sourceEvidence", Map.of("producerEnvironment", "REAL"));
                fixture.executor.execute(fixture.code, fixture.request);
                var pending = Json.read(AsyncHttpExecutionTest.db.queryForObject("select result::text from skill.execution_fact where tenant_id=? and call_id=?",
                        String.class, fixture.tenant, fixture.call));
                assertThat(pending.path("sourceEvidence").path("producerEnvironment").asText()).isEqualTo(demo || scenario.equals("mock") ? "DEMO" : "REAL");
                assertThat(pending.path("sourceEvidence").path("callId").asLong()).isEqualTo(fixture.call);
                assertThat(pending.path("sourceEvidence").path("executionPath").asText()).isEqualTo(scenario.equals("mock") ? "MOCK" : "HTTP");
                if (scenario.equals("mock")) { assertThat(fixture.submits.get()).isZero(); continue; }
                if (scenario.equals("legacy")) AsyncHttpExecutionTest.db.update("update skill.execution_fact set result=result-'sourceEvidence' where tenant_id=? and call_id=?", fixture.tenant, fixture.call);
                fixture.state = "DONE"; fixture.executor.reconcile(fixture.call);
                var finished = Json.read(AsyncHttpExecutionTest.db.queryForObject("select result::text from skill.execution_fact where tenant_id=? and call_id=?",
                        String.class, fixture.tenant, fixture.call));
                if (scenario.equals("legacy")) assertThat(finished.path("sourceEvidence").path("producerEnvironment").asText("UNKNOWN")).isEqualTo("UNKNOWN");
                else assertThat(finished.path("sourceEvidence")).isEqualTo(pending.path("sourceEvidence"));
                assertThat(finished.path("structured").path("sourceEvidence").path("producerEnvironment").asText()).isEqualTo("REAL");
            } finally {
                if (fixture.engine != null) fixture.engine.stop(0);
                if (fixture.httpWorkers != null) fixture.httpWorkers.shutdownNow();
                fixture.server.stop(0); SecurityContextHolder.clearContext();
            }
        }
    }
}
