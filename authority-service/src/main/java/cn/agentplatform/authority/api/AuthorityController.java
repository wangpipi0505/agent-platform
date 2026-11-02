package cn.agentplatform.authority.api;

import cn.agentplatform.authority.AuthorityException;
import cn.agentplatform.authority.client.ClientRegistry;
import cn.agentplatform.authority.decision.DelegatedAuthority;
import cn.agentplatform.authority.identity.SessionService;
import cn.agentplatform.authority.route.RouteDirectory;
import cn.agentplatform.authority.sign.KeyRing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * 三组端点（口径 B 最小可消费面）：
 * <ul>
 *   <li>{@code GET /.well-known/jwks.json} 公钥集，供平台按 kid 验签；</li>
 *   <li>{@code POST /oauth/token} 支持 {@code operation=client}（workload 票据）与
 *       {@code operation=forward}/{@code operation=exchange}（委派票据 / portal 令牌换票）；</li>
 *   <li>{@code POST /internal/authorize} 支持 {@code operation=authorize_workload}（第二跳决策断言）与
 *       {@code operation=authorize}（portal 主体或上游 delegated 票换决策）。</li>
 * </ul>
 * 其余 operation（{@code exchange_integration/resume/response_*}）在 M1 一律 403 + 审计：
 * 最小面越窄，越不可能出现"没验证就许可"的路径；完整语义留给 M2/M3。
 * <p>两条 POST 都走 {@link #handle}：先认证、再解析、再按目录裁决，permit/deny 都落一条
 * {@code authority.audit}（只记 client_id/operation/target/action/nonce，不记 secret 与 token 原文）。
 */
@RestController
public final class AuthorityController {

    private static final Logger audit = LoggerFactory.getLogger("agent.authority.audit");
    private static final int JWKS_MAX_AGE_SECONDS = 300;
    private static final Set<String> TICKET_OPERATIONS = Set.of("client");
    private static final Set<String> DECISION_OPERATIONS = Set.of("authorize_workload");

    private final KeyRing keys;
    private final ClientRegistry clients;
    private final RouteDirectory routes;
    private final TicketService tickets;
    private final SessionService sessions;
    private final DelegatedAuthority delegated;

    public AuthorityController(KeyRing keys, ClientRegistry clients, RouteDirectory routes, TicketService tickets,
                               SessionService sessions, DelegatedAuthority delegated) {
        this.keys = keys;
        this.clients = clients;
        this.routes = routes;
        this.tickets = tickets;
        this.sessions = sessions;
        this.delegated = delegated;
    }

    @GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(JWKS_MAX_AGE_SECONDS)).cachePublic())
                .body(keys.jwks());
    }

    @PostMapping(path = "/oauth/token", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> token(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody(required = false) byte[] body) {
        // 会话签发不是一次 ServiceCall：它换的是"人"而不是"服务能力"，因此在解析目录请求之前分流。
        if (SessionService.OPERATION.equals(SessionService.operationOf(body))) {
            return handleSession(authorization, body);
        }
        return handle("token", authorization, body, this::issueTicket);
    }

    @PostMapping(path = "/internal/authorize", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> authorize(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody(required = false) byte[] body) {
        return handle("decision", authorization, body, this::decide);
    }

    /** {@code operation=client}：目录命中 workload 行才签票，票面只描述"client 到 target 的这一跳"。 */
    private Map<String, Object> issueTicket(String clientId, AuthorityRequest request) {
        // 转发也走 tokenUri（平台客户端就是这么做的），但语义是把一张已登记决策换成新目标的票据。
        if (DelegatedAuthority.FORWARD.equals(request.operation())) {
            return delegated.forward(clientId, request);
        }
        // exchange 与 forward 同为"换票"，但入口凭据是 portal 用户令牌而不是已登记决策（EX-43 / SSE 订阅）。
        if (DelegatedAuthority.EXCHANGE.equals(request.operation())) {
            return delegated.exchange(clientId, request);
        }
        if (!TICKET_OPERATIONS.contains(request.operation())) {
            throw AuthorityException.denied("tokenUri 不接受 operation=" + request.operation());
        }
        if (!routes.permits(clientId, request.call().target(), request.call().action(), RouteDirectory.WORKLOAD)) {
            throw AuthorityException.denied("目录无 workload 行 client=" + clientId
                    + " target=" + request.call().target() + " action=" + request.call().action());
        }
        TicketService.Issued issued = tickets.ticket(clientId, request);
        audit.info("authority.audit endpoint=token outcome=permit client_id={} target={} action={} ticket_jti={}",
                clientId, request.call().target(), request.call().action(), issued.ticketId());
        return issued.asResponse();
    }

    /**
     * {@code operation=authorize_workload}：第二跳。票据必须是本服务签的 workload 票、票面四项与请求全等，
     * 且调用方就是 target 本身（否则等于允许"拿着别人的票替别人做决策"）。
     */
    private Map<String, Object> decide(String clientId, AuthorityRequest request) {
        if (DelegatedAuthority.AUTHORIZE.equals(request.operation())) {
            return delegated.authorize(clientId, request);
        }
        if (!DECISION_OPERATIONS.contains(request.operation())) {
            throw AuthorityException.denied("decisionUri 不接受 operation=" + request.operation());
        }
        if (request.serviceTicket() == null) {
            throw AuthorityException.invalidRequest("authorize_workload 需要 service_ticket", "missing service_ticket");
        }
        TicketService.TicketView ticket = tickets.verifyWorkloadTicket(request.serviceTicket(), request);
        if (!clientId.equals(request.call().target())) {
            throw AuthorityException.denied("client_id 不是该调用的 target: " + clientId);
        }
        if (!routes.permits(ticket.caller(), request.call().target(), request.call().action(), RouteDirectory.WORKLOAD)) {
            throw AuthorityException.denied("目录无 workload 行 caller=" + ticket.caller()
                    + " target=" + request.call().target() + " action=" + request.call().action());
        }
        return tickets.workloadAssertion(clientId, request, ticket);
    }

    private Map<String, Object> handleSession(String authorization, byte[] body) {
        String clientId = null;
        try {
            clientId = clients.authenticate(authorization);
            Map<String, Object> response = sessions.session(clientId, body);
            audit.info("authority.audit endpoint=token outcome=permit client_id={} operation=session", clientId);
            return response;
        } catch (AuthorityException denied) {
            audit.info("authority.audit endpoint=token outcome=deny client_id={} operation=session reason={}",
                    clientId, denied.auditReason());
            throw denied;
        }
    }

    private Map<String, Object> handle(String endpoint, String authorization, byte[] body, Dispatch dispatch) {
        String clientId = null;
        AuthorityRequest request = null;
        try {
            clientId = clients.authenticate(authorization);
            request = AuthorityRequest.parse(body);
            Map<String, Object> response = dispatch.run(clientId, request);
            audit.info("authority.audit endpoint={} outcome=permit client_id={} operation={} target={} action={} nonce={}",
                    endpoint, clientId, request.operation(), request.call().target(),
                    request.call().action(), request.nonce());
            return response;
        } catch (AuthorityException e) {
            audit.info("authority.audit endpoint={} outcome=deny client_id={} operation={} target={} action={}"
                            + " status={} code={} reason={}",
                    endpoint, clientId,
                    request == null ? null : request.operation(),
                    request == null ? null : request.call().target(),
                    request == null ? null : request.call().action(),
                    e.status(), e.code(), e.auditReason());
            throw e;
        }
    }

    @FunctionalInterface
    private interface Dispatch {
        Map<String, Object> run(String clientId, AuthorityRequest request);
    }
}
