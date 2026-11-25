package cn.agentplatform.engine.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
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
        var scope = new TaskScope("app-a", "software-a", Set.of("sensitive", "public"), 1);
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
                    new TaskScope("app-b", "software-a", scope.dataLabels(), 1)));
            assertDenied(() -> tasks.resolveIntegrationTask(task, actor,
                    new TaskScope("app-a", "software-b", scope.dataLabels(), 1)));
            assertDenied(() -> tasks.resolveIntegrationTask(task, actor,
                    new TaskScope("app-a", "software-a", Set.of("public"), 1)));
            assertDenied(() -> tasks.resolveIntegrationTask(task,
                    new Actor(actor.tenantId(), "software-a:bob", Set.of("AGENT_ADMIN"), Map.of()), scope));
            assertDenied(() -> tasks.resolveIntegrationTask(task,
                    new Actor("another-tenant", actor.principalId(), actor.roles(), Map.of()), scope));

            String run = "r_" + UUID.randomUUID().toString().replace("-", "");
            String artifactId = "task_result_" + run.substring(2);
            String file = "{\"text\":\"轨道窗口\"}";
            long runKey = db.queryForObject("""
                    insert into engine.run(public_id,app_code,app_version_id,trigger_type,status,
                      principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,output)
                    values(?,'app-a',1,'CHAT','SUCCEEDED',?,?,'input','{}','{}','{}',
                      jsonb_build_object('candidateSha256',repeat('a',64)),'{}',?::jsonb) returning id
                    """, Long.class, run, actor.principalId(), actor.tenantId(), file);
            db.update("insert into engine.task_step(task_id,step_id,state,run_id) values(?,'entry','SUCCEEDED',?)",
                    id, runKey);
            assertThat(tasks.integrationResult(task, actor, scope).get("artifacts")).isEqualTo(List.of());
            db.update("""
                    update engine.task set state='SUCCEEDED',result_ref=?,result_digest=? where id=?
                    """, "pg:run/" + run + "/output", Digest.sha256(file), id);
            var download = tasks.downloadIntegrationResult(run, artifactId, actor, scope);
            assertThat(download.content()).isEqualTo(file.getBytes(StandardCharsets.UTF_8));
            assertThat(download.digest()).isEqualTo(Digest.sha256(file));
            assertThat(tasks.integrationResult(task, actor, scope).get("artifacts"))
                    .isEqualTo(List.of(Map.of("artifactId", artifactId, "runId", run,
                            "downloadPath", "/v1/runs/" + run + "/artifacts/" + artifactId + "/download",
                            "digest", Digest.sha256(file), "publishedDigest", "a".repeat(64),
                            "mediaType", "application/json", "bytes", download.content().length)));
            assertDenied(() -> tasks.downloadIntegrationResult(run, artifactId, actor,
                    new TaskScope("app-b", scope.bizSystemCode(), scope.dataLabels(), 2)));
            assertDenied(() -> tasks.downloadIntegrationResult(run, artifactId, actor,
                    new TaskScope("app-a", scope.bizSystemCode(), Set.of("public"), 2)));
            assertDenied(() -> tasks.downloadIntegrationResult(run, artifactId,
                    new Actor(actor.tenantId(), "software-a:bob", Set.of("AGENT_ADMIN"), Map.of()), scope));
            db.update("update engine.run set output='{\"text\":\"changed\"}'::jsonb where id=?", runKey);
            assertThat(tasks.integrationResult(task, actor, scope).get("artifacts")).isEqualTo(List.of());
            assertThatThrownBy(() -> tasks.downloadIntegrationResult(run, artifactId, actor, scope))
                    .isInstanceOfSatisfying(PlatformException.class, error -> assertThat(error.status()).isEqualTo(404));
        });
    }

    private static void assertDenied(Runnable request) {
        assertThatThrownBy(request::run).isInstanceOfSatisfying(PlatformException.class, error -> {
            assertThat(error.status()).isEqualTo(403);
            assertThat(error.code()).isEqualTo("APP_BINDING_DENIED");
        });
    }
}
