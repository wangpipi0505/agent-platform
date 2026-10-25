package cn.agentplatform.engine.domain;

import cn.agentplatform.common.*;
import cn.agentplatform.common.schema.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;

import java.util.*;

/**
 * Same declaration-to-schema mapping as shared/input-schema.ts. Validate complete, already typed
 * values: defaults are UI initial values, never an implicit server-side mutation of saved inputs.
 * This helper is ready for other run entry points; this change wires named test inputs only.
 */
public final class InputDeclarations {
    private InputDeclarations() {}

    private static final JsonSchemaFactory SCHEMAS =
            JsonSchemaFactory.getInstance(
                    SpecVersion.VersionFlag.V202012,
                    builder ->
                            builder.enableSchemaCache(false)
                                    .schemaLoaders(
                                            loaders ->
                                                    loaders.add(
                                                            iri -> {
                                                                throw invalid("输入声明仅允许本地Schema引用");
                                                            })));

    private static PlatformException invalid(String detail) {
        return new PlatformException(400, "INVALID_INPUT_DECLARATION", detail);
    }

    public static JsonNode schema(JsonNode declaration) {
        try (var ignored = SchemaDeadline.until(System.nanoTime() + 2_000_000_000L)) {
            SchemaDeadline.check();
            if (declaration.isMissingNode()) return Json.tree(Map.of("type", "object"));
            SchemaWorkBudget.checkStructure(declaration);
            if (declaration.isObject()) {
                SchemaWorkBudget.inspect(declaration);
                return declaration.deepCopy();
            }
            if (!declaration.isArray()) throw invalid("输入声明必须为数组或JSON Schema对象");
            var result = Json.object();
            result.put("type", "object");
            var properties = result.putObject("properties");
            var required = result.putArray("required");
            for (var field : declaration) {
                if (!field.isObject()
                        || !field.path("name").isTextual()
                        || field.path("name").asText().isBlank()
                        || properties.has(field.path("name").asText()))
                    throw invalid("输入声明需要唯一name");
                String name = field.path("name").asText();
                var property = (ObjectNode) field.deepCopy();
                property.remove(List.of("name", "required", "desc", "description"));
                property.put("type", field.path("type").asText("STRING").toLowerCase(Locale.ROOT));
                properties.set(name, property);
                if (field.path("required").asBoolean()) required.add(name);
            }
            SchemaWorkBudget.inspect(result);
            return result;
        } catch (PlatformException error) {
            throw error;
        } catch (RuntimeException error) {
            throw invalid("输入声明无效：" + error.getMessage());
        }
    }

    public static void validate(JsonNode draft, JsonNode inputs) {
        try (var ignored = SchemaDeadline.until(System.nanoTime() + 2_000_000_000L)) {
            if (!inputs.isObject())
                throw new PlatformException(400, "INVALID_INPUT", "inputs必须为对象");
            JsonNode config = draft.path("config");
            // Both forms may coexist; neither one silently disables the other declaration.
            if (config.has("inputSchema"))
                validateSchema(schema(config.path("inputSchema")), inputs);
            JsonNode declaration =
                    "FLOW".equals(draft.path("type").asText())
                                    && draft.path("definition").has("inputs")
                            ? draft.path("definition").path("inputs")
                            : config.path("inputs");
            validateSchema(schema(declaration), inputs);
        }
    }

    private static void validateSchema(JsonNode schema, JsonNode inputs) {
        try (var ignored = SchemaDeadline.until(System.nanoTime() + 2_000_000_000L)) {
            var plan = SchemaWorkBudget.inspect(schema);
            var regex = new BoundedRegexFactory(plan.expressions());
            var config = new SchemaValidatorsConfig();
            config.setPreloadJsonSchemaRefMaxNestingDepth(64);
            config.setRegularExpressionFactory(regex);
            config.setFailFast(true);
            var validator = SCHEMAS.getSchema(schema, config);
            // Unused definitions must also resolve before accepting input. The existing loader
            // remains the final boundary for references targeting nonstandard document locations.
            initialize(validator, schema, plan);
            try {
                plan.admitInstance(inputs, regex.programUnits());
                SchemaDeadline.check();
            } catch (RuntimeException error) {
                throw new PlatformException(400, "INVALID_INPUT", "输入校验预算拒绝：" + error.getMessage());
            }
            var errors = validator.validate(inputs);
            SchemaDeadline.check();
            if (!errors.isEmpty())
                throw new PlatformException(
                        400,
                        "INPUT_SCHEMA_CHANGED",
                        "输入不符合当前输入声明: " + errors.iterator().next().getMessage());
        } catch (PlatformException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalid("输入声明无效：" + e.getMessage());
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
}
