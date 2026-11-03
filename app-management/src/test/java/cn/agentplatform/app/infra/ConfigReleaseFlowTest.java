package cn.agentplatform.app.infra;

import cn.agentplatform.app.application.AssistantExperiences;
import cn.agentplatform.app.application.ConnectionProfiles;
import cn.agentplatform.app.application.PublishedMetadataService;
import cn.agentplatform.app.application.IntegrationConfigReleases;
import cn.agentplatform.app.application.IntegrationProfiles;
import cn.agentplatform.common.PlatformException;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 PostgreSQL 定向用例：体验描述 → 接入档案 → 配置发布 → 校验/批准/激活 → 对外体验投影（INT-03 子步1）。
 *
 * 覆盖不能用 Mock 证明的部分：外键把发布钉在具体修订上、激活指针的 CAS 与唯一 ACTIVE、
 * requestId 只产生一次写入、草稿体验不会因为被引用就自动对外可见。无 AGENT_DB_PASSWORD 时整体跳过。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class ConfigReleaseFlowTest {

    static final String RUN = Long.toString(System.currentTimeMillis() % 1_000_000L, 36);
    static final String TENANT = "t-" + RUN;
    static final String OTHER_TENANT = "t2-" + RUN;
    static final AtomicInteger SEQ = new AtomicInteger();

    static JdbcTemplate db;
    static AssistantExperienceRepository experienceRepository;
    static AssistantExperiences experiences;
    static IntegrationProfileRepository profileRepository;
    static IntegrationProfiles profiles;
    static ConfigReleaseRepository releaseRepository;
    static ConnectionProfileRepository connectionRepository;
    static ConnectionProfiles connectionProfiles;
    static IntegrationConfigReleases releases;

    @BeforeAll
    static void schema() throws Exception {
        var ds = new DriverManagerDataSource(
                env("AGENT_DB_URL", "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12"),
                env("AGENT_DB_USER", "agent_v12"), System.getenv("AGENT_DB_PASSWORD"));
        ds.setDriverClassName("org.postgresql.Driver");
        db = new JdbcTemplate(ds);
        migrate(ds);
        var txm = new DataSourceTransactionManager(ds);
        var writes = new ConfigWriteLog(db);
        experienceRepository = new AssistantExperienceRepository(db, txm);
        experiences = new AssistantExperiences(experienceRepository, writes);
        profileRepository = new IntegrationProfileRepository(db, txm);
        profiles = new IntegrationProfiles(profileRepository, experienceRepository, writes);
        releaseRepository = new ConfigReleaseRepository(db, txm);
        connectionRepository = new ConnectionProfileRepository(db, txm);
        connectionProfiles = new ConnectionProfiles(connectionRepository);
        releases = new IntegrationConfigReleases(releaseRepository, experienceRepository, experiences,
                profileRepository, profiles, connectionRepository, writes,
                new PublishedMetadataService(db));
    }

    @AfterAll
    static void cleanup() {
        SecurityContextHolder.clearContext();
        if (db == null) {
            return;
        }
        db.update("delete from app.integration_config_release_activation where tenant_id=?", TENANT);
        db.update("delete from app.integration_config_release where tenant_id=?", TENANT);
        db.update("delete from app.config_write_request where tenant_id=?", TENANT);
        db.update("delete from app.integration_profile_revision where tenant_id=?", TENANT);
        db.update("delete from app.integration_profile where tenant_id=?", TENANT);
        db.update("delete from app.assistant_experience_revision where tenant_id=?", TENANT);
        db.update("delete from app.assistant_experience where tenant_id=?", TENANT);
        db.update("delete from app.connection_profile_version where tenant_id=?", TENANT);
        db.update("delete from app.connection_profile where tenant_id=?", TENANT);
    }

    @BeforeEach
    void actor() {
        authenticate(TENANT);
    }

    @Test
    void publicExperienceOnlyComesFromActiveRelease() {
        Fixture f = fixture();
        int experienceRev = approvedExperience(f.appCode());
        String profileId = approvedProfile(f.appCode(), experienceRev);
        String releaseId = approvedRelease(f, experienceRev, profileId, "production");

        Map<String, Object> view = releases.experience(current(), f.appCode(), "production");
        assertEquals("v1", view.get("schemaVersion"));
        assertEquals(f.appCode(), view.get("appCode"));
        assertEquals(experienceRev, ((Number) view.get("experienceRev")).intValue());
        assertEquals("逾期账单助手", view.get("title"));
        @SuppressWarnings("unchecked")
        Map<String, Object> ref = (Map<String, Object>) view.get("configReleaseRef");
        assertEquals(releaseId, ref.get("configReleaseId"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> capabilities = (List<Map<String, Object>>) view.get("capabilities");
        assertEquals(1, capabilities.size(), "只有应用版本实际引用的能力对外可见");
        assertEquals("customers.read", capabilities.get(0).get("code"));
        assertEquals(true, capabilities.get(0).get("readOnly"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> prompts = (List<Map<String, Object>>) view.get("prompts");
        assertEquals(1, prompts.size());
        assertEquals("overdue-brief", prompts.get(0).get("ref"));
        assertEquals(2, ((Number) prompts.get(0).get("version")).intValue());

        releases.disable(releaseId, Map.of("requestId", req("disable")));
        PlatformException e = assertThrows(PlatformException.class,
            () -> releases.experience(current(), f.appCode(), "production"));
        assertEquals("CONFIG_RELEASE_NOT_ACTIVE", e.code());
    }

    @Test
    void activatingNewReleaseSupersedesPrevious() {
        Fixture f = fixture();
        int experienceRev = approvedExperience(f.appCode());
        String profileId = approvedProfile(f.appCode(), experienceRev);
        String first = stagedRelease(f, experienceRev, profileId, "production");
        String second = stagedRelease(f, experienceRev, profileId, "production");
        releases.activate(first, Map.of("requestId", req("activate-first")));
        releases.activate(second, Map.of("requestId", req("activate-second")));
        assertEquals("SUPERSEDED", String.valueOf(releases.get(first).get("status")));
        assertEquals("ACTIVE", String.valueOf(releases.get(second).get("status")));
        assertEquals(second, releases.experience(current(), f.appCode(), "production")
            .get("configReleaseRef") instanceof Map<?, ?> m ? m.get("configReleaseId") : null);
        // 同一作用域只允许一个 ACTIVE，指针重复推进不会产生第二个
        assertEquals(1, db.queryForObject("""
            select count(*) from app.integration_config_release
             where tenant_id=? and app_code=? and status='ACTIVE'
            """, Integer.class, TENANT, f.appCode()));
    }

    @Test
    void sameRequestIdWritesOnce() {
        Fixture f = fixture();
        int experienceRev = approvedExperience(f.appCode());
        String profileId = approvedProfile(f.appCode(), experienceRev);
        Map<String, Object> body = releaseBody(f, experienceRev, profileId, 1, "staging");
        Map<String, Object> first = releases.create(body);
        Map<String, Object> second = releases.create(body);
        assertEquals(false, first.get("replayed"));
        assertEquals(true, second.get("replayed"));
        assertEquals(first.get("releaseId"), second.get("releaseId"));
        assertEquals(1, db.queryForObject("""
            select count(*) from app.integration_config_release where tenant_id=? and release_id=?
            """, Integer.class, TENANT, first.get("releaseId")));
    }

    @Test
    void draftExperienceCannotBeReleased() {
        Fixture f = fixture();
        int experienceRev = draftExperience(f.appCode());
        String profileId = approvedProfile(f.appCode(), experienceRev);
        String releaseId = draftRelease(f, experienceRev, profileId);
        PlatformException e = assertThrows(PlatformException.class,
            () -> releases.validate(releaseId, Map.of("requestId", req("validate"))));
        assertEquals("CONFIG_STATE_TRANSITION_INVALID", e.code());
    }

    @Test
    void releaseIsInvisibleToAnotherTenant() {
        Fixture f = fixture();
        int experienceRev = approvedExperience(f.appCode());
        String profileId = approvedProfile(f.appCode(), experienceRev);
        String releaseId = approvedRelease(f, experienceRev, profileId, "production");
        authenticate(OTHER_TENANT);
        assertEquals("CONFIG_REF_NOT_FOUND",
            assertThrows(PlatformException.class, () -> releases.get(releaseId)).code());
    }

    @Test
    void projectionFollowsCurrentlyPublishedVersion() {
        Fixture f = fixture();
        int experienceRev = approvedExperience(f.appCode());
        String profileId = approvedProfile(f.appCode(), experienceRev);
        Map<String, Object> created = releases.create(
            releaseBody(f, experienceRev, profileId, 1, "production"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refs = (List<Map<String, Object>>) created.get("skillRefs");
        assertEquals(1, refs.size(), "发布回显钉住的精确技能引用");
        assertEquals("customers.read", refs.get(0).get("code"));
        assertEquals(3L, ((Number) refs.get(0).get("version")).longValue());
        releases.validate(String.valueOf(created.get("releaseId")), Map.of("requestId", req("validate")));
        releases.approve(String.valueOf(created.get("releaseId")), Map.of("requestId", req("approve")));
        releases.activate(String.valueOf(created.get("releaseId")), Map.of("requestId", req("activate")));

        // 应用重发第 2 版之后，钉在第 1 版的配置不再代表当前应用：投影失败关闭而不是继续对外承诺旧能力。
        db.update("""
            insert into app.app_version(tenant_id, app_code, version_no, app_id, app_draft_id, draft_rev,
              candidate_sha256, snapshot, eval_report_id, published_by)
            select tenant_id, app_code, 2, app_id, app_draft_id, draft_rev, ?, '{}'::jsonb, 'eval-2', ?
              from app.app_version where tenant_id=? and app_code=? and version_no=1
            """, sha("candidate-2-" + f.appCode()), "u-" + RUN, TENANT, f.appCode());
        db.update("update app.application set current_version_no=2 where tenant_id=? and code=?",
            TENANT, f.appCode());
        PlatformException stale = assertThrows(PlatformException.class,
            () -> releases.experience(current(), f.appCode(), "production"));
        assertEquals("CONFIG_RELEASE_NOT_ACTIVE", stale.code());
    }

    @Test
    void disablingReferencedConnectionBlocksActivation() {
        Fixture f = fixture();
        int experienceRev = approvedExperience(f.appCode());
        String profileId = approvedProfile(f.appCode(), experienceRev);
        String connectionId = approvedConnection();

        // 正例：引用仍有效时可以激活。
        String live = stagedRelease(f, experienceRev, profileId, "staging", connectionId);
        assertEquals("ACTIVE", String.valueOf(
            releases.activate(live, Map.of("requestId", req("activate"))).get("status")));

        // 负例：引用方被停用之后，另一份发布不能带着它上线。
        String blocked = stagedRelease(f, experienceRev, profileId, "production", connectionId);
        connectionProfiles.disable(connectionId,
            Map.of("requestId", req("conn-disable"), "expectedRev", 1));
        PlatformException e = assertThrows(PlatformException.class,
            () -> releases.activate(blocked, Map.of("requestId", req("activate"))));
        assertEquals("CONFIG_RELEASE_NOT_ACTIVE", e.code());
        assertEquals(0, db.queryForObject("""
            select count(*) from app.integration_config_release_activation
             where tenant_id=? and app_code=? and scope='production'
            """, Integer.class, TENANT, f.appCode()));
    }

    @Test
    void capabilityWithoutSkillReferenceBlocksValidation() {
        Fixture f = fixture();
        int experienceRev = approvedExperience(f.appCode(), "customers.read", "ledger.write");
        String profileId = approvedProfile(f.appCode(), experienceRev);
        String releaseId = draftRelease(f, experienceRev, profileId);
        PlatformException e = assertThrows(PlatformException.class,
            () -> releases.validate(releaseId, Map.of("requestId", req("validate"))));
        assertEquals("CONFIG_STATE_TRANSITION_INVALID", e.code());
    }

    // ---------------------------------------------------------------- 夹具

    record Fixture(String appCode, int appVersion) {}

    private Fixture fixture() {
        String appCode = "app-" + RUN + "-" + SEQ.incrementAndGet();
        String candidate = sha("candidate-" + appCode);
        // V102 之后应用主实体带自增 id，草稿、版本与技能引用都用它做外键。
        // 配置发布钉住的是"某一次已发布版本"，所以夹具先把应用置为已发布、指针指向第 1 版。
        Integer appId = db.queryForObject("""
            insert into app.application(tenant_id, code, name, type, status, current_version_no, created_by)
            values (?, ?, ?, 'CHAT', 'PUBLISHED', 1, ?) returning id
            """, Integer.class, TENANT, appCode, "应用 " + appCode, "u-" + RUN);
        Integer draftId = db.queryForObject("""
            insert into app.app_draft(tenant_id, app_id, app_code, draft_rev)
            values (?, ?, ?, 1) returning id
            """, Integer.class, TENANT, appId, appCode);
        Integer versionId = db.queryForObject("""
            insert into app.app_version(tenant_id, app_code, version_no, app_id, app_draft_id, draft_rev,
              candidate_sha256, snapshot, eval_report_id, published_by)
            values (?, ?, 1, ?, ?, 1, ?, '{}'::jsonb, 'eval-1', ?) returning id
            """, Integer.class, TENANT, appCode, appId, draftId, candidate, "u-" + RUN);
        db.update("""
            insert into app.skill_reference(tenant_id, app_code, app_version_no, app_version_id,
              skill_code, skill_version)
            values (?, ?, 1, ?, 'customers.read', 3)
            """, TENANT, appCode, versionId);
        db.update("""
            insert into app.prompt_template(tenant_id, code, name) values (?, 'overdue-brief', '逾期摘要')
            on conflict do nothing
            """, TENANT);
        db.update("""
            insert into app.prompt_template_version(tenant_id, template_code, version_no, status, content,
              variables, sha256, published_by, published_at)
            values (?, 'overdue-brief', 2, 'PUBLISHED', '模板内容', '["customer"]'::jsonb, ?, ?, now())
            on conflict do nothing
            """, TENANT, sha("prompt"), "u-" + RUN);
        return new Fixture(appCode, 1);
    }

    private int draftExperience(String appCode) {
        Map<String, Object> body = experienceBody();
        body.put("requestId", req("exp-create"));
        return ((Number) experiences.create(appCode, body).get("experienceRev")).intValue();
    }

    private int approvedExperience(String appCode) {
        int rev = draftExperience(appCode);
        experiences.approve(appCode, Map.of("requestId", req("exp-approve"), "expectedRev", rev));
        return rev;
    }

    private String approvedProfile(String appCode, int experienceRev) {
        String profileId = "ip-" + RUN + "-" + SEQ.incrementAndGet();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("profileId", profileId);
        profile.put("appCode", appCode);
        profile.put("bizSystemCode", "crm");
        profile.put("environment", "production");
        profile.put("mode", "L2");
        profile.put("allowedOrigins", List.of("https://crm.example.com"));
        profile.put("experienceRef", Map.of("appCode", appCode, "experienceRev", experienceRev));
        profile.put("requestId", req("ip-create"));
        profiles.create(profile);
        profiles.approve(profileId, Map.of("requestId", req("ip-approve"), "expectedRev", 1));
        return profileId;
    }

    private String draftRelease(Fixture f, int experienceRev, String profileId) {
        return String.valueOf(releases.create(releaseBody(f, experienceRev, profileId, 1, "production"))
            .get("releaseId"));
    }

    /** 走到 APPROVED，激活由调用方决定顺序，便于验证"激活新发布使旧发布退位"。 */
    private String stagedRelease(Fixture f, int experienceRev, String profileId, String scope) {
        String releaseId = String.valueOf(releases.create(
            releaseBody(f, experienceRev, profileId, 1, scope)).get("releaseId"));
        releases.validate(releaseId, Map.of("requestId", req("validate")));
        releases.approve(releaseId, Map.of("requestId", req("approve")));
        return releaseId;
    }

    private String approvedRelease(Fixture f, int experienceRev, String profileId, String scope) {
        String releaseId = stagedRelease(f, experienceRev, profileId, scope);
        releases.activate(releaseId, Map.of("requestId", req("activate")));
        return releaseId;
    }

    private Map<String, Object> releaseBody(Fixture f, int experienceRev, String profileId, int profileRev,
        String scope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("releaseId", "rel-" + RUN + "-" + SEQ.incrementAndGet());
        body.put("appCode", f.appCode());
        body.put("appVersion", f.appVersion());
        body.put("experienceRev", experienceRev);
        body.put("integrationProfileId", profileId);
        body.put("integrationProfileRev", profileRev);
        body.put("connectionProfileRefs", List.of());
        body.put("activationScope", scope);
        body.put("requestId", req("rel-create"));
        return body;
    }

    private static Map<String, Object> experienceBody() {
        return experienceBody("customers.read");
    }

    private String approvedConnection() {
        String profileId = "cp-" + RUN + "-" + SEQ.incrementAndGet();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("profileId", profileId);
        body.put("protocol", "HTTP_SYNC");
        body.put("operationAllowlist", List.of("customers.read"));
        body.put("endpointPolicy", Map.of("hosts", List.of("https://crm.example.com")));
        body.put("authScheme", Map.of("kind", "API_KEY", "headerName", "X-Api-Key"));
        body.put("identityPolicy", Map.of("mode", "PLATFORM_PRINCIPAL"));
        body.put("allowedUses", List.of("read"));
        body.put("requestId", req("conn-create"));
        connectionProfiles.create(body);
        connectionProfiles.approve(profileId,
            Map.of("requestId", req("conn-approve"), "expectedRev", 1));
        return profileId;
    }

    private int approvedExperience(String appCode, String... capabilityCodes) {
        Map<String, Object> body = experienceBody(capabilityCodes);
        body.put("requestId", req("exp-create"));
        int rev = ((Number) experiences.create(appCode, body).get("experienceRev")).intValue();
        experiences.approve(appCode, Map.of("requestId", req("exp-approve"), "expectedRev", rev));
        return rev;
    }

    private String stagedRelease(Fixture f, int experienceRev, String profileId, String scope,
        String connectionId) {
        Map<String, Object> body = releaseBody(f, experienceRev, profileId, 1, scope);
        body.put("connectionProfileRefs",
            List.of(Map.of("profileId", connectionId, "profileRev", 1)));
        String releaseId = String.valueOf(releases.create(body).get("releaseId"));
        releases.validate(releaseId, Map.of("requestId", req("validate")));
        releases.approve(releaseId, Map.of("requestId", req("approve")));
        return releaseId;
    }

    private static Map<String, Object> experienceBody(String... capabilityCodes) {
        List<Map<String, Object>> capabilities = new ArrayList<>();
        for (String code : capabilityCodes) {
            capabilities.add(Map.of("code", code, "label", code, "readOnly", true));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "逾期账单助手");
        body.put("welcomeText", "我帮你找出逾期客户。");
        body.put("capabilityDescriptions", capabilities);
        body.put("promptRefs", List.of(Map.of("ref", "overdue-brief", "version", 2)));
        return body;
    }

    private static cn.agentplatform.common.Actor current() {
        return cn.agentplatform.common.Actor.current();
    }

    private static String req(String tag) {
        return "req-" + RUN + "-" + tag + "-" + SEQ.incrementAndGet();
    }

    private static String sha(String seed) {
        return cn.agentplatform.common.Digest.sha256(seed);
    }

    private static void authenticate(String tenant) {
        var jwt = Jwt.withTokenValue("verified-config-admin").header("alg", "HS256")
            .subject("u-" + RUN).claim("tenant_id", tenant).claim("roles", List.of("ADMIN"))
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static void migrate(DriverManagerDataSource ds) throws Exception {
        try {
            Flyway.configure().dataSource(ds).schemas("app").defaultSchema("app")
                .locations("classpath:db/migration").load().migrate();
        } catch (FlywayException e) {
            // 自愈：本卡的表若因前一次失败留下结构而没有迁移历史，先清掉本卡自己的表再迁移。
            try (var connection = ds.getConnection(); var statement = connection.createStatement()) {
                for (String table : List.of("integration_config_release_activation", "integration_config_release",
                    "integration_profile_revision", "integration_profile", "assistant_experience_revision",
                    "assistant_experience")) {
                    statement.executeUpdate("drop table if exists app." + table + " cascade");
                }
            }
            Flyway.configure().dataSource(ds).schemas("app").defaultSchema("app")
                .locations("classpath:db/migration").load().migrate();
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
