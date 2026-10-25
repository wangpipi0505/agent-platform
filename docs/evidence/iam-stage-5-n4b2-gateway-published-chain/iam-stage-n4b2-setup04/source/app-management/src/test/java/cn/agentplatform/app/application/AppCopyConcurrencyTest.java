package cn.agentplatform.app.application;

import static org.junit.jupiter.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real READ COMMITTED copy contention, isolated from the running application's database. */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class AppCopyConcurrencyTest {
    static DriverManagerDataSource ds;
    static JdbcTemplate db;
    static TransactionTemplate tx;
    static AppService apps;

    @BeforeAll
    static void database() {
        ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_app_copy_test",
                        "agent_v12",
                        System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure()
                .dataSource(ds)
                .schemas("app")
                .defaultSchema("app")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(ds);
        var manager = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(manager);
        apps =
                new AppService(
                        db,
                        manager,
                        "http://127.0.0.1:1",
                        "http://127.0.0.1:1",
                        "http://127.0.0.1:1",
                        "http://127.0.0.1:1");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void copyWaitingForMetadataUsesOneCommittedSourceRevision() throws Exception {
        String tenant = "copy-lock-" + UUID.randomUUID();
        PublishTransactionTest.actor(tenant);
        apps.create(
                Map.of(
                        "code",
                        "source",
                        "name",
                        "Before",
                        "type",
                        "FLOW",
                        "domain",
                        "before",
                        "tags",
                        List.of("before")));
        var config =
                Map.of(
                        "revision",
                        "after",
                        "skillRefs",
                        List.of(Map.of("code", "search", "version", 2)));
        var pendingCopy = new AtomicReference<Future<Map<String, Object>>>();

        // The outer transaction keeps the real metadata/save draft lock until PostgreSQL
        // confirms the copy SELECT is waiting on it; no timing-based sleep releases T1.
        try (var executor = Executors.newSingleThreadExecutor()) {
            var source =
                    tx.execute(
                            status -> {
                                assertEquals(
                                        "read committed",
                                        db.queryForObject(
                                                "show transaction_isolation", String.class));
                                int blocker =
                                        db.queryForObject("select pg_backend_pid()", Integer.class);
                                apps.metadata(
                                        "source",
                                        Map.of(
                                                "draftRev",
                                                1,
                                                "name",
                                                "After",
                                                "description",
                                                "updated together",
                                                "domain",
                                                "after",
                                                "bizCategory",
                                                "after-category",
                                                "tags",
                                                List.of("after")));
                                apps.save("source", Map.of("draftRev", 2, "config", config), false);
                                var updated = apps.get("source");
                                pendingCopy.set(
                                        executor.submit(
                                                () -> {
                                                    PublishTransactionTest.actor(tenant);
                                                    try {
                                                        return apps.copy(
                                                                "source",
                                                                Map.of(
                                                                        "code", "copied", "name",
                                                                        "Copied"));
                                                    } finally {
                                                        SecurityContextHolder.clearContext();
                                                    }
                                                }));
                                awaitCopyLock(blocker);
                                return updated;
                            });
            var copied = pendingCopy.get().get(10, TimeUnit.SECONDS);
            assertEquals(3L, source.get("draftRev"));
            var log = apps.lifecycleLogs("copied").getFirst();
            var detail = AppService.object(log.get("detail"));
            assertAll(
                    () -> assertEquals(source.get("domain"), copied.get("domain"), "domain"),
                    () -> assertEquals(source.get("tags"), copied.get("tags"), "tags"),
                    () -> assertEquals(source.get("bizCategory"), copied.get("bizCategory")),
                    () -> assertEquals(source.get("description"), copied.get("description")),
                    () -> assertEquals(config, copied.get("config"), "config"),
                    () -> assertEquals(3L, ((Number) detail.get("sourceDraftRev")).longValue()),
                    () -> assertEquals("source", detail.get("sourceAppCode")),
                    () -> assertNull(detail.get("sourceCurrentVersionNo")),
                    () -> assertEquals("DRAFT", log.get("fromState")),
                    () -> assertEquals("DRAFT", copied.get("status")),
                    () -> assertEquals(1L, copied.get("draftRev")),
                    () -> assertEquals(1, copied.get("draftNo")),
                    () -> assertNull(copied.get("currentVersionNo")),
                    () -> assertTrue(apps.versions("copied").isEmpty()),
                    () -> assertEquals(source, apps.get("source"), "copy must not mutate source"));
        }
    }

    static void awaitCopyLock(int blocker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        // A direct independent connection observes locks without joining T1's Spring transaction.
        try (var observer = ds.getConnection();
                var statement =
                        observer.prepareStatement(
                                "select pid,wait_event from pg_stat_activity where"
                                    + " datname=current_database() and wait_event_type='Lock' and"
                                    + " ?=any(pg_blocking_pids(pid)) and query like '%for update of"
                                    + " d%'")) {
            statement.setInt(1, blocker);
            statement.setQueryTimeout(5);
            while (System.nanoTime() < deadline) {
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        System.out.printf(
                                "COPY_LOCK_BARRIER blocker=%d waiter=%d event=%s%n",
                                blocker, rows.getInt("pid"), rows.getString("wait_event"));
                        return;
                    }
                }
            }
            fail("copy never entered the PostgreSQL draft lock wait before T1 commit");
        } catch (SQLException e) {
            throw new AssertionError("could not observe the copy lock barrier", e);
        }
    }
}
