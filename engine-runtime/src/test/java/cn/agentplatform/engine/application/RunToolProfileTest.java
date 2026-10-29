package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.engine.infra.RuntimeClients;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PLAT-14 T14.1（工具档案 + toolProfile 选择）：
 *
 * <ul>
 *   <li>profile 是声明式数据：应用配置 toolProfile（字符串）声明档案名，工具引用上的
 *       profiles（数组）声明归属。选择逻辑只读这两条声明，不存在任何路线/软件名分支
 *       （开发计划 §6.1）——任意 profile 名（含形似路线名的名字）行为完全由成员声明决定。
 *   <li>未声明 profile → 全部工具可见：存量应用冻结快照逐键不变（零回归锚点）。
 *   <li>停用工具（引用上 enabled=false）不装载，对齐 T22A schema tools[].enabled 语义。
 *   <li>语义定死：声明了 profile 但没有任何工具命中 → 放行空工具集继续运行，快照顶层
 *       toolSelection 显式留痕（与 T13 contextPack、T20 runtime 同层级），运行详情可读。
 * </ul>
 */
class RunToolProfileTest {
    /** 只允许读取已声明技能版本的依赖桩；任何其它外部调用或多余读取都会失败。 */
    private static final class SkillStub {
        final Map<String, String> bodies = new HashMap<>();
        final AtomicInteger reads = new AtomicInteger();

