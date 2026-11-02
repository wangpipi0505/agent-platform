package cn.agentplatform.common.authority;

import cn.agentplatform.authority.AuthorityApplication;
import cn.agentplatform.authority.AuthorityException;
import cn.agentplatform.authority.AuthorityTestSupport;
import cn.agentplatform.authority.DelegatedTestSupport;
import cn.agentplatform.authority.TestJwt;
import cn.agentplatform.authority.api.AuthorityRequest;
import cn.agentplatform.authority.api.TicketService;
import cn.agentplatform.authority.config.AuthorityServiceProperties;
import cn.agentplatform.authority.config.DelegationProperties;
import cn.agentplatform.authority.config.IssuerResolver;
import cn.agentplatform.authority.decision.DecisionStore;
import cn.agentplatform.authority.decision.DelegatedAuthority;
import cn.agentplatform.authority.identity.IdentityProperties;
import cn.agentplatform.authority.route.RouteDirectory;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.authority.ResourceBinding;
import cn.agentplatform.common.authority.ServiceCall;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EX-26 回归：短 {@code decision-seconds} 不能丢掉仍有效 grant 的恢复来源。
 *
 * <p>缺陷链路：{@code authorize} 把决策的 {@code expiresAt} 夹到
 * {@code min(issued+decisionSeconds, grantExpiresAt)}，而 {@code DecisionStore.sweep()} 按
 * {@code expiresAt} 清 entries；只要 {@code decision-seconds < grant-seconds}，一次写入就会把
 * 仍可 resume 的原委托清掉，{@code operation=resume} 只能报 {@code RESUME_GRANT_UNKNOWN}——
 * 把"grant 还有效"误报成"从未签发"。
 *
 * <p>修法引入 grant 保留区：登记按 {@code grantExpiresAt} 存活，resume 的 findByGrantRef 合并
 * entries 与保留区。本类用可控 {@link MutableClock} 直接推进时间（不真等 60s），四条用例分别钉住：
 * 恢复来源存活、消费标记跟随、grant 过期后可区分、保留区容量有界。
 *
 * <p>为什么只构造 {@link DecisionStore} 而不走 HTTP authorize：要复现的是"决策 expiresAt 比 grant
 * 短"这一存储层形状，authorize 的其余副作用（断言、票）与缺陷无关；resume 本身仍走真实
 * {@link DelegatedAuthority}（真实 KeyRing/TicketService/目录），换出的票也做 claims 断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AuthorityApplication.class,
        properties = {AuthorityTestSupport.P_ISSUER, AuthorityTestSupport.P_KID, AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY, DelegatedTestSupport.P_ENGINE, DelegatedTestSupport.P_DELEGATION_ON})
@TestPropertySource(properties = "agent.authority.delegation.decision-seconds=5")
class DecisionStoreGrantRetentionTest extends DelegatedTestSupport {

    private static final String ROOT_KIND = "app-version";
    private static final String ROOT = "run-ex26-root";
    private static final String RUN_ADVANCE = "run.advance";
    private static final String GRANT = "grant-ex26";

    /** 以"现在"为基准，方便 KeyRing（系统时钟）验票；store/authority 用可变时钟推进。 */
    private static final Instant T0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @Autowired
    TicketService tickets;
    @Autowired
    RouteDirectory routes;
    @Autowired
    DelegationProperties delegation;
    @Autowired
    AuthorityServiceProperties properties;
    @Autowired
    IdentityProperties identity;
    @Autowired
    IssuerResolver issuer;

