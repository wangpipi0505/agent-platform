package cn.agentplatform.common.authority;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.infra.ServiceTickets;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EX-26 真实链路：一个已在运行的 authority-service（18086）+ 六个登记客户端密钥。
 *
 * <p>为什么不是 MockMvc：EX-26 的判据是"服务真的接受了 IAM 配置并经真实 IAM 签发/采纳票据"，
 * 所以这里对 18086 做真实 HTTP，票和断言都由服务用它的 RSA 私钥签出，再由平台自己的
 * {@link HttpCurrentAuthorityClient} 用 JWKS 验签后使用。测试本身不签任何 RS256 字节。
 *
 * <p>为什么前 3 步（session → authorize → forward）是手写 HTTP：平台侧
 * {@code CurrentAuthorityClient} 没有"用门户 subject_token 起步"的方法（{@code authorize} 只接受
 * 已经是 delegated 的票），{@code exchange/exchange_integration/resume} 对应的 operation 在
 * authority-service 里尚未实现。所以起步三步按口径 B 的线上契约发原始请求，拿到票之后交给真实平台
 * 类（{@link HttpCurrentAuthorityClient#authorize}、{@link ServiceTickets}、
 * {@link IamServiceTicketProvider}）继续，链路后续全部由生产代码驱动。
 *
 * <p>开关：{@code EX26_AUTHORITY_CHAIN=true} 才执行；{@code EX26_EXPECT=delegation-on|delegation-off}
 * 区分两次运行。默认构建里这个类整体跳过，不会让离线单测连网。
 */
@EnabledIfEnvironmentVariable(named = "EX26_AUTHORITY_CHAIN", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ex26AuthorityChainTest {

    private static final String BASE = value("EX26_AUTHORITY_BASE", "http://127.0.0.1:18086");
    private static final String DIGEST = "0123456789abcdef".repeat(4);
    /** delegated 链上四跳必须完全相同的资源：forward 一旦换资源就是越权。 */
    private static final ResourceBinding SKILL_RESOURCE =
            new ResourceBinding("skill-definition", "ex26-demo-skill@1", DIGEST);
    private static final ResourceBinding ORIGIN_RESOURCE =
            new ResourceBinding("integration-origin", "ex26.origin", DIGEST);
    /** 目录里 gateway→skill 的 delegated 行。 */
    private static final String GATEWAY_TO_SKILL = "proxy.skill.post.SkillController.create";
    /** 目录里 skill→engine 的 delegated 行（RunController 的 wake 回调）。 */
    private static final String SKILL_TO_ENGINE = "run.wake";
    private static final String ENGINE_BASE = value("EX26_ENGINE_BASE", "http://127.0.0.1:18082");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    // ------------------------------------------------------------------ (a) workload

    @Test
    @Order(1)
    @EnabledIfEnvironmentVariable(named = "EX26_EXPECT", matches = "delegation-on")
    void a_workload_ticket_is_issued_by_client_and_accepted_by_app() {
        HttpCurrentAuthorityClient gateway = client("gateway");
        HttpCurrentAuthorityClient app = client("app");
        ServiceCall call = new ServiceCall("app", "integration.origin.check", ORIGIN_RESOURCE);

        ServiceTicket ticket = gateway.client(call);
        JsonNode claims = claims(ticket.value());
        System.out.println("[a-1] POST /oauth/token operation=client client=gateway target=app action=integration.origin.check");
        System.out.println("[a-1] http=200 ticket.header=" + header(ticket.value(), "typ") + "|" + header(ticket.value(), "alg")
                + "|kid=" + header(ticket.value(), "kid") + " ticket.len=" + ticket.value().length()
                + " expiresAt=" + ticket.expiresAt());
        System.out.println("[a-1] claims=" + show(claims, "iss", "aud", "sub", "caller", "client_id",
                "scope", "authority_mode", "iat", "exp", "jti"));
        assertEquals("at+jwt", header(ticket.value(), "typ"));
        assertEquals(BASE, claims.path("iss").asText());
        assertEquals("urn:agent-platform:service:app", claims.path("aud").asText());
        // workload 票的 sub 就是服务名本身（不是 urn）：见 HttpCurrentAuthorityClient.verifiedTicket 的
        // `same(config.serviceId(), c("sub"))` 工作负载分支——它比对的是裸 serviceId。
        assertEquals("gateway", claims.path("sub").asText());
        assertEquals("gateway", claims.path("caller").asText());
        assertEquals("integration.origin.check", claims.path("scope").asText());
        assertEquals("workload", claims.path("authority_mode").asText());
        assertEquals(ORIGIN_RESOURCE.kind(), claims.path("resource").path("kind").asText());
        assertTrue(claims.path("exp").asLong() - claims.path("iat").asLong() <= 60, "ticket TTL 必须 <= 60s");

        WorkloadAuthority adopted = app.authorizeWorkload(ticket.value(), call);
        System.out.println("[a-2] POST /internal/authorize operation=authorize_workload client=app");
        System.out.println("[a-2] http=200 caller=" + adopted.caller() + " decisionId=" + mask(adopted.decisionId())
                + " checkedAt=" + adopted.checkedAt() + " expiresAt=" + adopted.expiresAt());
        assertEquals("gateway", adopted.caller(), "app 采纳的票必须仍记 gateway 为调用方");
    }

    @Test
    @Order(2)
    @EnabledIfEnvironmentVariable(named = "EX26_EXPECT", matches = "delegation-on")
    void a_negatives_route_and_mode_are_enforced() {
        HttpCurrentAuthorityClient gateway = client("gateway");
        // 目录里没有 gateway→engine/run.wake 这一行：必须由签发侧拒绝，而不是签出更宽的票。
        ServiceCall unregistered = new ServiceCall("engine", SKILL_TO_ENGINE, SKILL_RESOURCE);
        PlatformException routeDenied = assertThrows(PlatformException.class, () -> gateway.client(unregistered));
        System.out.println("[a-3] gateway.client target=engine action=run.wake -> " + routeDenied.status()
                + " code=" + routeDenied.code() + " message=" + routeDenied.getMessage());
        assertEquals(403, routeDenied.status());
        // 签发侧拒绝的是路由（authority 审计 reason=目录无 workload 行，code=SYS-AUTH-403）；
        // 平台侧的 HttpCurrentAuthorityClient 会把它统一映射成 IAM_AUTHORITY_FORBIDDEN
        // （admin-common/.../BoundedIamHttp.java:79 case 403），边界的拒绝信号没有被吞掉。
        assertEquals("IAM_AUTHORITY_FORBIDDEN", routeDenied.code());

        // workload 票不能当 delegated 票用（两种 authority_mode 是不同信任等级，不得互相升级）。
        // 场景：把 gateway→app 的 workload 票重放到 app 的"完整授权"入口，企图换一个长效 delegated 授权。
        // 受众/范围校验都通过（票确实是给 app 的），拒绝必须来自授权层的 authority_mode 判定。
        HttpCurrentAuthorityClient skill = client("skill");
        HttpCurrentAuthorityClient app = client("app");
        ServiceTicket workloadTicket = gateway.client(new ServiceCall("app", "integration.origin.check", ORIGIN_RESOURCE));
        PlatformException modeDenied = assertThrows(PlatformException.class, () ->
                app.authorize(workloadTicket.value(), new ServiceCall("app", "integration.origin.check", ORIGIN_RESOURCE)));
        System.out.println("[a-4] skill.authorize(workload ticket) -> " + modeDenied.status()
                + " code=" + modeDenied.code() + " message=" + modeDenied.getMessage()
                + " (workload ticket authority_mode=" + claims(workloadTicket.value()).path("authority_mode").asText() + ")");
        assertEquals(403, modeDenied.status());
        assertEquals("SYS-AUTH-403", modeDenied.code());

        // AUTHORITY 模式缺授权上下文必须 403，绝不回落 LEGACY 自签：这里断言的是"没有票"而不是"有票"。
        ServiceTickets tickets = new ServiceTickets(ServiceTickets.AUTHORITY,
                new IamServiceTicketProvider(Clock.systemUTC(), skill), null,
                Map.of("engine", ENGINE_BASE));
        PlatformException noContext = assertThrows(PlatformException.class, () ->
                tickets.ticket(new Actor("t-ex26", "u-ex26", java.util.Set.of("operator"), Map.of()),
                        "skill", "POST", ENGINE_BASE + "/internal/runs/run-ex26/wake"));
        System.out.println("[a-5] ServiceTickets(AUTHORITY).ticket(no bound authority) -> " + noContext.status()
                + " code=" + noContext.code() + " message=" + noContext.getMessage() + " legacy_fallback=false");
        assertEquals(403, noContext.status());
        assertEquals("AUTHORITY_CONTEXT_MISSING", noContext.code());
    }

    // ------------------------------------------------------------------ (b) delegated

    @Test
    @Order(3)
    @EnabledIfEnvironmentVariable(named = "EX26_EXPECT", matches = "delegation-on")
    void b_delegated_chain_session_authorize_forward_is_adopted() throws Exception {
        // b-1 门户会话：authority 用 user-mgmt 换取身份并签 portal 域 token。
        Reply session = post("gateway", "/oauth/token", Map.of("operation", "session"));
        assertEquals(200, session.status, "session 必须 200：" + session.body);
        String subjectToken = session.body.path("access_token").asText();
        JsonNode subject = claims(subjectToken);
        System.out.println("[b-1] POST /oauth/token operation=session client=gateway -> http=" + session.status
                + " token_use=" + subject.path("token_use").asText()
                + " sub=" + mask(subject.path("sub").asText())
                + " tenant=" + mask(subject.path("tenant_id").asText())
                + " roles=" + subject.path("roles") + " scope=" + subject.path("scope").asText()
                + " iss=" + subject.path("iss").asText() + " aud=" + subject.path("aud"));

        // b-2 gateway 用 subject_token 换取 delegated 授权（决策 + 授权），这是整条链的信任起点。
        Map<String, Object> authorize = base("authorize", "skill", GATEWAY_TO_SKILL, SKILL_RESOURCE);
        authorize.put("subject_token", subjectToken);
        String authorizeBody = Json.write(authorize);
        Reply decision = post("gateway", "/internal/authorize", authorizeBody);
        assertEquals(200, decision.status, "authorize 必须 200：" + decision.body);
        String decisionAssertion = decision.body.path("assertion").asText();
        JsonNode dc = claims(decisionAssertion);
        String digest = Digest.sha256(Json.canonical(Json.read(authorizeBody)));
        System.out.println("[b-2] POST /internal/authorize operation=authorize client=gateway -> http=" + decision.status);
        System.out.println("[b-2] assertion.header=" + header(decisionAssertion, "typ") + "|" + header(decisionAssertion, "alg")
                + "|kid=" + header(decisionAssertion, "kid"));
        System.out.println("[b-2] claims=" + show(dc, "iss", "aud", "client_id", "token_use", "decision_id",
                "subject_caller", "grant_ref", "grant_expires_at", "authorization_version", "active", "current",
                "authority_mode", "target", "action", "nonce"));
        System.out.println("[b-2] request_sha256=" + dc.path("request_sha256").asText()
                + " recomputed=" + digest + " match=" + digest.equals(dc.path("request_sha256").asText())
                + " ttl=" + (dc.path("exp").asLong() - dc.path("iat").asLong()) + "s");
        System.out.println("[b-2] actor={" + "tenant=" + mask(dc.path("tenant_id").asText())
                + ", sub=" + mask(dc.path("sub").asText()) + ", roles=" + dc.path("roles") + "}");
        System.out.println("[b-2] initial_ceiling=" + show(dc.path("initial_ceiling"), "tenant_id", "sub", "roles", "attributes")
                + " operation=" + show(dc.path("operation"), "operation_id", "source_kind", "origin_kind",
                "issued_at", "expires_at", "ceiling_sha256"));

        // b-3 gateway 把授权推进到 skill：同一资源、只换 target/action。
        Map<String, Object> forward = base("forward", "skill", GATEWAY_TO_SKILL, SKILL_RESOURCE);
        forward.put("decision_id", text(dc, "decision_id"));
        forward.put("grant_ref", text(dc, "grant_ref"));
        forward.put("operation_id", text(dc.path("operation"), "operation_id"));
        String forwardBody = Json.write(forward);
        Reply forwarded = post("gateway", "/oauth/token", forwardBody);
        assertEquals(200, forwarded.status, "forward 必须 200：" + forwarded.body);
        String ticket1 = forwarded.body.path("ticket").asText();
        JsonNode t1 = claims(ticket1);
        System.out.println("[b-3] POST /oauth/token operation=forward client=gateway -> http=" + forwarded.status
                + " ticket.len=" + ticket1.length() + " ticket.typ=" + header(ticket1, "typ") + " kid=" + header(ticket1, "kid"));
        System.out.println("[b-3] ticket1.claims=" + show(t1, "aud", "sub", "caller", "scope", "authority_mode",
                "grant_ref", "grant_expires_at", "authorization_version", "jti", "subject_caller"));
        System.out.println("[b-3] ticket1.actor={" + "tenant=" + mask(t1.path("tenant_id").asText())
                + ", sub=" + mask(t1.path("sub").asText()) + ", roles=" + t1.path("roles") + "}");
        assertEquals("delegated", t1.path("authority_mode").asText());
        assertEquals("urn:agent-platform:service:skill", t1.path("aud").asText());
        assertEquals("gateway", t1.path("caller").asText());
        assertEquals(GATEWAY_TO_SKILL, t1.path("scope").asText());

        // b-4 skill 侧用真实平台客户端采纳这张票：RS256 验签 + 目录行校验 + 天花板校验。
        HttpCurrentAuthorityClient skill = client("skill");
        VerifiedAuthority skillAuthority = skill.authorize(ticket1, new ServiceCall("skill", GATEWAY_TO_SKILL, SKILL_RESOURCE));
        System.out.println("[b-4] skill.authorize -> ok caller=" + skillAuthority.caller()
                + " decisionId=" + mask(skillAuthority.decisionId())
                + " grant=" + mask(skillAuthority.delegation().value())
                + " authorizationVersion=" + skillAuthority.authorizationVersion()
                + " exp=" + skillAuthority.expiresAt());
        assertEquals("gateway", skillAuthority.caller());
        assertEquals(SKILL_RESOURCE, skillAuthority.operation().resource());

        // b-5 生产代码自己换票：ServiceTickets(AUTHORITY) → IamServiceTicketProvider → forward(skill→engine)。
        ServiceTickets tickets = new ServiceTickets(ServiceTickets.AUTHORITY,
                new IamServiceTicketProvider(Clock.systemUTC(), skill), null,
                Map.of("engine", ENGINE_BASE));
        String ticket2;
        try (AuthorityContext bound = AuthorityContext.bind(skillAuthority)) {
            // caller 固定 skill（skill 自己的出站身份）；路径走目录模板 /internal/runs/{id}/wake。
            ticket2 = tickets.ticket(skillAuthority.actor(), "skill", "POST",
                    ENGINE_BASE + "/internal/runs/run-ex26/wake");
        }
        JsonNode t2 = claims(ticket2);
        System.out.println("[b-5] ServiceTickets(AUTHORITY).ticket POST " + ENGINE_BASE + "/internal/runs/run-ex26/wake -> ok ticket.len=" + ticket2.length());
        System.out.println("[b-5] ticket2.claims=" + show(t2, "aud", "sub", "caller", "scope", "authority_mode",
                "grant_ref", "grant_expires_at", "operation", "subject_caller"));
        assertEquals("urn:agent-platform:service:engine", t2.path("aud").asText());
        assertEquals("skill", t2.path("caller").asText());
        assertEquals(SKILL_TO_ENGINE, t2.path("scope").asText());
        assertEquals("delegated", t2.path("authority_mode").asText());
        assertEquals(SKILL_RESOURCE.kind(), t2.path("resource").path("kind").asText());
        assertEquals(SKILL_RESOURCE.id(), t2.path("resource").path("id").asText());
        assertEquals(DIGEST, t2.path("resource").path("version_digest").asText());

        // b-6 engine 采纳第二跳：这是"delegated 调用被真正接受"的终点。
        HttpCurrentAuthorityClient engine = client("engine");
        VerifiedAuthority engineAuthority = engine.authorize(ticket2,
                new ServiceCall("engine", SKILL_TO_ENGINE, SKILL_RESOURCE));
        System.out.println("[b-6] engine.authorize(ticket2) -> ok caller=" + engineAuthority.caller()
                + " tenant=" + mask(engineAuthority.actor().tenantId())
                + " sub=" + mask(engineAuthority.actor().principalId())
                + " roles=" + engineAuthority.actor().roles()
                + " decisionId=" + mask(engineAuthority.decisionId())
                + " resource=" + engineAuthority.operation().resource().kind() + "/" + engineAuthority.operation().resource().id());
        // caller 记的是"谁把这张票交给我"（票面 caller=skill），不是链首门户用户；
        // 门户用户身份在 actor（tenant_id/sub）里，两者在 claims 里从未混用。
        assertEquals("skill", engineAuthority.caller());
        assertEquals(skillAuthority.actor().tenantId(), engineAuthority.actor().tenantId());
        assertEquals(skillAuthority.actor().principalId(), engineAuthority.actor().principalId());
    }

    // ------------------------------------------------------------------ (c) delegation off

    @Test
    @Order(4)
    @EnabledIfEnvironmentVariable(named = "EX26_EXPECT", matches = "delegation-off")
    void c_delegation_disabled_denies_same_path_without_legacy_fallback() throws Exception {
        // session 不依赖 delegation，必须仍然 200：证明变的是"委派"而不是整个服务。
        Reply session = post("gateway", "/oauth/token", Map.of("operation", "session"));
        System.out.println("[c-1] POST /oauth/token operation=session -> http=" + session.status
                + " (identity 仍启用，delegation 关闭)");
        assertEquals(200, session.status);
        String subjectToken = session.body.path("access_token").asText();

        Map<String, Object> authorize = base("authorize", "skill", GATEWAY_TO_SKILL, SKILL_RESOURCE);
        authorize.put("subject_token", subjectToken);
        Reply decision = post("gateway", "/internal/authorize", Json.write(authorize));
        System.out.println("[c-2] POST /internal/authorize operation=authorize -> http=" + decision.status
                + " body=" + decision.body);
        assertEquals(403, decision.status, "关闭 delegation 后同一路径必须 403");
        assertEquals("SYS-AUTH-403", decision.body.path("code").asText());

        Map<String, Object> forward = base("forward", "skill", GATEWAY_TO_SKILL, SKILL_RESOURCE);
        forward.put("decision_id", "dec-ex26");
        forward.put("grant_ref", "grant-ex26");
        forward.put("operation_id", "op-ex26");
        Reply forwarded = post("gateway", "/oauth/token", Json.write(forward));
        System.out.println("[c-3] POST /oauth/token operation=forward -> http=" + forwarded.status
                + " body=" + forwarded.body);
        assertEquals(403, forwarded.status, "关闭 delegation 后 forward 必须 403");

        // 同一时刻 AUTHORITY 模式仍拒绝自签：开关只关委派，没有把身份链降级回 HS256。
        PlatformException noContext = assertThrows(PlatformException.class, () ->
                new ServiceTickets(ServiceTickets.AUTHORITY,
                        new IamServiceTicketProvider(Clock.systemUTC(), client("skill")), null,
                        Map.of("engine", ENGINE_BASE))
                        .ticket(new Actor("t-ex26", "u-ex26", java.util.Set.of("operator"), Map.of()),
                                "skill", "POST", ENGINE_BASE + "/internal/runs/run-ex26/wake"));
        System.out.println("[c-4] ServiceTickets(AUTHORITY).ticket(no bound authority) -> " + noContext.status()
                + " code=" + noContext.code() + " legacy_fallback=false");
        assertEquals("AUTHORITY_CONTEXT_MISSING", noContext.code());
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> base(String operation, String target, String action, ResourceBinding resource) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operation", operation);
        body.put("target", target);
        body.put("action", action);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("kind", resource.kind());
        res.put("id", resource.id());
        res.put("version_digest", resource.versionDigest());
        body.put("resource", res);
        body.put("nonce", "ex26-" + java.util.UUID.randomUUID());
        return body;
    }

    private static HttpCurrentAuthorityClient client(String service) {
        String ref = "env:AGENT_SECRET_IAM_" + service.toUpperCase(Locale.ROOT) + "_CLIENT";
        AuthorityProperties config = new AuthorityProperties("iam", service, null,
                new AuthorityProperties.Authority(BASE, BASE + "/.well-known/jwks.json",
                        BASE + "/oauth/token", BASE + "/internal/authorize", service, ref, "RS256",
                        "urn:agent-platform:service:" + service, 60, 5, 1000, 2000),
                null, null, null, null, null);
        // testProfile=true：口径 B 口径下本地环回允许 http；生产必须 https。
        return new HttpCurrentAuthorityClient(config, Clock.systemUTC(),
                new WorkloadCredentialResolver(config, System::getenv, true), true);
    }

    private record Reply(int status, JsonNode body) {
        @Override
        public String toString() {
            return status + " " + body;
        }
    }

    private static Reply post(String service, String path, Map<String, Object> body) throws Exception {
        return post(service, path, Json.write(body));
    }

    private static Reply post(String service, String path, String body) throws Exception {
        String secret = System.getenv("AGENT_SECRET_IAM_" + service.toUpperCase(Locale.ROOT) + "_CLIENT");
        assertNotNull(secret, "缺少环境变量 AGENT_SECRET_IAM_" + service.toUpperCase(Locale.ROOT) + "_CLIENT");
        String basic = Base64.getEncoder().encodeToString((service + ":" + secret).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + basic)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String text = response.body() == null || response.body().isBlank() ? "{}" : response.body();
        return new Reply(response.statusCode(), Json.read(text));
    }

    private static JsonNode claims(String jwt) {
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length, "不是 JWS compact 三段式");
        return Json.read(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
    }

    private static String header(String jwt, String key) {
        String[] parts = jwt.split("\\.");
        return Json.read(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)).path(key).asText();
    }

    private static String text(JsonNode node, String key) {
        return node.path(key).asText();
    }

    private static String show(JsonNode node, String... keys) {
        List<String> parts = new ArrayList<>();
        for (String key : keys) {
            if (node == null || !node.has(key)) continue;
            JsonNode value = node.get(key);
            parts.add(key + "=" + (value.isTextual() && value.asText().length() > 24
                    ? mask(value.asText()) : value.toString()));
        }
        return "{" + String.join(", ", parts) + "}";
    }

    /** 证据里不留完整 sub/tenant/决策号。 */
    private static String mask(String value) {
        if (value == null || value.isBlank()) return "null";
        if (value.length() <= 6) return value.charAt(0) + "***";
        return value.substring(0, 6) + "***(" + value.length() + ")";
    }

    private static String value(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

}
