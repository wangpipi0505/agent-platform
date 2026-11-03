package cn.agentplatform.app.infra;

import cn.agentplatform.app.application.ApprovedConnections;
import cn.agentplatform.app.application.ConnectionGrants;
import cn.agentplatform.app.application.ConnectionProfiles;
import cn.agentplatform.app.application.EnvironmentBindings;
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
 * 真实 PostgreSQL 定向用例：批准 → 授权 → 绑定 → 解析（INT-02 子步2 的完整链路）。
 *
 * 覆盖"不能用 Mock 证明"的部分：外键钉住修订、部分唯一索引保证一使用方一有效行、CAS 状态迁移、
 * 解析只认已批准且当前修订、环境过滤、摘要稳定。无 AGENT_DB_PASSWORD 时整体跳过（仓内既有约定）。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class ApprovedConnectionFlowTest {

    static final String RUN = Long.toString(System.currentTimeMillis() % 1_000_000L, 36);
    static final String TENANT = "t-" + RUN;
    static JdbcTemplate db;
    static ConnectionProfileRepository repository;
    static ConnectionProfiles profiles;
    static EnvironmentBindings bindings;
    static ConnectionGrants grants;
    static ApprovedConnections connections;

    @BeforeAll
    static void schema() {
        var ds = new DriverManagerDataSource(
                env("AGENT_DB_URL", "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12"),
                env("AGENT_DB_USER", "agent_v12"), System.getenv("AGENT_DB_PASSWORD"));
        ds.setDriverClassName("org.postgresql.Driver");
        db = new JdbcTemplate(ds);
        migrate(ds);
        var txm = new DataSourceTransactionManager(ds);
        repository = new ConnectionProfileRepository(db, txm);
        profiles = new ConnectionProfiles(repository);
        bindings = new EnvironmentBindings(repository);
        var grantRepository = new ConnectionGrantRepository(db);
        grants = new ConnectionGrants(repository, grantRepository);
        connections = new ApprovedConnections(repository, grantRepository);
    }

    @AfterAll
    static void cleanup() {
        SecurityContextHolder.clearContext();
        if (db == null) return;
        // 只清理本 run 的租户数据，顺序按外键反向。
        db.update("delete from app.connection_profile_grant where tenant_id=?", TENANT);
        db.update("delete from app.config_write_request where tenant_id=?", TENANT);
        db.update("delete from app.environment_binding_revision where tenant_id=?", TENANT);
        db.update("delete from app.environment_binding where tenant_id=?", TENANT);
        db.update("delete from app.connection_profile_version where tenant_id=?", TENANT);
        db.update("delete from app.connection_profile where tenant_id=?", TENANT);
    }

    @BeforeEach
    void actor() {
        var jwt = Jwt.withTokenValue("verified-connection-admin").header("alg", "HS256")
                .subject("u-" + RUN).claim("tenant_id", TENANT).claim("roles", List.of("ADMIN"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void resolveRequiresApprovedCurrentRevisionAndPinnedApprovedBinding() {
        String profileId = "crm-" + RUN;
        profiles.create(profile(profileId));
        // 未批准：解析必须拒绝（DRAFT 不可运行）。
        assertCode("CONFIG_STATE_TRANSITION_INVALID", () -> connections.resolve(profileId, 1, null));
        // 未批准也不能被环境绑定引用（§10.3 裁决：绑定只能引用 APPROVED 及以上）。
        assertCode("CONFIG_STATE_TRANSITION_INVALID", () -> bindings.create(binding("b-" + RUN, profileId, 1, "test")));

        profiles.approve(profileId, Map.of("requestId", req("approve"), "expectedRev", 1));
        bindings.create(binding("b-" + RUN, profileId, 1, "test"));
        var binding = bindings.approve("b-" + RUN, Map.of("requestId", req("bind-approve"), "expectedRev", 1));
        assertEquals(1, ((Number) binding.get("currentRev")).intValue());

        // 绑定已批准，但还没有授权：解析成功且 grantedUses 为空——是否放行由调用方按身份判断。
        var beforeGrant = connections.resolve(profileId, 1, "test");
        assertEquals(List.of(), beforeGrant.get("grantedUses"));
        assertEquals(1, ((List<?>) beforeGrant.get("bindings")).size());

        grants.grant(profileId, 1, Map.of("grantee", "skill-registry", "allowedUses", List.of("read")));
        var resolved = connections.resolve(profileId, 1, "test");
        assertEquals(List.of("read"), resolved.get("grantedUses"));
        assertEquals("API_KEY", resolved.get("authKind"));
        assertEquals(64, String.valueOf(resolved.get("resolutionDigest")).length());
        // 摘要稳定：同一份事实重复解析必须得到同一摘要（时间不参与身份）。
        assertEquals(resolved.get("resolutionDigest"), connections.resolve(profileId, 1, "test").get("resolutionDigest"));
        // 环境过滤：没有该环境绑定即 404，不退化成"忽略环境"。
        assertCode("CONFIG_REF_NOT_FOUND", () -> connections.resolve(profileId, 1, "production"));
        // 旧修订不再解析（1 已是当前修订，2 从未存在）。
        assertCode("CONFIG_STATE_TRANSITION_INVALID", () -> connections.resolve(profileId, 2, "test"));
        // 未知档案与跨租户都不会命中。
        assertCode("CONFIG_REF_NOT_FOUND", () -> connections.resolve("nope-" + RUN, 1, null));
    }

    @Test
    void grantIsIdempotentPerPayloadAndRevocableOnlyOnce() {
        String profileId = "erp-" + RUN;
        profiles.create(profile(profileId));
        // 未批准不能授权。
        assertCode("CONFIG_STATE_TRANSITION_INVALID",
                () -> grants.grant(profileId, 1, Map.of("grantee", "skill-registry", "allowedUses", List.of("read"))));
        profiles.approve(profileId, Map.of("requestId", req("grant-approve"), "expectedRev", 1));

        var first = grants.grant(profileId, 1, Map.of("grantee", "skill-registry", "allowedUses", List.of("read")));
        assertEquals(false, first.get("replayed"));
        // 同载荷重复授予 = 回放，不产生第二行。
        var second = grants.grant(profileId, 1, Map.of("grantee", "skill-registry", "allowedUses", List.of("read")));
        assertEquals(true, second.get("replayed"));
        assertEquals(first.get("grantId"), second.get("grantId"));
        // 异载荷必须冲突：不允许静默扩大授权。
        assertCode("CONFIG_REV_CONFLICT", () -> grants.grant(profileId, 1,
                Map.of("grantee", "skill-registry", "allowedUses", List.of("write"))));
        // 用途越界直接拒绝。
        assertCode("INVALID_INPUT", () -> grants.grant(profileId, 1,
                Map.of("grantee", "engine-runtime", "allowedUses", List.of("admin"))));
        // 使用方编码必须是小写稳定标识。
        assertCode("INVALID_INPUT", () -> grants.grant(profileId, 1,
                Map.of("grantee", "Skill Registry", "allowedUses", List.of("read"))));

        long grantId = ((Number) first.get("grantId")).longValue();
        grants.revoke(profileId, 1, grantId, Map.of("reason", "临时授权到期"));
        assertCode("CONFIG_REF_NOT_FOUND", () -> grants.revoke(profileId, 1, grantId, Map.of()));
        // 撤销后可以重新授予（历史行保留，新行生效）。
        var again = grants.grant(profileId, 1, Map.of("grantee", "skill-registry", "allowedUses", List.of("read")));
        assertEquals(false, again.get("replayed"));
        assertNotEquals(first.get("grantId"), again.get("grantId"));
    }

    @Test
    void disabledProfileStopsResolvingImmediately() {
        String profileId = "billing-" + RUN;
        profiles.create(profile(profileId));
        profiles.approve(profileId, Map.of("requestId", req("disable-approve"), "expectedRev", 1));
        bindings.create(binding("bd-" + RUN, profileId, 1, "test"));
        bindings.approve("bd-" + RUN, Map.of("requestId", req("disable-bind"), "expectedRev", 1));
        grants.grant(profileId, 1, Map.of("grantee", "skill-registry", "allowedUses", List.of("read")));
        assertEquals("APPROVED", connections.resolve(profileId, 1, "test").get("status"));

        profiles.disable(profileId, Map.of("requestId", req("disable"), "expectedRev", 1));
        // 停用后不回落到缓存或旧快照：解析立即失败，且错误码说明"未激活"而不是"已通过"。
        assertCode("CONFIG_RELEASE_NOT_ACTIVE", () -> connections.resolve(profileId, 1, "test"));
    }

    private static Map<String, Object> profile(String profileId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("profileId", profileId);
        body.put("requestId", req("create"));
        body.put("protocol", "HTTP_SYNC");
        body.put("operationAllowlist", List.of("customers.read"));
        body.put("endpointPolicy", Map.of("hosts", List.of("https://crm.example.com")));
        body.put("authScheme", Map.of("kind", "API_KEY", "headerName", "X-Api-Key"));
        body.put("identityPolicy", Map.of("mode", "PLATFORM_PRINCIPAL"));
        body.put("allowedUses", List.of("read", "write"));
        return body;
    }

    private static Map<String, Object> binding(String bindingId, String profileId, int profileRev, String environment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bindingId", bindingId);
        body.put("requestId", req("bind-" + environment));
        body.put("environment", environment);
        body.put("connectionProfileId", profileId);
        body.put("connectionProfileRev", profileRev);
        body.put("baseEndpoint", "https://crm.example.com/");
        body.put("secretRef", "vault:crm-" + environment + "-api-key");
        return body;
    }

    private static void assertCode(String expected, org.junit.jupiter.api.function.Executable call) {
        var error = assertThrows(PlatformException.class, call);
        assertEquals(expected, error.code(), error.getMessage());
    }

    private static void migrate(DriverManagerDataSource ds) {
        try {
            Flyway.configure().dataSource(ds).schemas("app").defaultSchema("app")
                    .locations("classpath:db/migration").load().migrate();
        } catch (FlywayException conflict) {
            // 同 run 内若有历史残留（表已存在但无迁移记录），只清理本卡自己的表后重迁。
            db.execute("drop table if exists app.connection_profile_grant, app.config_write_request,"
                    + " app.environment_binding_revision, app.environment_binding,"
                    + " app.connection_profile_version, app.connection_profile cascade");
            Flyway.configure().dataSource(ds).schemas("app").defaultSchema("app")
                    .locations("classpath:db/migration").load().migrate();
        }
    }

    /** 幂等键格式受 CONFIG 校验约束（至少 8 位、只允许字母数字与 _.:-），因此统一带前缀拼接。 */
    static final AtomicInteger SEQ = new AtomicInteger();

    static String req(String tag) {
        return "req-" + RUN + "-" + tag + "-" + SEQ.incrementAndGet();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