    @Test
    void aShortDecisionTtlDoesNotLoseTheGrantResumeSource() {
        MutableClock clock = new MutableClock(T0);
        DecisionStore store = new DecisionStore(delegation.capacity(), clock);
        DelegatedAuthority authority = authority(store, clock);
        DecisionStore.Decision original = decision("dec-retained", GRANT,
                T0.plusSeconds(5), T0.plusSeconds(60), null, null);
        store.put(original);

        // 续期只推后展示有效期（+8 仍早于下面的 +10，entries 一定会被 sweep）。
        // 同时验证 touchExpiry 会镜像到保留区：漏镜像时保留区仍是 +5。
        assertThat(store.touchExpiry(original.id(), T0.plusSeconds(8))).isPresent();

        clock.advance(Duration.ofSeconds(10));
        // 任意一次写入都会 sweep：entries 按 expiresAt 清掉原决策，保留区按 grantExpiresAt 留下它。
        store.put(decision("dec-trigger", "grant-trigger", T0.plusSeconds(5), T0.plusSeconds(120), null, null));
        assertThat(store.find(original.id()))
                .as("decision-seconds=5 的 entries 必须已被 sweep，否则测不到恢复来源")
                .isEmpty();

        List<DecisionStore.Decision> retained = store.findByGrantRef(GRANT);
        assertThat(retained).as("grant 仍有效时 findByGrantRef 必须仍返回原委托").singleElement()
                .satisfies(kept -> {
                    assertThat(kept.id()).isEqualTo(original.id());
                    assertThat(kept.expiresAt())
                            .as("touchExpiry 的续期必须同步到保留区")
                            .isEqualTo(T0.plusSeconds(8));
                    assertThat(kept.grantExpiresAt()).isEqualTo(T0.plusSeconds(60));
                });

        // entries 已经没了，resume 只能靠保留区；成功换票即证明恢复来源活过了展示 TTL。
        Map<String, Object> issued = authority.resume(ENGINE, resumeRequest(GRANT, "n-resume-retained"));
        String ticket = (String) issued.get("ticket");
        assertThat(ticket).as("resume 必须返回 delegated 票").isNotBlank();
        JsonNode claims = TestJwt.claims(ticket);
        assertThat(claims.path("sub").textValue()).isEqualTo("alice");
        assertThat(claims.path("grant_ref").textValue()).isEqualTo(GRANT);
        assertThat(claims.path("caller").textValue()).isEqualTo(ENGINE);
    }

    @Test
    void aConsumedGrantStaysRevokedAfterTheDisplayEntryIsSwept() {
        MutableClock clock = new MutableClock(T0);
        DecisionStore store = new DecisionStore(delegation.capacity(), clock);
        DelegatedAuthority authority = authority(store, clock);
        DecisionStore.Decision original = decision("dec-revoked", GRANT,
                T0.plusSeconds(5), T0.plusSeconds(60), null, null);
        store.put(original);
        assertThat(store.consume(original.id(), GATEWAY)).isPresent();

        clock.advance(Duration.ofSeconds(10));
        store.put(decision("dec-trigger-2", "grant-trigger-2", T0.plusSeconds(5), T0.plusSeconds(120), null, null));
        assertThat(store.find(original.id())).isEmpty();

        DecisionStore.Decision retained = store.findByGrantRef(GRANT).get(0);
        assertThat(retained.consumedAt())
                .as("消费标记必须跟到保留区，否则 resume 会看到一份未撤销的旧委托")
                .isNotNull();
        assertThat(retained.consumedBy()).isEqualTo(GATEWAY);

        assertResumeDenied(authority, resumeRequest(GRANT, "n-resume-revoked"),
                "SYS-AUTH-403", "RESUME_REVOKED");
    }

    @Test
    void anExpiredGrantReportsAuthorityExpiredThenGetsSwept() {
        MutableClock clock = new MutableClock(T0);
        DecisionStore store = new DecisionStore(delegation.capacity(), clock);
        DelegatedAuthority authority = authority(store, clock);
        DecisionStore.Decision original = decision("dec-expired", GRANT,
                T0.plusSeconds(5), T0.plusSeconds(60), null, null);
        store.put(original);

        clock.advance(Duration.ofSeconds(61));
        // sweep 只在写入路径发生：此刻 entries 还没清，resume 必须先看到"grant 过期"，
        // 而不是被读路径的过滤退化成 RESUME_GRANT_UNKNOWN。
        assertResumeDenied(authority, resumeRequest(GRANT, "n-resume-expired"),
                "AUTHORITY_EXPIRED", "RESUME_GRANT_EXPIRED");

        store.put(decision("dec-trigger-3", "grant-trigger-3", T0.plusSeconds(5), T0.plusSeconds(180), null, null));
        assertThat(store.findByGrantRef(GRANT))
                .as("grantExpiresAt 过后保留区必须由 sweep 清掉")
                .isEmpty();
    }

