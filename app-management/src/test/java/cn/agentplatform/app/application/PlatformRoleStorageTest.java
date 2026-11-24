package cn.agentplatform.app.application;

import cn.agentplatform.app.api.AppController;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.authority.AuthorityProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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

    @Test
    void aSingleVerifiedIdentityGainsAndLosesBuildAccessAsLocalGrantsChange() {
        var source = new DriverManagerDataSource(TestDatabase.url("AGENT_DB_URL_ROLE_STORAGE"),
                TestDatabase.user(), TestDatabase.password());
        Flyway.configure().dataSource(source).schemas("app").defaultSchema("app")
                .locations("classpath:db/migration").outOfOrder(true).load().migrate();
        var db = new JdbcTemplate(source);
        var access = new PlatformAccessService(db);
        var apps = mock(AppService.class);
        var controller = new AppController(apps, mock(TemplateService.class),
                new AuthorityProperties("iam", "app", null, null, null, null, null, false, List.of()), access);
        String subject = UUID.randomUUID().toString();
        var body = Map.<String, Object>of("name", "Updated");
        var jwt = Jwt.withTokenValue("verified-fixture").header("alg", "RS256")
                .subject(subject).claim("tenant_id", "tenant-a").claim("roles", List.of("ADMIN")).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            db.update("insert into app.platform_user_role(tenant_id,subject,platform_role,granted_by)"
                    + " values('tenant-b',?,'ADMIN','fixture')", subject);
            assertThatThrownBy(() -> controller.metadata("app-a", body))
                    .isInstanceOfSatisfying(PlatformException.class, error -> assertThat(error.status()).isEqualTo(403));
            db.update("insert into app.platform_user_role(tenant_id,subject,platform_role,app_scope,granted_by)"
                    + " values('tenant-a',?,'BUILDER','[\"app-a\"]','fixture')", subject);
            assertThat(access.session(Actor.current()).roles()).containsExactly("BUILDER");
            assertThat(access.session(Actor.current()).globalRoles()).isEmpty();
            assertThat(access.session(Actor.current()).capabilities()).doesNotContain("skill.manage", "plugin.manage");
            assertThat(access.scope(Actor.current(), PlatformAccessService.VIEW).codes())
                    .containsExactly("app-a");
            controller.metadata("app-a", body);
            verify(apps).metadata("app-a", body);
            assertThatThrownBy(() -> controller.metadata("app-b", body))
                    .isInstanceOfSatisfying(PlatformException.class, error -> assertThat(error.status()).isEqualTo(403));
            db.update("update app.platform_user_role set status='REVOKED',revoked_at=now()"
                    + " where tenant_id='tenant-a' and subject=?", subject);
            assertThatThrownBy(() -> controller.metadata("app-a", body))
                    .isInstanceOfSatisfying(PlatformException.class, error -> assertThat(error.status()).isEqualTo(403));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void managementChangesAreTenantBoundAuditedAndIdempotent() {
        var source = new DriverManagerDataSource(TestDatabase.url("AGENT_DB_URL_ROLE_STORAGE"),
                TestDatabase.user(), TestDatabase.password());
        Flyway.configure().dataSource(source).schemas("app").defaultSchema("app")
                .locations("classpath:db/migration").outOfOrder(true).load().migrate();
        var db = new JdbcTemplate(source);
        var access = new PlatformAccessService(db);
        var grants = new PlatformGrantService(db, access);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        String tenant = "r39-" + UUID.randomUUID();
        var admin = new Actor(tenant, "platform-admin", Set.of("USER"), Map.of());
        var user = new Actor(tenant, "operator", Set.of("ADMIN"), Map.of());
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                grants.save(user, user.principalId(), "ADMIN", List.of(), null)))
                .isInstanceOfSatisfying(PlatformException.class, error -> assertThat(error.status()).isEqualTo(403));
        db.update("insert into app.platform_user_role(tenant_id,subject,platform_role,granted_by)"
                + " values(?,?,'ADMIN','fixture')", tenant, admin.principalId());
        db.update("insert into app.application(tenant_id,code,name,type,status,created_by)"
                + " values(?,'local-app','Fixture','CHAT','DRAFT','fixture')", tenant);

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                grants.save(admin, user.principalId(), "BUILDER", List.of("foreign-app"), null)))
                .isInstanceOfSatisfying(PlatformException.class, error -> assertThat(error.status()).isEqualTo(400));
        tx.executeWithoutResult(status -> {
            grants.save(admin, user.principalId(), "BUILDER", List.of("local-app"), null);
            grants.save(admin, user.principalId(), "BUILDER", List.of("local-app"), null);
        });
        assertThat(grants.grants(admin, user.principalId())).singleElement().satisfies(grant -> {
            assertThat(grant.role()).isEqualTo("BUILDER");
            assertThat(grant.appScope()).containsExactly("local-app");
        });
        assertThat(access.session(user).pages()).contains("/apps");
        String page = "/platform-test-" + UUID.randomUUID();
        db.update("insert into app.capability_page(capability,page_route,updated_by) values('agent.build',?,'fixture')",
                page);
        try {
            assertThat(access.session(user).pages()).contains(page);
        } finally {
            db.update("delete from app.capability_page where capability='agent.build' and page_route=?", page);
        }
        assertThat(grants.configurationAudit(admin)).filteredOn(item -> String.valueOf(item).contains(page))
                .extracting(item -> item.get("operation")).containsExactly("DELETE", "INSERT");
        tx.executeWithoutResult(status -> {
            grants.revoke(admin, user.principalId(), "BUILDER");
            grants.revoke(admin, user.principalId(), "BUILDER");
        });
        assertThat(access.session(user).roles()).isEmpty();
        assertThat(grants.audit(admin, user.principalId())).extracting(row -> row.get("event_type"))
                .containsExactly("PLATFORM_GRANT_REVOKED", "PLATFORM_GRANT_SAVED");
    }
}
