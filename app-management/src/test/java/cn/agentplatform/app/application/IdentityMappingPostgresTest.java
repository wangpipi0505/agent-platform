package cn.agentplatform.app.application;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.app.domain.IdentityMapping;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;

/**
 * PLAT-12/X-5：身份映射表 V107 在真实PG库上的验证；映射不存在必须显式报错，禁止静默造数据。
 * 连接坐标统一走 {@link TestDatabase}（{@code AGENT_DB_URL}/{@code AGENT_DB_USER}/{@code AGENT_DB_PASSWORD}），
 * 不建库、不绑定实例；用例以随机租户隔离，与其他 DB 集成测试共用同一已准备库。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class IdentityMappingPostgresTest {

    private JdbcTemplate db() {
        var ds =
                new DriverManagerDataSource(
                        TestDatabase.url(), TestDatabase.user(), TestDatabase.password());
        Flyway.configure()
                .dataSource(ds)
                .schemas("app")
                .defaultSchema("app")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return new JdbcTemplate(ds);
    }

    private final IdentityMappingService mappings =
            new IdentityMappingService(new cn.agentplatform.app.infra.IdentityMappingRepository(db()));

    @Test
    void v107LandsMappingTableAndNullableAuditColumns() {
        var db = db();
        assertThat(
                        db.queryForObject(
                                "select count(*) from information_schema.tables where table_schema='app'"
                                        + " and table_name='identity_mapping'",
                                Integer.class))
                .isEqualTo(1);
        for (String column : List.of("tenant_id", "principal_id", "mapping_key", "subject_snapshot")) {
            assertThat(
                            db.queryForObject(
                                    "select count(*) from information_schema.columns where table_schema='app'"
                                            + " and table_name='identity_mapping' and column_name='"
                                            + column
                                            + "'",
                                    Integer.class))
                    .as("identity_mapping.%s", column)
                    .isEqualTo(1);
        }
        for (String column : List.of("run_id", "credential_id")) {
            var nullable =
                    db.queryForMap(
                            "select is_nullable from information_schema.columns where table_schema='app'"
                                + " and table_name='lifecycle_log' and column_name='"
                                + column
                                + "'");
            assertThat(nullable.get("is_nullable")).as("lifecycle_log.%s", column).isEqualTo("YES");
        }
    }

    @Test
    void migrationKeepsLegacyLifecycleRowsWritableWithoutAssociationColumns() {
        var db = db();
        String tenant = "t-legacy-" + UUID.randomUUID();
        // 旧写入路径的显式列清单：不带 run_id/credential_id 也必须照常落行（可空列在旧数据上可跑）
        db.update(
                "insert into app.lifecycle_log(tenant_id,app_code,actor,action,from_state,to_state,detail)"
                        + " values(?,?,?,?,?,?,?::jsonb)",
                tenant,
                "app-legacy",
                "operator-old",
                "MODIFY",
                "DRAFT",
                "DRAFT",
                "{\"legacy\":true}");
        var legacy =
                db.queryForMap(
                        "select run_id,credential_id from app.lifecycle_log where tenant_id=? and actor=?",
                        tenant,
                        "operator-old");
        assertThat(legacy.get("run_id")).isNull();
        assertThat(legacy.get("credential_id")).isNull();
    }

    @Test
    void upsertKeepsOneRowAndBumpsRevisionOnlyWhenSnapshotChanges() {
        var db = db();
        String tenant = "t-upsert-" + UUID.randomUUID();
        var first = mappings.put(new Actor(tenant, "op1", Set.of("EDITOR"), Map.of("dept", Set.of("103"))));
        assertThat(first.revision()).isEqualTo(1);

        // 幂等重放：完全相同的快照再次写入，不推进 revision、不产生第二行
        var replay =
                mappings.put(new Actor(tenant, "op1", Set.of("EDITOR"), Map.of("dept", Set.of("103"))));
        assertThat(replay.revision()).isEqualTo(1);
        assertThat(replay.mappingKey()).isEqualTo(first.mappingKey());
        assertThat(
                        db.queryForObject(
                                "select count(*) from app.identity_mapping where tenant_id=? and principal_id='op1'",
                                Integer.class,
                                tenant))
                .isEqualTo(1);

        // 快照变化（roles 增加）：整行替换、revision 单调 +1，同一主体仍只有一行
        var changed =
                mappings.put(
                        new Actor(
                                tenant, "op1", Set.of("EDITOR", "USER_ADMIN"), Map.of("dept", Set.of("103"))));
        assertThat(changed.revision()).isEqualTo(2);
        assertThat(changed.subject().roles()).containsExactlyInAnyOrder("EDITOR", "USER_ADMIN");
        assertThat(changed.mappingKey()).isNotEqualTo(first.mappingKey());
        assertThat(
                        db.queryForObject(
                                "select count(*) from app.identity_mapping where tenant_id=? and principal_id='op1'",
                                Integer.class,
                                tenant))
                .isEqualTo(1);
    }

    @Test
    void rawDuplicatePrincipalInsertIsRejectedByUniqueConstraint() {
        var db = db();
        String tenant = "t-unique-" + UUID.randomUUID();
        String snapshot = "{\"tenantId\":\"" + tenant + "\",\"principalId\":\"op1\",\"roles\":[],\"attributes\":{}}";
        db.update(
                "insert into app.identity_mapping(tenant_id,principal_id,mapping_key,subject_snapshot,created_by)"
                        + " values(?,?,?,?::jsonb,'seed')",
                tenant,
                "op1",
                "k-" + tenant,
                snapshot);
        // 绕过服务的裸插入必须被库层唯一约束拒绝；服务的 upsert 语义建立在该约束之上
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "insert into app.identity_mapping(tenant_id,principal_id,mapping_key,subject_snapshot,created_by)"
                                                + " values(?,?,?,?::jsonb,'seed')",
                                        tenant,
                                        "op1",
                                        "k2-" + tenant,
                                        snapshot))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void missingMappingIsExplicitAndNeverAutoCreates() {
        var db = db();
        String tenant = "t-missing-" + UUID.randomUUID();
        assertThat(mappings.find(tenant, "nobody")).isEmpty();
        assertThatThrownBy(() -> mappings.require(tenant, "nobody"))
                .isInstanceOf(PlatformException.class)
                .hasFieldOrPropertyWithValue("code", "IDENTITY_MAPPING_NOT_FOUND")
                .hasFieldOrPropertyWithValue("status", 404);
        // 显式失败路径不得顺手造数据
        assertThat(
                        db.queryForObject(
                                "select count(*) from app.identity_mapping where tenant_id=?",
                                Integer.class,
                                tenant))
                .isZero();
    }

    @Test
    void storedMappingKeySurvivesJsonRoundTripAndMatchesSubjectDigest() {
        String tenant = "t-roundtrip-" + UUID.randomUUID();
        var actor = new Actor(tenant, "op9", Set.of("EDITOR", "APPROVER"), Map.of("dept", Set.of("103", "205")));
        mappings.put(actor);
        IdentityMapping stored = mappings.require(tenant, "op9");
        // 库里的 mapping_key 必须等于快照重算摘要：证明 JSON 落库/回读没有漂移快照材料
        assertThat(stored.subject().mappingKey()).isEqualTo(stored.mappingKey());
        assertThat(stored.subject().mappingKey()).isEqualTo(actor.subject().mappingKey());
        assertThat(stored.subject().roles()).containsExactlyInAnyOrder("EDITOR", "APPROVER");
        assertThat(stored.subject().attributes().get("dept")).containsExactlyInAnyOrder("103", "205");
    }
}
