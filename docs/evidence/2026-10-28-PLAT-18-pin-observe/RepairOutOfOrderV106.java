import org.flywaydb.core.Flyway;

/**
 * T18.3 一次性运维修复（仅限既有测试库，不用于 Fresh DB）：
 * agent_platform_v12 / agent_platform_iam_policy_v12 / agent_platform_iam_identity_v12
 * 已先应用到 V107（T12a 先入库），V106（本卡）晚于 V107 出现，常规 migrate 触发
 * Flyway "detected resolved migration not applied to database" 校验失败。
 * 本程序用 outOfOrder=true 把 V106 一次性补进这三个库，使后续所有常规 migrate 恢复顺序一致。
 * 用法：java -cp "<app-management 全量测试 classpath>;target/classes" RepairOutOfOrderV106.java <db>...
 */
public class RepairOutOfOrderV106 {
    public static void main(String[] args) {
        for (String db : args) {
            String url = "jdbc:postgresql://127.0.0.1:25432/" + db;
            var result =
                    Flyway.configure()
                            .dataSource(url, "agent_v12", System.getenv("AGENT_DB_PASSWORD"))
                            .schemas("app")
                            .defaultSchema("app")
                            .locations("classpath:db/migration")
                            .outOfOrder(true)
                            .load()
                            .migrate();
            System.out.println(db + " applied=" + result.migrationsExecuted + " success=" + result.success);
        }
    }
}
