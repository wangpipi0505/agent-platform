package cn.agentplatform.skill.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.skill.domain.ErrorSemantics.Declared;
import cn.agentplatform.skill.domain.ErrorSemantics.Remote;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

import org.junit.jupiter.api.Test;

/** PLAT-05：七字段错误对象的纯函数规范化（无 IO），及 errorSemantics 声明解析。 */
class ErrorSemanticsTest {

    @Test
    void errorObjectAlwaysCarriesSevenFieldsWithCorrectSemantics() {
        var error =
                ErrorSemantics.error(
                        "REMOTE_FAILED",
                        "TLE_CHECKSUM_INVALID",
                        "外部任务终态失败：wrong checksum",
                        "wrong checksum",
                        "trace-1");
        for (String field :
                List.of(
                        "semantic",
                        "code",
                        "category",
                        "retryable",
                        "userMessage",
                        "detail",
                        "traceId"))
            assertTrue(error.containsKey(field), "缺少字段: " + field);
        assertEquals("REMOTE_FAILED", error.get("semantic"));
        assertEquals("TLE_CHECKSUM_INVALID", error.get("code"));
        assertEquals("BUSINESS", error.get("category"));
        assertEquals(false, error.get("retryable"));
        assertEquals("外部任务失败：wrong checksum", error.get("userMessage"));
        assertEquals(Map.of("raw", "wrong checksum"), error.get("detail"));
        assertEquals("trace-1", error.get("traceId"));
        // reason 是既有结构的兼容键，保留原文诊断信息。
        assertEquals("外部任务终态失败：wrong checksum", error.get("reason"));
    }

    @Test
    void unknownSemanticsFallBackToSystemWithoutRetry() {
        var error = ErrorSemantics.error("MYSTERY", null, "未知失败", null, null);
        assertEquals("SYSTEM", error.get("category"));
        assertEquals(false, error.get("retryable"));
        assertNull(error.get("code"));
        assertNull(error.get("detail"));
        assertEquals("MYSTERY", error.get("semantic"));
    }

    @Test
    void semanticDefaultsCoverTheFiveLegacyClasses() {
        assertEquals("PARAM", ErrorSemantics.error("PARAM_ERROR", null, null, null, null).get("category"));
        assertEquals("AUTH", ErrorSemantics.error("NO_PERMISSION", null, null, null, null).get("category"));
        assertEquals("QUOTA", ErrorSemantics.error("QUOTA", null, null, null, null).get("category"));
        assertEquals(true, ErrorSemantics.error("QUOTA", null, null, null, null).get("retryable"));
        assertEquals(
                true,
                ErrorSemantics.error("RETRYABLE_FAILURE", null, null, null, null).get("retryable"));
        assertEquals(
                false,
                ErrorSemantics.error("RESULT_UNKNOWN", null, null, null, null).get("retryable"));
        assertEquals(
                "TRANSPORT",
                ErrorSemantics.error("RESULT_UNKNOWN", null, null, null, null).get("category"));
    }

    @Test
    void declaredMappingParsesObjectFormOnly() {
        var declared =
                Declared.parse(
                        Map.of(
                                "codePath",
                                "$.code",
                                "messagePath",
                                "$.message",
                                "retryableCodes",
                                List.of("TIMEOUT", "THROTTLED")));
        assertEquals("$.code", declared.codePath());
        assertEquals("$.message", declared.messagePath());
        assertEquals(Set.of("TIMEOUT", "THROTTLED"), declared.retryableCodes());
        // v1.2 的数组形态（match 列表）与缺省同样按平台默认映射解释，声明对象才是本期的解释对象。
        assertEquals(Declared.NONE, Declared.parse(List.of(Map.of("match", "HTTP 403"))));
        assertEquals(Declared.NONE, Declared.parse(null));
        assertEquals(Declared.NONE, Declared.parse(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> Declared.parse(Map.of("codePath", "code")));
        assertThrows(
                IllegalArgumentException.class,
                () -> Declared.parse(Map.of("retryableCodes", "TIMEOUT")));
    }

    @Test
    void extractionUsesDeclaredPathsBeforePlatformDefaults() {
        JsonNode body =
                cn.agentplatform.common.Json.read(
                        "{\"ok\":false,\"bizCode\":\"DATA_SCOPE_DENIED\",\"text\":\"无权访问其他部门数据\"}");
        var declared = Declared.parse(Map.of("codePath", "$.bizCode", "messagePath", "$.text"));
        Remote remote = ErrorSemantics.extract(body, declared);
        assertEquals("DATA_SCOPE_DENIED", remote.code());
        assertEquals("无权访问其他部门数据", remote.message());

        Remote defaults = ErrorSemantics.extract(body, Declared.NONE);
        assertNull(defaults.code());
        assertNull(defaults.message());

        JsonNode bridge =
                cn.agentplatform.common.Json.read(
                        "{\"ok\":false,\"code\":\"DATA_SCOPE_DENIED\",\"message\":\"无权访问其他部门数据\"}");
        Remote bridgeRemote = ErrorSemantics.extract(bridge, Declared.NONE);
        assertEquals("DATA_SCOPE_DENIED", bridgeRemote.code());
        assertEquals("无权访问其他部门数据", bridgeRemote.message());

        assertEquals(Remote.NONE, ErrorSemantics.extract(null, declared));
        assertEquals(
                Remote.NONE, ErrorSemantics.extract(cn.agentplatform.common.Json.read("\"text\""), declared));
    }

    @Test
    void retryableCodeHitOnlyAppliesToDeclaredList() {
        var declared = Declared.parse(Map.of("retryableCodes", List.of("TIMEOUT")));
        assertTrue(ErrorSemantics.retryableCode(declared, new Remote("TIMEOUT", null)));
        assertFalse(ErrorSemantics.retryableCode(declared, new Remote("BAD_INPUT", null)));
        assertFalse(ErrorSemantics.retryableCode(declared, new Remote(null, null)));
        assertFalse(ErrorSemantics.retryableCode(Declared.NONE, new Remote("TIMEOUT", null)));
    }
}
