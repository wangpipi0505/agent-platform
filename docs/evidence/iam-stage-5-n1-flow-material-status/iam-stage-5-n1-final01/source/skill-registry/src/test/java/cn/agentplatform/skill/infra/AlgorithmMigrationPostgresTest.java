package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;

class AlgorithmMigrationPostgresTest {
    @Test
    void v310UpgradePreservesLegacyHttpProofAndAlgorithmRowsAndAddsScopedUniqueIdentity()
            throws Exception {
        var database =
                new AlgorithmTestDatabase(
                        "agent_platform_skill_algorithm_migration_test", "310", true);
        var db = database.db;
        String tenant = "algorithm_upgrade_" + UUID.randomUUID();
        AlgorithmTestDatabase.identity(tenant, "owner");
        try (var api = new AlgorithmApiFixture()) {
            db.update(
                    "insert into skill.skill(tenant_id,code,name)"
                            + " values(?,'migration','migration')",
                    tenant);
            long version =
                    db.queryForObject(
                            "insert into"
                                + " skill.skill_version(tenant_id,skill_code,version_no,contract,sha256)"
                                + " values(?,'migration',1,'{}',?) returning id",
                            Long.class,
                            tenant,
                            Digest.sha256("migration"));
            db.update(
                    "insert into"
                        + " skill.external_job_binding(tenant_id,call_id,external_job_id,status,metadata)"
                        + " values(?,101,'alg-legacy','SUBMITTED','{\"namespace\":\"alg-test\",\"image\":\"fixture/algorithm:fixed\"}')",
                    tenant);
            var request =
                    Map.of(
                            "attemptId",
                            1L,
                            "callId",
                            102L,
                            "runId",
                            "r_migration",
                            "nodeInstanceId",
                            3L,
                            "skillVersionId",
                            version,
                            "idempotencyKey",
                            "http-migration-key",
                            "arguments",
                            Map.of(),
                            "guardedArguments",
                            Map.of());
            db.update(
                    "insert into"
                        + " skill.execution_fact(tenant_id,call_id,skill_version_id,principal_id,run_id,attempt_id,execution_owner,args_digest,skill_contract_sha256,request_snapshot,status,result,duration_ms,is_mock,call_kind)"
                        + " values(?,102,?,'owner','r_migration',1,'worker',?,?,?::jsonb,'UNKNOWN','{}',0,false,'FORMAL')",
                    tenant,
                    version,
                    Digest.sha256("{}"),
                    Digest.sha256("migration"),
                    Json.write(request));
            db.update(
                    "insert into"
                        + " skill.external_job_binding(tenant_id,call_id,status,metadata,principal_id,run_id,attempt_id,skill_version_id,idempotency_key,request_snapshot)"
                        + " values(?,102,'NOT_SENT',?::jsonb,'owner','r_migration',1,?,'http-migration-key',?::jsonb)",
                    tenant,
                    Json.write(
                            Map.of(
                                    "protocol",
                                    "HTTP",
                                    "contractSha256",
                                    Digest.sha256("migration"))),
                    version,
                    Json.write(request));
            String proofSha = Digest.sha256("immutable-old-http-proof");
            db.update(
                    "insert into"
                        + " skill.reconciliation_proof(tenant_id,principal_id,call_id,attempt_id,outcome,evidence,evidence_sha256,created_at)"
                        + " values(?,'owner',102,1,'NOT_SENT','{\"outcome\":\"NOT_SENT\"}',?,now())",
                    tenant,
                    proofSha);
            String beforeHttp =
                    db.queryForObject(
                            "select to_jsonb(b)::text from skill.external_job_binding b where"
                                    + " tenant_id=? and call_id=102",
                            String.class,
                            tenant);
            String beforeProof =
                    db.queryForObject(
                            "select md5(to_jsonb(p)::text) from skill.reconciliation_proof p where"
                                    + " tenant_id=?",
                            String.class,
                            tenant);
            Flyway.configure()
                    .dataSource(db.getDataSource())
                    .schemas("skill")
                    .defaultSchema("skill")
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            assertEquals(
                    4,
                    db.queryForObject(
                            "select count(*) from information_schema.columns where"
                                + " table_schema='skill' and table_name='external_job_binding' and"
                                + " column_name in"
                                + " ('external_job_uid','external_namespace','external_api_sha256','external_identity_sha256')",
                            Integer.class));
            String afterHttp =
                    db.queryForObject(
                            "select"
                                + " (to_jsonb(b)-'external_job_uid'-'external_namespace'-'external_api_sha256'-'external_identity_sha256')::text"
                                + " from skill.external_job_binding b where tenant_id=? and"
                                + " call_id=102",
                            String.class,
                            tenant);
            assertEquals(
                    Json.canonical(Json.read(beforeHttp)), Json.canonical(Json.read(afterHttp)));
            assertEquals(
                    beforeProof,
                    db.queryForObject(
                            "select md5(to_jsonb(p)::text) from skill.reconciliation_proof p where"
                                    + " tenant_id=?",
                            String.class,
                            tenant));
            assertEquals(
                    proofSha,
                    db.queryForObject(
                            "select evidence_sha256 from skill.reconciliation_proof where"
                                    + " tenant_id=?",
                            String.class,
                            tenant));
            var contract =
                    new LinkedHashMap<String, Object>(
                            Map.of(
                                    "type",
                                    "ALGORITHM",
                                    "skillVersionId",
                                    version,
                                    "sha256",
                                    Digest.sha256("migration"),
                                    "jobTemplate",
                                    Map.of("image", "fixture/algorithm:fixed"),
                                    "timeoutMs",
                                    200));
            var adapter = AlgorithmTestDatabase.adapter(db, api, 1024, 4096);
            var outputs = new SkillAdapters(new ArtifactStore(db), adapter);
            assertTrue(
                    assertThrows(
                                    SkillAdapters.Failure.class,
                                    () ->
                                            adapter.invoke(
                                                    contract,
                                                    Map.of(),
                                                    "legacy",
                                                    101,
                                                    true,
                                                    outputs,
                                                    "r_legacy",
                                                    1))
                            .getMessage()
                            .contains("LEGACY"));
            assertEquals(0, api.routes.size());
            assertEquals(
                    null,
                    db.queryForMap(
                                    "select * from skill.external_job_binding where tenant_id=? and"
                                            + " call_id=101",
                                    tenant)
                            .get("external_identity_sha256"));
            var algorithmRequest = new LinkedHashMap<String, Object>(request);
            algorithmRequest.put("callId", 103L);
            algorithmRequest.put("idempotencyKey", "algorithm-migration-key");
            adapter.bind(contract, algorithmRequest);
            var original =
                    db.queryForMap(
                            "select * from skill.external_job_binding where tenant_id=? and"
                                    + " call_id=103",
                            tenant);
            assertEquals("REGISTERED", original.get("status"));
            AlgorithmTestDatabase.identity(tenant + "_foreign", "owner");
            var other = new LinkedHashMap<>(algorithmRequest);
            other.put("callId", 104L);
            assertThrows(SkillAdapters.Failure.class, () -> adapter.bind(contract, other));
            assertEquals(
                    1,
                    db.queryForObject(
                            "select count(*) from skill.external_job_binding where"
                                    + " metadata->>'protocol'='ALGORITHM'",
                            Integer.class));
            AlgorithmTestDatabase.identity(tenant, "owner");
            other.put("idempotencyKey", "other-migration-key");
            adapter.bind(contract, other);
            db.update(
                    "update skill.external_job_binding set external_job_uid='one-uid' where"
                            + " tenant_id=? and call_id=103",
                    tenant);
            assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    () ->
                            db.update(
                                    "update skill.external_job_binding set"
                                            + " external_job_uid='one-uid' where tenant_id=? and"
                                            + " call_id=104",
                                    tenant));
            var flyway =
                    Flyway.configure()
                            .dataSource(db.getDataSource())
                            .schemas("skill")
                            .defaultSchema("skill")
                            .locations("classpath:db/migration")
                            .load();
            flyway.validate();
            assertEquals("311", flyway.info().current().getVersion().getVersion());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void completeMigrationDatabaseIsAtV311() {
        var database = new AlgorithmTestDatabase("agent_platform_skill_algorithm_test", null);
        assertEquals(
                1,
                database.db.queryForObject(
                        "select count(*) from skill.flyway_schema_history where version='311' and"
                                + " success",
                        Integer.class));
    }
}
