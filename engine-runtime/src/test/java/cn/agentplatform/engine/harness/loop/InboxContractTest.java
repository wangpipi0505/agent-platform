package cn.agentplatform.engine.harness.loop;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.harness.session.SessionEvents;
import cn.agentplatform.engine.infra.EngineTestDatabase;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;

/**
 * H1-3 Inbox 两阶段消费契约测试（台账 §6 验收：入队幂等、认领租约、旧代次确认拒绝、
 * 提交后不重复消费、同事务回滚=恢复以事务提交为界）。
 * 机制规格：主方案 §5.2" Inbox 双队列与持久确认消费"；事件负载：contracts/session-events.md §1.5。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class InboxContractTest {

    static JdbcTemplate db;
    static Inbox inbox;          // 独立事务模式
    static Inbox traceInbox;     // 同事务模式（复用 TraceStore 边界，验证回滚语义）
    static TraceStore trace;

    @BeforeAll
    static void connect() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        EngineTestDatabase.url(),
                        EngineTestDatabase.user(),
                        EngineTestDatabase.password());
        db = new JdbcTemplate(ds);
        var manager = new DataSourceTransactionManager(ds);
        inbox = new Inbox(db, manager);
        trace = new TraceStore(db, manager);
        traceInbox = new Inbox(trace);
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
        // 清理顺序（FK）：先禁触发器删 session_event → 删 agent_inbox → 删 chat_session。
        // AfterEach 同样清理：防止本类残留行污染其他对 chat_session 做无界清理的测试类（FK）。
        db.execute("ALTER TABLE engine.session_event DISABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.session_event where session_id like 'hi_t_%'");
        db.execute("ALTER TABLE engine.session_event ENABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.agent_inbox where session_id like 'hi_t_%'");
        db.update("delete from engine.chat_session where public_id like 'hi_t_%'");
    }

    String session() {
        String id = "hi_t_" + UUID.randomUUID();
        db.update(
                "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot)"
                    + " values(?,'engine-test','alice','test','{}')",
                id);
        return id;
    }

    int inboxRows(String sid) {
        Integer n =
                db.queryForObject(
                        "select count(*) from engine.agent_inbox where session_id=?",
                        Integer.class,
                        sid);
        return n == null ? 0 : n;
    }

    int events(String sid, String type) {
        Integer n =
                db.queryForObject(
                        "select count(*) from engine.session_event where session_id=? and type=?",
                        Integer.class,
                        sid,
                        type);
        return n == null ? 0 : n;
    }

    Map<String, Object> inboxRow(String sid, String queue, String dedupeKey) {
        var rows =
                db.queryForList(
                        "select * from engine.agent_inbox where session_id=? and queue=?"
                            + " and dedupe_key=?",
                        sid,
                        queue,
                        dedupeKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 租约拨回过去：模拟认领者停滞到租约过期（避免 sleep，确定性触发接管路径）。 */
    void expireLease(String sid, String queue, String dedupeKey) {
        db.update(
                "update engine.agent_inbox set claim_lease_until=now()-interval '1 second'"
                    + " where session_id=? and queue=? and dedupe_key=?",
                sid,
                queue,
                dedupeKey);
    }

    /** 入队幂等：同 dedupeKey 两次 enqueue → 同一 position、投影单行；事件目录两条留痕。 */
    @Test
    void enqueueIsIdempotentOnDedupeKey() {
        String sid = session();
        long p1 = inbox.enqueue(sid, "NEXT_TURN", "req-1", Json.tree(Map.of("text", "hello")));
        long p2 =
                inbox.enqueue(
                        sid, "NEXT_TURN", "req-1", Json.tree(Map.of("text", "重投递负载不同")));
        assertEquals(p1, p2, "重复入队返回既有 position");
        assertEquals(1, inboxRows(sid), "UNIQUE(session,queue,dedupe_key)：投影行唯一");
        assertEquals(
                2,
                events(sid, SessionEvents.INBOX_ENQUEUE),
                "事件目录允许重复追加——重投递在事实源留痕，投影只物化唯一行");
        // NEXT_STEP 独立队列、独立 position 序
        assertEquals(1, inbox.enqueue(sid, "NEXT_STEP", "inj-1", Json.tree(Map.of("n", 1))));
        assertEquals(2, inboxRows(sid));
        // 事件负载 {queue,dedupeKey,message}（contracts/session-events.md §1.5）
        var row =
                db.queryForMap(
                        "select data::text as d from engine.session_event where session_id=?"
                            + " and type='inbox/enqueue' order by seq limit 1",
                        sid);
        var payload = Json.read(String.valueOf(row.get("d")), JsonNode.class);
        assertEquals("NEXT_TURN", payload.path("queue").asText());
        assertEquals("req-1", payload.path("dedupeKey").asText());
        assertEquals("hello", payload.path("message").path("text").asText());
    }

    /** 认领租约：认领写 owner+generation=1；未过期租约的行不可重复认领（跳过取后续行/无行返 null）。 */
    @Test
    void claimHonorsLeaseAndGeneration() {
        String sid = session();
        inbox.enqueue(sid, "NEXT_TURN", "r1", Json.tree(Map.of("i", 1)));
        inbox.enqueue(sid, "NEXT_TURN", "r2", Json.tree(Map.of("i", 2)));
        var c1 = inbox.claim(sid, "NEXT_TURN", "worker-a", 60);
        assertNotNull(c1);
        assertEquals(1, c1.position(), "按 position FIFO 取最早一条");
        assertEquals("r1", c1.dedupeKey());
        assertEquals("worker-a", c1.owner());
        assertEquals(1, c1.generation(), "首次认领代次=1（未认领行默认 0，认领 +1）");
        assertNotNull(c1.leaseUntil());
        assertEquals("worker-a", String.valueOf(inboxRow(sid, "NEXT_TURN", "r1").get("claimed_by")));
        assertEquals(
                1,
                ((Number) inboxRow(sid, "NEXT_TURN", "r1").get("claim_generation")).intValue());
        // 未过期重复认领：该行不可认领——claim 跳到后续可认领行而非接管
        var c2 = inbox.claim(sid, "NEXT_TURN", "worker-b", 60);
        assertNotNull(c2);
        assertEquals("r2", c2.dedupeKey(), "未过期租约的行不可被认领");
        assertEquals(1, c2.generation());
        // 两行租约均未过期 → 无可认领返回 null
        assertNull(inbox.claim(sid, "NEXT_TURN", "worker-c", 60), "无可认领行返回 null");
    }

    /** 旧代次确认拒绝：租约过期后 B 接管（generation=2）→ A 的确认抛 409 INBOX_CLAIM_STALE；B 成功且行删除。 */
    @Test
    void staleGenerationConsumeRejected() {
        String sid = session();
        inbox.enqueue(sid, "NEXT_TURN", "r1", Json.tree(Map.of("i", 1)));
        var a = inbox.claim(sid, "NEXT_TURN", "worker-a", 60);
        assertEquals(1, a.generation());
        expireLease(sid, "NEXT_TURN", "r1");
        var b = inbox.claim(sid, "NEXT_TURN", "worker-b", 60);
        assertNotNull(b, "租约过期行可被再次认领（接管）");
        assertEquals("worker-b", b.owner());
        assertEquals(2, b.generation(), "接管代次递增");
        // 旧认领者 A 的确认被拒绝：409 INBOX_CLAIM_STALE，且事务回滚不留事件
        var ex =
                assertThrows(
                        PlatformException.class,
                        () -> inbox.consume(sid, "NEXT_TURN", "r1", a.owner(), a.generation(), 1),
                        "旧代次确认拒绝");
        assertEquals(409, ex.status());
        assertEquals("INBOX_CLAIM_STALE", ex.code());
        assertNotNull(inboxRow(sid, "NEXT_TURN", "r1"), "被拒绝的确认不消费（行仍在）");
        assertEquals(0, events(sid, SessionEvents.INBOX_CONSUME), "拒绝路径不留 inbox/consume 事件");
        // 现任认领者 B 确认成功：行物理删除
        inbox.consume(sid, "NEXT_TURN", "r1", b.owner(), b.generation(), 1);
        assertNull(inboxRow(sid, "NEXT_TURN", "r1"), "确认消费后行物理删除");
        assertEquals(1, events(sid, SessionEvents.INBOX_CONSUME));
    }

    /** 提交后不重复消费：consume 后重复 consume 幂等静默（无行不抛），inbox/consume 事件仅一条。 */
    @Test
    void consumeIdempotentAfterCommit() {
        String sid = session();
        inbox.enqueue(sid, "NEXT_STEP", "inj-1", Json.tree(Map.of("n", 1)));
        var c = inbox.claim(sid, "NEXT_STEP", "worker-a", 60);
        inbox.consume(sid, "NEXT_STEP", "inj-1", c.owner(), c.generation(), 3);
        // 提交后重复确认：行已删除 → 幂等静默返回，不抛、不留痕
        assertDoesNotThrow(() -> inbox.consume(sid, "NEXT_STEP", "inj-1", c.owner(), c.generation(), 3));
        // 从未入队的 dedupeKey 同样幂等静默
        assertDoesNotThrow(
                () -> inbox.consume(sid, "NEXT_STEP", "never-enqueued", "worker-a", 1, 3));
        assertEquals(0, inboxRows(sid));
        assertEquals(1, events(sid, SessionEvents.INBOX_CONSUME), "inbox/consume 事件仅一条");
        // 事件负载 {queue,dedupeKey,consumedByStep}
        var row =
                db.queryForMap(
                        "select data::text as d from engine.session_event where session_id=?"
                            + " and type='inbox/consume'",
                        sid);
        var payload = Json.read(String.valueOf(row.get("d")), JsonNode.class);
        assertEquals("NEXT_STEP", payload.path("queue").asText());
        assertEquals("inj-1", payload.path("dedupeKey").asText());
        assertEquals(3, payload.path("consumedByStep").asInt());
    }

    /** 同事务回滚：trace.atomic 内 enqueue+抛异常 → 投影行与事件一并回滚（恢复以事务提交为界）。 */
    @Test
    void sameTransactionRollbackDiscardsRowAndEvent() {
        String sid = session();
        assertThrows(
                IllegalStateException.class,
                () ->
                        trace.atomic(
                                () -> {
                                    traceInbox.enqueue(
                                            sid, "NEXT_STEP", "boom-1", Json.tree(Map.of("x", 1)));
                                    throw new IllegalStateException("模拟提交前中断");
                                }),
                "事务内异常向上传播");
        assertEquals(0, inboxRows(sid), "投影行随事务回滚");
        assertEquals(0, events(sid, SessionEvents.INBOX_ENQUEUE), "事件与行同事务：一并回滚");
        // 回滚后同 dedupeKey 重新入幂等入队成功——租约过期按 inbox/enqueue 重投递的恢复路径
        long p = traceInbox.enqueue(sid, "NEXT_STEP", "boom-1", Json.tree(Map.of("x", 1)));
        assertEquals(1, p);
        assertEquals(1, inboxRows(sid));
        assertEquals(1, events(sid, SessionEvents.INBOX_ENQUEUE));
    }
}
