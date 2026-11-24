package cn.agentplatform.app.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The first grant and its audit event commit together before application startup completes. */
@Component
public class PlatformAdminBootstrap implements ApplicationRunner {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final String tenant;
    private final String subject;

    public PlatformAdminBootstrap(JdbcTemplate db, PlatformTransactionManager manager,
                                  @Value("${AGENT_BOOTSTRAP_ADMIN_SUBJECT:}") String configured) {
        this.db = db;
        this.transaction = new TransactionTemplate(manager);
        var identity = configured.isBlank() ? new String[] {"", ""} : configured.split(":", 2);
        if (!configured.isBlank() && (identity.length != 2 || !valid(identity[0]) || !valid(identity[1])))
            throw new IllegalArgumentException("AGENT_BOOTSTRAP_ADMIN_SUBJECT 必须为 tenant:subject");
        tenant = identity[0];
        subject = identity[1];
    }

    @Override
    public void run(ApplicationArguments args) {
        if (tenant.isEmpty()) return;
        transaction.executeWithoutResult(status -> {
            // Serialize the empty-table decision across instances and against concurrent grants.
            db.execute("lock table app.platform_user_role in share row exclusive mode");
            int created = db.update("insert into app.platform_user_role(tenant_id,subject,platform_role,granted_by)"
                    + " select ?,?,'ADMIN','bootstrap' where not exists(select 1 from app.platform_user_role)",
                    tenant, subject);
            if (created == 1) {
                db.update("insert into app.platform_role_audit(tenant_id,subject,platform_role,event_type,actor_type)"
                        + " values(?,?,'ADMIN','BOOTSTRAP_ADMIN_GRANTED','SYSTEM')", tenant, subject);
            }
        });
    }

    private static boolean valid(String value) {
        return !value.isBlank() && value.length() <= 128 && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }
}
