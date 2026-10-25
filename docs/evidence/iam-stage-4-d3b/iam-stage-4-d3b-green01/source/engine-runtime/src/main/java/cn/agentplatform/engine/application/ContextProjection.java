package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.function.Function;

/** Four-stage request projection (§31.6.2); original message/fact rows are never rewritten. */
public final class ContextProjection {
    public Map<String, Object> apply(
            Map<String, Object> original,
            Function<Map<String, Object>, JsonNode> measure,
            Function<List<Map<String, Object>>, String> summarize) {
        Map<String, Object> request = new LinkedHashMap<>(original);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (JsonNode message : Json.tree(original.get("messages")))
            messages.add(Json.mapper().convertValue(message, Map.class));
        request.put("messages", messages);
        Set<Map<String, Object>> protectedMessages =
                Collections.newSetFromMap(new IdentityHashMap<>());
        protectedMessages.add(messages.getFirst());
        protectedMessages.add(
                messages.get(
                        Json.tree(original.get("manifest")).path("currentUserIndex").asInt(1)));
        var config = Json.tree(request.get("params")).path("context");
        List<Object> log = new ArrayList<>();
        List<Map<String, Object>> dropped = new ArrayList<>();
        Map<String, Object> protectedRequest = new LinkedHashMap<>(request);
        protectedRequest.put(
                "messages", messages.stream().filter(protectedMessages::contains).toList());
        JsonNode protectedMeasurement = measure.apply(protectedRequest);
        valid(protectedMeasurement);
        long protectedTokens = protectedMeasurement.path("inputTokens").asLong();
        JsonNode accounting = measured(request, measure, config, protectedTokens);
        valid(accounting);
        int keep = Math.max(1, config.path("historyTurns").asInt(20));
        if (needsTrim(accounting, config) || groups(messages, protectedMessages) > keep) {
            // Stage 1 preserves each call/result identity while projecting only historical tool
            // content.
            int cap = Math.max(64, config.path("toolResultChars").asInt(512));
            for (int i = 1; i < messages.size() - 1; i++) {
                var m = messages.get(i);
                String content = Objects.toString(m.get("content"), "");
                if ("tool".equals(m.get("role")) && content.length() > cap) {
                    m.put(
                            "content",
                            content.substring(0, cap / 2)
                                    + "\n[tool output omitted; original retained in trace]\n"
                                    + content.substring(content.length() - cap / 2));
                    log.add(
                            Map.of(
                                    "stage",
                                    "TOOL_OUTPUT_TRIM",
                                    "messageIndex",
                                    i,
                                    "sourceSha256",
                                    Digest.sha256(content),
                                    "originalChars",
                                    content.length()));
                }
            }
            accounting = measured(request, measure, config, protectedTokens);
            valid(accounting);
        }
        // Stage 2 removes complete assistant-call/result groups, never a lone result or protected
        // input.
        while ((needsTrim(accounting, config) || groups(messages, protectedMessages) > keep)
                && messages.size() > 3) {
            int start = 1;
            while (start < messages.size() && protectedMessages.contains(messages.get(start)))
                start++;
            if (start >= messages.size()) break;
            int end = groupEnd(messages, start, protectedMessages);
            if (end >= messages.size()) break;
            var removed = new ArrayList<Map<String, Object>>(messages.subList(start, end));
            dropped.addAll(removed);
            messages.subList(start, end).clear();
            log.add(
                    Map.of(
                            "stage",
                            "WHOLE_TURN_DROP",
                            "sourceSha256",
                            Digest.sha256(Json.canonical(removed)),
                            "messageCount",
                            removed.size()));
            accounting = measured(request, measure, config, protectedTokens);
            valid(accounting);
        }
        if (!dropped.isEmpty() && config.path("summarization").path("enabled").asBoolean()) {
            // Stage 3 callback is an independently persisted, budgeted single model invocation.
            String summary = summarize.apply(Collections.unmodifiableList(dropped));
            if (summary == null || summary.isBlank())
                throw new PlatformException(502, "SUMMARY_EMPTY", "压缩模型没有返回摘要");
            messages.add(
                    1,
                    new LinkedHashMap<>(
                            Map.of(
                                    "role",
                                    "system",
                                    "content",
                                    "[前情摘要 / previous context summary]\n" + summary)));
            log.add(
                    Map.of(
                            "stage",
                            "MODEL_SUMMARY",
                            "sourceSha256",
                            Digest.sha256(Json.canonical(dropped)),
                            "summarySha256",
                            Digest.sha256(summary)));
            accounting = measured(request, measure, config, protectedTokens);
            valid(accounting);
        }
        // Stage 4 truncates only projected historical content. System and current-user instructions
        // stay intact.
        while (over(accounting)) {
            int index = -1, longest = 64;
            for (int i = 1; i < messages.size(); i++) {
                if (protectedMessages.contains(messages.get(i))) continue;
                int length = Objects.toString(messages.get(i).get("content"), "").length();
                if (length > longest) {
                    longest = length;
                    index = i;
                }
            }
            if (index < 0)
                throw new PlatformException(409, "CONTEXT_PROTECTED_OVERFLOW", "受保护提示与输出预留超过模型窗口");
            var message = messages.get(index);
            String text = message.get("content").toString();
            message.put("content", text.substring(0, text.length() / 2));
            log.add(
                    Map.of(
                            "stage",
                            "HARD_TRUNCATE",
                            "messageIndex",
                            index,
                            "sourceSha256",
                            Digest.sha256(text),
                            "retainedChars",
                            text.length() / 2));
            accounting = measured(request, measure, config, protectedTokens);
            valid(accounting);
        }
        validatePairs(messages);
        Map<String, Object> manifest = new LinkedHashMap<>();
        if (original.get("manifest") != null)
            manifest.putAll(
                    Json.mapper().convertValue(Json.tree(original.get("manifest")), Map.class));
        for (int i = 0; i < messages.size(); i++)
            if (protectedMessages.contains(messages.get(i))
                    && "user".equals(messages.get(i).get("role")))
                manifest.put("currentUserIndex", i);
        manifest.put("measurement", accounting);
        manifest.put("tokenCountExact", accounting.path("exact").asBoolean());
        manifest.put("trimLog", log);
        manifest.put(
                "sourceMessagesSha256", Digest.sha256(Json.canonical(original.get("messages"))));
        manifest.put("projectedMessagesSha256", Digest.sha256(Json.canonical(messages)));
        request.put("manifest", manifest);
        return request;
    }

