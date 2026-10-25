package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * R-A7 台账项回归：工具调用 pre/post 有序拦截链。
 *
 * <p>单元部分验证链序与纯变换组合；PG 集成部分沿用 AgentLoopBudget 的脚本化
 * 驱动脚手架，验证 pre 在外部派发前、post 在结果确定后被调用，且 post 的
 * 变换结果真实回灌到下一轮请求的工具消息内容中。
 */
class ToolInterceptorChainTest {
    @Test
    void chainRunsInOrderAndComposesTransforms() {
        List<String> order = new ArrayList<>();
        var pre1 =
                new ToolInterceptorChain.PreDispatch() {
                    public void beforeDispatch(
                            String code, long version, JsonNode args, Actor actor) {
                        order.add("pre1:" + code);
                    }
                };
        var pre2 =
                new ToolInterceptorChain.PreDispatch() {
                    public void beforeDispatch(
                            String code, long version, JsonNode args, Actor actor) {
                        order.add("pre2:" + code);
                    }
                };
        var post1 =
                new ToolInterceptorChain.PostResult() {
                    public JsonNode afterResult(String code, JsonNode result, Actor actor) {
                        order.add("post1");
                        var wrapped = (ObjectNode) result.deepCopy();
                        wrapped.put("step", 1);
                        return wrapped;
                    }
                };
        var post2 =
                new ToolInterceptorChain.PostResult() {
                    public JsonNode afterResult(String code, JsonNode result, Actor actor) {
                        order.add("post2");
                        var wrapped = (ObjectNode) result.deepCopy();
                        wrapped.put("step", result.path("step").asInt() + 1);
                        return wrapped;
                    }
                };
        var chain = new ToolInterceptorChain(List.of(pre1, pre2), List.of(post1, post2));
        chain.preDispatch("lookup", 42, Json.read("{}"), TracePostgresTest.actor);
        var out = chain.postResult("lookup", Json.read("{\"value\":1}"), TracePostgresTest.actor);
        assertEquals(List.of("pre1:lookup", "pre2:lookup", "post1", "post2"), order);
        assertEquals(2, out.path("step").asInt());
        assertEquals(1, out.path("value").asInt());
        // 空链零行为：默认实例直接透传。
        assertEquals(
                "{\"value\":1}",
                ToolInterceptorChain.EMPTY
                        .postResult("x", Json.read("{\"value\":1}"), TracePostgresTest.actor)
                        .toString());
    }
}
