package cn.agentplatform.app.application;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.PlatformException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AGENT_DB_URL_ROLE_STORAGE", matches = ".+")
class IntegrationCredentialVerificationTest {
    @Test
    void apiKeyVerificationUsesOnlyItsCredentialAndNeverReturnsSecretMaterial() {
        var source = new DriverManagerDataSource(TestDatabase.url("AGENT_DB_URL_ROLE_STORAGE"),
                TestDatabase.user(), TestDatabase.password());
        var db = new JdbcTemplate(source);
        assertThat(db.queryForObject("select current_database()", String.class))
                .isEqualTo("agent_platform_role_storage_test");
        Flyway.configure().dataSource(source).schemas("app").defaultSchema("app")
                .locations("classpath:db/migration").outOfOrder(true).load().migrate();
        var service = new IntegrationService(db, mock(AppService.class));
        var tenant = "r40-" + UUID.randomUUID();
        var secret = UUID.randomUUID().toString();
        db.update("insert into app.application(tenant_id,code,name,type,status,created_by)"
                + " values(?,'bound-app','Fixture','CHAT','DRAFT','fixture')", tenant);
        long id = db.queryForObject("""
                insert into app.integration_credential(tenant_id,app_code,biz_system_code,secret_ref,
                    secret_sha256,status,claim_limits,allowed_origins,issued_by,role_mapping)
                values(?,'bound-app','software-a','env:fixture',?,'ACTIVE',
                    '{"roles":["reader"]}','[]','fixture','{"reader":"AGENT_VIEWER"}') returning id
                """, Long.class, tenant, Digest.sha256(secret));

        var verified = service.verifyApiKey(id, secret);
        assertThat(verified).containsEntry("tenantId", tenant).containsEntry("appCode", "bound-app")
                .containsEntry("bizSystemCode", "software-a")
                .containsEntry("policyVersion", id + ":1")
                .containsKeys("claimLimits", "allowedOrigins", "roleMapping", "policyDigest")
                .doesNotContainKeys("secretRef", "secretSha256");
        assertThatThrownBy(() -> service.verifyApiKey(id, "wrong-key"))
                .isInstanceOfSatisfying(PlatformException.class, error -> assertThat(error.status()).isEqualTo(401));

        db.update("insert into app.platform_user_role(tenant_id,subject,platform_role,granted_by)"
                + " values(?,'integration-user','ADMIN','fixture')", tenant);
        assertThat(service.verifyApiKey(id, secret)).isEqualTo(verified);
        db.update("update app.integration_credential set policy_revision=policy_revision+1 where id=?", id);
        assertThat(service.verifyApiKey(id, secret)).containsEntry("policyVersion", id + ":2")
                .containsEntry("policyDigest", verified.get("policyDigest"));
        db.update("update app.integration_credential set status='REVOKED',revoked_at=now() where id=?", id);
        assertThatThrownBy(() -> service.verifyApiKey(id, secret))
                .isInstanceOfSatisfying(PlatformException.class, error -> assertThat(error.status()).isEqualTo(401));
    }
}
