package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

import java.util.*;

/**
 * INT-02 子步3：受控连接引用随调用登记钉住。
 *
 * 关注的不是"能不能存"，而是三条不变量：
 * 1) 引用只来自运行快照（引擎权威），登记后按 idempotency_key 固定；
 * 2) 同一逻辑调用换引用必须被拒（409 CALL_BINDING_CHANGED），不能静默沿用旧记录改目标；
 * 3) 旧运行恢复时读到的仍是自己那份引用，不会被新运行的绑定带跑。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class ToolCallBindingRefsTest {

    static JdbcTemplate db;
    static TraceStore store;

    @BeforeAll
    static void database() {
        var ds = new DriverManagerDataSource(url(), user(), password());
        Flyway.configure().dataSource(ds).schemas("engine").defaultSchema("engine")
                .locations("classpath:db/migration").load().migrate();
        db = new JdbcTemplate(ds);
        store = new TraceStore(db, new DataSourceTransactionManager(ds));
    }

    /**
     * 本用例只读/写 engine schema，不建库：用 AGENT_DB_* 约定直接指向测试库，
     * 避免依赖需要建库权限的 EngineTestDatabase（DEV 账号 createdb=false）。
     */
    static String url() {
        return System.getenv().getOrDefault("AGENT_DB_URL",
                "jdbc:postgresql://127.0.0.1:35432/agent_platform_test");
    }

    static String user() {
        return System.getenv().getOrDefault("AGENT_DB_USER", "dev_ap_test");
    }

    static String password() {
        return System.getenv("AGENT_DB_PASSWORD");
    }

    /** 租约在造夹具时领取一次：同一逻辑调用的重试/恢复复用同一租约，不该重新抢。 */
    record Fixture(
            long run, long node, String publicId, Actor owner, long version, long attemptId,
            String leaseOwner) {}

    Fixture fixture(String tenant, List<Map<String, Object>> refs) {
        var owner = new Actor(tenant, "alice", Set.of("EDITOR"), Map.of());
        String publicId = "r_" + UUID.randomUUID().toString().replace("-", "");
        long run = db.queryForObject(
                "insert into engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,"
                        + "input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode)"
                        + " values(?,'bind','{}','DEBUG','RUNNING','alice',?,'bind','{}','{}','{}','{}','{}','STEP')"
                        + " returning id",
                Long.class, publicId, tenant);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("tools", List.of(Map.of("code", "read", "skillVersionId", 7L,
                "contractDigest", Digest.sha256("binding-refs-contract"))));
        if (!refs.isEmpty()) config.put("bindingRefs", refs);
        db.update("update engine.run set snapshot=?::jsonb where id=?", Json.write(Map.of("config", config)), run);
        long node = db.queryForObject(
                "insert into engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition)"
                        + " values(?,'n',1,'CODE','READY','{\"type\":\"CODE\",\"expression\":\"1\"}') returning id",
                Long.class, run);
        var lease = store.claim(node, "worker");
        return new Fixture(
                run,
                node,
                publicId,
                owner,
                7L,
                ((Number) lease.get("attemptId")).longValue(),
                Objects.toString(lease.get("lease_owner")));
    }

    com.fasterxml.jackson.databind.JsonNode request(Fixture f, List<Map<String, Object>> refs) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of(
                "runId", f.publicId(),
                "nodeInstanceId", f.node(),
                "attemptId", f.attemptId(),
                "callSeq", 1,
                "skillVersionId", f.version(),
                "skillCode", "read",
                "arguments", Map.of("q", "1"),
                "idempotencyKey", Digest.sha256(f.run() + ":" + f.node() + ":1:" + f.version()),
                "leaseOwner", f.leaseOwner()));
        if (refs != null && !refs.isEmpty()) body.put("bindingRefs", refs);
        return Json.tree(body);
    }

    /** 读侧返回的是 JsonNode；再套一层 Json.tree 会把数组洗成空节点，所以直接取。 */
    static JsonNode refsOf(Map<String, Object> view) {
        Object value = view.get("bindingRefs");
        return value instanceof JsonNode node ? node : Json.tree(value);
    }

    static Map<String, Object> ref(String profileId) {
        return Map.of("profileId", profileId, "profileRev", 1, "operation", "customers.read");
    }

    @Test
    void bindingRefsComeFromSnapshotAndArePinnedToTheCall() {
        String tenant = "bind-" + UUID.randomUUID();
        var a = fixture(tenant, List.of(ref("crm-a")));
        var calls = new ToolCalls(store);
        var registered = calls.register(request(a, null), a.owner());

        // 载荷里不写引用也能登记：引用由引擎从快照铺，不依赖调用方自报。
        assertThat(refsOf(registered).path(0).path("profileId").asText())
                .isEqualTo("crm-a");

        // 同一逻辑调用重复登记（重试/恢复）仍然成功，引用不变。
        var again = calls.register(request(a, null), a.owner());
        assertThat(((Number) again.get("callId")).longValue())
                .isEqualTo(((Number) registered.get("callId")).longValue());

        // 同一逻辑调用换引用：拒绝，且不改动已登记的引用。
        assertThatThrownBy(() -> calls.register(request(a, List.of(ref("crm-b"))), a.owner()))
                .isInstanceOfSatisfying(PlatformException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo("CALL_BINDING_CHANGED");
                });
        var stored = calls.get(((Number) registered.get("callId")).longValue(), a.owner());
        assertThat(refsOf(stored).path(0).path("profileId").asText())
                .isEqualTo("crm-a");
    }

    @Test
    void anotherRunKeepsItsOwnBindingEvenAfterTheFirstRunWasRegistered() {
        String tenant = "bind-" + UUID.randomUUID();
        var calls = new ToolCalls(store);
        var first = fixture(tenant, List.of(ref("crm-a")));
        var second = fixture(tenant, List.of(ref("crm-b")));
        var one = calls.register(request(first, null), first.owner());
        var two = calls.register(request(second, null), second.owner());

        assertThat(refsOf(one).path(0).path("profileId").asText())
                .isEqualTo("crm-a");
        assertThat(refsOf(two).path(0).path("profileId").asText())
                .isEqualTo("crm-b");
        // 旧运行恢复：仍是自己那份，不跟随新运行。
        var restored = calls.get(((Number) one.get("callId")).longValue(), first.owner());
        assertThat(refsOf(restored).path(0).path("profileId").asText())
                .isEqualTo("crm-a");
    }

    @Test
    void callsWithoutBindingRefsKeepTheLegacyShape() {
        String tenant = "bind-" + UUID.randomUUID();
        var f = fixture(tenant, List.of());
        var registered = new ToolCalls(store).register(request(f, null), f.owner());

        // 未引用受控连接的调用：字段是 JSON null，不是空数组——读侧据此区分"没声明"与"声明为空"。
        assertThat(registered.get("bindingRefs")).isInstanceOf(JsonNode.class);
        assertThat(((JsonNode) registered.get("bindingRefs")).isNull()).isTrue();
    }
}
