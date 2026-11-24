package cn.agentplatform.engine.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.api.RunController;
import cn.agentplatform.engine.application.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "AGENT_R37_ENGINE_DB_URL", matches = ".+")
class EvaluationRunPostgresTest {
    @Test
    void monitorDelegationCreatesAndExecutesTheSameFrozenCandidateOnce() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("AGENT_R37_ENGINE_DB_URL"),
                System.getenv("AGENT_DB_USER"), System.getenv("AGENT_DB_PASSWORD"));
        var db = new JdbcTemplate(source);
        assertThat(db.queryForObject("select current_database()", String.class)).isEqualTo("agent_platform_r37_eval");
        Flyway.configure().dataSource(source).schemas("engine").defaultSchema("engine")
                .locations("classpath:db/migration").load().migrate();

        var authority = new LoopbackAuthority();
        authority.portalActor(new Actor("tenant-a", "alice", Set.of("USER"),
                Map.of("region", Set.of("A"), "dataLabels", Set.of("initial"))));
        try (authority; var gateway = authority.client("gateway");
             var monitor = authority.client("monitor"); var engine = authority.client("engine")) {
            var clock = authority.clock();
            var stores = new RunAuthorityStore(source, clock);
            var trace = new TraceStore(db, new DataSourceTransactionManager(source), clock);
            var clients = mock(RuntimeClients.class);
            when(clients.iamEnabled()).thenReturn(true);
            try (var scheduler = new RunScheduler(new RunScheduleRepository(trace, true), 1, 1, 10, 90000, 1000)) {
                var service = new RunService(trace, clients, new FlowDriver(trace, null), null, scheduler,
                        new RunAuthority(stores, engine, clock, Duration.ofSeconds(3)), stores, clock);
                var candidate = Json.object();
                candidate.put("appCode", "ops");
                candidate.put("type", "FLOW");
                candidate.put("name", "IAM evaluation");
                candidate.put("description", "");
                candidate.put("domain", "");
                candidate.put("bizCategory", "");
                candidate.set("tags", Json.tree(List.of()));
                candidate.set("config", Json.object());
                candidate.set("definition", Json.read(OwnedFlowSchedulerPostgresTest.SIMPLE));
                String digest = Digest.sha256(Json.canonical(candidate));
                candidate.put("draftRev", 7);
                candidate.put("candidateSha256", digest);
                var root = EvaluationCandidateBinding.of("ops", 7, digest);
                var create = new ServiceCall("monitor", "proxy.monitor.post.EvaluationController.evaluate", root);
                var execute = new ServiceCall("engine", "evaluation.run", root);
                var verified = monitor.authorize(gateway.exchange(authority.portal(), create).value(), create);
                var ticket = monitor.forward(verified, execute);
                var body = Json.tree(Map.of("appCode", "ops", "draftRev", 7, "candidateSha256", digest,
                        "datasetVersion", "dataset-1", "evalReportId", UUID.randomUUID().toString(),
                        "caseId", "case-1", "inputs", Map.of("sample", "fixture"), "frozenCandidate", candidate));
                var http = new MockHttpServletRequest("POST", "/internal/evaluations/run");
                http.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/internal/evaluations/run");
                var response = new MockHttpServletResponse();
                var controller = new RunController(service, null, null, null);
                var handler = new HandlerMethod(controller, RunController.class.getMethod("eval", JsonNode.class));
                var receiver = new ServiceAuthorization("engine", new ServiceRoutePolicy(), engine);
                try (var decoder = new TrustedJwtDecoder(authority.config("engine"), clock, true)) {
                    SecurityContextHolder.getContext().setAuthentication(
                            new JwtAuthenticationToken(decoder.decode(ticket.value()), List.of()));
                    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(http));
                    receiver.preHandle(http, response, handler);
                    var first = Json.tree(controller.eval(body.deepCopy())).path("data");
                    String runId = first.path("runId").asText();
                    long run = db.queryForObject("select id from engine.run where public_id=?", Long.class, runId);
                    assertThat(stores.load(run).root()).isEqualTo(root);
                    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    String status;
                    do {
                        status = db.queryForObject("select status from engine.run where id=?", String.class, run);
                        if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status)) break;
                        Thread.sleep(25);
                    } while (System.nanoTime() < deadline);
                    assertThat(status).isEqualTo("SUCCEEDED");
                    var result = Json.tree(controller.eval(body.deepCopy())).path("data");
                    assertThat(result.path("runId").asText()).isEqualTo(runId);
                    assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
                    assertThat(result.path("sourceEvidence").path("environment").asText()).isEqualTo("REAL");
                    var changed = (com.fasterxml.jackson.databind.node.ObjectNode) body.deepCopy();
                    changed.set("inputs", Json.tree(Map.of("sample", "changed")));
                    assertThatThrownBy(() -> controller.eval(changed))
                            .isInstanceOfSatisfying(cn.agentplatform.common.PlatformException.class,
                                    error -> assertThat(error.code()).isEqualTo("REQUEST_CONFLICT"));
                } finally {
                    receiver.afterCompletion(http, response, handler, null);
                    SecurityContextHolder.clearContext();
                    RequestContextHolder.resetRequestAttributes();
                }
            }
        }
    }
}
