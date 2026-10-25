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
        String prompt = config.path("systemPrompt").asText(config.path("prompt").asText("你是一个助手。"));
        for (JsonNode template : config.path("frozenPrompts"))
            prompt += "\n\n[" + template.path("code").asText() + "]\n" + render(template, inputs);
        prompt =
                cn.agentplatform.engine.domain.Values.resolve(
                                Json.tree(prompt), Json.tree(Map.of("input", inputs)))
                        .asText();
        if (prompt.contains("${")) throw new IllegalArgumentException("PROMPT_VARIABLE_MISSING");
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
        // Never split tool call/result pairs. Reject overflow until a configured summarizer exists.
        // Density is an estimate for inspection only. The model gateway performs
        // tokenizer-calibrated
        // admission.
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

    private String render(JsonNode template, JsonNode inputs) {
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
        var matcher = pattern.matcher(template.path("content").asText());
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!variables.containsKey(name))
                throw new IllegalArgumentException("PARAM_ERROR:unknown prompt variable " + name);
            JsonNode value = variables.get(name);
            matcher.appendReplacement(
                    result,
                    java.util.regex.Matcher.quoteReplacement(
                            value.isValueNode() ? value.asText() : value.toString()));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
