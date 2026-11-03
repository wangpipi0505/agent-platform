package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

/**
 * SEM-05.1：语义回执读口只回本运行的调用，且不把被拒的投影混进成功结果。
 *
 * <p>三条不变量：①跨运行/跨租户取不到回执；②{@code valid=false} 的拒绝标记单独归入
 * {@code rejected}，不与成功结果同列；③读口只做脱敏投影——attempt/节点主键不外泄。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class SemanticResultsReadTest {

    static JdbcTemplate db;
    static TraceStore store;

    @BeforeAll
    static void database() {
        var ds = new DriverManagerDataSource(url(), user(), password());
        Flyway.configure()
                .dataSource(ds)
                .schemas("engine")
                .defaultSchema("engine")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(ds);
        store = new TraceStore(db, new DataSourceTransactionManager(ds));
    }

    static String url() {
        return System.getenv()
                .getOrDefault("AGENT_DB_URL", "jdbc:postgresql://127.0.0.1:35432/agent_platform_test");
    }

    static String user() {
        return System.getenv().getOrDefault("AGENT_DB_USER", "dev_ap_test");
    }

    static String password() {
        return System.getenv("AGENT_DB_PASSWORD");
    }

    private static final String RUN = Long.toString(System.currentTimeMillis() % 1_000_000L, 36);

    record Fixture(long run, long node, String publicId, Actor owner) {}

    static Fixture fixture(String suffix, Actor owner) {
        String publicId = "run-" + RUN + "-" + suffix;
        Long run =
                db.queryForObject(
                        "insert into engine.run(public_id,app_code,app_version_id,trigger_type,status,"
                                + "principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,"
                                + "inputs) values(?,?,1,'TASK','RUNNING',?,?,?,?::jsonb,?::jsonb,?::jsonb,"
                                + "?::jsonb,?::jsonb) returning id",
                        Long.class,
                        publicId,
                        "app-" + RUN,
                        owner.principalId(),
                        owner.tenantId(),
                        "input-" + publicId,
                        "{}",
                        "{}",
                        "{}",
                        "{\"config\":{}}",
                        "{}");
        Long node =
                db.queryForObject(
                        "insert into engine.node_instance(run_id,step_seq,node_type,status,definition)"
                                + " values(?,1,'TOOL','SUCCEEDED','{}'::jsonb) returning id",
                        Long.class,
                        run);
        return new Fixture(run, node, publicId, owner);
    }

    static void note(Fixture fixture, long callId, boolean valid, String digest) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("schemaVersion", "1.0");
        record.put("profile", "read-only");
        record.put("recordId", "sr_" + digest.substring(0, 32));
        record.put("status", valid ? "NO_MATCH" : "ERROR");
        db.update(
                "insert into engine.attempt_note(node_instance_id,attempt_id,kind,payload)"
                        + " values(?,?,?,?::jsonb)",
                fixture.node(),
                null,
                "TOOL_EXECUTION_FACT",
                Json.write(
                        Map.of(
                                "callId",
                                callId,
                                "result",
                                Map.of(
                                        "semantic",
                                        Map.of(
                                                "valid", valid,
                                                "digest", digest,
                                                "record", record)))));
    }

    @Test
    void readEndpointSeparatesRejectedProjectionsAndStaysInsideTheRun() {
        var mine = new Actor("t-" + RUN, "u-" + RUN, Set.of("USER"), Map.of());
        var other = new Actor("t2-" + RUN, "u2-" + RUN, Set.of("USER"), Map.of());
        var a = fixture("a", mine);
        var b = fixture("b", other);
        note(a, 101L, true, "a".repeat(64));
        note(a, 102L, false, "b".repeat(64));
        note(b, 201L, true, "c".repeat(64));

        var items = store.semanticResults(a.publicId(), mine);
        assertThat(items).hasSize(2);
        var valid = items.stream().filter(i -> Boolean.TRUE.equals(i.get("valid"))).toList();
        var rejected = items.stream().filter(i -> !Boolean.TRUE.equals(i.get("valid"))).toList();
        assertThat(valid).hasSize(1);
        assertThat(rejected).hasSize(1);
        assertThat(valid.getFirst().get("callId")).isEqualTo(101L);
        var record = (Map<?, ?>) valid.getFirst().get("record");
        assertThat(record.get("recordId")).isEqualTo("sr_" + "a".repeat(32));
        // 只投影契约字段：内部主键不外泄。
        assertThat(valid.getFirst()).doesNotContainKeys("nodeInstanceId", "attemptId", "id");

        // 另一运行的调用取不到；别人读我的运行直接 404，不给"存在但无权"的探测面。
        assertThat(store.semanticResults(b.publicId(), other)).hasSize(1);
        assertThatThrownBy(() -> store.semanticResults(a.publicId(), other))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("运行不存在");
    }
}
