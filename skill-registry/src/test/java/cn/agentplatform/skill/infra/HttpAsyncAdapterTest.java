package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.PlatformException;

import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * T09：异步（SUBMIT_QUERY/CALLBACK）没有可重放的请求体，契约声明 multipart 时必须在
 * {@link HttpAsyncAdapter#invoke} 开头显式 400 拒绝——否则表单会被降级成 JSON 体发出，
 * 再由兜底 catch 折叠成“结果未知”，既不可重试也不可诊断。
 */
class HttpAsyncAdapterTest {
    private static Map<String, Object> contract(boolean multipart) {
        var mapping = new LinkedHashMap<String, Object>();
        mapping.put("path", List.of());
        mapping.put("query", List.of());
        if (multipart) {
            mapping.put(
                    "multipart",
                    Map.of(
                            "fields", List.of(Map.of("name", "payloadType", "from", "payloadType")),
                            "files",
                                    List.of(
                                            Map.of(
                                                    "name", "file",
                                                    "from", "file",
                                                    "filename", "trajectory.csv",
                                                    "maxBytes", 1024L,
                                                    "allowedContentTypes", List.of("text/csv")))));
        } else {
            mapping.put("body", "body");
        }
        var contract = new LinkedHashMap<String, Object>();
        contract.put("type", "HTTP");
        contract.put("endpoint", "http://127.0.0.1:2/api/v1/jobs");
        contract.put("httpMethod", "POST");
        contract.put("invocationKind", "SUBMIT_QUERY");
        contract.put("httpMapping", mapping);
        return contract;
    }

    @Test
    void 声明multipart的异步契约在invoke开头400拒绝() {
        // db 为 null：拒绝发生在任何绑定查询之前，否则这里会是 NPE 而不是显式业务错误。
        var adapter = new HttpAsyncAdapter(null);
        var ex = assertThrows(
                PlatformException.class,
                () ->
                        adapter.invoke(
                                contract(true),
                                Map.of("payloadType", "SPACE", "file", "art_" + "a".repeat(32)),
                                "idem",
                                1L,
                                false,
                                "run",
                                1L,
                                null));
        assertEquals(400, ex.status());
        assertEquals("ASYNC_MULTIPART_UNSUPPORTED", ex.code());
        assertTrue(ex.getMessage().contains("multipart"), ex.getMessage());
        assertTrue(ex.getMessage().contains("同步"), ex.getMessage());
    }

    @Test
    void 拒绝先于尝试身份校验() {
        var adapter = new HttpAsyncAdapter(null);
        // expectedAttempt=0 本应由 requireAttempt 抛 PARAM_ERROR；拿到 multipart 拒绝说明判定确在开头。
        var ex = assertThrows(
                PlatformException.class,
                () ->
                        adapter.invoke(
                                contract(true), Map.of(), "idem", 1L, false, "run", 0L, null));
        assertEquals("ASYNC_MULTIPART_UNSUPPORTED", ex.code());
    }
}
