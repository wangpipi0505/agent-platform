package cn.agentplatform.common.authority;

import cn.agentplatform.authority.AuthorityApplication;
import cn.agentplatform.authority.AuthorityTestSupport;
import cn.agentplatform.authority.DelegatedTestSupport;
import cn.agentplatform.authority.TestJwt;
import cn.agentplatform.authority.decision.DecisionStore;
import cn.agentplatform.common.Json;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-26 未完成部分：{@code operation=resume} 的服务端契约。
 *
 * <p>resume 是"用已经持久化的原委托换一张新的短票据"：请求方是已认证服务，凭据只有
 * {@code grant_ref}（不重放原 portal JWT，也不由调用方自填 expiry），本服务从自己的决策登记簿
 * 重建主体/角色/上限，签出的新票必须是 {@code authority_mode=delegated}、
 * {@code operation.source_kind=DELEGATED}（平台客户端 {@code resume} 逐字要求）、
 * {@code grant_ref}/{@code initial_ceiling} 与原委托一致（客户端 {@code verifyCeiling}
 * 的 "Signed grant ceiling is immutable across resume"）。
 *
 * <p>失败面全部 403 且不回落 legacy：原委托不存在、已撤销（一次性消费标记）、已过期、
 * resource 越出原委托绑定的 root。第一条用例走平台真实客户端 {@link HttpCurrentAuthorityClient#resume}，
 * 其余用例走原始 HTTP 以便断言响应 code 与审计 reason。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AuthorityApplication.class,
        properties = {
                AuthorityTestSupport.P_ISSUER,
                AuthorityTestSupport.P_KID,
                AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY,
                AuthorityTestSupport.P_APP,
                DelegatedTestSupport.P_ENGINE,
                DelegatedTestSupport.P_DELEGATION_ON})
class DelegatedResumeContractTest extends DelegatedTestSupport {

    /** engine 的本地推进动作；{@code ServiceRoutePolicy.localRunAdvance} 的唯一 action。 */
    private static final String RUN_ADVANCE = "run.advance";
    /** 与 engine 受理面一致：委派绑定的 root 是 app-version，不是 run。 */
    private static final String ROOT_KIND = "app-version";

    @Autowired
    private DecisionStore decisions;

    @Test
    void resumeExchangesTheStoredGrantForANewDelegatedTicket() {
        ServiceCall inbound = rootCall(FIRST_ACTION, "appv-resume-ok");
        ServiceCall advance = rootCall(RUN_ADVANCE, "appv-resume-ok");
        String bootstrap = DelegatedTickets.bootstrap(keys, inbound, GATEWAY);
        ListAppender<ILoggingEvent> audit = attachAudit();

        ServiceTicket resumed;
        try (HttpCurrentAuthorityClient engine = client(ENGINE, ENGINE_SECRET_REF, ENGINE_SECRET)) {
            VerifiedAuthority authority = engine.authorize(bootstrap, inbound);
            // 真实客户端会逐项校验票面：source_kind=DELEGATED、origin_kind=PORTAL、caller/client_id=engine、
            // scope=run.advance、grant_ref/initial_ceiling 不变、exp<=operation.expires_at<=grant_expires_at。
            resumed = engine.resume(authority.delegation(), advance);
        }

        JsonNode claims = TestJwt.verifiedClaims(resumed.value(), "at+jwt", publishedKey());
        assertThat(claims.get("authority_mode").asText()).isEqualTo("delegated");
        assertThat(claims.get("caller").asText()).isEqualTo(ENGINE);
        assertThat(claims.get("client_id").asText()).isEqualTo(ENGINE);
        assertThat(claims.get("target").asText()).isEqualTo(ENGINE);
        assertThat(claims.get("scope").asText()).isEqualTo(RUN_ADVANCE);
        assertThat(claims.get("grant_ref").asText()).isEqualTo(DelegatedTickets.GRANT_REF);
        assertThat(claims.get("tenant_id").asText()).isEqualTo(DelegatedTickets.TENANT_ID);
        assertThat(claims.get("sub").asText()).isEqualTo(DelegatedTickets.SUBJECT);
        // 上限必须与原委托逐字节一致：客户端 verifyCeiling 用它重算 ceiling_sha256。
        assertThat(claims.get("initial_ceiling"))
                .isEqualTo(Json.mapper().valueToTree(DelegatedTickets.actorClaims()));

        JsonNode operation = claims.get("operation");
        assertThat(operation.get("source_kind").asText()).isEqualTo("DELEGATED");
        assertThat(operation.get("origin_kind").asText()).isEqualTo("PORTAL");
        assertThat(operation.get("grant_ref").asText()).isEqualTo(DelegatedTickets.GRANT_REF);
        assertThat(operation.get("resource").get("kind").asText()).isEqualTo(ROOT_KIND);
        assertThat(operation.get("resource").get("id").asText()).isEqualTo("appv-resume-ok");
        // 新操作必须是一次新的签发，不能沿用上游那张票的 operation_id。
        assertThat(operation.get("operation_id").asText()).isNotEqualTo(DelegatedTickets.OPERATION_ID);
        assertThat(claims.get("exp").asLong())
                .isLessThanOrEqualTo(claims.get("grant_expires_at").asLong());
        assertThat(claims.get("exp").asLong())
                .isLessThanOrEqualTo(operation.get("expires_at").asLong());
        assertThat(claims.get("exp").asLong()).isGreaterThan(Instant.now().getEpochSecond());

        List<String> lines = auditLines(audit);
        assertThat(lines).anyMatch(line -> line.contains("operation=resume") && line.contains("outcome=permit"));
        assertThat(lines).noneMatch(line -> line.contains("eyJ"));
    }

