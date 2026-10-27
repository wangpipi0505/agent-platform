package cn.agentplatform.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.agentplatform.common.Json;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PLAT-14 T14.2（大响应策略·纯函数部分）：工具输出注入模型上下文前的引擎级阈值策略。
 *
 * <p>语义定死（方案 §4.14"适配器对超限响应返回 OUTPUT_TOO_LARGE + 分页建议，不静默截断"）：
 * 超限不静默截断、不再整块塞给模型——以 OUTPUT_TOO_LARGE 可读错误信封
 * （semantic/code/category/retryable/userMessage/detail/traceId + reason 兼容键，含
 * 实际大小与上限）替换模型可见内容；工具契约声明了分页（contract.pagination，方案
 * §4.14 形状）时附分页元信息供模型带页参续拉。原始输出不丢：替换只发生在模型可见面，
 * 引擎调用事实与技能侧 trace 仍保留完整结果。
 */
class ToolOutputPolicyTest {
    private static final String TRACE = "r_test:7:round0:tc1";

    private static JsonNode toolWith(JsonNode contract) {
        return Json.tree(Map.of("code", "lookup", "contract", contract));
    }

    /** 分页声明的载体形状：技能契约里的 pagination 键（contract.pagination）。 */
    private static JsonNode toolWithPagination(JsonNode declaration) {
        return toolWith(Json.tree(Map.of("pagination", declaration)));
    }

    private static JsonNode noDeclaration() {
        return toolWith(Json.object());
    }

    private static JsonNode paginationDeclaration() {
        return Json.read(
                "{\"request\":{\"page\":\"page\",\"size\":\"size\"},"
                        + "\"response\":{\"itemsPath\":\"$.items\",\"totalPath\":\"$.total\"},"
                        + "\"maxPageSize\":200}");
    }

    /** 超过默认阈值 262144 的填充负载（~300KB）。 */
    private static Map<String, Object> oversized(Object... extra) {
        var payload = new LinkedHashMap<String, Object>();
        for (int i = 0; i < extra.length; i += 2) payload.put((String) extra[i], extra[i + 1]);
        payload.put("blob", "x".repeat(300 * 1024));
        return payload;
    }

    // === ② 阈值内零变化锚点 ================================================================

    @Test
    void withinThresholdReturnsResultUnchanged() {
        var policy = ToolOutputPolicy.defaults();
        var result = Json.read("{\"value\":1,\"text\":\"普通工具输出\"}");
        assertSame(result, policy.enforce(noDeclaration(), result, TRACE));
    }

    @Test
    void defaultThresholdIsBelowP2_11Scenario() {
        // 台账验收锚点：P2-11 的 550KB 场景必须触发；默认 256KiB < 550KB。
        assertThat(ToolOutputPolicy.defaults().maxBytes()).isEqualTo(262144);
        assertThat(ToolOutputPolicy.defaults().maxBytes()).isLessThan(550L * 1024);
    }

    // === ① 超限无声明：可读错误，不静默截断 =================================================

    @Test
    void overLimitWithoutDeclarationProducesReadableError() {
        var policy = ToolOutputPolicy.defaults();
        var result =
                Json.tree(
                        oversized(
                                "items",
                                java.util.List.of(
                                        Map.of("name", "satellite-0001", "marker", "LEO-ALPHA-MARKER")),
                                "total",
                                5414));
        long bytes = Json.write(result).getBytes(StandardCharsets.UTF_8).length;
        var content = policy.enforce(noDeclaration(), result, TRACE);
        var error = content.path("error");
        assertThat(error.path("semantic").asText()).isEqualTo("OUTPUT_TOO_LARGE");
        assertThat(error.path("code").asText()).isEqualTo("OUTPUT_TOO_LARGE");
        assertThat(error.path("category").asText()).isEqualTo("BUSINESS");
        assertThat(error.path("retryable").asBoolean()).isFalse();
        // userMessage 可读且含实际大小与上限。
        assertThat(error.path("userMessage").asText())
                .contains(String.valueOf(bytes))
                .contains("262144");
        assertThat(error.path("traceId").asText()).isEqualTo(TRACE);
        assertThat(error.path("detail").path("actualBytes").asLong()).isEqualTo(bytes);
        assertThat(error.path("detail").path("maxBytes").asLong()).isEqualTo(262144);
        assertThat(error.path("detail").path("tool").asText()).isEqualTo("lookup");
        // 七字段信封保持 reason 兼容键。
        assertThat(error.path("reason").asText()).contains("OUTPUT_TOO_LARGE");
        // 大负载本体绝不进入模型可见内容（不整块塞给模型）。
        assertThat(content.toString()).doesNotContain("LEO-ALPHA-MARKER");
        // 无分页声明：不发明分页提示。
        assertThat(content.has("pagination")).isFalse();
    }

    // === ③ 声明分页：错误 + 分页元信息（按声明路径提取观测值） ==============================

