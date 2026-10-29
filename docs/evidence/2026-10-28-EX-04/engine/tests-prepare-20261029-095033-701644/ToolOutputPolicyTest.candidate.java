package cn.agentplatform.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.agentplatform.common.Json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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

    private static ObjectNode offsetDeclaration() {
        return (ObjectNode)
                Json.read(
                        "{\"mode\":\"OFFSET\",\"request\":{\"offset\":\"skip\",\"size\":\"take\"},"
                                + "\"maxPageSize\":37,\"maxRows\":7}");
    }

    @Test
    void offsetHintKeepsDeclaredCursorAndCapsWithoutInventingObservedValues() {
        var policy = new ToolOutputPolicy(1024);
        var declaration = offsetDeclaration();
        var result =
                Json.tree(
                        oversized(
                                "items",
                                IntStream.range(0, 20).mapToObj(i -> Map.of("id", i)).toList(),
                                "total",
                                9000));
        var original = result.deepCopy();
        long bytes = Json.write(result).getBytes(StandardCharsets.UTF_8).length;

        var content = policy.enforce(toolWithPagination(declaration), result, TRACE);

        var error = content.path("error");
        assertThat(error.path("semantic").asText()).isEqualTo(ToolOutputPolicy.CODE);
        assertThat(error.path("code").asText()).isEqualTo(ToolOutputPolicy.CODE);
        assertThat(error.path("category").asText()).isEqualTo("BUSINESS");
        assertThat(error.path("retryable").asBoolean()).isFalse();
        assertThat(error.path("traceId").asText()).isEqualTo(TRACE);
        assertThat(error.path("reason").asText()).contains(ToolOutputPolicy.CODE);
        assertThat(error.path("detail"))
                .isEqualTo(Json.tree(Map.of("tool", "lookup", "actualBytes", bytes, "maxBytes", 1024L)));
        var hint = content.path("pagination");
        assertThat(hint.isObject()).isTrue();
        assertThat(hint.size()).isEqualTo(6);
        assertThat(hint.path("mode").asText()).isEqualTo("OFFSET");
        assertThat(hint.path("request")).isEqualTo(declaration.path("request"));
        assertThat(hint.path("request").has("page")).isFalse();
        assertThat(hint.path("maxPageSize").asInt()).isEqualTo(37);
        assertThat(hint.path("maxRows").asInt()).isEqualTo(7);
        assertThat(hint.path("observed").isObject()).isTrue();
        assertThat(hint.path("observed").isEmpty()).isTrue();
        assertThat(hint.has("response")).isFalse();
        assertThat(hint.has("nextOffset")).isFalse();
        assertThat(hint.path("hint").asText()).contains("skip", "take");
        assertThat(content.has("items")).isFalse();
        // 声明的maxRows只作提示；模型可见面替换不修改原始工具输出。
        assertThat(result).isEqualTo(original);
        assertThat(result.path("items").size()).isEqualTo(20);
    }

    @Test
    void pageModeAndLegacyOptionalResponseKeepExistingHintSemantics() {
        var policy = new ToolOutputPolicy(1024);
        var result =
                Json.tree(oversized("items", java.util.List.of(Map.of("id", 1)), "total", 9));
        var explicitPage = (ObjectNode) paginationDeclaration();
        explicitPage.put("mode", "PAGE");
        var explicitHint =
                policy.enforce(toolWithPagination(explicitPage), result, TRACE).path("pagination");
        assertThat(explicitHint.path("request")).isEqualTo(explicitPage.path("request"));
        assertThat(explicitHint.path("response")).isEqualTo(explicitPage.path("response"));
        assertThat(explicitHint.path("maxPageSize").asInt()).isEqualTo(200);
        assertThat(explicitHint.path("observed"))
                .isEqualTo(Json.tree(Map.of("total", 9, "itemCount", 1)));

        var legacyMinimal = Json.read("{\"request\":{\"page\":\"p\",\"size\":\"n\"}}");
        var minimalHint =
                policy.enforce(toolWithPagination(legacyMinimal), result, TRACE).path("pagination");
        assertThat(minimalHint.path("request")).isEqualTo(legacyMinimal.path("request"));
        assertThat(minimalHint.has("mode")).isFalse();
        assertThat(minimalHint.has("maxPageSize")).isFalse();
        assertThat(minimalHint.has("response")).isFalse();
        assertThat(minimalHint.path("observed").isObject()).isTrue();
        assertThat(minimalHint.path("observed").isEmpty()).isTrue();

        var optionalEmptyResponse = (ObjectNode) legacyMinimal.deepCopy();
        optionalEmptyResponse.set("response", Json.object());
        var emptyResponseHint =
                policy.enforce(toolWithPagination(optionalEmptyResponse), result, TRACE)
                        .path("pagination");
        assertThat(emptyResponseHint.path("response")).isEqualTo(Json.object());
        assertThat(emptyResponseHint.path("observed")).isEqualTo(Json.object());
    }

    @Test
    void exactThresholdKeepsSameNodeForOffsetMalformedAndAbsentDeclarations() {
        var result = Json.read("{\"text\":\"精确字节阈值\"}");
        var policy = new ToolOutputPolicy(Json.write(result).getBytes(StandardCharsets.UTF_8).length);
        for (var tool :
                java.util.List.of(
                        toolWithPagination(offsetDeclaration()),
                        toolWithPagination(Json.object()),
                        noDeclaration())) {
            assertSame(result, policy.enforce(tool, result, TRACE));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDeclaredPagination")
    void invalidPaginationDoesNotCreateRuntimeHintOrThrow(String label, JsonNode declaration) {
        var policy = new ToolOutputPolicy(1024);
        var tool = toolWithPagination(declaration);
        var result = Json.tree(Map.of("blob", "x".repeat(4096)));

        assertThat(ToolOutputPolicy.declaredPagination(tool)).as(label).isNull();
        var content = policy.enforce(tool, result, TRACE);
        assertThat(content.has("pagination")).as(label).isFalse();
        assertThat(content.path("error").path("code").asText()).isEqualTo(ToolOutputPolicy.CODE);
        assertThat(content.path("error").path("traceId").asText()).isEqualTo(TRACE);
    }

    private static Stream<Arguments> invalidDeclaredPagination() {
        var page = (ObjectNode) paginationDeclaration();
        return Stream.of(
                Arguments.of("null declaration", Json.read("null")),
                Arguments.of("empty declaration", Json.object()),
                Arguments.of("OFFSET without mode", offsetDeclaration().without("mode")),
                Arguments.of(
                        "OFFSET with page mixed into request",
                        offsetDeclaration()
                                .set("request", Json.read("{\"offset\":\"skip\",\"size\":\"take\",\"page\":\"p\"}"))),
                Arguments.of(
                        "OFFSET numeric parameter name",
                        offsetDeclaration()
                                .set("request", Json.read("{\"offset\":3,\"size\":\"take\"}"))),
                Arguments.of(
                        "OFFSET blank parameter name",
                        offsetDeclaration()
                                .set("request", Json.read("{\"offset\":\"  \",\"size\":\"take\"}"))),
                Arguments.of("OFFSET missing maxRows", offsetDeclaration().without("maxRows")),
                Arguments.of("OFFSET zero page cap", offsetDeclaration().put("maxPageSize", 0)),
                Arguments.of("OFFSET fractional rows cap", offsetDeclaration().put("maxRows", 1.5)),
                Arguments.of("OFFSET textual rows cap", offsetDeclaration().put("maxRows", "7")),
                Arguments.of(
                        "OFFSET overflowing page cap", offsetDeclaration().put("maxPageSize", 2147483648L)),
                Arguments.of("OFFSET unknown key", offsetDeclaration().put("nextOffset", 10)),
                Arguments.of(
                        "OFFSET response is not a supported declaration",
                        offsetDeclaration().set("response", Json.read("{\"itemsPath\":\"$.items\"}"))),
                Arguments.of(
                        "unknown mode with otherwise valid PAGE", page.deepCopy().put("mode", "CURSOR")),
                Arguments.of(
                        "null mode with otherwise valid PAGE", page.deepCopy().putNull("mode")),
                Arguments.of(
                        "PAGE numeric parameter name",
                        page.deepCopy()
                                .set("request", Json.read("{\"page\":3,\"size\":\"size\"}"))),
                Arguments.of(
                        "PAGE with offset mixed into request",
                        page.deepCopy()
                                .set("request", Json.read("{\"page\":\"page\",\"size\":\"size\",\"offset\":\"skip\"}"))),
                Arguments.of("PAGE negative cap", page.deepCopy().put("maxPageSize", -1)),
                Arguments.of("PAGE malformed response", page.deepCopy().put("response", 3)),
                Arguments.of(
                        "PAGE nontext response path",
                        page.deepCopy().set("response", Json.read("{\"itemsPath\":3}"))),
                Arguments.of("PAGE must not declare OFFSET maxRows", page.deepCopy().put("maxRows", 7)));
    }
}
