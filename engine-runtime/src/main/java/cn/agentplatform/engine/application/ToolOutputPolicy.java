package cn.agentplatform.engine.application;

import cn.agentplatform.common.Json;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * PLAT-14 T14.2：工具（技能/MCP）输出注入模型上下文前的引擎级大响应策略。
 *
 * <p>语义定死（方案 §4.14"适配器对超限响应返回 OUTPUT_TOO_LARGE + 分页建议，不静默截断"）：
 * 工具输出按 UTF-8 序列化字节超过引擎阈值时，不再整块塞给模型、也不静默截断，而是把
 * 模型可见的工具结果替换为可读错误信封 OUTPUT_TOO_LARGE（semantic/code/category/
 * retryable/userMessage/detail{tool,actualBytes,maxBytes}/traceId + reason 兼容键）；
 * 工具契约声明了分页时额外附分页元信息（声明的页参名/上限/响应路径 + 按路径从原响应
 * 提取的 total、条目数观测值），模型可带页参续拉——大响应转为可分页消费而不是禁用。
 * 运行不因超限直接失败：与 PLAT-05 可解释拒绝同构，错误作为工具结果交给模型处理，
 * 无进展循环由既有 NO_PROGRESS 指纹熔断兜底。
 *
 * <p>原始输出不丢：替换只发生在模型可见面，引擎 tool_call 调用事实与技能侧 trace 仍
 * 保留完整结果（ContextProjection 的 TOOL_OUTPUT_TRIM 等兜底链路保持不变，本策略是
 * 它上游的源头闸门）。阈值内的输出逐字原样返回——无声明工具、小输出零变化。
 *
 * <p>分页声明载体：引擎工具来自应用冻结快照 config.tools[]，其 contract 键即技能契约
 * （RunService.prepareSnapshot 原样透传），故按方案 §4.14 形状消费
 * contract.pagination（request.page/size + response.itemsPath/totalPath + maxPageSize）。
 * 本体侧 O-02 的 exposure 分页声明落在 ontorepo 工具投影，不经此结构到引擎；两侧语义
 * 一致（声明了分页参数/上限就遵循），载体位置以各自代码现状为准。声明形状非法一律
 * 视为未声明（防御读取，注册侧校验不在本卡范围）。当前无技能声明分页时全部走引擎级
 * 默认阈值。
 *
 * <p>调参：application.yml `agent.tool.output-max-bytes`（环境变量
 * AGENT_TOOL_OUTPUT_MAX_BYTES），默认 262144（256KiB），低于 P2-11 的 550KB 场景。
 */
@Component
public final class ToolOutputPolicy {
    public static final long DEFAULT_MAX_BYTES = 262144;
    public static final String CODE = "OUTPUT_TOO_LARGE";

    private final long maxBytes;

    public ToolOutputPolicy(@Value("${agent.tool.output-max-bytes:262144}") long maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("agent.tool.output-max-bytes 必须为正整数");
        this.maxBytes = maxBytes;
    }

    /** 手工构造（测试夹具、非 Spring 装配）时的默认策略：256KiB。 */
    public static ToolOutputPolicy defaults() {
        return new ToolOutputPolicy(DEFAULT_MAX_BYTES);
    }

    public long maxBytes() {
        return maxBytes;
    }

    /**
     * 阈值内原样返回同一节点（零变化锚点）；超限返回替换后的模型可见内容：
     * {error: 信封} 或 {error: 信封, pagination: 分页提示}。
     *
     * @param tool    冻结快照 config.tools[] 中的工具声明（可含 contract.pagination）
     * @param result  拦截链处理后的工具执行结果
     * @param traceId 调用定位串（run/node/round/call），错误信封可追溯性必需
     */
    public JsonNode enforce(JsonNode tool, JsonNode result, String traceId) {
        long actual = Json.write(result).getBytes(StandardCharsets.UTF_8).length;
        if (actual <= maxBytes) return result;
        JsonNode declaration = declaredPagination(tool);
        var replacement = Json.object();
        replacement.set("error", Json.tree(envelope(tool, actual, traceId, declaration != null)));
        if (declaration != null) replacement.set("pagination", paginationHint(declaration, result));
        return replacement;
    }

    /** 七字段统一错误信封（+reason 兼容键），与 contracts/skill-invocation.md §11.1 同构。 */
    private Map<String, Object> envelope(
            JsonNode tool, long actual, String traceId, boolean paginated) {
        var error = new LinkedHashMap<String, Object>();
        error.put("semantic", CODE);
        error.put("code", CODE);
        error.put("category", "BUSINESS");
        error.put("retryable", false);
        error.put(
                "userMessage",
                "工具输出 " + actual + " 字节超过引擎上限 " + maxBytes
                        + " 字节，已保留在调用事实中、未注入模型上下文；请缩小查询范围"
                        + (paginated ? "或使用工具声明的分页参数分批获取" : "") + "后重试");
        error.put(
                "detail",
                Map.of(
                        "tool",
                        tool.path("code").asText(tool.path("name").asText("")),
                        "actualBytes",
                        actual,
                        "maxBytes",
                        maxBytes));
        error.put("traceId", Objects.toString(traceId, ""));
        error.put("reason", CODE + ": actual=" + actual + "B limit=" + maxBytes + "B");
        return error;
    }

    /** 分页声明读取：非对象或缺 request.page/size 文本一律视为未声明。 */
    static JsonNode declaredPagination(JsonNode tool) {
        var declaration = tool.path("contract").path("pagination");
        if (!declaration.isObject()) return null;
        var request = declaration.path("request");
        if (request.path("page").asText("").isBlank() || request.path("size").asText("").isBlank())
            return null;
        return declaration;
    }

    /** 分页提示：声明原样透传 + 按声明响应路径提取的观测值 + 续拉指引。不切片原响应。 */
    private JsonNode paginationHint(JsonNode declaration, JsonNode result) {
        var hint = Json.object();
        hint.set("request", declaration.path("request").deepCopy());
        if (declaration.path("response").isObject())
            hint.set("response", declaration.path("response").deepCopy());
        if (declaration.path("maxPageSize").isIntegralNumber())
            hint.set("maxPageSize", declaration.path("maxPageSize"));
        var observed = Json.object();
        JsonNode total =
                resolve(result, declaration.path("response").path("totalPath").asText(""));
        if (!total.isMissingNode() && !total.isNull()) observed.set("total", total);
        JsonNode items =
                resolve(result, declaration.path("response").path("itemsPath").asText(""));
        if (items.isArray()) observed.put("itemCount", items.size());
        hint.set("observed", observed);
        hint.put(
                "hint",
                "本次输出超过引擎上限、未注入模型；请按 request 声明的分页参数（"
                        + declaration.path("request").path("page").asText()
                        + "/"
                        + declaration.path("request").path("size").asText()
                        + "）分批调用本工具并汇总结果");
        return hint;
    }

    /** 最小路径解析：仅支持方案 §4.14 示例的 "$.a.b" 与 "a.b" 点路径；解析不到返回 Missing。 */
    static JsonNode resolve(JsonNode node, String path) {
        if (path == null || path.isBlank())
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        var cursor = node;
        for (String segment : path.replaceFirst("^\\$\\.", "").split("\\.", -1)) {
            if (segment.isEmpty()) continue;
            cursor = cursor.path(segment);
        }
        return cursor;
    }
}