    @Test
    void resumeTicketCanBeConsumedByTheSameEngineLocalAdvance() {
        ServiceCall inbound = rootCall(FIRST_ACTION, "appv-resume-consume");
        ServiceCall advance = rootCall(RUN_ADVANCE, "appv-resume-consume");
        String bootstrap = DelegatedTickets.bootstrap(keys, inbound, GATEWAY);

        try (HttpCurrentAuthorityClient engine = client(ENGINE, ENGINE_SECRET_REF, ENGINE_SECRET)) {
            VerifiedAuthority authority = engine.authorize(bootstrap, inbound);
            ServiceTicket resumed = engine.resume(authority.delegation(), advance);
            // 目录里没有 engine→engine 的 delegated 行（run.advance 是本地续接边），
            // 消费侧必须与签发侧同样接受它，否则 resume 签得出的票永远换不回（EX-26）。
            VerifiedAuthority next = engine.authorize(resumed.value(), advance);
            assertThat(next.decisionId()).isNotBlank();
            assertThat(next.delegation()).isNotNull();
            assertThat(next.call().action()).isEqualTo(RUN_ADVANCE);
        }
    }

    @Test
    void resumeRejectsAGrantThatHasExpired() {
        ListAppender<ILoggingEvent> audit = attachAudit();
        decisions.put(expiredGrant(DelegatedTickets.GRANT_REF, "appv-resume-expired"));

        ResponseEntity<JsonNode> denied = postJson("/oauth/token", basic(ENGINE, ENGINE_SECRET),
                resumeBody(rootCall(RUN_ADVANCE, "appv-resume-expired"), "resume-expired", DelegatedTickets.GRANT_REF));

        assertThat(denied.getStatusCode()).as("body: %s", denied.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("AUTHORITY_EXPIRED");
        assertThat(auditLines(audit)).anyMatch(line -> line.contains("reason=RESUME_GRANT_EXPIRED"));
    }

    @Test
    void resumeRejectsAResourceOutsideTheGrantedRoot() {
        ServiceCall inbound = rootCall(FIRST_ACTION, "appv-resume-escalated");
        String bootstrap = DelegatedTickets.bootstrap(keys, inbound, GATEWAY);
        authorize(inbound, bootstrap, "resume-escalated");
        ListAppender<ILoggingEvent> audit = attachAudit();

        ResponseEntity<JsonNode> denied = postJson("/oauth/token", basic(ENGINE, ENGINE_SECRET),
                resumeBody(rootCall(RUN_ADVANCE, "appv-resume-other"), "resume-escalated-2",
                        DelegatedTickets.GRANT_REF));

        assertThat(denied.getStatusCode()).as("body: %s", denied.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
        assertThat(auditLines(audit)).anyMatch(line -> line.contains("reason=RESUME_ESCALATION"));
    }

    @Test
    void resumeRejectsARevokedGrant() {
        ServiceCall inbound = rootCall(FIRST_ACTION, "appv-resume-revoked");
        String bootstrap = DelegatedTickets.bootstrap(keys, inbound, GATEWAY);
        JsonNode decision = authorize(inbound, bootstrap, "resume-revoked");
        // 仓库没有撤销 API/字段；"不再可用"的唯一既有信号是一次性消费标记（consumedAt）。
        assertThat(decisions.consume(decision.get("decision_id").asText(), "revocation")).isPresent();
        ListAppender<ILoggingEvent> audit = attachAudit();

        ResponseEntity<JsonNode> denied = postJson("/oauth/token", basic(ENGINE, ENGINE_SECRET),
                resumeBody(rootCall(RUN_ADVANCE, "appv-resume-revoked"), "resume-revoked-2",
                        DelegatedTickets.GRANT_REF));

        assertThat(denied.getStatusCode()).as("body: %s", denied.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
        assertThat(auditLines(audit)).anyMatch(line -> line.contains("reason=RESUME_REVOKED"));
    }

    @Test
    void resumeRejectsAGrantReferenceThatWasNeverIssuedHere() {
        ListAppender<ILoggingEvent> audit = attachAudit();

        ResponseEntity<JsonNode> denied = postJson("/oauth/token", basic(ENGINE, ENGINE_SECRET),
                resumeBody(rootCall(RUN_ADVANCE, "appv-resume-unknown"), "resume-unknown", "grant-never-issued"));

        assertThat(denied.getStatusCode()).as("body: %s", denied.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("SYS-AUTH-403");
        assertThat(auditLines(audit)).anyMatch(line -> line.contains("reason=RESUME_GRANT_UNKNOWN"));
    }

    /**
     * 平台侧真实客户端：resume 只能从"已认证服务"发起，Basic 凭据由 secret_ref 解析，
     * 走 {@code POST /oauth/token}（tokenUri），与生产 RunAuthority 同一路径。
     * 这里复用 {@link WorkloadCredentialResolver} 的包内构造，和 {@code DelegatedForwardContractTest} 一致。
     */
    private HttpCurrentAuthorityClient client(String service, String secretRef, String secret) {
        AuthorityProperties config = config(service, secretRef);
        WorkloadCredentialResolver credentials = new WorkloadCredentialResolver(config, ref -> secret, true);
        return new HttpCurrentAuthorityClient(config, Clock.systemUTC(), credentials, true);
    }

    private AuthorityProperties config(String service, String secretRef) {
        return new AuthorityProperties("iam", service, null,
                new AuthorityProperties.Authority(
                        ISSUER,
                        url("/.well-known/jwks.json"),
                        url("/oauth/token"),
                        url("/internal/authorize"),
                        service,
                        secretRef,
                        "RS256",
                        "urn:agent-platform:service:" + service,
                        60,
                        5,
                        1000,
                        2000),
                null, null, null, false, null);
    }

    private static ServiceCall rootCall(String action, String rootId) {
        return new ServiceCall(ENGINE, action, new ResourceBinding(ROOT_KIND, rootId, DIGEST));
    }

    /**
     * {@code operation=authorize} 的请求体必须逐字段等于上游票面的 call（这里 resource 是 app-version），
     * 继承来的 {@code body(...)} 固定 run 资源，只适用于 run 夹具；本类的上游票都是 app-version root。
     */
    private JsonNode authorize(ServiceCall call, String bootstrap, String nonce) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operation", "authorize");
        body.put("target", call.target());
        body.put("action", call.action());
        body.put("resource", Map.of("kind", call.resource().kind(), "id", call.resource().id(),
                "version_digest", call.resource().versionDigest()));
        body.put("nonce", nonce);
        body.put("service_ticket", bootstrap);
        ResponseEntity<JsonNode> response = postJson("/internal/authorize", basic(ENGINE, ENGINE_SECRET),
                Json.write(body));
        assertThat(response.getStatusCode()).as("authorize body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        return TestJwt.claims(response.getBody().get("assertion").asText());
    }

    private static String resumeBody(ServiceCall call, String nonce, String grantRef) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operation", "resume");
        body.put("target", call.target());
        body.put("action", call.action());
        body.put("resource", Map.of("kind", call.resource().kind(), "id", call.resource().id(),
                "version_digest", call.resource().versionDigest()));
        body.put("nonce", nonce);
        body.put("grant_ref", grantRef);
        return Json.write(body);
    }

    /**
     * 一条"登记还在、grant 已到期"的决策：生产里 grant 寿命可能短于决策登记寿命
     * （决策有效期取 {@code min(decisionSeconds, grant_expires_at)}，但重申/续期窗口更宽），
     * 这里直接把 grant 到期时间放到过去，验证 resume 会明确区分"过期"与"不存在"。
     */
    private static DecisionStore.Decision expiredGrant(String grantRef, String rootId) {
        return new DecisionStore.Decision("dec-expired-" + rootId, ENGINE, ENGINE, RUN_ADVANCE,
                Map.of("kind", ROOT_KIND, "id", rootId, "version_digest", DIGEST),
                Map.of(), Map.of(), Map.of(), grantRef, Instant.now().minusSeconds(30),
                ENGINE, null, null, DelegatedTickets.AUTHORIZATION_VERSION,
                Instant.now().plusSeconds(300),
                null, null, null, null, null, null, null, null, null, null, null);
    }
}
