package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.infra.LiveMaterialScopeDigest;
import cn.agentplatform.engine.infra.RunAuthorityStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * EX-26 服务级验收：生产恢复链 {@link RunAuthority#currentRetained(long)} 对<b>真实</b>
 * authority-service（默认 127.0.0.1:18086，iam 模式）与<b>真实</b> PG 跑通
 * resume（换票）→ authorize（采纳）→ recordCheck（落库）。
 *
 * <p>与协议级 {@code skill-registry ... Ex26AuthorityChainTest} 的分工：那个用例证明 authority-service
 * 自身能签/能验；本用例证明<b>生产消费方</b>的恢复路径（RunAuthority + RunAuthorityStore 的
 * strict/recordCheck/CAS）能用真实票据成立——即“重启后从库里读回绑定再换票”这条路不是只在进程内
 * Loopback 假 authority 下成立。
 *
 * <p>默认跳过（{@code EX26_LIVE_RESUME} 未置 true），因为离线构建不允许连网连库。真跑需要：
 * 真实 18086（iam，含 EX-44 的 {@code origin_authentication_id}）、真实 PG
 * {@code agent_platform_engine_test}、以及 {@code AGENT_DB_PASSWORD}/{@code AGENT_SECRET_IAM_*_CLIENT}。
 *
 * <p>不变量（缺一这条链就会被 fail-closed 拒绝，测试行的每个字段都由此推导）：
 * <ol>
 *   <li>{@code run_authority.initial_ceiling} 是<b>天花板 Actor 的规范形状</b>
 *       （tenantId/principalId/roles/attributes），不是票面 {@code initial_ceiling} 的 snake_case 形状；
 *       两者语义相同、序列化不同，RunAuthorityStore.parseInitial 只认前者。</li>
 *   <li>{@code grant_ref/grant_expires_at/initial_ceiling/origin_authentication_id/resource} 必须与
 *       authority 签出的票据逐字节一致：{@code strict(...)} 用 record equals 比对这些值。</li>
 *   <li>三个根材料范围（run-snapshot/run-inputs/run-version-refs）必须齐全，{@code scopes()} 要求
 *       恰好 3 行，且 material.version 都等于 accepted_snapshot_sha256。</li>
 *   <li>{@code engine.run_authority}/{@code engine.material_scope} 有不可变触发器：清理必须绕过触发器，
 *       这是它们“初始材料不可改写”的设计，不是测试可以放宽的地方（见 {@code cleanup()}）。</li>
 * </ol>
 */
@EnabledIfEnvironmentVariable(named = "EX26_LIVE_RESUME", matches = "true")
class RunAuthorityLiveResumeTest {

    private static final String BASE = value("EX26_AUTHORITY_BASE", "http://127.0.0.1:18086");
    private static final String URL =
            value("EX26_LIVE_DB_URL", "jdbc:postgresql://127.0.0.1:25432/agent_platform_engine_test");
    private static final String USER = value("AGENT_DB_USER", "agent_v12");
    private static final String APP_CODE = "app-a";
    private static final int VERSION_NO = 1;
    /** 真实票据的 resource 必须在 exchange 时就定下来；摘要用固定串即可（authority 只校验 64 位小写 hex）。 */
    private static final ResourceBinding ROOT =
            new ResourceBinding("app-version", APP_CODE + "@" + VERSION_NO, Digest.sha256("ex26-live-candidate"));
    /** 受理时的主调：发布版本上的 run 动作，由 gateway 换成 engine 可消费的 delegated 票。 */
    private static final ServiceCall MAIN =
            new ServiceCall("engine", "proxy.engine.post.RunController.run", ROOT);
    /** 恢复续接的本地边：engine→engine run.advance，只由 ServiceRoutePolicy.localRunAdvance 放行。 */
    private static final ServiceCall LOCAL = new ServiceCall("engine", "run.advance", ROOT);

    private static final Duration BACKOFF = Duration.ofSeconds(30);

    static DriverManagerDataSource dataSource;
    static JdbcTemplate db;
    static HttpClient http;
    /** 本用例插入的 run id：@AfterEach 逐条删除（含 material_scope/run_authority/run 三表）。 */
    final Set<Long> inserted = new LinkedHashSet<>();

    @BeforeAll
    static void database() {
        String password = System.getenv("AGENT_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            // 显式失败：否则 Postgres 会把“没有密码”报成连接错误，掩盖住真正的环境缺项。
            throw new IllegalStateException(
                    "EX26_LIVE_RESUME 需要 AGENT_DB_PASSWORD（以及 18086 的 AGENT_SECRET_IAM_ENGINE_CLIENT）");
        }
        dataSource = new DriverManagerDataSource(URL, USER, password);
        Flyway.configure()
                .dataSource(dataSource)
                .schemas("engine")
                .defaultSchema("engine")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(dataSource);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    /**
     * 清理自己插入的行。两张表都有 BEFORE UPDATE OR DELETE 触发器（V216），
     * {@code session_replication_role=replica} 是唯一无需改 DDL 的绕过方式；agent_v12 是本库超级用户，
     * 且整个删除在单事务内完成，失败即回滚，不会留下半行。
     */
    @AfterEach
    void cleanup() {
        if (inserted.isEmpty()) return;
        var ids = List.copyOf(inserted);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("set local session_replication_role = replica");
                for (long runId : ids) {
                    statement.executeUpdate("delete from engine.material_scope where run_id=" + runId);
                    statement.executeUpdate("delete from engine.run_authority where run_id=" + runId);
                    statement.executeUpdate("delete from engine.run where id=" + runId);
                }
            }
            connection.commit();
        } catch (SQLException e) {
            throw new AssertionError("清理 EX26_LIVE_RESUME 插入行失败，run_id=" + ids, e);
        } finally {
            inserted.clear();
        }
    }

    /**
     * 正例：真实 grant → 换票 → 采纳 → 落库。断言的每一条都对应恢复链上的一个 fail-closed 关卡。
     */
    @Test
    void currentRetainedResumesAuthorizesAndRecordsCheck() {
        Grant grant = openGrant();
        long runId = insertBoundRun(grant, grant.grantExpiresAt());
        var authority = new RunAuthority(new RunAuthorityStore(dataSource, Clock.systemUTC()),
                engineClient(BASE), Clock.systemUTC(), BACKOFF);

        // 换票本身也验一遍：票面必须是 delegated/engine/run.advance——这是 59ef2b42 修复的消费路径。
        var delegation = new DelegationRef(grant.grantRef(),
                new SubjectKey(grant.actor().tenantId(), grant.actor().principalId()), grant.grantExpiresAt());
        var ticket = engineClient(BASE).resume(delegation, LOCAL);
        JsonNode resumed = claims(ticket.value());
        assertEquals("delegated", resumed.get("authority_mode").asText(), "换出的票必须是 delegated 模式");
        assertEquals("engine", resumed.get("caller").asText());
        assertEquals("engine", resumed.get("target").asText());
        assertEquals("run.advance", resumed.get("scope").asText());
        assertEquals("urn:agent-platform:service:engine", resumed.get("aud").asText());

        var proof = authority.currentRetained(runId);

        assertNotNull(proof, "currentRetained 必须返回采纳结果而不是 null");
        var current = proof.current();
        assertNotNull(current, "proof.current() 必须非空");
        assertEquals("engine", current.caller(), "采纳后的 caller 是 engine（不是签票时的 gateway）");
        assertEquals("engine", current.call().target());
        assertEquals("run.advance", current.call().action());
        assertEquals(ROOT, current.call().resource(), "恢复只能续接同一发布版本，不能换资源");
        assertEquals("DELEGATED", current.operation().sourceKind(), "resume 签出的 operation 必须是 DELEGATED");
        assertEquals("PORTAL", current.operation().originKind(), "origin 必须仍是门户身份");
        assertTrue(proof.hasInitialProof(), "断言里必须带 initial_ceiling，否则 strict 会拒绝");
        assertEquals(grant.actor(), proof.requireInitialCeiling(), "天花板必须与 grant 逐字段相同");
        assertEquals(grant.origin(), proof.requireOriginAuthenticationId(), "origin 必须与 grant 相同");
        assertTrue(current.expiresAt().isAfter(Instant.now()), "采纳结果不能一拿到就过期");

        var row = db.queryForMap(
                "select last_decision_id,last_checked_at,blocked_reason,retry_not_before,check_version"
                        + " from engine.run_authority where run_id=?", runId);
        assertEquals(current.decisionId(), row.get("last_decision_id"), "recordCheck 必须记下本次采纳的决策");
        assertNotNull(row.get("last_checked_at"), "recordCheck 必须写检查时间");
        assertNull(row.get("blocked_reason"), "成功路径不能留下 blocked_reason");
        assertNull(row.get("retry_not_before"), "成功路径不能留下重试时间");
        assertEquals(1L, ((Number) row.get("check_version")).longValue(), "CAS 必须把 check_version 推进一格");
    }

    /**
     * 负例 1（时间）：库里读回的 grant_expires_at 已过去 → 不进入网络，直接记 AUTHORITY_EXPIRED。
     * 这条证明“恢复期过期”不是静默放行，而且错误码/落库原因一致。
     *
     * <p>注意 retry_not_before 在此路径<b>必须为空</b>：V216 的 CHECK 与 recordCheck 都规定只有可重试的
     * AUTHORITY_UNAVAILABLE 才带退避时间；过期是终态，给它一个重试时间反而是错误的可用性承诺。
     */
    @Test
    void expiredGrantRecordsAuthorityExpiredWithoutNetwork() {
        Grant grant = openGrant();
        long runId = insertBoundRun(grant, Instant.now().minusSeconds(60));
        var authority = new RunAuthority(new RunAuthorityStore(dataSource, Clock.systemUTC()),
                engineClient(BASE), Clock.systemUTC(), BACKOFF);

        var failure = assertThrows(PlatformException.class, () -> authority.currentRetained(runId));
        assertEquals(403, failure.status());
        assertEquals("AUTHORITY_EXPIRED", failure.code());

        var row = db.queryForMap(
                "select last_decision_id,blocked_reason,retry_not_before,check_version"
                        + " from engine.run_authority where run_id=?", runId);
        assertNull(row.get("last_decision_id"), "过期路径没有可采纳的决策");
        assertEquals("AUTHORITY_EXPIRED", row.get("blocked_reason"), "失败原因必须落库，不能只抛异常");
        assertNull(row.get("retry_not_before"), "终态失败不写重试时间（V216 CHECK 与 recordCheck 同步约束）");
        assertEquals(1L, ((Number) row.get("check_version")).longValue());
    }

    /**
     * 负例 2（可用性）：真实 grant 仍在有效期内，但客户端指向死端口 → 503 AUTHORITY_UNAVAILABLE，
     * 并写入 retry_not_before=checked+backoff。第二次调用在退避期内直接 503，不再触网也不再推进版本，
     * 这就是“失败不静默放行 + 重试有界”的证据。
     */
    @Test
    void unreachableAuthorityRecordsBoundedRetry() {
        Grant grant = openGrant();
        long runId = insertBoundRun(grant, grant.grantExpiresAt());
        var authority = new RunAuthority(new RunAuthorityStore(dataSource, Clock.systemUTC()),
                engineClient("http://127.0.0.1:1"), Clock.systemUTC(), BACKOFF);

        var failure = assertThrows(PlatformException.class, () -> authority.currentRetained(runId));
        assertEquals(503, failure.status());
        // 对外抛的是传输层的 IAM_AUTHORITY_UNAVAILABLE；RunAuthority 把它归一成落库原因 AUTHORITY_UNAVAILABLE。
        // 两者表示同一件事：调用方拿到 503，库里的 blocked_reason 是可判定的恢复状态。
        assertEquals("IAM_AUTHORITY_UNAVAILABLE", failure.code());

        var row = db.queryForMap(
                "select last_decision_id,blocked_reason,retry_not_before,check_version"
                        + " from engine.run_authority where run_id=?", runId);
        assertNull(row.get("last_decision_id"));
        assertEquals("AUTHORITY_UNAVAILABLE", row.get("blocked_reason"));
        assertNotNull(row.get("retry_not_before"), "可重试失败必须带退避截止时间");
        assertEquals(1L, ((Number) row.get("check_version")).longValue());

        var again = assertThrows(PlatformException.class, () -> authority.currentRetained(runId));
        assertEquals(503, again.status(), "退避期内仍是 503，不放过任何一笔请求");
        assertEquals(1L, ((Number) db.queryForObject(
                        "select check_version from engine.run_authority where run_id=?", Long.class, runId)).longValue(),
                "退避期内不再写库：失败不应把 CAS 版本推成噪声");
    }

    // ---------------------------------------------------------------- 真实票据

    /** 用真实 HTTP 拿到一条 delegation grant，并让 engine 先采纳一次以形成可被 resume 命中的决策。 */
    private Grant openGrant() {
        String portalToken = portalSession();
        String ticket = exchange(portalToken, ROOT);
        var client = engineClient(BASE);
        var admission = client.authorizeRetained(ticket, MAIN);
        admission.requireVerified();
        var current = admission.current();
        return new Grant(current.delegation().value(), current.delegation().expiresAt(),
                admission.requireInitialCeiling(), current.call().resource(),
                admission.requireOriginAuthenticationId(), current.decisionId(),
                current.authorizationVersion(), current.operation().operationId(), MAIN);
    }

    /** gateway 的门户会话：真实 RSA 签名，含 EX-44 的 origin（旧部署没有它，解不开就说明构建过期）。 */
    private static String portalSession() {
        JsonNode response = post("gateway", "/oauth/token", Json.write(Map.of("operation", "session")));
        String token = text(response, "access_token");
        JsonNode payload = claims(token);
        assertTrue(payload.hasNonNull("origin_authentication_id"),
                "18086 签出的会话票缺少 origin_authentication_id：运行实例早于 EX-44，重启后重跑");
        assertTrue(payload.get("origin_authentication_id").asText().matches("oa1:[0-9a-f]{32}"));
        return token;
    }

    /** gateway 把门户票换成 engine 可消费的 delegated 票（变体 A：普通 exchange）。 */
    private static String exchange(String portalToken, ResourceBinding root) {
        String body = Json.write(Map.of(
                "operation", "exchange",
                "subject_token", portalToken,
                "target", MAIN.target(),
                "action", MAIN.action(),
                "resource", Map.of("kind", root.kind(), "id", root.id(), "version_digest", root.versionDigest()),
                "nonce", "ex26-live-" + UUID.randomUUID()));
        return text(post("gateway", "/oauth/token", body), "ticket");
    }

    private static HttpCurrentAuthorityClient engineClient(String base) {
        var config = new AuthorityProperties("iam", "engine", null,
                new AuthorityProperties.Authority(base, base + "/.well-known/jwks.json",
                        base + "/oauth/token", base + "/internal/authorize", "engine",
                        "env:AGENT_SECRET_IAM_ENGINE_CLIENT", "RS256", "urn:agent-platform:service:engine",
                        60, 5, 1000, 2000),
                null, null, null, null, null);
        // testProfile=true：本机 http 端点允许；生产必须 https（AuthorityProperties.validate 的既有约束）。
        return new HttpCurrentAuthorityClient(config, Clock.systemUTC(),
                new WorkloadCredentialResolver(config, true), true);
    }

    // ---------------------------------------------------------------- 造最小 run + 绑定 + 材料范围

    /**
     * 造一条最小 run 与它的 run_authority/材料范围行。字段不是随便填的：每列都被
     * {@code RunAuthorityStore.load}/{@code MaterialScopeStore.scopes} 反向校验，注释标出必须与 grant 一致的部分。
     */
    private long insertBoundRun(Grant grant, Instant grantExpiresAt) {
        String publicId = "ex26-live-" + UUID.randomUUID();
        // engine.run：CHAT 触发要求 app_version_id 非空；status 用 PENDING；snapshot 供 run-snapshot 摘要。
        JsonNode snapshot = Json.tree(Map.of(
                "appCode", APP_CODE,
                "versionNo", VERSION_NO,
                "appVersionId", VERSION_NO,
                "candidateSha256", grant.root().versionDigest(),
                "config", Map.of()));
        long runId = db.queryForObject("""
                insert into engine.run(public_id,app_code,app_version_id,trigger_type,status,principal_id,
                  tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode,request_id)
                values (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,'FULL',?)
                returning id""", Long.class,
                publicId, APP_CODE, VERSION_NO, "CHAT", "PENDING",
                grant.actor().principalId(), grant.actor().tenantId(), "ex26-live",
                "{}", "{}", "{}", Json.write(snapshot), "{}", UUID.randomUUID().toString());
        inserted.add(runId);

        // accepted_snapshot_sha256 就是 run-snapshot 的 payload 摘要（真实 createBound 同样从 payload 算）。
        String snapshotSha = LiveMaterialScopeDigest.digest(db, "run-snapshot", runId);
        String initialSha = MaterialAuthorization.ceilingSha256(grant.actor());
        String initial = Json.write(grant.actor());

        // run_authority：grant_ref/grant_expires_at/initial_ceiling/resource_digest/root_id 必须与 grant 完全一致。
        db.update("""
                insert into engine.run_authority(run_id,tenant_id,principal_id,grant_ref,grant_expires_at,
                  initial_ceiling,resource_digest,source_kind,origin_authentication_id,app_version_id,
                  app_version_no,root_kind,root_id,initial_ceiling_sha256,accepted_snapshot_sha256,
                  admission_decision_id,authorization_version,operation_id)
                values (?,?,?,?,?,?::jsonb,?, 'PORTAL',?,?,?, 'app-version',?,?,?,?,?,?)""",
                runId, grant.actor().tenantId(), grant.actor().principalId(),
                grant.grantRef(), Timestamp.from(grantExpiresAt), initial,
                grant.root().versionDigest(), grant.origin(),
                VERSION_NO, VERSION_NO, grant.root().id(), initialSha, snapshotSha,
                grant.decisionId(), grant.authorizationVersion(), grant.operationId());

        // 三个根材料范围：scopes() 要求恰好 3 行，且 material.version 都等于 accepted_snapshot_sha256。
        var owner = new SubjectKey(grant.actor().tenantId(), grant.actor().principalId());
        var snapshotScope = scope("run-snapshot", publicId, snapshotSha,
                LiveMaterialScopeDigest.digest(db, "run-snapshot", runId), owner, grant, List.of());
        var inputsScope = scope("run-inputs", publicId, snapshotSha,
                LiveMaterialScopeDigest.digest(db, "run-inputs", runId), owner, grant, List.of());
        var refsScope = scope("run-version-refs", publicId, snapshotSha,
                LiveMaterialScopeDigest.digest(db, "run-version-refs", runId), owner, grant,
                List.of(snapshotScope.scopeSha256()));
        for (var scope : List.of(snapshotScope, inputsScope, refsScope)) {
            db.update("""
                    insert into engine.material_scope(tenant_id,producer,kind,material_id,material_version,
                      payload_sha256,scope_sha256,scope,run_id)
                    values (?,?,?,?,?,?,?,?::jsonb,?)""",
                    owner.tenantId(), scope.material().producer(), scope.material().kind(),
                    scope.material().id(), scope.material().version(), scope.material().payloadSha256(),
                    scope.scopeSha256(), Json.write(scope), runId);
        }
        return runId;
    }

    private static MaterialScopeV1 scope(String kind, String publicId, String version, String payloadSha,
            SubjectKey owner, Grant grant, List<String> parents) {
        var material = new MaterialRef("engine", kind, publicId, version, payloadSha);
        var entitlements = new TreeMap<>(grant.actor().attributes());
        // dataLabels 是义务不是权限集合（MaterialAuthorization.cover 的注释），因此不进 requiredEntitlements。
        entitlements.remove("dataLabels");
        var draft = new MaterialScopeV1(1, material, owner, grant.decisionId(), grant.authorizationVersion(),
                MaterialAuthorization.ceilingSha256(grant.actor()), grant.actor().roles(), entitlements,
                Set.of(), parents, null);
        // scopeSha256 不覆盖自身字段，先用 null 算摘要再回填——与 MaterialScopeStore.withDigest 同一算法。
        return new MaterialScopeV1(1, material, owner, grant.decisionId(), grant.authorizationVersion(),
                MaterialAuthorization.ceilingSha256(grant.actor()), grant.actor().roles(), entitlements,
                Set.of(), parents, MaterialAuthorization.scopeDigest(draft));
    }

    // ---------------------------------------------------------------- HTTP 辅助

    private static JsonNode post(String service, String path, String body) {
        String secret = System.getenv("AGENT_SECRET_IAM_" + service.toUpperCase(Locale.ROOT) + "_CLIENT");
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("缺少客户端密钥环境变量 AGENT_SECRET_IAM_"
                    + service.toUpperCase(Locale.ROOT) + "_CLIENT（值不落盘，见 .local/ex26-authority-run/env.sh）");
        }
        String authorization = "Basic " + Base64.getEncoder()
                .encodeToString((service + ":" + secret).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, response.statusCode(), () -> "POST " + path + " -> " + response.statusCode());
            return Json.read(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("POST " + BASE + path + " 失败（18086 是否在跑？）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("POST " + BASE + path + " 被中断", e);
        }
    }

    /** 票面只用于断言，不参与授权；解码失败即视为环境错误。 */
    private static JsonNode claims(String jwt) {
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length, "不是 JWS compact 序列化");
        return Json.read(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("响应缺少 " + field);
        }
        return value.textValue();
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 一条真实 grant：grant_ref/有效期/天花板/resource/origin 全部来自 authority 的签名断言，
     * 测试自己只负责“把库里的绑定写成与它逐字段一致”。
     */
    private record Grant(String grantRef, Instant grantExpiresAt, Actor actor, ResourceBinding root,
            String origin, String decisionId, String authorizationVersion, String operationId, ServiceCall admission) {}
}
