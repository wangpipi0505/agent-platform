import cn.agentplatform.skill.infra.ArtifactStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 本次红绿测试fixture恢复工具：固定测试库、测试tenant/principal/run、固定日期、有界键集，禁止用于开发业务库。 */
class ArtifactFixtureCleanup {
    public static void main(String[] ignored) throws Exception {
        var db =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12",
                                "agent_v12",
                                System.getenv("AGENT_DB_PASSWORD")));
        var rows =
                db.queryForList(
                        "select tenant_id,artifact_id from skill.artifact where tenant_id like"
                            + " 'artifact-test-%' and principal_id='owner' and run_id='r_artifact'"
                            + " and created_at >= '2026-10-20T03:10:00+08:00' and created_at <"
                            + " '2026-10-20T03:20:00+08:00' order by created_at limit 64");
        int deleted = 0;
        try (var store = new AutoCloseStore(new ArtifactStore(db))) {
            for (var row : rows) {
                String tenant = row.get("tenant_id").toString(),
                        id = row.get("artifact_id").toString();
                db.update(
                        "update skill.artifact set"
                            + " state='CLEANUP_PENDING',cleanup_after=now()-interval '1"
                            + " second',cleanup_lease_until=null where tenant_id=? and"
                            + " artifact_id=?",
                        tenant,
                        id);
                deleted += store.value.cleanTenant(tenant, 1);
            }
        }
        System.out.println(
                "Test fixture recovery processed=" + deleted + ", bounded=" + rows.size());
        Integer pending =
                db.queryForObject(
                        "select count(*) from skill.artifact where tenant_id like 'artifact-test-%'"
                            + " and principal_id='owner' and run_id='r_artifact' and created_at >="
                            + " '2026-10-20T03:10:00+08:00' and created_at <"
                            + " '2026-10-20T03:20:00+08:00' and state<>'DELETED'",
                        Integer.class);
        if (pending != 0) throw new IllegalStateException("Fixture cleanup remains pending");
    }

    record AutoCloseStore(ArtifactStore value) implements AutoCloseable {
        public void close() {
            value.close();
        }
    }
}
