package cn.agentplatform.engine.application;

import cn.agentplatform.common.Actor;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * R-A7：工具调用 pre/post 有序拦截链（横切扩展点）。
 *
 * <p>定位：把超时预算、调用审计、结果脱敏/裁剪等横切能力从 AgentDriver.skill 的
 * 主流程里解耦出来——引擎本体不含业务策略，策略以链式插件挂载。对应
 * deepseek-harness 工具管线 pre-execute/execute/post-execute 三段瀑布的降维版：
 * 本工程工具调用已有"登记→认领→提交→事实"四步状态机兜底安全，这里只补两个
 * 有序扩展点，不上事件总线。
 *
 * <p>语义与不变量：
 * ①pre 链在"外部派发之前、登记/认领之后"执行，按序调用；任一环节抛异常即短路
 *   本次调用（异常语义由抛出方负责，如超时策略抛 TIMEOUT），已登记的 tool_call
 *   由既有恢复路径对账，链不回滚登记事实；
 * ②post 链在结果确定后（含 REGISTERED 幂等命中路径）、返回驱动循环之前执行，
 *   按序调用且每个环节可对结果做纯变换（返回新 JsonNode），链不吞异常；
 * ③两个链默认为空（EMPTY），空链零开销；Spring 注入时按 @Order 排序。
 */
@Component
public class ToolInterceptorChain {
    /** 外部派发前钩子：可短路（抛异常）、只读上下文。 */
    public interface PreDispatch {
        void beforeDispatch(
                String skillCode, long skillVersionId, JsonNode arguments, Actor actor);
    }

    /** 结果确定后钩子：可对结果做纯变换（返回替换值）。 */
    public interface PostResult {
        JsonNode afterResult(String skillCode, JsonNode result, Actor actor);
    }

    public static final ToolInterceptorChain EMPTY =
            new ToolInterceptorChain(List.of(), List.of());

    private final List<PreDispatch> pre;
    private final List<PostResult> post;

    public ToolInterceptorChain(List<PreDispatch> pre, List<PostResult> post) {
        this.pre = List.copyOf(pre);
        this.post = List.copyOf(post);
    }

    public void preDispatch(
            String skillCode, long skillVersionId, JsonNode arguments, Actor actor) {
        for (PreDispatch hook : pre) hook.beforeDispatch(skillCode, skillVersionId, arguments, actor);
    }

    public JsonNode postResult(String skillCode, JsonNode result, Actor actor) {
        for (PostResult hook : post) result = hook.afterResult(skillCode, result, actor);
        return result;
    }
}
