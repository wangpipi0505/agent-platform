package cn.agentplatform.engine.harness.session;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.engine.infra.EngineTestDatabase;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;
import java.util.stream.*;

/**
 * A3-7（台账 §7.4 验收=重放正确）：todo/write 整表替换事件+投影、job 事件对+owner 联动。
 *
 * <p>机制规格：主方案 §5.9；事件负载：contracts/session-events.md §1.5（log-only 整值替换
 * 或事件对，无独立投影表——投影从事件 fold 派生）。
 * 夹具照 SessionStoreContractTest：h37_% 前缀双向清理，session_event 追加不可变触发器
 * 清理期临时禁用（删完即复启）。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class TodoJobsTest {

    static JdbcTemplate db;
    static SessionStore store;
    static TodoStore todos;
    static JobTracker jobs;

    @BeforeAll
    static void connect() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        EngineTestDatabase.url(),
                        EngineTestDatabase.user(),
                        EngineTestDatabase.password());
        db = new JdbcTemplate(ds);
        var manager = new DataSourceTransactionManager(ds);
        store = new SessionStore(db, manager);
        todos = new TodoStore(db, manager);
        jobs = new JobTracker(db, manager);
        org.flywaydb.core.Flyway.configure()
                .dataSource(ds)
                .schemas("engine")
                .defaultSchema("engine")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /** h37_% 双向清理（BeforeEach/AfterEach 同一方法）：先禁触发器删事件再删会话（FK 序）。 */
    @BeforeEach
    @AfterEach
    void clean() {
        db.execute("ALTER TABLE engine.session_event DISABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.session_event where session_id like 'h37_%'");
        db.execute("ALTER TABLE engine.session_event ENABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.chat_session where public_id like 'h37_%'");
    }

    String session() {
        String id = "h37_" + UUID.randomUUID();
        db.update(
                "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot)"
                    + " values(?,'engine-test','alice','test','{}')",
                id);
        return id;
    }

    long settledEventCount(String sid) {
        return store.read(sid, 0, 0).stream()
                .filter(e -> SessionEvents.JOB_SETTLED.equals(e.type()))
                .count();
    }

    /** todo 三态替换重放：三次整表替换→投影=最后一张表；重放任意次结果一致；非法 status 400 不落事件。 */
    @Test
    void todoWholeTableReplacementReplaysToLastTable() {
        String sid = session();
        todos.write(sid, List.of(new TodoStore.TodoItem("t1", "查资料", "PENDING"), new TodoStore.TodoItem("t2", "写报告", "PENDING")));
        todos.write(
                sid,
                List.of(
                        new TodoStore.TodoItem("t1", "查资料", "IN_PROGRESS"),
                        new TodoStore.TodoItem("t2", "写报告", "PENDING"),
                        new TodoStore.TodoItem("t3", "校对", "PENDING")));
        todos.write(
                sid, List.of(new TodoStore.TodoItem("t1", "查资料", "COMPLETED"), new TodoStore.TodoItem("t2", "写报告", "IN_PROGRESS")));

        var current = todos.project(sid);
        assertEquals(2, current.size(), "整表替换：投影=最后一张表（2 条），不是历史累积");
        assertEquals(List.of("COMPLETED", "IN_PROGRESS"), current.stream().map(TodoStore.TodoItem::status).toList());

        // 重放任意次结果一致（每次都从事件流全量 fold，无独立投影表可漂移）
        var replay = todos.project(sid);
        assertEquals(current, replay);
        assertEquals(current, todos.project(sid));
        assertEquals(current, TodoStore.fold(store.read(sid, 0, 0)), "静态 fold 与实例投影一致");

        // 事件流恰三条 todo/write，每条负载=全量 {items:[...]}
        var events = store.read(sid, 0, 0);
        assertEquals(3, events.size());
        assertTrue(events.stream().allMatch(e -> SessionEvents.TODO_WRITE.equals(e.type())));
        assertEquals(2, events.get(0).data().path("items").size());
        assertEquals(3, events.get(1).data().path("items").size());
        assertEquals("PENDING", events.get(0).data().path("items").get(0).path("status").asText());

        // 非法 status：400 TODO_STATUS_INVALID，事件不落库（写入侧校验，log-only 无表约束）
        PlatformException bad =
                assertThrows(
                        PlatformException.class,
                        () -> todos.write(sid, List.of(new TodoStore.TodoItem("t9", "非法态", "DONE"))));
        assertEquals(400, bad.status());
        assertEquals("TODO_STATUS_INVALID", bad.code());
        assertEquals(3, store.read(sid, 0, 0).size(), "非法写入不得追加事件");

        // 空列表=清空整表（合法新态，仍是整值替换）
        todos.write(sid, List.of());
        var cleared = todos.project(sid);
        assertTrue(cleared.isEmpty());
        assertEquals(cleared, todos.project(sid), "清空后重放仍一致");
        assertEquals(4, store.read(sid, 0, 0).size());
    }

    /** job 事件对：register→settle→active 空；重复 settle 幂等（事件仅一条 job/settled），重放一致。 */
    @Test
    void jobEventPairSettlesOnceAndProjectionReplays() {
        String sid = session();
        jobs.register(sid, "job-1", "owner-x", Json.tree(Map.of("kind", "rebuild-index")));

        var registered = store.read(sid, 0, 0);
        assertEquals(1, registered.size());
        assertEquals(SessionEvents.JOB_REGISTERED, registered.get(0).type());
        assertEquals("job-1", registered.get(0).data().path("jobId").asText());
        assertEquals("owner-x", registered.get(0).data().path("owner").asText());
        assertEquals("rebuild-index", registered.get(0).data().path("payload").path("kind").asText());

        var before = jobs.project(sid);
        assertEquals(1, before.registered().size());
        assertEquals("owner-x", before.registered().get(0).owner());
        assertTrue(before.settled().isEmpty());
        assertEquals(1, before.active().size(), "登记未收口：active=registered−settled");

        jobs.settle(sid, "job-1", "COMPLETED", "owner-x");
        var after = jobs.project(sid);
        assertTrue(after.active().isEmpty(), "settle 后 active 空");
        assertEquals(1, after.settled().size());
        assertEquals("COMPLETED", after.settled().get(0).outcome());
        assertEquals(SessionEvents.JOB_SETTLED, store.read(sid, 1, 0).get(0).type());

        // 重复 settle 幂等：静默返回（不抛），事件仅一条 job/settled，投影不变
        assertDoesNotThrow(() -> jobs.settle(sid, "job-1", "COMPLETED", "owner-x"));
        assertDoesNotThrow(() -> jobs.settle(sid, "job-1", "FAILED", "owner-x"));
        assertEquals(1, settledEventCount(sid), "幂等收口不得产生第二条 job/settled");
        assertEquals(after, jobs.project(sid), "重复 settle 后重放结果与首次收口一致");
        assertEquals(2, store.read(sid, 0, 0).size(), "register+settle 恰两事件");
    }

    /** owner 联动：同 owner 全部未 settled job 收口 CANCELLED_OWNER_GONE，其余 owner 的 job 仍 active。 */
    @Test
    void cancelByOwnerSettlesOnlyThatOwnersActiveJobs() {
        String sid = session();
        jobs.register(sid, "job-A", "own-A", Json.tree(Map.of()));
        jobs.register(sid, "job-A2", "own-A", Json.tree(Map.of())); // 同 owner 第二个 job：验证"全部"
        jobs.register(sid, "job-B", "own-B", Json.tree(Map.of()));

        jobs.cancelByOwner(sid, "own-A");

        var p = jobs.project(sid);
        assertEquals(2, p.settled().size(), "own-A 名下全部未收口 job 连带取消");
        assertTrue(p.settled().stream().allMatch(s -> JobTracker.OUTCOME_CANCELLED_OWNER_GONE.equals(s.outcome())));
        assertTrue(p.settled().stream().allMatch(s -> "own-A".equals(s.owner())));
        assertTrue(p.settled().stream().anyMatch(s -> "job-A".equals(s.jobId())));
        assertTrue(p.settled().stream().anyMatch(s -> "job-A2".equals(s.jobId())));
        assertEquals(1, p.active().size(), "own-B 的 job 不受影响");
        assertEquals("job-B", p.active().get(0).jobId());

        // 事件对收口留痕：settled 事件负载含 outcome=CANCELLED_OWNER_GONE
        var settledEvents =
                store.read(sid, 0, 0).stream()
                        .filter(e -> SessionEvents.JOB_SETTLED.equals(e.type()))
                        .toList();
        assertEquals(2, settledEvents.size());
        assertEquals(
                JobTracker.OUTCOME_CANCELLED_OWNER_GONE,
                settledEvents.get(0).data().path("outcome").asText());

        // 重放一致 + 重复取消幂等（已收口不重复补事件）
        assertEquals(p, jobs.project(sid));
        jobs.cancelByOwner(sid, "own-A");
        assertEquals(2, settledEventCount(sid), "重复 cancelByOwner 不产生新收口事件");
        assertEquals(p, jobs.project(sid));
    }

    /** 会话事件序号连续（append 契约②不破）：混合 todo/job 操作后 seq 严格 1..N。 */
    @Test
    void mixedOperationsKeepSessionEventSeqsContiguous() {
        String sid = session();
        todos.write(sid, List.of(new TodoStore.TodoItem("t1", "a", "PENDING")));
        jobs.register(sid, "job-1", "own", Json.tree(Map.of()));
        todos.write(sid, List.of(new TodoStore.TodoItem("t1", "a", "IN_PROGRESS")));
        jobs.settle(sid, "job-1", "COMPLETED", "own");
        jobs.cancelByOwner(sid, "own"); // job-1 已收口：不补事件

        var events = store.read(sid, 0, 0);
        assertEquals(4, events.size(), "已收口 job 的 cancelByOwner 不追加事件");
        var seqs = events.stream().map(SessionStore.SessionEvent::seq).toList();
        assertEquals(LongStream.rangeClosed(1, events.size()).boxed().toList(), seqs, "契约②：seq 严格连续");
        assertEquals(
                List.of(
                        SessionEvents.TODO_WRITE,
                        SessionEvents.JOB_REGISTERED,
                        SessionEvents.TODO_WRITE,
                        SessionEvents.JOB_SETTLED),
                events.stream().map(SessionStore.SessionEvent::type).toList(),
                "事件类型序与操作序一致");

        // 投影重放：todo=最后一张表，job 全收口 active 空
        assertEquals("IN_PROGRESS", todos.project(sid).get(0).status());
        assertTrue(jobs.project(sid).active().isEmpty());
        assertEquals("COMPLETED", jobs.project(sid).settled().get(0).outcome());
    }
}
