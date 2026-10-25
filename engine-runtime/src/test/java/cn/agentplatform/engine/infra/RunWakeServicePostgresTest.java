package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.RunWakeService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * R-A6 台账项回归：非审批类外部事件唤醒（幂等 wake）。
 *
 * <p>验证四点：唤醒只把 dispatch_next_at 提前到 now（不改状态）；同一 requestId
 * 重复投递去重（deduped 且事件唯一）；终态 run 为无害 no-op；请求缺字段按 400 拒绝。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class RunWakeServicePostgresTest {
    final TracePostgresTest fixture = new TracePostgresTest();
    RunWakeService wakes;

    @BeforeAll
    static void connect() throws Exception {
        TracePostgresTest.connect();
    }

    @BeforeEach
    void clean() {
        fixture.clean();
        wakes = new RunWakeService(TracePostgresTest.store);
    }

    private String publicId(long r) {
        return TracePostgresTest.db.queryForObject(
                "select public_id from engine.run where id=?", String.class, r);
    }

    private int wakeEvents(long r) {
        return TracePostgresTest.db.queryForObject(
                "select count(*) from engine.run_event where run_id=? and type='WAKE_REQUESTED'",
                Integer.class,
                r);
    }

    @Test
    void wakePullsDispatchEarlierWithoutTouchingStatus() {
        long r = fixture.run();
        TracePostgresTest.db.update(
                "update engine.run set status='WAITING_EXTERNAL'"
                        + ",dispatch_next_at=now()+interval '1 hour' where id=?",
                r);
        var result =
                wakes.wake(
                        publicId(r),
                        Json.read("{\"requestId\":\"cb-1\",\"source\":\"algorithm-callback\"}"),
                        TracePostgresTest.actor);
        assertEquals(true, result.get("woken"));
        assertEquals("WAITING_EXTERNAL", result.get("status"));
        // 唤醒后调度时刻已提前到当下（不再是一小时后），状态保持等待外部。
        assertTrue(
                Boolean.TRUE.equals(
                        TracePostgresTest.db.queryForObject(
                                "select dispatch_next_at<=now()+interval '1 second' from"
                                        + " engine.run where id=?",
                                Boolean.class,
                                r)));
        assertEquals(1, wakeEvents(r));
    }

    @Test
    void duplicateRequestIdIsDedupedToOneEvent() {
        long r = fixture.run();
        var body = Json.read("{\"requestId\":\"cb-2\",\"source\":\"integration\"}");
        wakes.wake(publicId(r), body, TracePostgresTest.actor);
        var again = wakes.wake(publicId(r), body, TracePostgresTest.actor);
        assertEquals(true, again.get("deduped"));
        assertEquals(false, again.get("woken"));
        assertEquals(1, wakeEvents(r));
    }

    @Test
    void terminalRunIsHarmlessNoOp() {
        long r = fixture.run();
        TracePostgresTest.db.update(
                "update engine.run set status='SUCCEEDED',ended_at=now() where id=?", r);
        var result =
                wakes.wake(
                        publicId(r),
                        Json.read("{\"requestId\":\"cb-3\",\"source\":\"integration\"}"),
                        TracePostgresTest.actor);
        assertEquals(false, result.get("woken"));
        assertEquals("SUCCEEDED", result.get("status"));
        assertEquals(0, wakeEvents(r));
    }

    @Test
    void missingFieldsAreRejected() {
        long r = fixture.run();
        var error =
                assertThrows(
                        PlatformException.class,
                        () -> wakes.wake(publicId(r), Json.read("{\"requestId\":\"x\"}"),
                                TracePostgresTest.actor));
        assertEquals("WAKE_INVALID", error.code());
    }
}
