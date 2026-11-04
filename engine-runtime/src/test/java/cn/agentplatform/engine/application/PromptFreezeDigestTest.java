package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.infra.RuntimeClients;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 冻结是模板摘要进入不可变运行的唯一时机：摘要非法必须拒绝冻结，合法摘要必须留痕到
 * versionRefs，供事后核对"这次运行到底用了哪一版提示词正文"。
 */
class PromptFreezeDigestTest {
    private static final String SHA = Digest.sha256("prompt-v1-body");
    private static final String TEMPLATE_PREFIX = "/internal/prompt-templates/";

    /** 只允许读取已发布模板的依赖桩；任何其它外部调用或多余读取都会失败。 */
    private static final class AppStub {
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicReference<String> body = new AtomicReference<>("{}");

        RuntimeClients clients() {
            return new RuntimeClients() {
                @Override
                public JsonNode get(String service, String path, Actor actor) {
                    if (!"app".equals(service) || !path.startsWith(TEMPLATE_PREFIX))
                        throw new AssertionError("冻结只应读取已发布模板，实际读取 " + service + path);
                    reads.incrementAndGet();
                    return Json.read(body.get());
                }

                @Override
                public JsonNode post(String service, String path, Object payload, Actor actor) {
                    throw new AssertionError("冻结不得调用写接口 " + service + path);
                }
            };
        }
    }

    private static Actor actor() {
        return new Actor("tenant-a", "user-1", Set.of(), Map.of());
    }

    private static RunService service(AppStub stub) {
        return new RunService(null, stub.clients(), null, null);
    }

    private static ObjectNode promptRef(String code, int version) {
        var ref = Json.object();
        ref.put("code", code);
        ref.put("version", version);
        return ref;
    }

    private static JsonNode appSnapshot(List<JsonNode> configRefs, List<JsonNode> nodeRefs) {
        var config = Json.object();
        config.put("modelEntryId", 1);
        var configured = config.putArray("promptRefs");
        configRefs.forEach(configured::add);
        var definition = Json.object();
        var nodes = definition.putArray("nodes");
        for (JsonNode ref : nodeRefs)
            nodes.addObject().put("id", "n-" + nodes.size()).set("promptRef", ref);
        var snapshot = Json.object();
        snapshot.put("versionNo", 1);
        snapshot.set("config", config);
        snapshot.set("definition", definition);
        return snapshot;
    }

    /** 已发布模板的读回形状；sha 为 null 时模拟升级前发布、库里没有摘要的模板。 */
    private static String template(String sha) {
        var node = Json.object();
        node.put("code", "hello");
        node.put("versionNo", 1);
        node.put("status", "PUBLISHED");
        node.put("content", "你好 {{name}}");
        var variable = node.putArray("variables").addObject();
        variable.put("name", "name");
        variable.put("type", "string");
        node.set("outputContract", Json.read("{\"format\":\"text\"}"));
        if (sha != null) node.put("sha256", sha);
        return Json.write(node);
    }

    private static JsonNode freeze(RunService service, JsonNode snapshot) throws Exception {
        var method =
                RunService.class.getDeclaredMethod(
                        "prepareSnapshot", JsonNode.class, Actor.class, Long.class);
        method.setAccessible(true);
        return (JsonNode) method.invoke(service, snapshot, actor(), null);
    }

    /** 反射调用会把运行时异常包成 InvocationTargetException；取回真实异常便于断言状态码。 */
    private static PlatformException freezeFailure(RunService service, JsonNode snapshot) {
        var thrown = assertThrows(InvocationTargetException.class, () -> freeze(service, snapshot));
        var cause = thrown.getCause();
        assertInstanceOf(PlatformException.class, cause);
        return (PlatformException) cause;
    }

    private static JsonNode versionRefs(RunService service, JsonNode source, JsonNode frozen)
            throws Exception {
        var method =
                RunService.class.getDeclaredMethod(
                        "versionRefs", JsonNode.class, JsonNode.class, JsonNode.class);
        method.setAccessible(true);
        return Json.read(Json.write(method.invoke(service, source, frozen, Json.object())));
    }

    /** 开关经既有装配 setter 设置：字段迁到快照协作对象后仍只走受控装配面。 */
    private static void digestCheck(RunService service, boolean enabled) {
        service.promptDigestCheck(enabled);
    }

    @Test
    void frozenPromptRecordsDigestInVersionRefs() throws Exception {
        var stub = new AppStub();
        stub.body.set(template(SHA));
        var service = service(stub);
        var source = appSnapshot(List.of(), List.of(promptRef("hello", 1)));

        var frozen = freeze(service, source);
        assertEquals(1, stub.reads.get());
        assertEquals(1, frozen.path("config").path("frozenPrompts").size());

        var refs = versionRefs(service, source, frozen);
        assertEquals(1, refs.path("promptShas").size(), "冻结摘要必须留痕到 versionRefs.promptShas");
        var entry = refs.path("promptShas").path("hello");
        assertEquals(1, entry.path("version").asInt());
        assertEquals(SHA, entry.path("sha256").asText());
        assertEquals(1, refs.path("promptVers").path("hello").asInt(), "promptVers 形状保持不变");
    }

    @Test
    void templateWithoutValidDigestIsRejected() {
        var stub = new AppStub();
        var service = service(stub);
        var source = appSnapshot(List.of(), List.of(promptRef("hello", 1)));
        for (String sha : Arrays.asList(null, "a".repeat(63), "B".repeat(64))) {
            stub.body.set(template(sha));
            var failure = freezeFailure(service, source);
            assertEquals(409, failure.status(), "摘要非法必须拒绝冻结: " + sha);
            assertEquals("PROMPT_TEMPLATE_SHA_INVALID", failure.code());
            assertTrue(failure.getMessage().contains("hello"), failure.getMessage());
            assertTrue(failure.getMessage().contains("1"), failure.getMessage());
        }
    }

    @Test
    void duplicatePromptReferencesFreezeOnceAndRecordOneDigest() throws Exception {
        var stub = new AppStub();
        stub.body.set(template(SHA));
        var service = service(stub);
        var ref = promptRef("hello", 1);
        var source = appSnapshot(List.of(ref), List.of(ref));

        var frozen = freeze(service, source);
        assertEquals(1, stub.reads.get(), "同一 code:version 只允许读取一次");
        assertEquals(1, frozen.path("config").path("frozenPrompts").size());

        var refs = versionRefs(service, source, frozen);
        assertEquals(1, refs.path("promptShas").size());
        assertEquals(SHA, refs.path("promptShas").path("hello").path("sha256").asText());
    }

    @Test
    void disabledDigestCheckSkipsRejectionButNeverRecordsInvalidDigest() throws Exception {
        var stub = new AppStub();
        stub.body.set(template(null));
        var service = service(stub);
        digestCheck(service, false);
        var source = appSnapshot(List.of(), List.of(promptRef("hello", 1)));

        var frozen = freeze(service, source);
        var refs = versionRefs(service, source, frozen);
        assertEquals(0, refs.path("promptShas").size(), "关闭校验也不得把无效摘要写成留痕");
        assertEquals(1, refs.path("promptVers").path("hello").asInt());
    }
}
