package cn.agentplatform.engine.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.api.ChatSessionController;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "AGENT_R37_ENGINE_DB_URL", matches = ".+")
class EvaluationRunPostgresTest {
    @Test
    void evaluationTurnsReuseTheAuthorizedPriorAnswerInCanonicalModelInput() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("AGENT_R37_ENGINE_DB_URL"),
                System.getenv("AGENT_DB_USER"), System.getenv("AGENT_DB_PASSWORD"));
        var db = new JdbcTemplate(source);
        assertThat(db.queryForObject("select current_database()", String.class)).matches("agent_platform_r37_eval(?:_[a-z0-9]+)?");
        Flyway.configure().dataSource(source).schemas("engine").defaultSchema("engine")
                .locations("classpath:db/migration").outOfOrder(true).load().migrate();
        var fixture = new LoopbackAuthority();
        fixture.portalActor(new Actor("tenant-a", "alice", Set.of("USER"),
                Map.of("region", Set.of("A"), "dataLabels", Set.of("initial"))));
        try (fixture; var gateway = fixture.client("gateway"); var monitor = fixture.client("monitor");
             var engine = fixture.client("engine")) {
            var clock = fixture.clock();
            var trace = new TraceStore(db, new DataSourceTransactionManager(source), clock);
            var store = new RunAuthorityStore(source, clock);
            var clients = mock(RuntimeClients.class);
            when(clients.iamEnabled()).thenReturn(true);
            var requests = new CopyOnWriteArrayList<JsonNode>();
            var actualModel = Map.of("modelEntryId", 1, "modelName", "fixture-chat",
                    "providerId", 1, "endpoint", "http://127.0.0.1:1");
            when(clients.chatInitialStream(any(byte[].class), anyString(), any(), any(), any())).thenAnswer(call -> {
                requests.add(Json.read(new String((byte[]) call.getArgument(0), StandardCharsets.UTF_8)));
                return Json.tree(Map.of("text", "answer-" + requests.size(), "toolCalls", List.of(),
                        "usage", Map.of(), "actualModel", actualModel));
            });
            var workerFailure = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
            var repository = new RunScheduleRepository(trace, true);
            try (var scheduler = new RunScheduler(repository, 1, 1, 10, 90000, 1000) {
                     @Override
                     public void bind(java.util.function.LongConsumer work, Runnable renew, Runnable ready) {
                         super.bind(run -> {
                             try {
                                 work.accept(run);
                             } catch (RuntimeException failure) {
                                 workerFailure.compareAndSet(null, failure);
                                 throw failure;
                             }
                         }, renew, ready);
                     }
                 };
                 var chunks = new ChunkBus(); var decoder = new TrustedJwtDecoder(fixture.config("engine"), clock, true)) {
                var service = new RunService(trace, clients, new FlowDriver(trace, null), null, scheduler,
                        new RunAuthority(store, engine, clock, Duration.ofSeconds(3)), store, clock,
                        new ModelInputMaterials(source, engine, clock), chunks);
                scheduler.start();
                var candidate = Json.object();
                candidate.put("appCode", "r37chat");
                candidate.put("type", "CHAT");
                candidate.put("name", "IAM chat");
                candidate.put("description", "");
                candidate.put("domain", "");
                candidate.put("bizCategory", "");
                candidate.set("tags", Json.tree(List.of()));
                candidate.set("definition", Json.object());
                candidate.set("config", Json.tree(Map.of("modelEntryId", 1, "promptSource", "DIRECT", "systemPrompt", "system")));
                String digest = Digest.sha256(Json.canonical(candidate));
                candidate.put("draftRev", 1);
                candidate.put("candidateSha256", digest);
                var entry = Map.of("baseUrl", actualModel.get("endpoint"), "providerId", 1, "modelName", "fixture-chat");
                candidate.set("dependencyVersions", Json.tree(Map.of("skillRefs", List.of(), "modelRefs", List.of(
                        Map.of("modelEntryId", 1, "modelBindingRef", Map.of("bindingId", 1,
                                "sha256", Digest.sha256(Json.canonical(entry))), "entry", entry)))));
                var root = EvaluationCandidateBinding.of("r37chat", 1, digest);
                var create = new ServiceCall("monitor", "proxy.monitor.post.EvaluationController.evaluate", root);
                var call = new ServiceCall("engine", "evaluation.turn", root);
                var ingress = monitor.authorize(gateway.exchange(fixture.portal(), create).value(), create);
                var ticket = monitor.forward(ingress, call);
                var controller = new ChatSessionController(new ChatSessions(trace, clients, service));
                var handler = new HandlerMethod(controller, ChatSessionController.class.getMethod("evaluationTurn", JsonNode.class));
                var receiver = new ServiceAuthorization("engine", new ServiceRoutePolicy(), engine);
                var request = new MockHttpServletRequest("POST", "/internal/evaluations/turn");
                request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/internal/evaluations/turn");
                var response = new MockHttpServletResponse();
                String report = UUID.randomUUID().toString();
                SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(ticket.value()), List.of()));
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
                try {
                    receiver.preHandle(request, response, handler);
                    var body = Json.tree(Map.of("appCode", "r37chat", "draftRev", 1, "candidateSha256", digest,
                            "datasetVersion", "chat-1", "evalReportId", report, "caseId", "history",
                            "turnNo", 1, "inputs", Map.of("message", "first"), "frozenCandidate", candidate));
                    var first = Json.tree(controller.evaluationTurn(body.deepCopy())).path("data");
                    awaitSuccess(db, first.path("runId").asText(), workerFailure);
                    var turn2 = (com.fasterxml.jackson.databind.node.ObjectNode) body.deepCopy();
                    turn2.put("turnNo", 2);
                    turn2.put("conversationId", first.path("conversationId").asText());
                    turn2.set("inputs", Json.tree(Map.of("message", "second")));
                    var second = Json.tree(controller.evaluationTurn(turn2.deepCopy())).path("data");
                    awaitSuccess(db, second.path("runId").asText(), workerFailure);
                    assertThat(Json.tree(controller.evaluationTurn(turn2.deepCopy())).path("data").path("runId"))
                            .isEqualTo(second.path("runId"));
                    assertThat(requests).hasSize(2);
                    assertThat(requests.get(1).path("callKind").asText()).isEqualTo("EVALUATION");
                    assertThat(requests.get(1).path("messages")).isEqualTo(Json.tree(List.of(
                            Map.of("role", "system", "content", "system"),
                            Map.of("role", "user", "content", "first"),
                            Map.of("role", "assistant", "content", "answer-1"),
                            Map.of("role", "user", "content", "second"))));
                    var history = Json.read(db.queryForObject("select (snapshot->'evaluationHistory')::text"
                                    + " from engine.run where public_id=?", String.class, second.path("runId").asText()));
                    assertThat(history.path("priorTurns").get(0).path("runId")).isEqualTo(first.path("runId"));
                    assertThat(history.path("priorTurns").get(0).path("outputScopeSha256").asText()).matches("[a-f0-9]{64}");
                } finally {
                    receiver.afterCompletion(request, response, handler, null);
                    SecurityContextHolder.clearContext();
                    RequestContextHolder.resetRequestAttributes();
                }
            }
        }
    }

    private static void awaitSuccess(JdbcTemplate db, String run,
            java.util.concurrent.atomic.AtomicReference<RuntimeException> failure) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        String status;
        do {
            if (failure.get() != null) throw new AssertionError("Evaluation worker failed", failure.get());
            status = db.queryForObject("select status from engine.run where public_id=?", String.class, run);
            if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status)) break;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertThat(status).isEqualTo("SUCCEEDED");
    }

    @Test
    void monitorDelegationCreatesAndExecutesTheSameFrozenCandidateOnce() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("AGENT_R37_ENGINE_DB_URL"),
                System.getenv("AGENT_DB_USER"), System.getenv("AGENT_DB_PASSWORD"));
        var db = new JdbcTemplate(source);
        assertThat(db.queryForObject("select current_database()", String.class)).matches("agent_platform_r37_eval(?:_[a-z0-9]+)?");
        Flyway.configure().dataSource(source).schemas("engine").defaultSchema("engine")
                .locations("classpath:db/migration").outOfOrder(true).load().migrate();

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
            try (var scheduler = spy(new RunScheduler(new RunScheduleRepository(trace, true), 1, 1, 10, 90000, 1000))) {
                doNothing().when(scheduler).created(anyLong(), isNull());
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
                var verified = monitor.authorize(gateway.exchange(authority.portal(10), create).value(), create);
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
                    var original = stores.load(run).delegation();
                    String oldGrant = original.value();
                    receiver.afterCompletion(http, response, handler, null);
                    authority.advanceClock(Duration.between(clock.instant(), original.expiresAt()).plusSeconds(1));
                    var resume = new ServiceCall("monitor", "proxy.monitor.post.EvaluationController.resume", root);
                    var fresh = monitor.authorize(gateway.exchange(authority.portal(), resume).value(), resume);
                    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                            decoder.decode(monitor.forward(fresh, execute).value()), List.of()));
                    receiver.preHandle(http, response, handler);
                    var changed = (com.fasterxml.jackson.databind.node.ObjectNode) body.deepCopy();
                    changed.set("inputs", Json.tree(Map.of("sample", "changed")));
                    assertThatThrownBy(() -> controller.eval(changed))
                            .isInstanceOfSatisfying(cn.agentplatform.common.PlatformException.class,
                                    error -> assertThat(error.code()).isEqualTo("REQUEST_CONFLICT"));
                    assertThat(stores.load(run).delegation().value()).isEqualTo(oldGrant);
                    assertThat(Json.tree(controller.eval(body.deepCopy())).path("data").path("runId").asText()).isEqualTo(runId);
                    assertThat(stores.load(run).delegation().value()).isNotEqualTo(oldGrant);
                    assertThat(db.queryForObject("select grant_ref from engine.run_authority where run_id=?",
                            String.class, run)).isEqualTo(oldGrant);
                    assertThat(db.queryForObject("select count(*) from engine.evaluation_grant_generation where run_id=?",
                            Integer.class, run)).isEqualTo(1);
                    scheduler.start();
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
                } finally {
                    receiver.afterCompletion(http, response, handler, null);
                    SecurityContextHolder.clearContext();
                    RequestContextHolder.resetRequestAttributes();
                }
            }
        }
    }
}
