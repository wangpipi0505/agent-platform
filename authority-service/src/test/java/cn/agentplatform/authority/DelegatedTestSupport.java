package cn.agentplatform.authority;

import cn.agentplatform.authority.identity.SessionService;
import cn.agentplatform.authority.sign.KeyRing;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.authority.ResourceBinding;
import cn.agentplatform.common.authority.ServiceCall;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

/**
 * EX-42 两条 operation 的测试支撑：固定 <em>gateway → engine → skill</em> 这条真实目录链
 * （{@code gateway -> engine / proxy.engine.post.RunController.run} 与
 * {@code engine -> skill / skill.process.cancel} 都是目录里的 delegated 行）。
 *
 * <p>为什么用这两个动作而不是卡面写的 {@code run.wake}：{@code run.wake} 的 delegating 方是
 * {@code skill}，与本链路的两跳方向相反；目录行是唯一口径，测试跟着目录走。
 */
public abstract class DelegatedTestSupport extends AuthorityTestSupport {

    public static final String ENGINE = "engine";
    public static final String ENGINE_SECRET = "test-engine-secret";
    public static final String ENGINE_SECRET_REF = "env:AGENT_SECRET_IAM_ENGINE_CLIENT";
    public static final String P_ENGINE = "agent.authority.clients.engine=" + ENGINE_SECRET;
    public static final String SKILL = "skill";
    public static final String FIRST_ACTION = "proxy.engine.post.RunController.run";
    public static final String NEXT_ACTION = "skill.process.cancel";
    public static final String RUN_ID = "run-1";
    public static final String P_DELEGATION_ON = "agent.authority.delegation.enabled=true";

    @Autowired
    protected KeyRing keys;

    @MockitoSpyBean
    protected SessionService sessions;

    @BeforeEach
    void acceptSyntheticPortalSubjects() {
        doNothing().when(sessions).requireCurrentIdentity(anyString(), anyString());
    }

    protected static ServiceCall call(String target, String action) {
        return new ServiceCall(target, action, new ResourceBinding("run", RUN_ID, DIGEST));
    }

    protected static Map<String, Object> runResource() {
        return Map.of("kind", "run", "id", RUN_ID, "version_digest", DIGEST);
    }

    /** 请求体：字段名与 {@code AuthorityRequest} 白名单一致，{@code extra} 放 operation 专属字段。 */
    protected static String body(String operation, ServiceCall call, String nonce, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operation", operation);
        body.put("target", call.target());
        body.put("action", call.action());
        body.put("resource", runResource());
        body.put("nonce", nonce);
        body.putAll(extra);
        return Json.write(body);
    }

    /** 走真实 HTTP 的 {@code operation=authorize}，返回决策断言 claims（平台客户端读的就是这份 claims）。 */
    protected JsonNode authorize(String bootstrap, ServiceCall call, String nonce) {
        ResponseEntity<JsonNode> response = postJson("/internal/authorize", basic(ENGINE, ENGINE_SECRET),
                body("authorize", call, nonce, Map.of("service_ticket", bootstrap)));
        assertThat(response.getStatusCode()).as("authorize body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        return TestJwt.claims(response.getBody().get("assertion").asText());
    }

    protected static Map<String, Object> forwardFields(JsonNode decision) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("decision_id", decision.get("decision_id").asText());
        fields.put("grant_ref", decision.get("grant_ref").asText());
        fields.put("operation_id", decision.get("operation").get("operation_id").asText());
        return fields;
    }

    /** 走真实 HTTP 的 {@code operation=forward}，返回原始响应（成功/拒绝都由调用方断言）。 */
    protected ResponseEntity<JsonNode> postForward(JsonNode decision, ServiceCall call, String nonce) {
        // forward 走 tokenUri（卡片第 2 条 + 平台客户端 query(..., token=true) 都是这个端点）。
        return postJson("/oauth/token", basic(ENGINE, ENGINE_SECRET),
                body("forward", call, nonce, forwardFields(decision)));
    }

    protected static ListAppender<ILoggingEvent> attachAudit() {
        Logger logger = (Logger) LoggerFactory.getLogger("agent.authority.audit");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    protected static List<String> auditLines(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
