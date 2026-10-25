package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;

@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class DebugControlsTest {
    final TracePostgresTest fixture = new TracePostgresTest();

    @BeforeAll
    static void connect() throws Exception {
        TracePostgresTest.connect();
    }

    @BeforeEach
    void clean() {
        fixture.clean();
    }

    @Test
    void controlsUseOwnerAndRevisionCasAndRejectUnknownBreakpoints() {
        long r = fixture.run();
        var n = fixture.node(r, "READY");
        var id =
                TracePostgresTest.db.queryForObject(
                        "select public_id from engine.run where id=?", String.class, r);
        var service = new DebugControls(TracePostgresTest.store);
        var req = Json.read("{\"expectedRevision\":0,\"breakpoints\":[\"n1\"],\"mocks\":[]}");
        assertEquals(1, service.update(id, req, TracePostgresTest.actor).get("revision"));
        assertThrows(
                PlatformException.class, () -> service.update(id, req, TracePostgresTest.actor));
        assertThrows(
                PlatformException.class,
                () ->
                        service.get(
                                id, new Actor("engine-test", "other", Set.of("EDITOR"), Map.of())));
    }

    @Test
    void lastRealRechecksOwnershipAndExactArguments() {
        long r = fixture.run(), n = fixture.node(r, "READY");
        var lease = TracePostgresTest.store.claim(n, "a");
        var c = new ToolCalls(TracePostgresTest.store);
        var req = fixture.call(r, n, lease);
        long call =
                TraceStore.number(c.register(req.register(), TracePostgresTest.actor), "callId");
        TracePostgresTest.db.update(
                "update engine.tool_call set"
                    + " status='SUCCEEDED',result='{\"structured\":{\"value\":42}}',skill_contract_sha256=?"
                    + " where id=?",
                "a".repeat(64),
                call);
        var service = new DebugControls(TracePostgresTest.store);
        var selected =
                service.lastReal(
                        42,
                        "lookup",
                        Digest.sha256(Json.canonical(Map.of("x", 1))),
                        "a".repeat(64),
                        call,
                        TracePostgresTest.actor);
        assertEquals(42, selected.path("fixedResponse").path("structured").path("value").asInt());
        assertThrows(
                PlatformException.class,
                () ->
                        service.lastReal(
                                42,
                                "lookup",
                                Digest.sha256("other"),
                                "a".repeat(64),
                                call,
                                TracePostgresTest.actor));
        assertThrows(
                PlatformException.class,
                () ->
                        service.lastReal(
                                42,
                                "lookup",
                                Digest.sha256(Json.canonical(Map.of("x", 1))),
                                "a".repeat(64),
                                call,
                                new Actor("engine-test", "other", Set.of("ADMIN"), Map.of())));
    }

    @Test
    void registeredMockKeepsItsRevisionWhenControlsChange() {
        long r = fixture.run(), n = fixture.node(r, "READY");
        var contract = "b".repeat(64);
        var snapshot =
                Json.tree(
                        Map.of(
                                "config",
                                Map.of(
                                        "tools",
                                        List.of(
                                                Map.of(
                                                        "code",
                                                        "lookup",
                                                        "skillVersionId",
                                                        42,
                                                        "contractDigest",
                                                        contract))),
                                "debugControls",
                                Map.of(
                                        "breakpoints",
                                        List.of(),
                                        "mocks",
                                        List.of(
                                                Map.of(
                                                        "skillCode",
                                                        "lookup",
                                                        "mode",
                                                        "FIXED",
                                                        "fixedResponse",
                                                        Map.of(
                                                                "structured",
                                                                Map.of("value", 1)))))));
        TracePostgresTest.db.update(
                "update engine.run set snapshot=?::jsonb where id=?", Json.write(snapshot), r);
        var lease = TracePostgresTest.store.claim(n, "worker");
        var req = fixture.call(r, n, lease);
        var calls = new ToolCalls(TracePostgresTest.store);
        var first = calls.register(req.register(), TracePostgresTest.actor);
        assertEquals(
                1,
                Json.tree(first.get("mock_snapshot"))
                        .path("fixedResponse")
                        .path("structured")
                        .path("value")
                        .asInt());
        TracePostgresTest.db.update(
                "update engine.run set"
                    + " snapshot=jsonb_set(snapshot,'{debugControls,mocks,0,fixedResponse,structured,value}','2'),debug_controls_revision=1"
                    + " where id=?",
                r);
        var retry = calls.register(req.register(), TracePostgresTest.actor);
        assertEquals(
                1,
                Json.tree(retry.get("mock_snapshot"))
                        .path("fixedResponse")
                        .path("structured")
                        .path("value")
                        .asInt());
        assertEquals(0, ((Number) retry.get("debug_controls_revision")).intValue());
    }

    @Test
    void fixedMockUsesOnlyAuthoritativeSnapshotAndSendsNoExecutableBodyOverride() {
        long r = fixture.run(), n = fixture.node(r, "READY");
        TracePostgresTest.db.update(
                "update engine.run set snapshot=?::jsonb where id=?",
                Json.write(
                        Map.of(
                                "config",
                                Map.of(
                                        "tools",
                                        List.of(
                                                Map.of(
                                                        "code",
                                                        "lookup",
                                                        "skillVersionId",
                                                        42,
                                                        "contractDigest",
                                                        Digest.sha256("fixture-contract")))),
                                "debugControls",
                                Map.of(
                                        "mocks",
                                        List.of(
                                                Map.of(
                                                        "skillCode",
                                                        "lookup",
                                                        "mode",
                                                        "FIXED",
                                                        "fixedResponse",
                                                        Map.of(
                                                                "structured",
                                                                Map.of("value", 42))))))),
                r);
        var node = TracePostgresTest.store.claim(n, "worker");
        var clients =
                new RuntimeClients() {
                    @Override
                    public com.fasterxml.jackson.databind.JsonNode post(
                            String service, String path, Object body, Actor actor) {
                        var req = Json.tree(body);
                        assertTrue(
                                req.path("mock").isEmpty(),
                                "Only persisted engine authority may enable a mock");
                        var authority =
                                new ToolCalls(TracePostgresTest.store)
                                        .get(req.path("callId").asLong(), actor);
                        assertEquals(
                                42,
                                Json.tree(authority.get("mock_snapshot"))
                                        .path("fixedResponse")
                                        .path("structured")
                                        .path("value")
                                        .asInt());
                        return Json.tree(
                                Map.of(
                                        "status",
                                        "SUCCEEDED",
                                        "result",
                                        Map.of("structured", Map.of("value", 42))));
                    }
                };
        var result =
                new AgentDriver(TracePostgresTest.store, clients, new ContextAssembler())
                        .skill(
                                TracePostgresTest.db.queryForMap(
                                        "select * from engine.run where id=?", r),
                                node,
                                "lookup",
                                42,
                                Json.tree(Map.of("x", 1)),
                                1,
                                TracePostgresTest.actor);
        assertEquals(42, result.path("structured").path("value").asInt());
    }
}
