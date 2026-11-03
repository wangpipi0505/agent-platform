package cn.agentplatform.app.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.app.application.AppService;
import cn.agentplatform.common.PlatformException;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * 能力包纯规则的取值与校验契约（MNT-08）。
 *
 * <p>这些断言固定的是"领域规则对 JSON 取值的既有反应"：状态码 400、错误码 INVALID_INPUT、提示文案与缺省值
 * （缺数组按空数组处理、其余一律拒绝）。结构调整只允许改变这些规则所在的位置，不允许顺带改变任何一条，
 * 因此本类不启动 Spring、不连接数据库，也不依赖应用服务的实例状态。
 */
class CapabilityValuesTest {

    @Test
    void textRejectsBlankNullAndOverlongWithSameCodeAndMessage() {
        var body = new LinkedHashMap<String, Object>();
        assertTextRejected(body);
        body.put("environment", "");
        assertTextRejected(body);
        body.put("environment", "   ");
        assertTextRejected(body);
        body.put("environment", "x".repeat(129));
        assertTextRejected(body);

        body.put("environment", "DEV");
        assertEquals("DEV", AppService.text(body, "environment"));
        body.put("environment", "x".repeat(128));
        assertEquals(128, AppService.text(body, "environment").length());
    }

    private static void assertTextRejected(Map<String, Object> body) {
        var failure = assertThrows(PlatformException.class, () -> AppService.text(body, "environment"));
        assertEquals(400, failure.status());
        assertEquals("INVALID_INPUT", failure.code());
        assertEquals("缺少或无效字段: environment", failure.getMessage());
    }

    @Test
    void objectAcceptsOnlyJsonObject() {
        assertObjectRejected("不是对象");
        assertObjectRejected(List.of("a"));
        assertObjectRejected(null);

        var value = new LinkedHashMap<String, Object>(Map.of("k", 1));
        assertSame(value, AppService.object(value));
    }

    private static void assertObjectRejected(Object value) {
        var failure = assertThrows(PlatformException.class, () -> AppService.object(value));
        assertEquals(400, failure.status());
        assertEquals("INVALID_INPUT", failure.code());
        assertEquals("必须提供JSON对象", failure.getMessage());
    }

    @Test
    void objectsTreatsMissingAsEmptyAndRejectsNonArray() {
        assertTrue(AppService.objects(null).isEmpty());

        var empty = new ArrayList<Map<String, Object>>();
        assertSame(empty, AppService.objects(empty));

        assertObjectsRejected(Map.of("k", 1));
        assertObjectsRejected("x");
        assertObjectsRejected(List.of("x"));
        assertObjectsRejected(List.of(Map.of("k", 1), 2));

        var single = List.<Map<String, Object>>of(Map.of("toolId", "t1"));
        assertEquals(1, AppService.objects(single).size());
    }

    private static void assertObjectsRejected(Object value) {
        var failure = assertThrows(PlatformException.class, () -> AppService.objects(value));
        assertEquals(400, failure.status());
        assertEquals("INVALID_INPUT", failure.code());
        assertEquals("必须提供对象数组", failure.getMessage());
    }

    @Test
    void bindingPlanAcceptsOnlyDeclaredFieldsAndKeepsOrder() {
        var extra = assertThrows(
                PlatformException.class,
                () -> CapabilityBinding.plan(Map.of("environment", "DEV", "extra", "x")));
        assertEquals(400, extra.status());
        assertEquals("INVALID_INPUT", extra.code());
        assertEquals("解析计划仅接受 environment/routeProfileId/bindings", extra.getMessage());

        assertObjectsRejectedInPlan();

        var plan = CapabilityBinding.plan(new LinkedHashMap<>(Map.of(
                "environment", "DEV",
                "routeProfileId", "r1",
                "bindings", List.of(Map.of("toolId", "t1")))));
        assertEquals("DEV", plan.get("environment"));
        assertEquals("r1", plan.get("routeProfileId"));
        assertEquals(List.of("environment", "routeProfileId", "bindings"), List.copyOf(plan.keySet()));
    }

    private static void assertObjectsRejectedInPlan() {
        var failure = assertThrows(
                PlatformException.class,
                () -> CapabilityBinding.plan(Map.of(
                        "environment", "DEV", "routeProfileId", "r1", "bindings", "not-a-list")));
        assertEquals("INVALID_INPUT", failure.code());
        assertEquals("必须提供对象数组", failure.getMessage());
    }

    @Test
    void bindingSelectRejectsUndeclaredEnvironmentBeforeResolvingProfiles() {
        var pkg = new LinkedHashMap<String, Object>();
        pkg.put("software", Map.of("environments", Map.of("DEV", Map.of())));
        var plan = Map.of("environment", "PROD", "routeProfileId", "r1", "bindings", List.of());

        var failure = assertThrows(PlatformException.class, () -> CapabilityBinding.select(pkg, plan));
        assertEquals(400, failure.status());
        assertEquals("INVALID_INPUT", failure.code());
        assertEquals("environment 未在本包声明", failure.getMessage());
    }

    /** 抽取后的领域入口必须与兼容入口逐条同义，且不经过应用服务。 */
    @Test
    void domainEntryMatchesCompatEntryWithoutApplicationService() {
        var body = new LinkedHashMap<String, Object>();
        assertEquals(
                AppService.text(Map.of("environment", "DEV"), "environment"),
                CapabilityValues.text(Map.of("environment", "DEV"), "environment"));

        var value = new LinkedHashMap<String, Object>(Map.of("k", 1));
        assertSame(value, CapabilityValues.object(value));
        assertSame(AppService.object(value), CapabilityValues.object(value));

        assertEquals(List.of(), CapabilityValues.objects(null));
        assertEquals(
                AppService.objects(List.<Map<String, Object>>of(Map.of("toolId", "t1"))).size(),
                CapabilityValues.objects(List.<Map<String, Object>>of(Map.of("toolId", "t1"))).size());

        var failure = assertThrows(PlatformException.class, () -> CapabilityValues.text(body, "environment"));
        assertEquals(400, failure.status());
        assertEquals("INVALID_INPUT", failure.code());
        assertEquals("缺少或无效字段: environment", failure.getMessage());
    }
}
