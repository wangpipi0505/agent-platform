package cn.agentplatform.engine.harness.loop;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * pre-step 责任链挂点（H1-4 骨架，H1-6/T2-9 挂接）：turn 内每步模型请求前按序执行，
 * 可改写或拒绝消息（抛异常短路）。默认空链零开销。压缩触发点（T2-9）挂本链。
 */
public final class PreStepChain {

    /** 改写/拒绝钩子：返回替换后的 inputs；抛异常即拒绝本步。 */
    public interface PreStep {
        JsonNode beforeStep(String sessionId, long turn, JsonNode inputs);
    }

    public static final PreStepChain EMPTY = new PreStepChain(java.util.List.of());

    private final java.util.List<PreStep> steps;

    public PreStepChain(java.util.List<PreStep> steps) {
        this.steps = java.util.List.copyOf(steps);
    }

    /** 按序执行；任一环节异常向上传播（拒绝语义由抛出方负责）。 */
    public JsonNode apply(String sessionId, long turn, JsonNode inputs) {
        for (PreStep step : steps) inputs = step.beforeStep(sessionId, turn, inputs);
        return inputs;
    }
}
