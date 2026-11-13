package cn.agentplatform.engine.harness.session;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.infra.EngineTestDatabase;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * H1-1 契约四条单测（台账 §6 阶段 1 验收：撕裂尾不返回、越 seq 拒绝、单写者锁、
 * 中断补写 synthetic UNKNOWN）+ deriveMessages 增量折叠。
 * 契约权威：contracts/session-events.md §2；机制规格：主方案 §5.1。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class SessionStoreContractTest {

    static JdbcTemplate db;
    static SessionStore store;

    @BeforeAll
    static void connect() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        EngineTestDatabase.url(),
                        EngineTestDatabase.user(),
                        EngineTestDatabase.password());
        db = new JdbcTemplate(ds);
        store = new SessionStore(db, new DataSourceTransactionManager(ds));
        org.flywaydb.core.Flyway.configure()
                .dataSource(ds)
                .schemas("engine")
                .defaultSchema("engine")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void clean() {
        // session_event 追加不可变（触发器守护）——测试清理期临时禁用，删完即复启。
        db.execute("ALTER TABLE engine.session_event DISABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.session_event where session_id like 'hs_t_%'");
        db.execute("ALTER TABLE engine.session_event ENABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.chat_session where public_id like 'hs_t_%'");
    }

    String session() {
        String id = "hs_t_" + UUID.randomUUID();
        db.update(
                "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot)"
                    + " values(?,'engine-test','alice','test','{}')",
                id);
        return id;
    }

    /** 越 seq 拒绝（契约②）：期望 seq 与库内 next-seq 不符即 409；相符才落库。 */
    @Test
    void appendRejectsNonContiguousExpectedSeq() {
        String sid = session();
        assertEquals(1, store.append(sid, 1, SessionEvents.USER_MESSAGE, Json.tree(Map.of("text", "hi")), null));
        assertThrows(
                PlatformException.class,
                () ->
                        store.append(
                                sid, 3, SessionEvents.USER_MESSAGE, Json.tree(Map.of("text", "gap")), null));
        assertEquals(2, store.append(sid, 2, SessionEvents.USER_MESSAGE, Json.tree(Map.of("text", "ok")), null));
        assertEquals(2, store.watermark(sid));
    }

    /** 撕裂尾不返回（契约①）：断口后的行（模拟直写/未提交撕裂）不进读取结果。 */
    @Test
    void readSkipsTornTailAtFirstGap() {
        String sid = session();
        store.append(sid, null, SessionEvents.USER_MESSAGE, Json.tree(Map.of("text", "a")), null);
        store.append(sid, null, SessionEvents.ASSISTANT_MESSAGE, Json.tree(Map.of("text", "b")), null);
        // 绕过 append 的撕裂尾：seq=5 与已提交前缀 (1,2) 之间断口
        db.update(
                "insert into engine.session_event(session_id,seq,type,data) values(?,5,'system/message','{}'::jsonb)",
                sid);
        var head = store.read(sid, 0, 0);
        assertEquals(List.of(1L, 2L), head.stream().map(SessionStore.SessionEvent::seq).toList());
        assertTrue(store.read(sid, 2, 0).isEmpty(), "断口后撕裂尾不得返回");
    }

    /** 单写者锁（契约③）：并发 append 在 chat_session 行锁下串行，seq 严格连续无重复。 */
    @Test
    void concurrentAppendsSerializeUnderWriterLock() throws Exception {
        String sid = session();
        int threads = 4, perThread = 5;
        var pool = Executors.newFixedThreadPool(threads);
        var seqs = new ConcurrentLinkedQueue<Long>();
        var latch = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(
                    () -> {
                        try {
                            for (int i = 0; i < perThread; i++)
                                seqs.add(
                                        store.append(
                                                sid,
                                                null,
                                                SessionEvents.USER_MESSAGE,
                                                Json.tree(Map.of("n", i)),
                                                null));
                        } finally {
                            latch.countDown();
                        }
                    });
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS), "并发 append 应在写锁下全部完成而非死锁");
        pool.shutdown();
        var sorted = seqs.stream().sorted().toList();
        assertEquals(
                LongStream.rangeClosed(1, threads * perThread).boxed().toList(),
                sorted,
                "并发写者产出的 seq 必须严格连续且无重复");
    }

    /** 中断补写（契约④）：tool/result 一律 synthetic+UNKNOWN（无 error），未闭合 turn 补 interrupted。 */
    @Test
    void recoverySynthesisWritesSyntheticUnknownAndClosesTurn() {
        String sid = session();
        store.append(sid, null, SessionEvents.TURN_START, Json.tree(Map.of("turn", 1)), null);
        store.append(
                sid, null, SessionEvents.TOOL_CALL, Json.tree(Map.of("callId", "c1", "name", "lookup", "args", Map.of("x", 1))), null);
        store.synthesizeMissingToolResult(sid, "c1", Json.tree(Map.of("recoverySource", "AUTO")));
        store.closeInterruptedTurn(sid, 1);

        var events = store.read(sid, 0, 0);
        var result = events.get(events.size() - 2);
        var end = events.get(events.size() - 1);
        assertEquals(SessionEvents.TOOL_RESULT, result.type());
        assertTrue(result.data().path("synthetic").asBoolean(), "补写必须标注 synthetic");
        assertEquals(SessionEvents.SEMANTIC_UNKNOWN, result.data().path("semantic").asText());
        assertEquals("c1", result.data().path("callId").asText());
        assertEquals(
                SessionEvents.SYNTHETIC_VISIBLE_TEXT,
                result.data().path("result").path("text").asText());
        assertFalse(result.data().has("error"), "补写结果禁止表述为失败");
        assertEquals(SessionEvents.TURN_END, end.type());
        assertEquals(SessionEvents.TURN_REASON_INTERRUPTED, end.data().path("reason").asText());
        // 已闭合 turn 不重复补写
        store.closeInterruptedTurn(sid, 1);
        assertEquals(4, store.read(sid, 0, 0).size());
    }

    /** deriveMessages 增量 fold：只投影消息类事件；surface replace 摘除区间后追加替换消息。 */
    @Test
    void deriveMessagesFoldsIncrementallyWithSurfaceReplace() {
        String sid = session();
        store.append(sid, null, SessionEvents.SYSTEM_MESSAGE, Json.tree(Map.of("content", "sys")), null);
        store.append(sid, null, SessionEvents.USER_MESSAGE, Json.tree(Map.of("content", "u1")), null);
        store.append(sid, null, SessionEvents.ASSISTANT_MESSAGE, Json.tree(Map.of("content", "a1")), null);
        store.append(sid, null, SessionEvents.ASSISTANT_ATTEMPT, Json.tree(Map.of("note", "retry")), null);

        var first = store.deriveMessages(sid, null);
        assertEquals(3, first.messages().size(), "attempt 不入模型历史");
        assertEquals(4, first.lastSeq());

        // 压缩替换：2..3 区间被摘要消息（seq=5，携带 replace surfaceOp）替换
        store.append(
                sid,
                null,
                SessionEvents.USER_MESSAGE,
                Json.tree(Map.of("content", "摘要：此前对话")),
                Json.tree(Map.of("op", "replace", "startSeq", 2, "endSeq", 3)));
        var second = store.deriveMessages(sid, first);
        assertEquals(
                List.of("system", "user"),
                second.messages().stream().map(SessionStore.Message::role).toList(),
                "被替换区间消息摘除，替换消息入投影");
        assertEquals(
                "摘要：此前对话",
                second.messages().get(1).content().path("content").asText());
        assertEquals(5, second.lastSeq());
        // 幂等续读：无增量时投影不变
        assertEquals(second.messages().size(), store.deriveMessages(sid, second).messages().size());
    }
}