        RuntimeClients clients() {
            return new RuntimeClients() {
                @Override
                public JsonNode get(String service, String path, Actor actor) {
                    if (!"skill".equals(service) || !path.startsWith("/internal/skills/"))
                        throw new AssertionError(
                                "冻结只应读取技能版本，实际读取 " + service + path);
                    String code =
                            path.substring("/internal/skills/".length()).split("\\?", 2)[0];
                    String body = bodies.get(code);
                    if (body == null) throw new AssertionError("未声明的技能读取 " + code);
                    reads.incrementAndGet();
                    return Json.read(body);
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

    private static RunService service(SkillStub stub) {
        return new RunService(null, stub.clients(), null, null);
    }

    /** 已发布技能版本的读回形状；guard 为空对象表示对当前主体可见。 */
    private static String skill(String code, long versionId) {
        return skill(code, versionId, Json.object());
    }

    private static String skill(String code, long versionId, JsonNode guard) {
        var node = Json.object();
        node.put("skillVersionId", versionId);
        node.put("status", "PUBLISHED");
        node.put("description", code + " 工具");
        node.set("guard", guard);
        node.set("execution", Json.read("{\"kind\":\"HTTP_SYNC\",\"readonly\":true}"));
        node.set("definition", Json.read("{\"code\":\"" + code + "\"}"));
        node.set("inputSchema", Json.read("{\"type\":\"object\"}"));
        node.set("outputSchema", Json.read("{\"type\":\"object\"}"));
        node.put("digest", cn.agentplatform.common.Digest.sha256(code));
        return Json.write(node);
    }

    /** 工具引用：profiles/enabled 为 null 时不声明该键（存量形状）。 */
    private static JsonNode ref(String code, int version, List<String> profiles, Boolean enabled) {
        var r = Json.object();
        r.put("code", code);
        r.put("version", version);
        if (profiles != null) {
            var arr = r.putArray("profiles");
            profiles.forEach(arr::add);
        }
        if (enabled != null) r.put("enabled", enabled);
        return r;
    }

    private static JsonNode snapshot(
            String toolProfile, List<JsonNode> configRefs, List<JsonNode> nodeRefs) {
        var config = Json.object();
        if (toolProfile != null) config.put("toolProfile", toolProfile);
        var refs = config.putArray("skillRefs");
        configRefs.forEach(refs::add);
        var definition = Json.object();
        var nodes = definition.putArray("nodes");
        for (JsonNode nodeRef : nodeRefs)
            nodes.addObject().put("id", "n-" + nodes.size()).set("skillRef", nodeRef);
        var s = Json.object();
        s.set("config", config);
        s.set("definition", definition);
        return s;
    }

    private static JsonNode freeze(RunService service, JsonNode snapshot) throws Exception {
        var method =
                RunService.class.getDeclaredMethod(
                        "prepareSnapshot", JsonNode.class, Actor.class, Long.class);
        method.setAccessible(true);
        return (JsonNode) method.invoke(service, snapshot, actor(), null);
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<String> toolCodes(JsonNode frozen) {
        List<String> out = new ArrayList<>();
        frozen.path("config").path("tools").forEach(t -> out.add(t.path("code").asText()));
        return out;
    }

    // === ① 声明 profile 后只有属于该档案的工具装载，快照可见 ================================

    @Test
    void declaredProfileLoadsOnlyMemberToolsAndTracesSelection() throws Exception {
        var stub = new SkillStub();
        stub.bodies.put("demo_read", skill("demo_read", 11));
        stub.bodies.put("demo_write", skill("demo_write", 12));
        var service = service(stub);
        // 配置引用与图节点引用两个声明位置都按同一份档案数据裁决。
        var source =
                snapshot(
                        "qa",
                        List.of(ref("demo_read", 1, List.of("qa"), null)),
                        List.of(ref("demo_write", 1, List.of("ops", "qa"), null)));

        var frozen = freeze(service, source);

        assertEquals(List.of("demo_read", "demo_write"), toolCodes(frozen));
        // 声明随工具冻结：config.tools[].profiles 记录档案归属（对齐 22A tools 区块形状）。
        assertEquals(
                List.of("qa"),
                texts(frozen.path("config").path("tools").get(0).path("profiles")));
        assertEquals(
                List.of("ops", "qa"),
                texts(frozen.path("config").path("tools").get(1).path("profiles")));
        // 快照顶层留痕：与 contextPack/runtime 同层级，记录生效清单与所选档案。
        var trace = frozen.path("toolSelection");
        assertTrue(trace.isObject(), "使用档案特性的应用必须在快照留痕");
        assertEquals("qa", trace.path("profile").asText());
        assertEquals(List.of("demo_read", "demo_write"), texts(trace.path("selected")));
        assertFalse(trace.has("excluded"), "全部命中时不得伪造排除记录");
        assertFalse(trace.has("warning"));
    }

    // === ② 未声明 profile 的存量零回归锚点 ==================================================

    @Test
    void legacyAppWithoutProfileFeatureFreezesExactlyAsBefore() throws Exception {
        var stub = new SkillStub();
        stub.bodies.put("demo_read", skill("demo_read", 11));
        stub.bodies.put("demo_write", skill("demo_write", 12));
        var service = service(stub);
        var source =
                snapshot(
                        null,
                        List.of(ref("demo_read", 1, null, null), ref("demo_write", 1, null, null)),
                        List.of());

        var frozen = freeze(service, source);

        assertFalse(frozen.has("toolSelection"), "存量应用冻结快照不得新增任何顶层键");
        assertEquals(List.of("demo_read", "demo_write"), toolCodes(frozen));
        for (JsonNode entry : frozen.path("config").path("tools")) {
            Set<String> keys = new HashSet<>();
            entry.fieldNames().forEachRemaining(keys::add);
            assertEquals(
                    Set.of(
                            "code",
                            "name",
                            "skillVersionId",
                            "execution",
                            "guard",
                            "contract",
                            "inputSchema",
                            "outputSchema",
                            "contractDigest",
                            "definition"),
                    keys,
                    "工具条目形状必须与既有冻结逐键一致，不得新增 profiles 键");
        }
    }

    // === ③ 工具的 profiles 未含目标档案 → 不装载 ============================================

    @Test
    void toolOutsideDeclaredProfileIsExcludedWithReason() throws Exception {
        var stub = new SkillStub();
        stub.bodies.put("demo_read", skill("demo_read", 11));
        stub.bodies.put("demo_write", skill("demo_write", 12));
        var service = service(stub);
        var source =
                snapshot(
                        "qa",
                        List.of(
                                ref("demo_read", 1, List.of("qa"), null),
                                ref("demo_write", 1, null, null)),
                        List.of());

        var frozen = freeze(service, source);

        assertEquals(List.of("demo_read"), toolCodes(frozen), "未列 profiles 的工具不属于任何档案");
        var excluded = frozen.path("toolSelection").path("excluded");
        assertEquals(1, excluded.size());
        assertEquals("demo_write", excluded.get(0).path("code").asText());
        assertEquals("PROFILE_NOT_MEMBER", excluded.get(0).path("reason").asText());
    }

    // === ④ profile 无命中 → 放行空工具集 + 快照留痕（语义定死） ==============================

    @Test
    void profileWithoutAnyMatchRunsEmptyToolSetWithVisibleWarning() throws Exception {
        var stub = new SkillStub();
        stub.bodies.put("demo_read", skill("demo_read", 11));
        var service = service(stub);
        var source = snapshot("qa", List.of(ref("demo_read", 1, List.of("ops"), null)), List.of());

        // 不抛异常即定死语义：不拒绝启动，放行空工具集。
        var frozen = freeze(service, source);

        assertTrue(toolCodes(frozen).isEmpty(), "无命中时不得回退为全量装载");
        var trace = frozen.path("toolSelection");
        assertEquals("qa", trace.path("profile").asText());
        assertTrue(texts(trace.path("selected")).isEmpty());
        assertEquals("demo_read", trace.path("excluded").get(0).path("code").asText());
        assertEquals("PROFILE_NOT_MEMBER", trace.path("excluded").get(0).path("reason").asText());
        assertTrue(
                trace.path("warning").asText().contains("qa"),
                "无命中必须显式留痕可读警告：" + trace.path("warning").asText());
    }

    // === ⑤ enabled=false 的工具不装载（对齐 22A schema enabled 语义） =======================

    @Test
    void disabledToolIsNotLoadedAndTracedAsDisabled() throws Exception {
        var stub = new SkillStub();
        stub.bodies.put("demo_read", skill("demo_read", 11));
        stub.bodies.put("demo_write", skill("demo_write", 12));
        var service = service(stub);
        var source =
                snapshot(
                        null,
                        List.of(
                                ref("demo_read", 1, null, false),
                                ref("demo_write", 1, null, null)),
                        List.of());

        var frozen = freeze(service, source);

        assertEquals(List.of("demo_write"), toolCodes(frozen));
        var excluded = frozen.path("toolSelection").path("excluded");
        assertEquals(1, excluded.size());
        assertEquals("demo_read", excluded.get(0).path("code").asText());
        assertEquals("DISABLED", excluded.get(0).path("reason").asText());
    }

    @Test
    void disabledToolStaysUnloadedEvenWhenProfileMatches() throws Exception {
        var stub = new SkillStub();
        stub.bodies.put("demo_read", skill("demo_read", 11));
        var service = service(stub);
        var source = snapshot("qa", List.of(ref("demo_read", 1, List.of("qa"), false)), List.of());

        var frozen = freeze(service, source);

        assertTrue(toolCodes(frozen).isEmpty(), "停用优先：档案命中也不能复活停用工具");
        assertEquals("DISABLED", frozen.path("toolSelection").path("excluded").get(0).path("reason").asText());
    }

    // === ⑥ 无 route 特判的结构证明：选择逻辑只读声明数据 ====================================

    @Test
    void selectionDependsOnlyOnDeclaredMembershipNotProfileName() throws Exception {
        var stub = new SkillStub();
        stub.bodies.put("demo_read", skill("demo_read", 11));
        var service = service(stub);
        // profile 名是自由约定标签：形似路线名/软件名的名字不触发任何特殊行为，
        // 是否装载完全由 profiles 成员声明决定。
        for (String name :
                List.of("route-one", "route-three", "ROUTE_X", "space-visibility", "任意档案名")) {
            var source =
                    snapshot(
                            name,
                            List.of(ref("demo_read", 1, List.of("route-three"), null)),
                            List.of());
            var frozen = freeze(service, source);
            boolean loaded = toolCodes(frozen).size() == 1;
            assertEquals(
                    "route-three".equals(name),
                    loaded,
                    "选择只能由成员声明决定，不得对档案名特判: " + name);
            assertEquals(name, frozen.path("toolSelection").path("profile").asText());
        }
    }

    @Test
    void profilePredicateReadsOnlyRefDeclarationAndProfileString() {
        var declared = ref("demo_read", 1, List.of("qa", "ops"), null);
        var bare = ref("demo_write", 1, null, null);
        // 空白 profile 等价未声明：任何引用都可见（存量行为）。
        for (String blank : new String[] {null, "", "  "}) {
            assertTrue(
                    RunService.toolInProfile(declared, blank),
                    "未声明 profile 时全部可见: " + blank);
            assertTrue(RunService.toolInProfile(bare, blank));
        }
        // 成员关系是精确匹配的声明事实，与名字内容无关。
        assertTrue(RunService.toolInProfile(declared, "qa"));
        assertFalse(RunService.toolInProfile(bare, "qa"), "未列 profiles 的工具不属于任何档案");
        assertFalse(RunService.toolInProfile(declared, "Qa"), "匹配是声明值精确相等");
        var empty = ref("demo_x", 1, List.of(), null);
        assertFalse(RunService.toolInProfile(empty, "qa"), "空 profiles 数组不属于任何档案");
    }

    // === 补充边界：声明了 profiles 但未选档案 → 全量装载，仅留痕 =============================

    @Test
    void memberDeclarationsWithoutProfileLoadEverythingAndTraceOnly() throws Exception {
        var stub = new SkillStub();
        stub.bodies.put("demo_read", skill("demo_read", 11));
        stub.bodies.put("demo_write", skill("demo_write", 12));
        var service = service(stub);
        var source =
                snapshot(
                        null,
                        List.of(
                                ref("demo_read", 1, List.of("qa"), null),
                                ref("demo_write", 1, null, null)),
                        List.of());

        var frozen = freeze(service, source);

        assertEquals(List.of("demo_read", "demo_write"), toolCodes(frozen), "未选档案 → 全部工具");
        var trace = frozen.path("toolSelection");
        assertTrue(trace.isObject());
        assertFalse(trace.has("profile"), "未选档案不得伪造 profile 值");
        assertEquals(List.of("demo_read", "demo_write"), texts(trace.path("selected")));
        assertFalse(trace.has("excluded"));
        assertFalse(trace.has("warning"));
    }

    // === 补充边界：guard 拒见保持既有静默语义，不冒充档案排除 ================================

    @Test
    void guardInvisibleToolStaysSilentlyExcludedAsBefore() throws Exception {
        var stub = new SkillStub();
        stub.bodies.put(
                "demo_secret",
                skill(
                        "demo_secret",
                        13,
                        Json.read("{\"roles\":[\"APPROVER\"],\"attributes\":{}}")));
        stub.bodies.put("demo_read", skill("demo_read", 11));
        var service = service(stub);
        var source =
                snapshot(
                        "qa",
                        List.of(
                                ref("demo_secret", 1, List.of("qa"), null),
                                ref("demo_read", 1, List.of("qa"), null)),
                        List.of());

        var frozen = freeze(service, source);

        assertEquals(List.of("demo_read"), toolCodes(frozen));
        assertFalse(
                frozen.path("toolSelection").has("excluded"),
                "guard 拒见是既有安全语义，不得计入档案排除");
    }

    // === 运行详情可见：留痕铺到详情顶层，旧运行不落键 ========================================

    @Test
    void runDetailSurfacesToolSelectionAtTopLevel() {
        var frozen = Json.object();
        var trace = frozen.putObject("toolSelection");
        trace.put("profile", "qa");
        var selected = trace.putArray("selected");
        selected.add("demo_read");

        var detail = new LinkedHashMap<String, Object>();
        RunService.surfaceToolSelection(detail, frozen);
        var surfaced = (JsonNode) detail.get("toolSelection");
        assertEquals("qa", surfaced.path("profile").asText());
        assertEquals(List.of("demo_read"), texts(surfaced.path("selected")));
    }

    @Test
    void legacyRunWithoutToolSelectionSurfacesNoKey() {
        var detail = new LinkedHashMap<String, Object>();
        RunService.surfaceToolSelection(detail, Json.read("{\"appCode\":\"old\"}"));
        assertFalse(detail.containsKey("toolSelection"), "旧运行详情不得补键");
    }

    // EX-04 PG readback bridge: fixed data, no runtime PG/HTTP/file dependency.
    // Source Run: green-skill-20261029-103602-527; 137 UTF-8 bytes.
    // JSON SHA256: 0e99aae36146e3b35a06f9755e2532c1fed3202e095cd5b9cbc89efeedf8b2d9
    // XML SHA256: 30220156caf1644beca88d4ef634511a333171d18df84b783ba0bdb4ddae0921
    private static final String PERSISTED_PROJECTION =
                "{\"pagination\":{\"mode\":\"OFFSET\",\"maxRows\":2,"
                        + "\"request\":{\"size\":\"takeRows\",\"offset\":\"startAt\"},"
                        + "\"maxPageSize\":6},\"exposure\":{\"kind\":\"QUERY\"}}";
    private static final String PERSISTED_PROJECTION_SHA256 =
            "0e99aae36146e3b35a06f9755e2532c1fed3202e095cd5b9cbc89efeedf8b2d9";

    @Test
    void projectionContractSurvivesActualFreezeAndFeedsOffsetPolicy() throws Exception {
        var stub = new SkillStub();
        var published =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        Json.read(skill("query_projection", 41));
        assertEquals(
                PERSISTED_PROJECTION_SHA256,
                cn.agentplatform.common.Digest.sha256(PERSISTED_PROJECTION));
        var projection = Json.read(PERSISTED_PROJECTION);
        var definition =
                (com.fasterxml.jackson.databind.node.ObjectNode) published.path("definition");
        definition.set("exposure", projection.path("exposure"));
        definition.set("pagination", projection.path("pagination"));
        var expected = definition.deepCopy();
        stub.bodies.put("query_projection", Json.write(published));
        var source = snapshot(null, List.of(ref("query_projection", 3, null, null)), List.of());

        var frozen = freeze(service(stub), source);

        assertEquals(1, stub.reads.get());
        assertEquals(List.of("query_projection"), toolCodes(frozen));
        var tool = frozen.path("config").path("tools").get(0);
        assertEquals(expected, tool.path("contract"));
        for (String key : List.of("pagination", "exposure"))
            assertEquals(expected.path(key), tool.path("contract").path(key), key);
        for (String key : List.of("execution", "guard", "inputSchema", "outputSchema"))
            assertEquals(published.path(key), tool.path(key), key);
        assertEquals(41L, tool.path("skillVersionId").asLong());
        assertEquals(published.path("digest"), tool.path("contractDigest"));
        assertFalse(frozen.has("toolSelection"));
        assertEquals(expected, definition);

        var result = Json.tree(Map.of("blob", "x".repeat(4096), "total", 99));
        var hint =
                new ToolOutputPolicy(1024)
                        .enforce(tool, result, "ex04:freeze:call")
                        .path("pagination");
        assertEquals("OFFSET", hint.path("mode").asText());
        assertEquals(expected.path("pagination").path("request"), hint.path("request"));
        assertEquals(6, hint.path("maxPageSize").asInt());
        assertEquals(2, hint.path("maxRows").asInt());
        assertFalse(hint.path("request").has("page"));
        assertFalse(hint.has("response"));
        assertFalse(hint.has("nextOffset"));
        assertTrue(hint.path("observed").isObject());
        assertTrue(hint.path("observed").isEmpty());
    }
}