    @Test
    void declaredPaginationAddsHintWithObservedTotal() {
        var policy = ToolOutputPolicy.defaults();
        var result =
                Json.tree(
                        oversized(
                                "items",
                                java.util.List.of(
                                        Map.of("id", 1, "name", "satellite-0001"),
                                        Map.of("id", 2)),
                                "page",
                                0,
                                "total",
                                5414));
        long bytes = Json.write(result).getBytes(StandardCharsets.UTF_8).length;
        var content = policy.enforce(toolWithPagination(paginationDeclaration()), result, TRACE);
        var error = content.path("error");
        assertThat(error.path("code").asText()).isEqualTo("OUTPUT_TOO_LARGE");
        assertThat(error.path("detail").path("actualBytes").asLong()).isEqualTo(bytes);
        var hint = content.path("pagination");
        assertThat(hint.path("request").path("page").asText()).isEqualTo("page");
        assertThat(hint.path("request").path("size").asText()).isEqualTo("size");
        assertThat(hint.path("response").path("itemsPath").asText()).isEqualTo("$.items");
        assertThat(hint.path("response").path("totalPath").asText()).isEqualTo("$.total");
        assertThat(hint.path("maxPageSize").asInt()).isEqualTo(200);
        // 按声明路径从原响应提取的观测值：模型据此带页参续拉。
        assertThat(hint.path("observed").path("total").asLong()).isEqualTo(5414);
        assertThat(hint.path("observed").path("itemCount").asInt()).isEqualTo(2);
        assertThat(hint.path("hint").asText()).isNotBlank();
    }

    @Test
    void declarationWithoutMatchingPathsStillHintsDeclaredParams() {
        var policy = ToolOutputPolicy.defaults();
        // 原响应不含声明路径能命中的字段：提示仍带声明参数，观测值缺省不伪造。
        var result = Json.tree(oversized());
        var content = policy.enforce(toolWithPagination(paginationDeclaration()), result, TRACE);
        assertThat(content.path("pagination").path("request").path("page").asText())
                .isEqualTo("page");
        assertThat(content.path("pagination").path("observed").has("total")).isFalse();
    }

    // === ④ 形状非法的分页声明视为未声明（防御读取，不误伤既有工具） =========================

    @Test
    void malformedPaginationDeclarationTreatedAsUndeclared() {
        var policy = ToolOutputPolicy.defaults();
        var result = Json.tree(Map.of("blob", "x".repeat(4096)));
        // 非对象声明
        var textual = Json.tree(Map.of("code", "lookup", "contract", Map.of("pagination", "page")));
        assertThat(policy.enforce(textual, result, TRACE).has("pagination")).isFalse();
        // 缺 request.page/size 的对象声明
        var incomplete =
                toolWith(
                        Json.tree(
                                Map.of(
                                        "pagination",
                                        Json.read("{\"request\":{\"page\":\"page\"},\"maxPageSize\":10}"))));
        assertThat(policy.enforce(incomplete, result, TRACE).has("pagination")).isFalse();
    }

    // === ⑤ 阈值可配置 ======================================================================

    @Test
    void thresholdIsConfigurable() {
        // 4KB 负载：高于收紧后的 1024、低于默认 262144——同一负载随阈值配置改变命运。
        var result = Json.tree(Map.of("blob", "x".repeat(4096)));
        var strict = new ToolOutputPolicy(1024);
        assertThat(strict.maxBytes()).isEqualTo(1024);
        var content = strict.enforce(noDeclaration(), result, TRACE);
        assertThat(content.path("error").path("code").asText()).isEqualTo("OUTPUT_TOO_LARGE");
        assertThat(content.path("error").path("detail").path("maxBytes").asLong()).isEqualTo(1024);
        // 同一负载在默认阈值下原样放行：配置改的是阈值，不是行为语义。
        assertSame(result, ToolOutputPolicy.defaults().enforce(noDeclaration(), result, TRACE));
    }

    @Test
    void nonPositiveThresholdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ToolOutputPolicy(0));
        assertThrows(IllegalArgumentException.class, () -> new ToolOutputPolicy(-1));
    }

    // === 度量口径：UTF-8 字节而非字符数 =====================================================

    @Test
    void sizeMeasuredInUtf8BytesNotChars() {
        // 序列化串 211 个字符（{"text":"…"} 包裹 + 200 个 CJK 字符），UTF-8 却是 611 字节；
        // 阈值 300：按字节超限必须拒绝，按字符数度量则不会拒绝——此用例可辨析度量口径。
        var policy = new ToolOutputPolicy(300);
        var result = Json.tree(Map.of("text", "星".repeat(200)));
        long expected = Json.write(result).getBytes(StandardCharsets.UTF_8).length;
        assertThat(expected).isEqualTo(611);
        assertThat(Json.write(result).length()).isLessThan(300);
        var content = policy.enforce(noDeclaration(), result, TRACE);
        assertThat(content.path("error").path("detail").path("actualBytes").asLong())
                .isEqualTo(expected);
    }

    @Test
    void traceIdFlowsThroughVerbatim() {
        var policy = ToolOutputPolicy.defaults();
        var result = Json.tree(oversized());
        var content = policy.enforce(noDeclaration(), result, "run-abc:node-9:round2:call-77");
        assertThat(content.path("error").path("traceId").asText())
                .isEqualTo("run-abc:node-9:round2:call-77");
    }
}
