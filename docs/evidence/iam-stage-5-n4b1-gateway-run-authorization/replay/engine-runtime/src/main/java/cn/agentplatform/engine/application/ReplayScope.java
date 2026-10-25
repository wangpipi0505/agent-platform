package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.domain.RuntimeFault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/**
 * Mirrors SkillRules' narrowing semantics on the immutable contract; never widens verified Actor
 * data.
 */
public final class ReplayScope {
    private ReplayScope() {}

    public static void permissions(JsonNode guard, Actor actor, boolean requireDeclaration) {
        if (!guard.isObject()) deny("缺少冻结守卫");
        if (requireDeclaration
                && guard.path("roles").isEmpty()
                && guard.path("attributes").isEmpty()) deny("技能未声明守卫，默认拒绝");
        for (JsonNode role : guard.path("roles")) actor.requireRole(role.asText());
        guard.path("attributes")
                .fields()
                .forEachRemaining(rule -> allowed(rule.getKey(), rule.getValue(), actor));
    }

    private static Set<String> allowed(String name, JsonNode declared, Actor actor) {
        if (!declared.isArray()) deny("守卫属性声明无效");
        Set<String> allowed = new HashSet<>(actor.attributes().getOrDefault(name, Set.of()));
        if (!declared.isEmpty()) {
            Set<String> staticValues = new HashSet<>();
            declared.forEach(value -> staticValues.add(value.asText()));
            allowed.retainAll(staticValues);
        }
        if (allowed.isEmpty()) deny("用户无该范围权限：" + name);
        return allowed;
    }

    public static JsonNode secure(JsonNode guard, Actor actor, JsonNode args) {
        permissions(guard, actor, true);
        if (!args.isObject()) deny("守卫参数必须为对象");
        var secured = (ObjectNode) args.deepCopy();
        guard.path("attributes")
                .fields()
                .forEachRemaining(
                        rule -> {
                            Set<String> allowed = allowed(rule.getKey(), rule.getValue(), actor);
                            JsonNode proposed = args.path(rule.getKey());
                            if (proposed.isArray()) {
                                Set<String> intersection = new LinkedHashSet<>();
                                for (JsonNode value : proposed)
                                    if (value.isValueNode() && allowed.contains(value.asText()))
                                        intersection.add(value.asText());
                                if (intersection.isEmpty()) deny("用户无参数范围权限：" + rule.getKey());
                                secured.set(rule.getKey(), Json.tree(intersection));
                            } else if (proposed.isTextual()) {
                                if (!allowed.contains(proposed.asText()))
                                    deny("用户无参数范围权限：" + rule.getKey());
                            } else deny("守卫参数缺失或类型不正确：" + rule.getKey());
                        });
        return secured;
    }

    private static void deny(String reason) {
        throw new RuntimeFault("NO_PERMISSION", "guard", reason);
    }
}
