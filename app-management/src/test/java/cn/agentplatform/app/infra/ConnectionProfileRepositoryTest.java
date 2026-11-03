package cn.agentplatform.app.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 PostgreSQL 定向用例（无 AGENT_DB_PASSWORD 时整体跳过，与仓内既有约定一致）。
 *
 * 覆盖的是结构卡要求"不能用 Mock 证明"的部分：修订只追加、expectedRev CAS、requestId 幂等重放、
 * 跨对象引用必须落在已存在修订上、租户隔离。模式来自 V111 迁移文件本身，因此本用例同时验证该迁移可执行。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class ConnectionProfileRepositoryTest {

    static final Actor ADMIN = new Actor("t1", "u1", Set.of("ADMIN"), Map.of());
    static JdbcTemplate db;
    static ConnectionProfileRepository repository;
    static int seq;

    @BeforeAll
    static void schema() throws Exception {
        String url = env("AGENT_DB_URL", "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12");
        var ds = new DriverManagerDataSource(url, env("AGENT_DB_USER", "agent_v12"),
                System.getenv("AGENT_DB_PASSWORD"));
        ds.setDriverClassName("org.postgresql.Driver");
        db = new JdbcTemplate(ds);
        db.execute("create schema if not exists app");
        db.execute("drop table if exists app.config_write_request, app.environment_binding_revision,"
                + " app.environment_binding, app.connection_profile_version, app.connection_profile cascade");
        for (String statement : migration()) db.execute(statement);
        repository = new ConnectionProfileRepository(db, new DataSourceTransactionManager(ds));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 迁移正文按 ; 切分执行；文件头注释先剥掉，避免注释里的分号被当成语句边界。 */
    private static List<String> migration() throws Exception {
        try (InputStream in = ConnectionProfileRepositoryTest.class
                .getResourceAsStream("/db/migration/V111__connection_profiles_and_env_bindings.sql")) {
            assertNotNull(in, "V111 迁移必须写在 main/resources/db/migration");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            StringBuilder sql = new StringBuilder();
            for (String line : text.split("\n")) {
                String trimmed = line.strip();
                if (trimmed.startsWith("--") || trimmed.isEmpty()) continue;
                sql.append(line).append('\n');
            }
            List<String> out = new ArrayList<>();
            for (String statement : sql.toString().split(";")) {
                if (!statement.isBlank()) out.add(statement.strip());
            }
            return out;
        }
    }

    private static ConnectionProfileRepository.ProfileRevision revision(String operation) {
        return new ConnectionProfileRepository.ProfileRevision("HTTP_SYNC", List.of(operation), Map.of(),
                Map.of("kind", "API_KEY"), Map.of("mode", "PLATFORM_PRINCIPAL"), List.of(), List.of(),
                List.of("assistant-tool"), "a".repeat(64));
    }

    private static String id(String prefix) {
        return prefix + "-" + (++seq);
    }

    @Test
    void replayWithSameRequestIdReturnsFirstResultAndKeepsOneRevision() {
        String profileId = id("crm-a");
        String sha = "b".repeat(64);
        var first = repository.createProfile(ADMIN, profileId, "req-" + profileId, sha, revision("customers.read"));
        assertEquals(1, first.get("currentRev"));
        assertEquals("DRAFT", first.get("status"));
        assertEquals(Boolean.FALSE, first.get("replayed"));

        var replayed = repository.createProfile(ADMIN, profileId, "req-" + profileId, sha, revision("customers.read"));
        assertEquals(Boolean.TRUE, replayed.get("replayed"));
        assertEquals(1, replayed.get("currentRev"));
        assertEquals(1, db.queryForObject("select count(*) from app.connection_profile_version"
                + " where tenant_id='t1' and profile_id=?", Integer.class, profileId));
    }

    @Test
    void sameRequestIdWithDifferentPayloadIsRejected() {
        String profileId = id("crm-b");
        repository.createProfile(ADMIN, profileId, "req-" + profileId, "b".repeat(64), revision("customers.read"));
        PlatformException e = assertThrows(PlatformException.class, () -> repository.createProfile(ADMIN, profileId,
                "req-" + profileId, "c".repeat(64), revision("orders.read")));
        assertEquals(409, e.status());
        assertEquals("CONFIG_REV_CONFLICT", e.code());
    }

    @Test
    void reviseUsesExpectedRevCasAndNeverRewritesHistory() {
        String profileId = id("crm-c");
        repository.createProfile(ADMIN, profileId, "req-" + profileId, "b".repeat(64), revision("customers.read"));

        PlatformException conflict = assertThrows(PlatformException.class, () -> repository.reviseProfile(ADMIN,
                profileId, 2, "req-" + profileId + "-rev2", "c".repeat(64), revision("orders.read")));
        assertEquals(409, conflict.status());
        assertEquals("CONFIG_REV_CONFLICT", conflict.code());

        var revised = repository.reviseProfile(ADMIN, profileId, 1, "req-" + profileId + "-rev1",
                "d".repeat(64), revision("orders.read"));
        assertEquals(2, revised.get("currentRev"));
        var revisions = (List<?>) revised.get("revisions");
        assertEquals(2, revisions.size());
        Map<?, ?> first = (Map<?, ?>) revisions.get(0);
        Map<?, ?> second = (Map<?, ?>) revisions.get(1);
        assertEquals(List.of("customers.read"), first.get("operationAllowlist"));
        assertEquals(List.of("orders.read"), second.get("operationAllowlist"));
        assertEquals("HTTP_SYNC", second.get("protocol"));
    }

    @Test
    void approvedOrDisabledProfileRejectsFurtherRevision() {
        String profileId = id("crm-d");
        repository.createProfile(ADMIN, profileId, "req-" + profileId, "b".repeat(64), revision("customers.read"));
        var approved = repository.transitionProfile(ADMIN, profileId, 1, "req-" + profileId + "-ap",
                "e".repeat(64), "APPROVED");
        assertEquals("APPROVED", approved.get("status"));

        PlatformException e = assertThrows(PlatformException.class, () -> repository.reviseProfile(ADMIN, profileId,
                1, "req-" + profileId + "-rev", "f".repeat(64), revision("orders.read")));
        assertEquals(400, e.status());
        assertEquals("INVALID_INPUT", e.code());

        repository.transitionProfile(ADMIN, profileId, 1, "req-" + profileId + "-dis", "1".repeat(64), "DISABLED");
        PlatformException again = assertThrows(PlatformException.class, () -> repository.reviseProfile(ADMIN,
                profileId, 1, "req-" + profileId + "-rev2", "2".repeat(64), revision("orders.read")));
        assertEquals(400, again.status());
    }

    @Test
    void bindingMustPointAtExistingProfileRevision() {
        String profileId = id("crm-e");
        repository.createProfile(ADMIN, profileId, "req-" + profileId, "b".repeat(64), revision("customers.read"));

        var bindingRevision = new ConnectionProfileRepository.BindingRevision("production", profileId, 1,
                "https://crm.example.com", "vault:kv/agent/crm", Map.of("runner", "http"),
                Map.of("origins", List.of("https://crm.example.com")), Map.of("path", "/healthz"), "3".repeat(64));
        var created = repository.createBinding(ADMIN, id("bind-a"), "req-" + profileId + "-bind",
                "4".repeat(64), bindingRevision);
        assertEquals(1, created.get("currentRev"));
        assertEquals("production", ((Map<?, ?>) ((List<?>) created.get("revisions")).get(0)).get("environment"));

        var dangling = new ConnectionProfileRepository.BindingRevision("production", profileId, 9,
                "https://crm.example.com", "vault:kv/agent/crm", Map.of(), Map.of(), Map.of(), "5".repeat(64));
        PlatformException e = assertThrows(PlatformException.class, () -> repository.createBinding(ADMIN, id("bind-b"),
                "req-" + profileId + "-bind2", "6".repeat(64), dangling));
        assertEquals(400, e.status());
        assertEquals("CONFIG_VERSION_UNKNOWN", e.code());
    }

    @Test
    void sameIdentifierInAnotherTenantIsIndependent() {
        String profileId = id("crm-f");
        var other = new Actor("t2", "u2", Set.of("ADMIN"), Map.of());
        repository.createProfile(ADMIN, profileId, "req-" + profileId, "b".repeat(64), revision("customers.read"));
        assertDoesNotThrow(() -> repository.createProfile(other, profileId, "req-" + profileId,
                "b".repeat(64), revision("customers.read")));
        assertEquals(1, repository.profiles(ADMIN).stream().filter(p -> profileId.equals(p.get("profileId"))).count());
        assertEquals(1, repository.profiles(other).size());
    }
}
