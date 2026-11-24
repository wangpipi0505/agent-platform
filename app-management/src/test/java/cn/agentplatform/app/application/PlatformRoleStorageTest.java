package cn.agentplatform.app.application;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AGENT_DB_URL_ROLE_STORAGE", matches = ".+")
class PlatformRoleStorageTest {
    @Test
    void grantsRemainTenantScopedAndRuntimeMappingsUseTheFixedVocabulary() {
        var source = new DriverManagerDataSource(TestDatabase.url("AGENT_DB_URL_ROLE_STORAGE"),
                TestDatabase.user(), TestDatabase.password());
        var db = new JdbcTemplate(source);
        assertThat(db.queryForObject("select current_database()", String.class))
                .isEqualTo("agent_platform_role_storage_test");
        Flyway.configure().dataSource(source).schemas("app").defaultSchema("app")
                .locations("classpath:db/migration").outOfOrder(true).load().migrate();

        String subject = UUID.randomUUID().toString();
        String insert = "insert into app.platform_user_role(tenant_id,subject,platform_role,granted_by)"
                + " values(?,?,?,'authorizer')";
        db.update(insert, "tenant-a", subject, "BUILDER");
        db.update(insert, "tenant-b", subject, "BUILDER");
        assertThat(db.queryForObject(
                "select app_scope::text from app.platform_user_role where tenant_id=? and subject=?",
                String.class, "tenant-a", subject)).isEqualTo("[]");
        assertThatThrownBy(() -> db.update(insert, "tenant-a", subject, "BUILDER"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update(insert, "tenant-a", subject, "AGENT_ADMIN"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update(
                "update app.platform_user_role set status='REVOKED' where tenant_id=? and subject=?",
                "tenant-a", subject)).isInstanceOf(DataIntegrityViolationException.class);
        db.update("update app.platform_user_role set status='REVOKED',revoked_at=now()"
                + " where tenant_id=? and subject=?", "tenant-a", subject);
        assertThat(db.queryForObject(
                "select status from app.platform_user_role where tenant_id=? and subject=?",
                String.class, "tenant-b", subject)).isEqualTo("ACTIVE");

        String appCode = "r39-" + subject;
        db.update("insert into app.application(tenant_id,code,name,type,status,created_by)"
                + " values('tenant-a',?,'Fixture','CHAT','DRAFT','authorizer')", appCode);
        long credentialId = db.queryForObject("insert into app.integration_credential("
                        + "tenant_id,app_code,biz_system_code,secret_ref,secret_sha256,status,claim_limits,"
                        + "allowed_origins,issued_by,role_mapping) values('tenant-a',?,'client-biz','env:fixture',"
                        + "repeat('a',64),'ACTIVE','{}','[]','authorizer','{\"reader\":\"AGENT_VIEWER\"}') returning id",
                Long.class, appCode);
        assertThatThrownBy(() -> db.update("update app.integration_credential set role_mapping=?::jsonb where id=?",
                "{\"author\":\"AGENT_BUILDER\"}", credentialId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("update app.integration_credential set role_mapping=?::jsonb where id=?",
                "[]", credentialId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(db.queryForObject(
                "select role_mapping->>'reader' from app.integration_credential where id=?",
                String.class, credentialId)).isEqualTo("AGENT_VIEWER");
    }
}
