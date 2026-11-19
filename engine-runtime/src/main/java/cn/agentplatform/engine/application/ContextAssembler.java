package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ContextAssembler {
    // Mechanism reference actually read: deepseek-harness 47f9438,
    // system-prompt/src/index.ts ordered sections/strict variables; session/src/invariant.ts
    // tool call/result relations. We reimplement those invariants on frozen PG snapshots;
    // no Harness runtime, TypeScript module, or mutable global provider registry is imported.
    public Map<String, Object> assemble(
            JsonNode config, JsonNode inputs, List<Map<String, Object>> history, List<?> tools) {
        return assemble(config, inputs, history, tools, List.of());
    }

    public Map<String, Object> assemble(
            JsonNode config,
            JsonNode inputs,
            List<Map<String, Object>> history,
            List<?> tools,
            List<Map<String, Object>> sessionHistory) {
        String prompt = promptText(config, inputs);
        prompt =
                cn.agentplatform.engine.domain.Values.resolve(
                                Json.tree(prompt), Json.tree(Map.of("input", inputs)))
                        .asText();
        if (prompt.contains("${")) throw new IllegalArgumentException("PROMPT_VARIABLE_MISSING");
        // 本体文本是数据而非模板：必须在 Values.resolve 与 ${ 守卫之后追加，
        // 否则示例问答里的 ${...} 会被当作变量替换或触发 PROMPT_VARIABLE_MISSING。
        String ontology = config.path("ontologyContext").asText("");
        if (!ontology.isBlank()) prompt += "\n\n[本体上下文]\n" + ontology;
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", prompt));
        messages.addAll(sessionHistory);
        int currentUserIndex = messages.size();
        messages.add(
                Map.of(
                        "role",
                        "user",
                        "content",
                        inputs.has("message")
                                ? inputs.path("message").asText()
                                : inputs.toString()));
        Set<String> pendingCalls = new HashSet<>();
        for (Map<String, Object> message : history) {
            JsonNode view = Json.tree(message);
            if (view.path("role").asText().equals("assistant"))
                for (JsonNode call : view.path("tool_calls")) {
                    String id = call.path("id").asText();
                    if (id.isBlank() || !pendingCalls.add(id))
                        throw new IllegalArgumentException("TOOL_CALL_PAIR_INVALID");
                }
            if (view.path("role").asText().equals("tool")
                    && !pendingCalls.remove(view.path("tool_call_id").asText()))
                throw new IllegalArgumentException("TOOL_RESULT_WITHOUT_CALL");
            messages.add(message);
        }
        if (!pendingCalls.isEmpty()) throw new IllegalArgumentException("TOOL_RESULT_MISSING");
        int estimated = (Json.write(messages).length() + Json.write(tools).length() + 2) / 3;
        int output =
                config.path("output")
                        .path("maxTokens")
                        .asInt(config.path("maxOutputTokens").asInt(2048));
        // Never split tool call/result pairs. Overflow is NOT rejected here: ModelExchange
        // routes every dispatch through ContextProjection's four-stage pipeline
        // (§31.6.2 tool-output trim → whole-turn drop → model summary → hard truncate),
        // which projects a bounded copy and never rewrites these assembled rows.
        // Density below is an estimate for inspection only; the model gateway performs
        // tokenizer-calibrated admission.
        Map<String, Object> request = new LinkedHashMap<>();
        request.put(
                "modelEntryId",
                config.path("modelEntryId")
                        .asLong(config.path("modelBinding").path("modelEntryId").asLong()));
        request.put("messages", messages);
        request.put("toolDefinitions", tools);
        var params =
                config.path("params").isObject()
                        ? (com.fasterxml.jackson.databind.node.ObjectNode)
                                config.path("params").deepCopy()
                        : Json.object();
        for (String key : List.of("sampling", "output", "context", "timeout", "budgets"))
            if (config.has(key)) params.set(key, config.path(key));
        params.put("maxOutputTokens", output);
        if (config.path("sampling").has("temperature"))
            params.set("temperature", config.path("sampling").path("temperature"));
        if (config.path("sampling").has("topP"))
            params.set("topP", config.path("sampling").path("topP"));
        request.put("params", params);
        request.put("requiredLabels", config.path("requiredLabels"));
        request.put(
                "manifest",
                Map.of(
                        "stages",
                        List.of(
                                "PREPARE",
                                "RENDER",
                                "MESSAGES",
                                "ACCOUNT",
                                "TRIM",
                                "VALIDATE",
                                "FREEZE"),
                        "currentUserIndex",
                        currentUserIndex,
                        "estimatedTokens",
                        estimated,
                        "tokenCountExact",
                        false,
                        "admission",
                        "MODEL_GATEWAY_CALIBRATED_TOKENIZER",
                        "trimLog",
                        List.of(),
                        "sources",
                        List.of("frozenAppConfig", "inputs", "persistedHistory")));
        if (((Number) request.get("modelEntryId")).longValue() == 0)
            throw new IllegalArgumentException("MODEL_NOT_CONFIGURED");
        return request;
    }

    public static String promptText(JsonNode config, JsonNode inputs) {
        // R-24：promptSource 二值枚举对齐——TEMPLATE=冻结模板主段（EX-20 既有）；DIRECT=内联
        // systemPrompt 显式声明（V901 演示种子口径，此前被 409 误拒）；缺省=旧内联兼容。
        boolean primary;
        boolean explicitInline = false;
        if (config.has("promptSource")) {
            String source = config.path("promptSource").asText("");
            if ("TEMPLATE".equals(source)) primary = true;
            else if ("DIRECT".equals(source)) { primary = false; explicitInline = true; }
            else throw new PlatformException(409, "PROMPT_TEMPLATE_SNAPSHOT_MISMATCH", "提示词来源无效（仅 TEMPLATE/DIRECT）");
        } else primary = false;
        if (primary && (!config.path("frozenPrompts").isArray() || config.path("frozenPrompts").isEmpty()))
            throw new PlatformException(409, "PROMPT_TEMPLATE_SNAPSHOT_MISMATCH", "模板主来源缺少冻结正文");
        if (explicitInline && config.path("systemPrompt").asText("").isBlank())
            throw new PlatformException(409, "PROMPT_TEMPLATE_SNAPSHOT_MISMATCH", "直连来源缺少内联正文");
        // 只有显式新选择才切换主段；旧内联和附加模板维持原重放顺序。
        String prompt = primary ? "" : config.path("systemPrompt").asText(config.path("prompt").asText("你是一个助手。"));
        for (JsonNode template : config.path("frozenPrompts"))
            prompt += (prompt.isEmpty() && primary ? "" : "\n\n") + "[" + template.path("code").asText() + "]\n" + render(template, inputs);
        return prompt;
    }

    private static String render(JsonNode template, JsonNode inputs) {
        Map<String, JsonNode> variables = new HashMap<>();
        for (JsonNode declaration : template.path("variables")) {
            String name = declaration.path("name").asText();
            JsonNode value = inputs.path(name);
            if (value.isMissingNode() && declaration.has("defaultValue"))
                value = declaration.path("defaultValue");
            if ((value.isMissingNode() || value.isNull())
                    && declaration.path("required").asBoolean())
                throw new IllegalArgumentException("PARAM_ERROR:prompt." + name);
            variables.put(name, value);
        }
        var pattern = java.util.regex.Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");
        String content = template.path("content").asText();
        var matcher = pattern.matcher(content);
        long renderedLength = content.length();
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!variables.containsKey(name))
                throw new IllegalArgumentException("PARAM_ERROR:unknown prompt variable " + name);
            JsonNode value = variables.get(name);
            String replacement = value.isValueNode() ? value.asText() : value.toString();
            renderedLength += replacement.length() - (matcher.end() - matcher.start());
            if (renderedLength > 2_097_152)
                throw new PlatformException(413, "PROMPT_TOO_LARGE", "模板展开超过提示词大小上限");
            matcher.appendReplacement(
                    result,
                    java.util.regex.Matcher.quoteReplacement(
                            replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
