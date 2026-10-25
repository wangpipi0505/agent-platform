package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;

@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class ReplayServiceTest {
    final TracePostgresTest fixture = new TracePostgresTest();

    @BeforeAll
    static void connect() throws Exception {
        TracePostgresTest.connect();
    }

    @BeforeEach
    void clean() {
        TracePostgresTest.db.update("delete from engine.reproduction");
        fixture.clean();
    }

    @Test
    void controlledReproductionCopiesEvidenceWithoutExternalDispatchAndIsOwned() {
        long r = fixture.run(), n = fixture.node(r, "READY");
        var lease = TracePostgresTest.store.claim(n, "a");
        TracePostgresTest.store.commit(
                r,
                n,
                TraceStore.number(lease, "attemptId"),
                lease.get("lease_owner").toString(),
                Map.of("answer", 42));
        TracePostgresTest.db.update(
                "update engine.run set status='SUCCEEDED',output='{\"answer\":42}' where id=?", r);
        String id =
                TracePostgresTest.db.queryForObject(
                        "select public_id from engine.run where id=?", String.class, r);
        var replay = new ReplayService(TracePostgresTest.store, null);
        var log = replay.log(id, TracePostgresTest.actor);
        var req =
                Json.tree(
                        Map.of("requestId", "replay-one", "sourceDigest", log.get("sourceDigest")));
        var result = replay.recordMaterial(id, req, TracePostgresTest.actor);
        assertEquals("RECORDED", result.get("status"));
        assertEquals(0, result.get("externalCalls"));
        assertEquals(
                result.get("reproductionId"),
                replay.recordMaterial(id, req, TracePostgresTest.actor).get("reproductionId"));
        assertThrows(
                PlatformException.class,
                () ->
                        replay.get(
                                result.get("reproductionId").toString(),
                                new Actor("engine-test", "other", Set.of("ADMIN"), Map.of())));
        assertThrows(
                PlatformException.class,
                () ->
                        replay.recordMaterial(
                                id,
                                Json.read("{\"requestId\":\"other\",\"sourceDigest\":\"wrong\"}"),
                                TracePostgresTest.actor));
    }
}