    private static JsonNode measured(
            Map<String, Object> request,
            Function<Map<String, Object>, JsonNode> measure,
            JsonNode config,
            long protectedTokens) {
        var measured =
                (com.fasterxml.jackson.databind.node.ObjectNode) measure.apply(request).deepCopy();
        valid(measured);
        long reserve = Json.tree(request.get("params")).path("maxOutputTokens").asLong();
        if (reserve < 1 || reserve > measured.path("maxOutputTokens").asLong())
            throw new PlatformException(409, "OUTPUT_RESERVATION_INVALID", "输出预留超出冻结模型能力");
        measured.put("reservedOutputTokens", reserve);
        measured.put("protectedInputTokens", protectedTokens);
        measured.put("safetyTokens", 64);
        long budget = measured.path("contextWindow").asLong() - reserve - 64;
        if (config.path("historyTokens").asLong() > 0)
            budget = Math.min(budget, protectedTokens + config.path("historyTokens").asLong());
        measured.put("inputBudgetTokens", budget);
        return measured;
    }

    private static boolean over(JsonNode m) {
        return m.path("inputTokens").asLong()
                > m.path("inputBudgetTokens")
                        .asLong(
                                m.path("contextWindow").asLong()
                                        - m.path("reservedOutputTokens")
                                                .asLong(m.path("maxOutputTokens").asLong())
                                        - 64);
    }

    private static boolean needsTrim(JsonNode m, JsonNode config) {
        double ratio = config.path("summarization").path("triggerRatio").asDouble(0.7);
        if (ratio <= 0 || ratio > 1)
            throw new PlatformException(400, "CONTEXT_RATIO_INVALID", "压缩触发比例必须位于(0,1]");
        return over(m)
                || config.path("summarization").path("enabled").asBoolean()
                        && (m.path("inputTokens").asDouble()
                                        + m.path("reservedOutputTokens").asDouble()
                                        + 64)
                                > m.path("contextWindow").asDouble() * ratio;
    }

    private static void valid(JsonNode m) {
        if (m.path("inputTokens").asLong(-1) < 0
                || m.path("contextWindow").asLong() < 1
                || m.path("maxOutputTokens").asLong() < 1
                || m.path("tokenizer").asText().isBlank())
            throw new PlatformException(502, "MODEL_MEASUREMENT_INVALID", "模型计量不可用");
    }

    private static int groupEnd(
            List<Map<String, Object>> messages,
            int start,
            Set<Map<String, Object>> protectedMessages) {
        int end = start + 1;
        if ("user".equals(messages.get(start).get("role"))
                && !protectedMessages.contains(messages.get(start))) {
            while (end < messages.size()
                    && !"user".equals(messages.get(end).get("role"))
                    && !protectedMessages.contains(messages.get(end))) end++;
        } else while (end < messages.size() && "tool".equals(messages.get(end).get("role"))) end++;
        return end;
    }

    private static int groups(
            List<Map<String, Object>> messages, Set<Map<String, Object>> protectedMessages) {
        int count = 0;
        for (int i = 1; i < messages.size(); ) {
            if (!protectedMessages.contains(messages.get(i))) count++;
            i = groupEnd(messages, i, protectedMessages);
        }
        return count;
    }

    private static void validatePairs(List<Map<String, Object>> messages) {
        Set<String> pending = new HashSet<>();
        for (var message : messages) {
            JsonNode m = Json.tree(message);
            if (m.path("role").asText().equals("assistant"))
                for (JsonNode call : m.path("tool_calls"))
                    if (!pending.add(call.path("id").asText()))
                        throw new PlatformException(409, "TOOL_PAIR_INVALID", "工具调用重复");
            if (m.path("role").asText().equals("tool")
                    && !pending.remove(m.path("tool_call_id").asText()))
                throw new PlatformException(409, "TOOL_PAIR_INVALID", "工具结果缺少调用");
        }
        if (!pending.isEmpty()) throw new PlatformException(409, "TOOL_PAIR_INVALID", "工具调用缺少结果");
    }
}
