package cn.agentplatform.app.infra;

import cn.agentplatform.app.application.AssistantExperiences;
import cn.agentplatform.app.application.ConnectionProfiles;
import cn.agentplatform.app.application.IntegrationConfigReleases;
import cn.agentplatform.app.application.IntegrationProfiles;
import cn.agentplatform.app.application.PublishedMetadataService;
import cn.agentplatform.app.application.SemanticBindings;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
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
 * 真实 PostgreSQL 定向用例：语义绑定修订 → 配置发布钉住 → 校验/批准/激活 → 运行投影（SEM-02.2 阶段2）。
 *
 * <p>验证不能用内存替身证明的部分：发布行与引用行在同一事务写入；激活与运行投影都先看
 * 绑定父行当前状态（撤权优先于历史快照）；同 requestId 只产生一次写入；旧发布在新修订/
 * 停用之后仍能反查自己钉住的那一版，但不能再作为能力面。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class SemanticConfigWiringFlowTest {

    static final String RUN = Long.toString(System.currentTimeMillis() % 1_000_000L, 36);
    static final String TENANT = "t-" + RUN;
    static final AtomicInteger SEQ = new AtomicInteger();

    static JdbcTemplate db;
    static AssistantExperienceRepository experienceRepository;
    static AssistantExperiences experiences;
    static IntegrationProfileRepository profileRepository;
    static IntegrationProfiles profiles;
    static ConfigReleaseRepository releaseRepository;
    static ConnectionProfileRepository connectionRepository;
    static ConnectionProfiles connectionProfiles;
    static SemanticBindingRepository bindingRepository;
    static SemanticBindings bindings;
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
        bindingRepository = new SemanticBindingRepository(db, txm);
        bindings = new SemanticBindings(bindingRepository, writes);
        releases = new IntegrationConfigReleases(releaseRepository, experienceRepository, experiences,
                profileRepository, profiles, connectionRepository, writes,
                new PublishedMetadataService(db), bindings);
    }

    @AfterAll
    static void cleanup() {
        SecurityContextHolder.clearContext();
        if (db == null) {
            return;
        }
        db.update("delete from app.integration_config_release_activation where tenant_id=?", TENANT);
        // 引用行同时外键到发布与绑定修订；必须先删引用再删发布/修订，不能依赖级联。
        db.update("delete from app.integration_config_release_semantic_ref where tenant_id=?", TENANT);
        db.update("delete from app.integration_config_release where tenant_id=?", TENANT);
        db.update("delete from app.config_write_request where tenant_id=?", TENANT);
        db.update("delete from app.semantic_binding_revision where tenant_id=?", TENANT);
        db.update("delete from app.semantic_binding where tenant_id=?", TENANT);
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
    void releasePinsSemanticRevisionAndProjectsItAfterActivation() {
        Fixture f = fixture();
        String bindingId = binding(f.appCode());
        int experienceRev = approvedExperience(f.appCode());
        String profileId = approvedProfile(f.appCode(), experienceRev);

        Map<String, Object> body = releaseBody(f, experienceRev, profileId);
        body.put("semanticBindings", List.of(Map.of("bindingId", bindingId, "bindingRev", 1)));
        String releaseId = String.valueOf(releases.create(body).get("releaseId"));
        releases.validate(releaseId, Map.of("requestId", req("validate")));
        releases.approve(releaseId, Map.of("requestId", req("approve")));
        releases.activate(releaseId, Map.of("requestId", req("activate")));

        // 引用行与发布行同事务写入，且钉住的是声明时的修订（不是"当前最新"）。
        assertEquals(1, db.queryForObject("""
            select count(*) from app.integration_config_release_semantic_ref
             where tenant_id=? and release_id=? and binding_id=? and binding_rev=1
            """, Integer.class, TENANT, releaseId, bindingId));

        Map<String, Object> view = releases.get(releaseId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pinned = (List<Map<String, Object>>) view.get("semanticBindings");
        assertEquals(1, pinned.size());
        Map<String, Object> binding = pinned.get(0);
        assertEquals(bindingId, binding.get("bindingId"));
        assertEquals("ACTIVE", String.valueOf(binding.get("status")));
        assertEquals(1, ((Number) binding.get("bindingRev")).intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> releaseRef = (Map<String, Object>) binding.get("releaseRef");
        assertEquals("sp-" + RUN, releaseRef.get("spaceCode"));
        assertEquals("v7", releaseRef.get("version"));

        // 对外运行投影也带同一份精确引用；没有语义绑定时不伪造空数组之外的内容。
        Map<String, Object> projection = releases.experience(Actor.current(), f.appCode(), "production");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projected = (List<Map<String, Object>>) projection.get("semanticBindings");
        assertEquals(1, projected.size());
        assertEquals(bindingId, projected.get(0).get("bindingId"));
        assertEquals("ACTIVE", String.valueOf(projected.get(0).get("status")));
    }

    @Test
    void disablingBindingBlocksNewActivationButOldReleaseStaysTraceable() {
        Fixture f = fixture();
        String bindingId = binding(f.appCode());
        int experienceRev = approvedExperience(f.appCode());
        String profileId = approvedProfile(f.appCode(), experienceRev);

        String first = staged(f, experienceRev, profileId, bindingId);
        releases.activate(first, Map.of("requestId", req("activate")));
        bindings.disable(bindingId, Map.of("requestId", req("disable"), "expectedRev", 1));

        // 撤权优先于历史快照：旧发布仍可反查，但不能再作为能力面。
        Map<String, Object> old = releases.get(first);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> oldPinned = (List<Map<String, Object>>) old.get("semanticBindings");
        assertEquals("DISABLED", String.valueOf(oldPinned.get(0).get("status")));
        PlatformException blocked = assertThrows(PlatformException.class,
            () -> releases.experience(Actor.current(), f.appCode(), "production"));
        assertEquals("SEMANTIC_REF_INVALID", blocked.code());

        // 新发布即使把同一个（已停用）修订钉住，也不能通过校验/激活：撤权在最早一步就阻断。
        Map<String, Object> secondBody = releaseBody(f, experienceRev, profileId);
        secondBody.put("semanticBindings", List.of(Map.of("bindingId", bindingId, "bindingRev", 1)));
        String second = String.valueOf(releases.create(secondBody).get("releaseId"));
        PlatformException activation = assertThrows(PlatformException.class,
            () -> releases.validate(second, Map.of("requestId", req("validate"))));
        assertEquals("CONFIG_RELEASE_NOT_ACTIVE", activation.code());
        assertEquals(1, db.queryForObject("""
            select count(*) from app.integration_config_release_activation
             where tenant_id=? and app_code=? and scope='production'
            """, Integer.class, TENANT, f.appCode()));
    }

    @Test
    void sameRequestIdWritesOneReleaseAndOneReferenceSet() {
        Fixture f = fixture();
        String bindingId = binding(f.appCode());
        int experienceRev = approvedExperience(f.appCode());
        String profileId = approvedProfile(f.appCode(), experienceRev);

        Map<String, Object> body = releaseBody(f, experienceRev, profileId);
        body.put("semanticBindings", List.of(Map.of("bindingId", bindingId, "bindingRev", 1)));
        body.put("requestId", req("create"));
        Map<String, Object> first = releases.create(body);
        Map<String, Object> second = releases.create(body);

        assertEquals(false, first.get("replayed"));
        assertEquals(true, second.get("replayed"));
        assertEquals(first.get("releaseId"), second.get("releaseId"));
        assertEquals(1, db.queryForObject("""
            select count(*) from app.integration_config_release where tenant_id=? and release_id=?
            """, Integer.class, TENANT, first.get("releaseId")));
        assertEquals(1, db.queryForObject("""
            select count(*) from app.integration_config_release_semantic_ref
             where tenant_id=? and release_id=?
            """, Integer.class, TENANT, first.get("releaseId")));
    }

    @Test
    void unknownRevisionAndForeignBindingAreRejectedBeforeWrite() {
        Fixture f = fixture();
        String bindingId = binding(f.appCode());
        int experienceRev = approvedExperience(f.appCode());
        String profileId = approvedProfile(f.appCode(), experienceRev);

        Map<String, Object> missingRev = releaseBody(f, experienceRev, profileId);
        missingRev.put("semanticBindings", List.of(Map.of("bindingId", bindingId, "bindingRev", 2)));
        PlatformException unknown = assertThrows(PlatformException.class,
            () -> releases.create(missingRev));
        assertEquals("CONFIG_VERSION_UNKNOWN", unknown.code());

        // 另一应用名下的绑定不能被本应用借用，即使它确实存在。
        Fixture other = fixture();
        String foreign = binding(other.appCode());
        Map<String, Object> crossApp = releaseBody(f, experienceRev, profileId);
        crossApp.put("semanticBindings", List.of(Map.of("bindingId", foreign, "bindingRev", 1)));
        PlatformException conflict = assertThrows(PlatformException.class,
            () -> releases.create(crossApp));
        assertEquals("CONFIG_REV_CONFLICT", conflict.code());

        // 同一发布内重复引用同一绑定是声明错误，不允许"去重后继续"。
        Map<String, Object> duplicate = releaseBody(f, experienceRev, profileId);
        duplicate.put("semanticBindings", List.of(
            Map.of("bindingId", bindingId, "bindingRev", 1),
            Map.of("bindingId", bindingId, "bindingRev", 1)));
        PlatformException repeated = assertThrows(PlatformException.class,
            () -> releases.create(duplicate));
        assertEquals("INVALID_INPUT", repeated.code());

        assertEquals(0, db.queryForObject("""
            select count(*) from app.integration_config_release where tenant_id=? and app_code=?
            """, Integer.class, TENANT, f.appCode()));
    }

    @Test
    void newBindingRevisionDoesNotRewriteOlderRelease() {
        Fixture f = fixture();
        String bindingId = binding(f.appCode());
        int experienceRev = approvedExperience(f.appCode());
        String profileId = approvedProfile(f.appCode(), experienceRev);

        String releaseId = staged(f, experienceRev, profileId, bindingId);
        releases.activate(releaseId, Map.of("requestId", req("activate")));

        Map<String, Object> revision = bindingBody(f.appCode(), bindingId);
        revision.remove("bindingId");
        revision.remove("appCode");
        revision.put("expectedRev", 1);
        revision.put("requestId", req("revise"));
        revision.put("opCode", "task.recheck");
        bindings.revise(bindingId, revision);

        Map<String, Object> view = releases.get(releaseId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pinned = (List<Map<String, Object>>) view.get("semanticBindings");
        assertEquals(1, ((Number) pinned.get(0).get("bindingRev")).intValue());
        assertEquals("task.query", pinned.get(0).get("opCode"), "旧发布仍指向旧修订，不被新修订改写");
    }

    // ---------------------------------------------------------------- 夹具

    record Fixture(String appCode, int appVersion) {}

    private Fixture fixture() {
        String appCode = "app-" + RUN + "-" + SEQ.incrementAndGet();
        String candidate = sha("candidate-" + appCode);
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

    private String binding(String appCode) {
        String bindingId = "sb-" + RUN + "-" + SEQ.incrementAndGet();
        Map<String, Object> body = bindingBody(appCode, bindingId);
        Map<String, Object> out = bindings.create(body);
        assertEquals(1, ((Number) out.get("currentRev")).intValue());
        return bindingId;
    }

    private static Map<String, Object> bindingBody(String appCode, String bindingId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bindingId", bindingId);
        body.put("appCode", appCode);
        body.put("releaseRef", Map.of(
            "spaceCode", "sp-" + RUN,
            "releaseId", UUID.randomUUID().toString(),
            "version", "v7",
            "contentDigest", sha("bundle-" + bindingId)));
        body.put("extensionId", "ext-" + bindingId);
        body.put("extensionDigest", sha("extension-" + bindingId));
        body.put("opCode", "task.query");
        body.put("skill", Map.of(
            "skillCode", "customers.read",
            "skillVersion", 3,
            "skillSha256", sha("skill-" + bindingId)));
        body.put("profiles", List.of("read-only@1.0"));
        body.put("requestId", req("binding"));
        return body;
    }

    private int approvedExperience(String appCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "逾期账单助手");
        body.put("welcomeText", "我帮你找出逾期客户。");
        body.put("capabilityDescriptions", List.of(
            Map.of("code", "customers.read", "label", "customers.read", "readOnly", true)));
        body.put("promptRefs", List.of(Map.of("ref", "overdue-brief", "version", 2)));
        body.put("requestId", req("exp-create"));
        int rev = ((Number) experiences.create(appCode, body).get("experienceRev")).intValue();
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

    private Map<String, Object> releaseBody(Fixture f, int experienceRev, String profileId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("releaseId", "rel-" + RUN + "-" + SEQ.incrementAndGet());
        body.put("appCode", f.appCode());
        body.put("appVersion", f.appVersion());
        body.put("experienceRev", experienceRev);
        body.put("integrationProfileId", profileId);
        body.put("integrationProfileRev", 1);
        body.put("connectionProfileRefs", List.of());
        body.put("activationScope", "production");
        body.put("requestId", req("rel-create"));
        return body;
    }

    private String staged(Fixture f, int experienceRev, String profileId, String bindingId) {
        Map<String, Object> body = releaseBody(f, experienceRev, profileId);
        body.put("semanticBindings", List.of(Map.of("bindingId", bindingId, "bindingRev", 1)));
        String releaseId = String.valueOf(releases.create(body).get("releaseId"));
        releases.validate(releaseId, Map.of("requestId", req("validate")));
        releases.approve(releaseId, Map.of("requestId", req("approve")));
        return releaseId;
    }

    private static String req(String tag) {
        return "req-" + RUN + "-" + tag + "-" + SEQ.incrementAndGet();
    }

    private static String sha(String seed) {
        return Digest.sha256(seed);
    }

    private static void authenticate(String tenant) {
        var jwt = Jwt.withTokenValue("verified-semantic-admin").header("alg", "HS256")
            .subject("u-" + RUN).claim("tenant_id", tenant).claim("roles", List.of("ADMIN"))
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static void migrate(DriverManagerDataSource ds) throws Exception {
        try {
            Flyway.configure().dataSource(ds).schemas("app").defaultSchema("app")
                .locations("classpath:db/migration").load().migrate();
        } catch (FlywayException e) {
            try (var connection = ds.getConnection(); var statement = connection.createStatement()) {
                for (String table : List.of("integration_config_release_semantic_ref",
                    "integration_config_release_activation", "integration_config_release",
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
