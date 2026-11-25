package cn.agentplatform.engine.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.authority.IntegrationRuntimeRoutes.TaskScope;
import cn.agentplatform.engine.application.Tasks;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class IntegrationTaskReadPostgresTest {
    @Test
    void readsOnlyAnOwnedTaskWithMatchingApplicationSoftwareAndLabels() {
        var source = new DriverManagerDataSource(EngineTestDatabase.url(),
                EngineTestDatabase.user(), EngineTestDatabase.password());
        Flyway.configure().dataSource(source).schemas("engine").defaultSchema("engine").outOfOrder(true)
                .locations("classpath:db/migration").load().migrate();
        var db = new JdbcTemplate(source);
        var tm = new DataSourceTransactionManager(source);
        var tasks = new Tasks(new TraceStore(db, tm));
        var actor = new Actor("r40-task-read-test", "software-a:alice", Set.of("AGENT_VIEWER"), Map.of());
        var scope = new TaskScope("app-a", "software-a", Set.of("sensitive", "public"));
        new TransactionTemplate(tm).executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            String task = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            long id = db.queryForObject("""
                    insert into engine.task(public_id,tenant_id,principal_id,app_code,config_release_id,
                      config_digest,mode,goal,input_ref,input_digest,state,request_id,request_digest)
                    values(?,?,?,'app-a','appver-1',repeat('a',64),'AGENT','fixture','input',
                      repeat('b',64),'ACCEPTED',?,repeat('c',64)) returning id
                    """, Long.class, task, actor.tenantId(), actor.principalId(), task);
            assertDenied(() -> tasks.resolveIntegrationTask(task, actor, scope));
            db.update("""
                    insert into engine.task_integration_scope(task_id,credential_id,biz_system_code,data_labels)
                    values(?,1,'software-a','["sensitive"]')
                    """, id);
            assertThat(tasks.resolveIntegrationTask(task, actor, scope)).isEqualTo(id);
            assertDenied(() -> tasks.resolveIntegrationTask(task, actor,
                    new TaskScope("app-b", "software-a", scope.dataLabels())));
            assertDenied(() -> tasks.resolveIntegrationTask(task, actor,
                    new TaskScope("app-a", "software-b", scope.dataLabels())));
            assertDenied(() -> tasks.resolveIntegrationTask(task, actor,
                    new TaskScope("app-a", "software-a", Set.of("public"))));
            assertDenied(() -> tasks.resolveIntegrationTask(task,
                    new Actor(actor.tenantId(), "software-a:bob", Set.of("AGENT_ADMIN"), Map.of()), scope));
            assertDenied(() -> tasks.resolveIntegrationTask(task,
                    new Actor("another-tenant", actor.principalId(), actor.roles(), Map.of()), scope));
        });
    }

    private static void assertDenied(Runnable request) {
        assertThatThrownBy(request::run).isInstanceOfSatisfying(PlatformException.class, error -> {
            assertThat(error.status()).isEqualTo(403);
            assertThat(error.code()).isEqualTo("APP_BINDING_DENIED");
        });
    }
}
