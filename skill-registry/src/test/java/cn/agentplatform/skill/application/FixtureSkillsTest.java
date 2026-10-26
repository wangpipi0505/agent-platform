package cn.agentplatform.skill.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.infra.SkillClients;

import com.fasterxml.jackson.databind.JsonNode;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;

/**
 * AP-S06 失败路径先行：技能侧 fixture 演示数据隔离。
 * 设计不变量——生产/默认模式（agent.demo-mode=false）下禁止注册 fixture 命名约定的技能；
 * 清理动作只命中 fixture 行（引用中的跳过、有发布历史的停用、纯草稿删除）并写审计。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class FixtureSkillsTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager tx;
    static String tenant;
    SkillClients clients = mock(SkillClients.class);
    SkillCatalog catalog = new SkillCatalog(db, tx, clients, "http://unused");
    Actor admin = new Actor(tenant, "skill-fixture-admin", Set.of("ADMIN"), Map.of());
    Actor editor = new Actor(tenant, "skill-fixture-editor", Set.of("EDITOR"), Map.of());

    @BeforeAll
    static void database() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12",
                        "agent_v12",
                        System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas("skill").defaultSchema("skill").load().migrate();
        db = new JdbcTemplate(ds);
        tx = new DataSourceTransactionManager(ds);
        tenant = "skill-fixture-test-" + UUID.randomUUID().toString().substring(0, 8);
        // SkillCatalog.create 内部经 Actor.current() 取租户，与 SkillExecutionTest 同法注入已验 JWT。
        var jwt =
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("skill-fixture-admin")
                        .claim("tenant_id", tenant)
                        .claim("roles", java.util.List.of("ADMIN", "EDITOR"))
                        .issuedAt(java.time.Instant.now())
                        .expiresAt(java.time.Instant.now().plusSeconds(600))
                        .build();
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.oauth2.server.resource.authentication
                                .JwtAuthenticationToken(jwt));
    }

    @AfterAll
    static void cleanup() {
        if (db == null) return;
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        db.update("delete from skill.connection_test where tenant_id=?", tenant);
        db.update("delete from skill.acceptance where tenant_id=?", tenant);
        // 平台不变量（V302 触发器 + skill_version 外键）：已发布版本契约不可删改，
        // 因此先删纯草稿版本，再只删已无版本引用的技能主行；发布行按原样留证（租户每次
        // 运行随机生成，不污染其他数据），审计行正常清理。
        db.update("delete from skill.skill_version where tenant_id=? and status='DRAFT'", tenant);
        db.update(
                "delete from skill.skill s where s.tenant_id=? and not exists (select 1 from"
                        + " skill.skill_version v where v.tenant_id=s.tenant_id and"
                        + " v.skill_code=s.code)",
                tenant);
        db.update("delete from skill.fixture_audit where tenant_id=?", tenant);
    }

    private String create(String code) {
        catalog.create(Map.of("code", code, "name", "测试技能", "category", "测试", "tags", List.of()));
        return code;
    }

    private void publish(String code) {
        db.update("update skill.skill set status='PUBLISHED' where tenant_id=? and code=?", tenant, code);
        db.update(
                "update skill.skill_version set status='PUBLISHED' where tenant_id=? and skill_code=?",
                tenant,
                code);
    }

    private FixtureSkills service(boolean demoMode) {
        return new FixtureSkills(db, catalog, clients, "http://unused", demoMode);
    }

    @Test
    void fixtureCodeDetectionFollowsNamingConvention() {
        assertThat(FixtureSkills.isFixtureCode("smoke_mcp_good_x")).isTrue();
        assertThat(FixtureSkills.isFixtureCode("smoke_write_ab12")).isTrue();
        assertThat(FixtureSkills.isFixtureCode("my_fixture_demo")).isTrue();
        assertThat(FixtureSkills.isFixtureCode("space_visibility")).isFalse();
        assertThat(FixtureSkills.isFixtureCode("openapi_ui_1")).isFalse();
        assertThat(FixtureSkills.isFixtureCode("invoice_parser")).isFalse();
    }

    @Test
    void nonDemoModeRejectsFixtureSkillCreation() {
        assertThatThrownBy(() -> service(false).assertCreatable("smoke_guard_reject"))
                .isInstanceOfSatisfying(
                        PlatformException.class,
                        e -> {
                            assertThat(e.status()).isEqualTo(409);
                            assertThat(e.code()).isEqualTo("SKILL-FIXTURE");
                        });
        // demo 模式放行；托管命名任何模式都放行。
        service(true).assertCreatable("smoke_guard_allow");
        service(false).assertCreatable("managed_business_skill");
    }

    @Test
    void purgeRequiresAdminHandlesDraftPublishedAndReferencedSeparately() {
        String draft = create("smoke_purge_draft_" + UUID.randomUUID().toString().substring(0, 6));
        String published = create("smoke_purge_pub_" + UUID.randomUUID().toString().substring(0, 6));
        publish(published);
        String referenced =
                create("smoke_purge_ref_" + UUID.randomUUID().toString().substring(0, 6));
        String managed = create("managed_keep_" + UUID.randomUUID().toString().substring(0, 6));
        when(clients.get(contains("/internal/skills/" + referenced + "/references")))
                .thenReturn(Json.tree(List.of(Map.of("appCode", "app_one"))));
        when(clients.get(contains("/internal/skills/" + draft + "/references")))
                .thenReturn(Json.tree(List.of()));
        when(clients.get(contains("/internal/skills/" + published + "/references")))
                .thenReturn(Json.tree(List.of()));
        when(clients.get(contains("/internal/skills/" + managed + "/references")))
                .thenReturn(Json.tree(List.of()));

        var service = service(false);
        assertThatThrownBy(() -> service.purge(editor))
                .isInstanceOfSatisfying(
                        PlatformException.class, e -> assertThat(e.status()).isEqualTo(403));

        var result = service.purge(admin);
        assertThat(((Number) result.get("removed")).intValue()).isEqualTo(1);
        assertThat(((Number) result.get("disabled")).intValue()).isEqualTo(1);
        // 同租户下其他用例创建的 fixture 草稿（未 stub 引用，默认拒绝）也会计入 skipped。
        assertThat(((Number) result.get("skipped")).intValue()).isGreaterThanOrEqualTo(1);

        // 按清理结果逐项核对：纯草稿删除；有发布历史停用；被引用跳过。
        @SuppressWarnings("unchecked")
        var outcomes = (Map<String, String>) result.get("skills");
        assertThat(outcomes.get(draft)).isEqualTo("REMOVED");
        assertThat(outcomes.get(published)).isEqualTo("DISABLED");
        assertThat(outcomes.get(referenced)).isEqualTo("SKIPPED_REFERENCED");

        // 纯草稿 fixture 被删除；有发布历史的停用保留；被应用引用的跳过；托管行不动。
        assertThat(skillCount(draft)).isZero();
        assertThat(skillStatus(published)).isEqualTo("DISABLED");
        assertThat(skillStatus(referenced)).isEqualTo("DRAFT");
        assertThat(skillStatus(managed)).isEqualTo("DRAFT");

        Integer audits =
                db.queryForObject(
                        "select count(*) from skill.fixture_audit where tenant_id=? and action='PURGE_SKILLS'",
                        Integer.class,
                        tenant);
        assertThat(audits).isEqualTo(1);
    }

    private int skillCount(String code) {
        return db.queryForObject(
                "select count(*) from skill.skill where tenant_id=? and code=?",
                Integer.class,
                tenant,
                code);
    }

    private String skillStatus(String code) {
        return db.queryForObject(
                "select status from skill.skill where tenant_id=? and code=?",
                String.class,
                tenant,
                code);
    }

    @Test
    void summaryReportsDemoModeAndCounts() {
        String code = create("smoke_purge_summary_" + UUID.randomUUID().toString().substring(0, 6));
        var summary = service(true).summary(admin);
        assertThat(summary.get("demoMode")).isEqualTo(true);
        assertThat((int) summary.get("fixtureCount")).isGreaterThanOrEqualTo(1);
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) summary.get("items");
        assertThat(items.stream().anyMatch(i -> code.equals(i.get("code")))).isTrue();
    }

    @Test
    void sourceDecorationMarksFixtureSkillsOnly() {
        String fixture = create("smoke_purge_mark_" + UUID.randomUUID().toString().substring(0, 6));
        String managed = create("managed_mark_" + UUID.randomUUID().toString().substring(0, 6));
        var decorated = FixtureSkills.withSource(catalog.list());
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) decorated;
        var fixtureRow =
                rows.stream().filter(r -> fixture.equals(r.get("code"))).findFirst().orElseThrow();
        var managedRow =
                rows.stream().filter(r -> managed.equals(r.get("code"))).findFirst().orElseThrow();
        assertThat(fixtureRow.get("source")).isEqualTo("FIXTURE");
        assertThat(managedRow.get("source")).isEqualTo("MANAGED");
        // 非 map 集合原样返回。
        assertThat(FixtureSkills.withSource(List.of("plain"))).isEqualTo(List.of("plain"));
    }
}
