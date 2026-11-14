package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.engine.infra.EngineTestDatabase;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.engine.harness.context.CompactionStore;
import cn.agentplatform.engine.harness.session.SessionEvents;
import cn.agentplatform.engine.harness.session.SessionStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;

/**
 * T2-9 定向测试（台账 §7.3）：压缩三事件锁协议+配对边界+孤儿锁检测+模型切换窗口检查。
 * 验收（主方案 §5.4/A14）：压缩后引用早期事实、配对成对遮蔽、恢复后行为一致。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class CompactionStoreTest {

    static JdbcTemplate db;
    static SessionStore sessions;
    static CompactionStore compaction;

    @BeforeAll
    static void connect() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        EngineTestDatabase.url(),
                        EngineTestDatabase.user(),
                        EngineTestDatabase.password());
        db = new JdbcTemplate(ds);
        var store = new TraceStore(db, new DataSourceTransactionManager(ds));
        sessions = new SessionStore(store);
        compaction = new CompactionStore(sessions);
        org.flywaydb.core.Flyway.configure()
                .dataSource(ds)
                .schemas("engine")
                .defaultSchema("engine")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    @AfterEach
    void clean() {
        db.execute("ALTER TABLE engine.session_event DISABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.session_event where session_id like 'h29_%'");
        db.execute("ALTER TABLE engine.session_event ENABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.chat_session where public_id like 'h29_%'");
    }

    String session() {
        String id = "h29_" + UUID.randomUUID();
        db.update(
                "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot)"
                    + " values(?,'engine-test','alice','h29-app','{}')",
                id);
        return id;
    }

    /** 构造配对完整的长会话：system→(user→assistant→tool call→tool result)×N。 */
    String pairedSession(int rounds) {
        String sid = session();
        sessions.append(sid, null, SessionEvents.SYSTEM_MESSAGE, Json.tree(Map.of("content", "系统提示")), null);
        for (int i = 1; i <= rounds; i++) {
            sessions.append(sid, null, SessionEvents.USER_MESSAGE, Json.tree(Map.of("content", "问题" + i)), null);
            sessions.append(
                    sid, null, SessionEvents.ASSISTANT_MESSAGE, Json.tree(Map.of("content", "回答" + i)), null);
            sessions.append(
                    sid,
                    null,
                    SessionEvents.TOOL_CALL,
                    Json.tree(Map.of("callId", "c" + i, "name", "lookup", "args", Map.of("q", i))),
                    null);
            sessions.append(
                    sid,
                    null,
                    SessionEvents.TOOL_RESULT,
                    Json.tree(Map.of("callId", "c" + i, "result", Map.of("value", i))),
                    null);
        }
        return sid;
    }

    @Test
    void compactionThreeEventLockProtocolWithPairingBoundary() {
        String sid = pairedSession(4);
        var range = compaction.resolveRange(sid, sessions.watermark(sid), 1);
        assertTrue(range.endSeq() > 0, "应解析出可压缩区间");
        var out = compaction.compact(sid, 1, "早期对话摘要：用户完成 3 轮查询", range, 512, "fixture-model");
        assertTrue((Integer) out.get("shadowedCount") >= 1);
        // 三事件锁+replace 消息全部落库
        var events = sessions.read(sid, 0, 0);
        assertTrue(events.stream().anyMatch(e -> SessionEvents.COMPACTION_START.equals(e.type())));
        assertTrue(events.stream().anyMatch(e -> SessionEvents.COMPACTION_SUMMARY.equals(e.type())));
        assertTrue(events.stream().anyMatch(e -> SessionEvents.COMPACTION_END.equals(e.type())));
        var replaceMsg =
                events.stream()
                        .filter(e -> SessionEvents.USER_MESSAGE.equals(e.type()) && e.surfaceOp() != null)
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                "replace",
                replaceMsg.surfaceOp().path("op").asText());
        // 投影：被遮蔽消息摘除、摘要入投影（A14：压缩后引用早期事实）
        var after = sessions.deriveMessages(sid, null);
        boolean summaryIn = after.messages().stream().anyMatch(m -> m.content().path("content").asText().contains("早期对话摘要"));
        assertTrue(summaryIn, "摘要消息入投影");
        // 重放一致（恢复后行为一致）
        var again = sessions.deriveMessages(sid, null);
        assertEquals(after.messages().size(), again.messages().size());
    }

    @Test
    void pairBoundaryIsEnforced() {
        String sid = pairedSession(3);
        // 人为构造未闭合 tool/call（模拟撕裂）
        sessions.append(
                sid,
                null,
                SessionEvents.TOOL_CALL,
                Json.tree(Map.of("callId", "c-open", "name", "archive", "args", Map.of())),
                null);
        var range = compaction.resolveRange(sid, sessions.watermark(sid), 1);
        // 区间若含未闭合调用→409 COMPACTION_PAIRING_BROKEN
        if (range.endSeq() >= sessions.watermark(sid) - 1) {
            var broken =
                    assertThrows(
                            PlatformException.class,
                            () ->
                                    compaction.compact(
                                            sid,
                                            1,
                                            "摘要",
                                            new CompactionStore.ShadowRange(1, sessions.watermark(sid), List.of()),
                                            512,
                                            "fixture-model"));
            assertEquals("COMPACTION_PAIRING_BROKEN", broken.code());
        }
    }

    @Test
    void orphanLockDetectedAndClosed() {
        String sid = pairedSession(2);
        // 模拟崩溃：只落 start（孤儿锁）
        sessions.append(sid, null, SessionEvents.COMPACTION_START, Json.tree(Map.of("turn", 1)), null);
        assertTrue(compaction.detectOrphanLock(sid), "start 有 end 无=孤儿锁");
        compaction.closeOrphanLock(sid, 1);
        assertFalse(compaction.detectOrphanLock(sid), "恢复补写后孤儿锁消除");
    }

    @Test
    void switchWindowCheckFailsWithDedicatedErrorCode() {
        String sid = pairedSession(2);
        var range = compaction.resolveRange(sid, sessions.watermark(sid), 1);
        // 浓缩执行器抛错→MODEL_SWITCH_COMPACTION_FAILED（保留原请求头语义由调用方承接）
        var failed =
                assertThrows(
                        PlatformException.class,
                        () ->
                                compaction.switchWindowCheck(
                                        sid,
                                        1,
                                        999999,
                                        60000,
                                        r -> {
                                            throw new IllegalStateException("浓缩失败注入");
                                        },
                                        range));
        assertEquals("MODEL_SWITCH_COMPACTION_FAILED", failed.code());
        // 窗口满足→直接通过（true）；窗口不足且浓缩成功→false 可继续
        assertTrue(compaction.switchWindowCheck(sid, 1, 1000, 60000, r -> Map.of(), range));
        boolean compacted =
                compaction.switchWindowCheck(
                        sid, 1, 999999, 60000, r -> compaction.compact(sid, 1, "切换前摘要", r, 256, "fixture"), range);
        assertFalse(compacted);
    }
}
