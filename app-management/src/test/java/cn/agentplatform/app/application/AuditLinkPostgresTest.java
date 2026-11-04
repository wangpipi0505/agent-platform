package cn.agentplatform.app.application;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;

/**
 * PLAT-12 实现项 4：审计三元组 runId↔principalId↔credentialId 落在既有 app.lifecycle_log 上，
 * 从业务用户/凭据可反查运行；不新建重复表。连接坐标统一走 {@link TestDatabase}
 * （{@code AGENT_DB_URL}/{@code AGENT_DB_USER}/{@code AGENT_DB_PASSWORD}），不建库、不绑定实例；
 * 用例以随机租户隔离，与其他 DB 集成测试共用同一已准备库。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class AuditLinkPostgresTest {

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

    /** 种子：应用行是 integration_credential 的外键前提；凭据行供关联校验。 */
    private String seedAppAndCredential(JdbcTemplate db, String tenant) {
        db.update(
                "insert into app.application(tenant_id,code,name,type,created_by) values(?,?,?,?,?)",
                tenant,
                "app1",
                "审计关联测试应用",
                "CHAT",
                "seed");
        db.update(
                "insert into app.integration_credential(tenant_id,app_code,biz_system_code,secret_ref,"
                        + "secret_sha256,status,claim_limits,allowed_origins,issued_by)"
                        + " values(?,?,?,?,'seed','ACTIVE','{}'::jsonb,'[]'::jsonb,'seed')",
                tenant,
                "app1",
                "biz1",
                "vault://seed");
        return db.queryForObject(
                "select id from app.integration_credential where tenant_id=? and app_code='app1'",
                String.class,
                tenant);
    }

    @Test
    void runAssociationIsRecordedAndQueryableFromPrincipalAndCredential() {
        var db = db();
        var service = new AuditLinkService(db);
        String tenant = "t-audit-" + UUID.randomUUID();
        String credentialId = seedAppAndCredential(db, tenant);
        var operator = new Actor(tenant, "operatorA", Set.of("EDITOR"), Map.of());
        service.recordRunLink(operator, "app1", "r_" + "a".repeat(32), credentialId);

        // 业务用户反查自己的运行
        var own = service.runsByPrincipal(operator, "operatorA");
        assertThat(own).hasSize(1);
        assertThat(own.getFirst())
                .containsEntry("runId", "r_" + "a".repeat(32))
                .containsEntry("principalId", "operatorA")
                .containsEntry("credentialId", credentialId)
                .containsEntry("appCode", "app1");

        // 从凭据反查运行（跨用户面，仅 ADMIN）
        var admin = new Actor(tenant, "_admin", Set.of("ADMIN"), Map.of());
        assertThat(service.runsByCredential(admin, credentialId))
                .extracting(r -> r.get("runId"))
                .containsExactly("r_" + "a".repeat(32));

        // 无运行的主体反查得到空列表（显式空，不是错误也不是造数据）
        assertThat(service.runsByPrincipal(admin, "nobody")).isEmpty();
    }

    @Test
    void nonAdminCannotQueryOthersRunsWhileAdminCan() {
        var db = db();
        var service = new AuditLinkService(db);
        String tenant = "t-guard-" + UUID.randomUUID();
        String credentialId = seedAppAndCredential(db, tenant);
        var operator = new Actor(tenant, "operatorA", Set.of("EDITOR"), Map.of());
        service.recordRunLink(operator, "app1", "r_" + "b".repeat(32), null);

        var intruder = new Actor(tenant, "operatorB", Set.of("EDITOR"), Map.of());
        assertThatThrownBy(() -> service.runsByPrincipal(intruder, "operatorA"))
                .isInstanceOf(PlatformException.class)
                .hasFieldOrPropertyWithValue("status", 403);

        var admin = new Actor(tenant, "_admin", Set.of("ADMIN"), Map.of());
        assertThat(service.runsByPrincipal(admin, "operatorA")).hasSize(1);
        // 凭据面是跨用户查询，非管理员即使查自己的凭据关联也被拒绝
        assertThatThrownBy(() -> service.runsByCredential(operator, credentialId))
                .isInstanceOf(PlatformException.class)
                .hasFieldOrPropertyWithValue("status", 403);
    }

    @Test
    void unknownReferencesAndMalformedRunIdAreRejectedExplicitly() {
        var db = db();
        var service = new AuditLinkService(db);
        String tenant = "t-invalid-" + UUID.randomUUID();
        String credentialId = seedAppAndCredential(db, tenant);
        var operator = new Actor(tenant, "operatorA", Set.of("EDITOR"), Map.of());

        assertThatThrownBy(() -> service.recordRunLink(operator, "no-such-app", "r_abc", null))
                .isInstanceOf(PlatformException.class)
                .hasFieldOrPropertyWithValue("code", "APP_NOT_FOUND");
        assertThatThrownBy(() -> service.recordRunLink(operator, "app1", "r_abc", "999999"))
                .isInstanceOf(PlatformException.class)
                .hasFieldOrPropertyWithValue("code", "INTEGRATION_NOT_FOUND");
        assertThatThrownBy(() -> service.recordRunLink(operator, "app1", "not a run id!", null))
                .isInstanceOf(PlatformException.class)
                .hasFieldOrPropertyWithValue("status", 400);
        // 全部失败路径都不得留下审计行
        assertThat(
                        db.queryForObject(
                                "select count(*) from app.lifecycle_log where tenant_id=?",
                                Integer.class,
                                tenant))
                .isZero();
    }

    @Test
    void integrationAuditWriterCarriesCredentialIdIntoReverseLookup() {
        var db = db();
        var service = new AuditLinkService(db);
        String tenant = "t-int-" + UUID.randomUUID();
        String credentialId = seedAppAndCredential(db, tenant);
        var operator = new Actor(tenant, "operatorA", Set.of("EDITOR"), Map.of());
        // 既有审计写入路径（IntegrationService.audit）带上 credential_id 后，关联列直接可查
        var integration = new IntegrationService(db, null);
        integration.audit(operator, "app1", "INTEGRATION_ISSUE", "biz1", credentialId);
        assertThat(
                        db.queryForObject(
                                "select credential_id from app.lifecycle_log where tenant_id=? and action='INTEGRATION_ISSUE'",
                                String.class,
                                tenant))
                .isEqualTo(credentialId);
        // 未关联 credential_id 的写入保持 null，不进入凭据反查
        integration.audit(operator, "app1", "INTEGRATION_REVOKE", "biz1", null);
        assertThat(
                        db.queryForObject(
                                "select count(*) from app.lifecycle_log where tenant_id=?"
                                        + " and action='INTEGRATION_REVOKE' and credential_id is null",
                                Integer.class,
                                tenant))
                .isEqualTo(1);
        // 凭据反查回答"哪些运行用过该凭据"：只取 run_id 与 credential_id 双全的关联行
        String runId = "r_" + "c".repeat(32);
        service.recordRunLink(operator, "app1", runId, credentialId);
        var admin = new Actor(tenant, "_admin", Set.of("ADMIN"), Map.of());
        var found = service.runsByCredential(admin, credentialId);
        assertThat(found).hasSize(1);
        assertThat(found.getFirst())
                .containsEntry("runId", runId)
                .containsEntry("principalId", "operatorA")
                .containsEntry("credentialId", credentialId);
    }
}