    @Test
    void theGrantReservationIsBoundedByCapacity() {
        DecisionStore store = new DecisionStore(4, Clock.fixed(T0, ZoneOffset.UTC));
        for (int i = 0; i < 6; i++) {
            store.put(decision("dec-cap-" + i, "grant-cap-" + i,
                    T0.plusSeconds(300), T0.plusSeconds(60 + i), null, null));
        }

        // 容量上界同时约束 entries 与保留区：6 条不同 grant，最多 4 条还能查到；按 grantExpiresAt
        // 淘汰最旧，所以 g0/g1 必须消失、g5 必须还在。用"非空 grant 计数"断言上界，不依赖内部结构。
        long surviving = 0;
        for (int i = 0; i < 6; i++) {
            if (!store.findByGrantRef("grant-cap-" + i).isEmpty()) {
                surviving++;
            }
        }
        assertThat(surviving)
                .as("grant 保留区必须与 entries 同量级有界，按 grantExpiresAt 淘汰最旧")
                .isLessThanOrEqualTo(4);
        assertThat(store.findByGrantRef("grant-cap-0")).isEmpty();
        assertThat(store.findByGrantRef("grant-cap-1")).isEmpty();
        assertThat(store.findByGrantRef("grant-cap-5")).singleElement()
                .satisfies(kept -> assertThat(kept.id()).isEqualTo("dec-cap-5"));
    }

    private DelegatedAuthority authority(DecisionStore store, Clock clock) {
        return new DelegatedAuthority(keys, tickets, routes, store, delegation, properties, identity, issuer, clock);
    }

    private static AuthorityRequest resumeRequest(String grantRef, String nonce) {
        ServiceCall call = new ServiceCall(ENGINE, RUN_ADVANCE, new ResourceBinding(ROOT_KIND, ROOT, DIGEST));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operation", DelegatedAuthority.RESUME);
        body.put("target", call.target());
        body.put("action", call.action());
        body.put("resource", Map.of("kind", ROOT_KIND, "id", ROOT, "version_digest", DIGEST));
        body.put("nonce", nonce);
        body.put("grant_ref", grantRef);
        return AuthorityRequest.parse(Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void assertResumeDenied(DelegatedAuthority authority, AuthorityRequest request,
                                           String code, String reason) {
        assertThatThrownBy(() -> authority.resume(ENGINE, request))
                .isInstanceOfSatisfying(AuthorityException.class, denied -> {
                    assertThat(denied.status()).isEqualTo(403);
                    assertThat(denied.code()).isEqualTo(code);
                    assertThat(denied.auditReason()).isEqualTo(reason);
                });
    }

    /**
     * 这条决策刻意不写 purpose/sourceToken/continuation 等 EX-44 字段：resume 只读
     * caller/target/action/resource/operation/consumedAt/grantExpiresAt，其余保持 null 才能证明
     * "恢复来源"不依赖展示链路的状态。
     */
    private static DecisionStore.Decision decision(String id, String grantRef, Instant expiresAt,
                                                   Instant grantExpiresAt, Instant consumedAt, String consumedBy) {
        Map<String, Object> actor = Map.of("tenant_id", "tenant-a", "sub", "alice");
        Map<String, Object> operation = Map.of(
                "operation_id", "op-" + id,
                "tenant_id", "tenant-a",
                "sub", "alice",
                "origin_kind", "PORTAL",
                "ceiling_sha256", DIGEST,
                "expires_at", grantExpiresAt.getEpochSecond());
        return new DecisionStore.Decision(id, ENGINE, ENGINE, RUN_ADVANCE,
                Map.of("kind", ROOT_KIND, "id", ROOT, "version_digest", DIGEST),
                actor, actor, operation, grantRef, grantExpiresAt, ENGINE,
                null, null, "delegated-v1", expiresAt,
                null, null, null, null, consumedAt, consumedBy,
                null, null, null, null, null);
    }

    /** 只推进 instant() 的可变时钟；KeyRing 仍用系统时钟，所以票面 claims 只解码不验时间窗。 */
    private static final class MutableClock extends Clock {

        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
