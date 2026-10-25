package cn.agentplatform.engine.domain;

import cn.agentplatform.common.*;
import cn.agentplatform.common.schema.*;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.networknt.schema.*;

import java.util.*;

/** Frozen final-answer contract. Tool-call rounds and SUMMARY calls are not final answers. */
public final class OutputPolicy {
    private static final ObjectMapper STRICT =
            Json.mapper()
                    .copy()
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
    private static final JsonSchemaFactory SCHEMAS =
            JsonSchemaFactory.getInstance(
                    SpecVersion.VersionFlag.V202012,
                    builder ->
                            builder.enableSchemaCache(false)
                                    .schemaLoaders(
                                            loaders ->
                                                    loaders.add(
                                                            iri -> {
                                                                // Local references may target
                                                                // arbitrary
                                                                // document locations. Never allow
                                                                // library resolution to fall
                                                                // through to its
                                                                // network or file loaders.
                                                                throw invalid("Schema仅允许本地引用");
                                                            })));
    private final String type, onInvalid;
    private final int maxRetries;
    private final JsonSchema schema;
    private final SchemaWorkBudget.Plan plan;
    private final long regexPrograms;

    public OutputPolicy(JsonNode output) {
        if (!output.isMissingNode() && !output.isObject()) throw invalid("output必须为对象");
        var format = output.path("format");
        if (!format.isMissingNode() && !format.isObject()) throw invalid("format必须为对象");
        if (format.has("type") && !format.path("type").isTextual())
            throw invalid("format.type必须为字符串");
        type = format.path("type").asText("none");
        if (!Set.of("none", "json").contains(type)) throw invalid("未知format.type");
        if (output.has("onInvalid") && !output.path("onInvalid").isTextual())
            throw invalid("onInvalid必须为字符串");
        onInvalid = output.path("onInvalid").asText("TERMINATE");
        if (!Set.of("RETRY", "TERMINATE", "PASS_WITH_FLAG").contains(onInvalid))
            throw invalid("未知onInvalid");
        var retries = output.path("maxRetries");
        if (!retries.isMissingNode()
                && (!retries.isIntegralNumber()
                        || !retries.canConvertToInt()
                        || retries.asInt() < 0)) throw invalid("maxRetries必须为非负整数");
        maxRetries = retries.asInt(2);
        var declaration = format.path("schema");
        if (!declaration.isMissingNode() && !declaration.isObject() && !declaration.isBoolean())
            throw invalid("schema必须为对象或布尔值");
        try (var ignored = SchemaDeadline.until(System.nanoTime() + 2_000_000_000L)) {
            SchemaDeadline.check();
            plan = declaration.isMissingNode() ? null : SchemaWorkBudget.inspect(declaration);
            var regex = new BoundedRegexFactory(plan == null ? Set.of() : plan.expressions());
            regexPrograms = regex.programUnits();
            var config = new SchemaValidatorsConfig();
            config.setPreloadJsonSchemaRefMaxNestingDepth(64);
            config.setRegularExpressionFactory(regex);
            config.setFailFast(true);
            schema =
                    type.equals("json") && !declaration.isMissingNode()
                            ? SCHEMAS.getSchema(declaration, config)
                            : null;
            // getSchema's best-effort preload swallows initialization exceptions in 1.5.6.
            // Explicit initialization must succeed before a request can consume a model budget.
            if (schema != null) initialize(schema, declaration, plan);
            SchemaDeadline.check();
        } catch (RuntimeException error) {
            throw invalid("Schema无效：" + error.getMessage());
        }
    }

    private static void initialize(
            JsonSchema validator, JsonNode schema, SchemaWorkBudget.Plan plan) {
        for (String pointer : plan.locations()) {
            SchemaDeadline.check();
            var location = new JsonNodePath(PathType.JSON_POINTER);
            var cursor = schema;
            if (!pointer.isEmpty())
                for (String segment : pointer.substring(1).split("/", -1)) {
                    String key = segment.replace("~1", "/").replace("~0", "~");
                    location =
                            cursor.isArray()
                                    ? location.append(Integer.parseInt(key))
                                    : location.append(key);
                    cursor =
                            cursor.isArray()
                                    ? cursor.path(Integer.parseInt(key))
                                    : cursor.path(key);
                }
            (pointer.isEmpty() ? validator : validator.getSubSchema(location))
                    .initializeValidators();
        }
        SchemaDeadline.check();
    }

    private static RuntimeFault invalid(String reason) {
        return new RuntimeFault("OUTPUT_POLICY_INVALID", "output", reason);
    }

    public String onInvalid() {
        return onInvalid;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public boolean json() {
        return type.equals("json");
    }

    public record Verdict(boolean valid, JsonNode structured, String reason) {}

    public Verdict validate(JsonNode answer) {
        if (!json()) return new Verdict(true, null, "");
        JsonNode value = null;
        try (var ignored = SchemaDeadline.until(System.nanoTime() + 2_000_000_000L)) {
            SchemaDeadline.check();
            if (!answer.path("text").isTextual())
                return new Verdict(false, null, "终答text必须为JSON文本");
            // Bound the raw text before parsing. The parsed instance gets its own work admission.
            if (answer.path("text").textValue().length() > 64 * 1024 * 1024)
                return new Verdict(false, null, "终答JSON文本超过校验预算");
            try {
                value = STRICT.readTree(answer.path("text").textValue());
                if (value == null || value.isMissingNode())
                    return new Verdict(false, null, "终答为空，要求有效JSON");
            } catch (Exception error) {
                return new Verdict(false, null, "模型终答不是严格JSON（含尾随内容或重复字段）");
            }
            SchemaDeadline.check();
            if (schema != null) {
                plan.admitInstance(value, regexPrograms);
                var errors = schema.validate(value);
                SchemaDeadline.check();
                if (!errors.isEmpty())
                    return new Verdict(false, value, bounded(errors.iterator().next().toString()));
            }
            return new Verdict(true, value, "");
        } catch (RuntimeException error) {
            return new Verdict(false, value, bounded("输出校验预算拒绝：" + error.getMessage()));
        }
    }

    private static String bounded(String reason) {
        return reason.length() <= 1024 ? reason : reason.substring(0, 1024);
    }

    public Map<String, Object> diagnostic(Verdict verdict, int attempt) {
        return Map.of(
                "valid",
                verdict.valid(),
                "semantic",
                verdict.valid() ? "OK" : "OUTPUT_INVALID",
                "path",
                "output",
                "reason",
                verdict.reason(),
                "onInvalid",
                onInvalid,
                "outputAttemptNo",
                attempt);
    }
}
