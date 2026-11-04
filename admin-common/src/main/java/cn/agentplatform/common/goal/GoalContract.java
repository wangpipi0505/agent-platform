package cn.agentplatform.common.goal;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.networknt.schema.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 目标的冻结对象/调用绑定；不执行表达式，不从最新调用反推目标参数。 */
public final class GoalContract {
    public static final Pattern PATH = Pattern.compile("\\$(?:\\.[A-Za-z_][A-Za-z0-9_]*(?:\\[(?:0|[1-9][0-9]{0,8})])*)+");
    private static final Pattern SKILL = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,63}");
    private static final JsonNode SCHEMA_DOCUMENT = schemaDocument();
    private static final JsonSchema DECLARATION_SCHEMA = validator("declaration");
    private static final JsonSchema PREDICATE_SCHEMA = validator("predicate");
    private GoalContract() {}

    private static JsonNode schemaDocument() {
        try (var input = GoalContract.class.getResourceAsStream("/semantic-runtime-goal-v1.schema.json")) {
            if (input == null) throw new IllegalStateException("Goal schema resource missing");
            return Json.read(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException error) { throw new IllegalStateException("Goal schema unavailable", error); }
    }

    private static JsonSchema validator(String definition) {
        var reference = Json.object();
        reference.set("$defs", SCHEMA_DOCUMENT.path("$defs"));
        reference.put("$ref", "#/$defs/" + definition);
        var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
                builder -> builder.schemaLoaders(loaders -> loaders.add(iri -> {
                    throw new IllegalArgumentException("Goal schema forbids external loading");
                })));
        return factory.getSchema(reference);
    }

    public static List<String> declarationErrors(JsonNode goal) {
        var errors = new ArrayList<String>();
        if (Json.canonical(goal).length() > 65536) return List.of("目标声明超过64KiB");
        for (var error : DECLARATION_SCHEMA.validate(goal)) errors.add(error.getMessage());
        errors.addAll(bindingErrors(goal));
        return List.copyOf(errors);
    }

    public static List<String> predicateErrors(JsonNode predicate) {
        return PREDICATE_SCHEMA.validate(predicate).stream().map(ValidationMessage::getMessage).toList();
    }

    public static void requireBindings(JsonNode config) {
        if (!config.has("goals")) return;
        JsonNode goals = config.path("goals");
        if (!goals.isArray() || goals.size() > 32)
            throw new cn.agentplatform.common.PlatformException(400, "GOAL_CRITERION_INVALID", "goals必须为最多32项目标数组");
        var ids = new java.util.HashSet<String>();
        for (JsonNode goal : goals) {
            if (!goal.path("goalId").isTextual() || !goal.path("goalId").asText().matches("[A-Za-z0-9._:-]{1,64}")
                    || !ids.add(goal.path("goalId").asText()) || !declarationErrors(goal).isEmpty())
                throw new cn.agentplatform.common.PlatformException(400, "GOAL_CRITERION_INVALID", "目标必须具有唯一标识、冻结的对象/调用绑定及合法goal@1.0判据");
        }
    }

    public static List<String> bindingErrors(JsonNode goal) {
        var errors = new ArrayList<String>();
        JsonNode criterion = goal.path("criterion"), target = criterion.path("target");
        if (!target.isObject() || !text(target.path("objectType"), 128) || !text(target.path("objectId"), 256))
            errors.add("criterion.target必须声明objectType和objectId");
        bindingErrors(criterion.path("readback"), target, "readback", errors);
        if (criterion.has("action")) bindingErrors(criterion.path("action"), target, "action", errors);
        return List.copyOf(errors);
    }

    private static void bindingErrors(JsonNode operation, JsonNode target, String kind, List<String> errors) {
        if (!operation.isObject() || !SKILL.matcher(operation.path("skillCode").asText()).matches()
                || !operation.path("arguments").isObject() || !validPath(operation.path("objectIdPath"))) {
            errors.add("criterion." + kind + "缺少技能、固定参数或合法objectIdPath");
            return;
        }
        JsonNode id = path(operation.path("arguments"), operation.path("objectIdPath").asText());
        if (!id.equals(target.path("objectId"))) errors.add("criterion." + kind + "参数对象与target不一致");
        if (kind.equals("readback") && !validPath(operation.path("resultObjectIdPath")))
            errors.add("criterion.readback缺少合法resultObjectIdPath");
    }

    public static String digest(JsonNode value) { return Digest.sha256(Json.canonical(value)); }

    public static boolean validPath(JsonNode value) {
        return value.isTextual() && value.asText().length() <= 256 && PATH.matcher(value.asText()).matches();
    }

    private static boolean text(JsonNode value, int max) {
        return value.isTextual() && !value.asText().isBlank() && value.asText().length() <= max;
    }

    /** 仅支持$根、点号字段和完整[n]数组下标；非法路径返回missing，不吞掉尾部表达式。 */
    public static JsonNode path(JsonNode root, String expression) {
        if (root == null || expression == null || expression.length() > 256 || !PATH.matcher(expression).matches())
            return MissingNode.getInstance();
        JsonNode node = root;
        for (String part : expression.substring(2).split("\\.")) {
            int bracket = part.indexOf('[');
            node = node.path(bracket < 0 ? part : part.substring(0, bracket));
            if (bracket < 0) continue;
            var matcher = Pattern.compile("\\[([0-9]+)]").matcher(part.substring(bracket));
            while (matcher.find()) {
                int index = Integer.parseInt(matcher.group(1));
                node = node.isArray() && index < node.size() ? node.path(index) : MissingNode.getInstance();
            }
        }
        return node;
    }
}
