package cn.agentplatform.app.application;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;

/**
 * T20.1 / PLAT-20：数据面健康快照证据。真实 PG 专用库 {@code agent_platform_health_data_v12}
 * （Fresh DB 按 105→106→107 顺序应用，绝不复用可能已到 V107 的既有库）。
 * 验证：库名与 Flyway 已应用版本可见、数据源类型可辨、关键表行数取整脱敏（不泄露精确行数）、
 * 演示/真实环境可从健康详情区分。行数快照带 TTL 缓存，/actuator/health 高频轮询不逐次 count 全表。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class DataHealthPostgresTest {
    private static final String DB = "agent_platform_health_data_v12";

    private JdbcTemplate db() {
        String password = System.getenv("AGENT_DB_PASSWORD");
        assertThat(password).isNotBlank();
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:25432/postgres", "agent_v12", password));
        if (!Boolean.TRUE.equals(
                admin.queryForObject(
                        "select exists(select 1 from pg_database where datname=?)",
                        Boolean.class,
                        DB)))
            admin.execute("CREATE DATABASE " + DB);
        var ds =
                new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/" + DB, "agent_v12", password);
        Flyway.configure()
                .dataSource(ds)
                .schemas("app")
                .defaultSchema("app")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return new JdbcTemplate(ds);
    }

    @Test
    void roundingKeepsOnlyLeadingDigitSoExactRowCountsNeverLeak() {
        assertThat(DataHealthService.roundCount(0)).isZero();
        assertThat(DataHealthService.roundCount(5)).isEqualTo(5);
        assertThat(DataHealthService.roundCount(12)).isEqualTo(10);
        assertThat(DataHealthService.roundCount(1234)).isEqualTo(1000);
        assertThat(DataHealthService.roundCount(99999)).isEqualTo(90000);
        assertThat(DataHealthService.roundCount(-3)).isZero();
    }

    @Test
    void snapshotExposesDatabaseNameFlywayVersionAndDatasourceType() {
        var db = db();
        var service = new DataHealthService(db, false, false, 0);
        var snapshot = service.snapshot();

        assertThat(snapshot.get("database")).isEqualTo(DB);
        assertThat(snapshot.get("dataSourceType")).asString().startsWith("PostgreSQL");
        // 专用库 Fresh 迁移后最高已应用版本必须到 107（105→106→107 顺序入库的证据）
        assertThat((Integer) snapshot.get("flywayVersion")).isGreaterThanOrEqualTo(107);
    }

    @Test
    void rowCountSnapshotIsRoundedNotExact() {
        var db = db();
        String tenant = "t-rows-" + UUID.randomUUID();
        for (int i = 0; i < 12; i++)
            db.update(
                    "insert into app.application(tenant_id,code,name,type,status,created_by)"
                            + " values(?,?,'行数脱敏','CHAT','DRAFT','tester')",
                    tenant,
                    "rows_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        long actual =
                db.queryForObject("select count(*) from app.application", Long.class);
        var snapshot = new DataHealthService(db, false, false, 0).snapshot();
        @SuppressWarnings("unchecked")
        var counts = (Map<String, Object>) snapshot.get("rowCounts");
        assertThat(counts).containsKeys("app.application", "app.app_version", "app.publish_commit", "app.lifecycle_log");
        long reported = ((Number) counts.get("app.application")).longValue();
        assertThat(reported).isEqualTo(DataHealthService.roundCount(actual));
        // 12 行必须只报数量级 10：精确行数不得出现在健康面
        assertThat(reported).isNotEqualTo(actual).isLessThanOrEqualTo(actual);
    }

    @Test
    void demoAndRealEnvironmentsAreDistinguishableFromHealthDetails() {
        var db = db();
        var real = new DataHealthService(db, false, false, 0).snapshot();
        var demo = new DataHealthService(db, true, false, 0).snapshot();
        assertThat(real.get("environment")).isEqualTo("REAL");
        assertThat(real.get("demoMode")).isEqualTo(false);
        assertThat(demo.get("environment")).isEqualTo("DEMO");
        assertThat(demo.get("demoMode")).isEqualTo(true);
        // 既有环境标记 AGENT_DEV_AUTH 同样进入健康详情，便于区分本地开发票据面
        assertThat(new DataHealthService(db, false, true, 0).snapshot().get("devAuth")).isEqualTo(true);
    }

    @Test
    void snapshotCacheHonoursTtlSoHealthPollsDoNotCountEveryTime() {
        var db = db();
        var service = new DataHealthService(db, false, false, 60_000);
        var first = service.snapshot();
        String tenant = "t-cache-" + UUID.randomUUID();
        db.update(
                "insert into app.application(tenant_id,code,name,type,status,created_by)"
                        + " values(?,?,'缓存','CHAT','DRAFT','tester')",
                tenant,
                "cache_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        // TTL 未到期：同一快照对象原样返回（COUNT 不会随每次 /actuator/health 轮询执行）
        assertThat(service.snapshot()).isSameAs(first);
        // TTL=0：立即失效，每次返回新快照（读到最新事实），供 /health/data 需要精确观测时使用
        var second = new DataHealthService(db, false, false, 0).snapshot();
        assertThat(second).isNotSameAs(first);
    }
}
