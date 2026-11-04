package cn.agentplatform.skill.application;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.infra.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 标准环回 A/B 端点；仅替换配置来源和事实存储，不替换执行/解析/HTTP适配器。 */
class ApprovedConnectionDispatchTest {
    HttpServer a, b;
    List<String> hits = new ArrayList<>();
    List<Map<String, Object>> resolutionBindings = new ArrayList<>();
    Map<Long, Map<String, Object>> facts = new HashMap<>();
    Map<Long, JsonNode> registrations = new HashMap<>();
    Map<Long, Boolean> approvals = new HashMap<>();
    Map<String, Object> contract;
    SkillExecution execution;
    SkillClients clients;
    boolean revoked;
    String secret = "env:AGENT_SECRET_AP_CR02_BOUND";
    String code = "crm-read";
    String bindingPath = "";

    @BeforeEach void setup() throws Exception {
        var jwt = Jwt.withTokenValue("verified").header("alg", "HS256").subject("operator")
                .claim("tenant_id", "dispatch-test").claim("roles", List.of("SERVICE", "EDITOR"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        a = server("A"); b = server("B");
        clients = mock(SkillClients.class);
        when(clients.get(anyString())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            if (url.startsWith("http://app.test/")) return profile();
            long call = Long.parseLong(url.split("tool-calls/")[1].split("/")[0]);
            if (url.endsWith("/approval")) return Json.tree(Map.of("approved", approvals.getOrDefault(call, false),
                    "skillVersionId", 7, "parametersDigest", Digest.sha256("{}")));
            return registrations.get(call);
        });
        when(clients.post(anyString(), any())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            long call = Long.parseLong(url.split("tool-calls/")[1].split("/")[0]);
            if (url.endsWith("/claim")) return registrations.get(call);
            return Json.tree(Map.of("submitted", true));
        });
        var receipts = mock(ConnectionResolutionReceipts.class);
        when(receipts.record(any(), anyString(), anyMap(), any(), anyMap())).thenAnswer(inv -> {
            Map<String, Object> scope = inv.getArgument(4);
            resolutionBindings.add((Map<String, Object>) scope.get("executionBinding"));
            var copy = new LinkedHashMap<String, Object>(inv.getArgument(2));
            copy.put("receiptId", 1L); return copy;
        });
        var db = mock(JdbcTemplate.class);
        when(db.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            if (((String) inv.getArgument(0)).startsWith("insert into skill.execution_fact(")) {
                Object[] values = inv.getArguments();
                var row = new LinkedHashMap<String, Object>();
                row.put("tenant_id", values[1]); row.put("call_id", values[2]);
                row.put("skill_version_id", values[3]); row.put("run_id", values[5]);
                row.put("attempt_id", values[6]); row.put("args_digest", values[8]);
                row.put("request_snapshot", values[10]); row.put("status", values[11]);
                row.put("result", values[12]); row.put("callback_state", "DELIVERED");
                facts.put(((Number) values[2]).longValue(), row);
            }
            return 1;
        });
        var catalog = mock(SkillCatalog.class);
        contract = new LinkedHashMap<>(Map.of("code", code, "type", "HTTP", "status", "PUBLISHED",
                "skillStatus", "ENABLED", "endpoint", base(a) + "/execute", "authScheme", "BEARER",
                "authRef", "env:AGENT_SECRET_AP_CR02_LEGACY", "sha256", "a".repeat(64),
                "execution", Map.of("readonly", true, "sideEffect", false)));
        contract.put("inputSchema", Map.of("type", "object"));
        contract.put("guard", Map.of("roles", List.of("EDITOR")));
        contract.put("outputSchema", Map.of("type", "object"));
        contract.put("timeoutMs", 10000);
        contract.put("outputMaxBytes", 8192);
        contract.put("invocationKind", "SYNC");
        when(catalog.versionId(7)).thenAnswer(inv -> contract);
        execution = new SkillExecution(db, mock(PlatformTransactionManager.class), catalog,
                new SkillAdapters(mock(ArtifactStore.class), mock(AlgorithmAdapter.class)), clients,
                new ApprovedConnectionResolver(clients, receipts, "http://app.test"), "http://engine.test") {
            @Override Map<String, Object> fact(long callId) { return facts.get(callId); }
        };
    }

    HttpServer server(String label) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.add(label + exchange.getRequestURI().getPath() + ":" + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"ok\":true,\"summary\":\"prepare\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start(); return server;
    }
    String base(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    Map<String, Object> ref(String binding) {
        return Map.of("profileId", "crm-prod", "profileRev", 1, "bindingId", binding,
                "bindingRev", 1, "operation", "customers.read");
    }
    JsonNode profile() {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) ApprovedConnectionGateTest.payload(
                "crm-prod", 1, "b".repeat(64), "BEARER", List.of("customers.read"));
        node.set("bindings", Json.tree(List.of(
                Map.of("bindingId", "crm-a", "bindingRev", 1, "environment", "dev", "baseEndpoint", base(a) + bindingPath, "secretRef", secret),
                Map.of("bindingId", "crm-b", "bindingRev", 1, "environment", "dev", "baseEndpoint", base(b) + bindingPath, "secretRef", secret))));
        node.set("grants", Json.tree(revoked ? List.of() : List.of(Map.of("grantee", "skill." + code,
                "allowedUses", List.of("read", "write", "execute")))));
        return node;
    }
    Map<String, Object> request(long call, String binding) {
        var req = new LinkedHashMap<String, Object>(Map.of("callId", call, "skillVersionId", 7L,
                "attemptId", 1L, "leaseOwner", "owner", "arguments", Map.of(), "runId", "r_" + call,
                "nodeInstanceId", call, "idempotencyKey", "key-" + call));
        var reg = new LinkedHashMap<String, Object>(Map.of("callId", call, "skill_version_id", 7,
                "attempt_id", 1, "skill_code", code, "args_digest", Digest.sha256("{}"),
                "node_instance_id", call, "runId", "r_" + call, "idempotency_key", "key-" + call,
                "claimed", true, "trigger_type", "FORMAL"));
        reg.put("bindingRefs", List.of(ref(binding))); reg.put("status", "REGISTERED");
        registrations.put(call, Json.tree(reg));
        req.put("bindingRefs", List.of(ref(binding))); return req;
    }
    @AfterEach void close() { a.stop(0); b.stop(0); SecurityContextHolder.clearContext(); }

    @Test void authoritativeBindingDrivesEndpointAndSecretInsteadOfLegacyContract() {
        var req = request(1, "crm-b");
        req.put("bindingRefs", List.of(ref("crm-a")));
        assertThat(execution.execute(code, req).get("status")).isEqualTo("SUCCEEDED");
        assertThat(hits).containsExactly("B/execute:Bearer bound-secret");
        assertThat(contract.get("endpoint")).isEqualTo(base(a) + "/execute");
        assertThat(contract.get("sha256")).isEqualTo("a".repeat(64));
        assertThat(resolutionBindings.getFirst()).containsEntry("bindingId", "crm-b")
                .containsEntry("bindingRev", 1).containsEntry("baseEndpoint", base(b))
                .containsEntry("resolutionDigest", "b".repeat(64));
        var frozen = Json.read(facts.get(1L).get("request_snapshot").toString());
        assertThat(frozen.path("bindingRefs").path(0).path("bindingId").asText()).isEqualTo("crm-b");
    }
    @Test void freshBindingDoesNotMoveOldRunAndRepeatedDeliveryDoesNotRepeatIo() {
        var old = request(1, "crm-a"); execution.execute(code, old);
        execution.execute(code, request(2, "crm-b"));
        execution.execute(code, request(3, "crm-a"));
        execution.execute(code, old);
        assertThat(hits).containsExactly("A/execute:Bearer bound-secret", "B/execute:Bearer bound-secret", "A/execute:Bearer bound-secret");
    }
    @Test void prepareAndConfirmedExecuteUseTheSameBinding() {
        contract.put("execution", Map.of("readonly", false, "sideEffect", true, "confirmRoles", List.of("EDITOR"),
                "approval", Map.of("mode", "PREPARE_EXECUTE", "prepare", Map.of("endpoint", base(a) + "/prepare"))));
        var req = request(1, "crm-b");
        assertThat(execution.execute(code, req).get("status")).isEqualTo("WAITING_APPROVAL");
        approvals.put(1L, true);
        assertThat(execution.execute(code, req).get("status")).isEqualTo("SUCCEEDED");
        execution.execute(code, req);
        assertThat(hits).containsExactly("B/prepare:Bearer bound-secret", "B/execute:Bearer bound-secret");
    }
    @Test void missingPinnedBindingCannotSelectTheNewestEnvironment() {
        var req = request(1, "crm-b");
        var reg = (com.fasterxml.jackson.databind.node.ObjectNode) registrations.get(1L);
        reg.set("bindingRefs", Json.tree(List.of(Map.of("profileId", "crm-prod", "profileRev", 1, "operation", "customers.read"))));
        assertThatThrownBy(() -> execution.execute(code, req)).isInstanceOf(PlatformException.class);
        assertThat(hits).isEmpty();
    }
    @Test void missingOriginalBindingNeverFallsBackToAnotherApprovedBinding() {
        var req = request(1, "crm-missing");
        assertThatThrownBy(() -> execution.execute(code, req)).isInstanceOf(PlatformException.class);
        assertThat(hits).isEmpty();
    }
    @Test void revokedGrantRejectsRestoredExecutionAndUnknownObservationBeforeIo() {
        var req = request(1, "crm-b"); revoked = true;
        assertThatThrownBy(() -> execution.execute(code, req)).isInstanceOf(PlatformException.class);
        facts.put(1L, new LinkedHashMap<>(Map.of("status", "UNKNOWN", "skill_version_id", 7L,
                "request_snapshot", Json.write(req))));
        ((com.fasterxml.jackson.databind.node.ObjectNode) registrations.get(1L)).put("status", "UNKNOWN");
        assertThatThrownBy(() -> execution.reconcile(1)).isInstanceOf(PlatformException.class);
        assertThat(hits).isEmpty();
    }
    @Test void unavailableBoundSecretRejectsBeforeNetworkEvenWhenLegacySecretExists() {
        secret = "env:AGENT_SECRET_AP_CR02_MISSING";
        assertThatThrownBy(() -> execution.execute(code, request(1, "crm-b")))
                .isInstanceOfSatisfying(PlatformException.class, denied -> assertThat(denied.status()).isEqualTo(401));
        assertThat(hits).isEmpty();
    }
    @Test void nativeOpenApiRootIsReplacedOnceInActualHttpRequest() {
        bindingPath = "/api";
        contract.put("endpoint", base(a) + "/api/execute");
        contract.put("httpMethod", "POST");
        contract.put("httpMapping", Map.of("path", List.of(), "query", List.of(), "body", ""));
        contract.put("importSource", Map.of("kind", "OPENAPI", "operationId", "customers.read",
                "method", "POST", "path", "/execute", "documentSha256", "c".repeat(64)));
        assertThat(execution.execute(code, request(1, "crm-b")).get("status")).isEqualTo("SUCCEEDED");
        assertThat(hits).containsExactly("B/api/execute:Bearer bound-secret");
        assertThat(contract.get("endpoint")).isEqualTo(base(a) + "/api/execute");
    }
    @Test void existingSkillCodeAlphabetIsAcceptedWithoutProfileIdRestrictions() {
        code = "C" + "r".repeat(61) + "_1"; contract.put("code", code);
        assertThat(execution.execute(code, request(1, "crm-b")).get("status")).isEqualTo("SUCCEEDED");
        assertThat(hits).containsExactly("B/execute:Bearer bound-secret");
    }
}
