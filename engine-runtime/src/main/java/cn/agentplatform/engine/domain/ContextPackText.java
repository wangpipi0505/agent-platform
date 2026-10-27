package cn.agentplatform.engine.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把本体上下文包渲染成注入系统提示的稳定文本。纯函数：无 IO、无时钟、无随机数，
 * 同一包的两次渲染逐字节一致（该文本参与 ModelInputMaterials 的请求哈希）。
 *
 * <p>字段读取只按固定 key 顺序探测，不迭代对象字段；条目顺序沿用包内数组顺序。
 */
public final class ContextPackText {
    public static final int MAX_CHARS = 8000;

    /** truncated 为 true 时 text 末尾带条目总数说明；items 是包内四段条目总数（含未渲染项）。 */
    public record Rendered(String text, boolean truncated, int items) {}

    private static final List<String> SECTIONS =
            List.of("conceptCards", "termDictionary", "relationSummaries", "exampleQa");
    private static final Map<String, String> HEADERS =
            Map.of(
                    "conceptCards", "【概念卡】",
                    "termDictionary", "【术语表】",
                    "relationSummaries", "【关系摘要】",
                    "exampleQa", "【示例问答】");

    private ContextPackText() {}

    public static String render(JsonNode pack) {
        return renderDetailed(pack).text();
    }

    public static Rendered renderDetailed(JsonNode pack) {
        int total = 0;
        for (String section : SECTIONS) total += count(pack.path(section));
        if (total == 0) return new Rendered("", false, 0);

        String note = "…（共 " + total + " 项，已截断）";
        // 为 "\n" + note 预留，确保追加说明后总长仍不超过预算。
        int reserve = note.length() + 1;
        StringBuilder out = new StringBuilder();
        boolean truncated = false;
        sections:
        for (String section : SECTIONS) {
            JsonNode items = pack.path(section);
            if (!items.isArray() || items.isEmpty()) continue;
            String header = HEADERS.get(section);
            boolean headerWritten = false;
            for (JsonNode item : items) {
                String line = line(section, item);
                if (line.isBlank()) continue;
                int separator = out.isEmpty() ? 0 : (headerWritten ? 1 : 2);
                int headerCost = headerWritten ? 0 : header.length() + 1;
                if (out.length() + separator + headerCost + line.length() + reserve > MAX_CHARS) {
                    truncated = true;
                    break sections;
                }
                if (out.isEmpty()) out.append(header).append('\n');
                else if (!headerWritten) out.append("\n\n").append(header).append('\n');
                else out.append('\n');
                out.append(line);
                headerWritten = true;
            }
        }
        if (truncated) {
            if (out.isEmpty()) out.append(note);
            else out.append('\n').append(note);
        }
        String text = out.toString();
        // 防御性截断：正常路径已由逐行预算保证不越界，这里只兜底并发/异常输入。
        if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS);
        return new Rendered(text, truncated, total);
    }

    private static int count(JsonNode items) {
        return items.isArray() ? items.size() : 0;
    }

    private static String line(String section, JsonNode item) {
        if (!item.isObject()) {
            String raw = item.asText("").trim();
            return raw.isEmpty() ? "" : "- " + raw;
        }
        return switch (section) {
            case "conceptCards" -> conceptCard(item);
            case "termDictionary" -> term(item);
            case "relationSummaries" -> relation(item);
            default -> example(item);
        };
    }

    private static String conceptCard(JsonNode item) {
        String name = first(item, "name", "code", "title");
        StringBuilder line = new StringBuilder("- ").append(name.isEmpty() ? "未命名概念" : name);
        String description = first(item, "description", "definition", "comment", "summary");
        if (!description.isEmpty()) line.append('：').append(description);
        String properties = names(item.path("properties"));
        if (!properties.isEmpty()) line.append("；属性：").append(properties);
        return line.toString();
    }

    private static String term(JsonNode item) {
        String value = first(item, "term", "name", "code", "entryId");
        StringBuilder line = new StringBuilder("- ").append(value);
        String definition = first(item, "definition", "description", "note");
        if (!definition.isEmpty()) line.append('：').append(definition);
        String aliases =
                item.path("aliases").isArray()
                        ? names(item.path("aliases"))
                        : first(item, "alias");
        if (!aliases.isEmpty()) line.append("（别名：").append(aliases).append('）');
        return line.toString();
    }

    private static String relation(JsonNode item) {
        String name = first(item, "name", "code", "label");
        StringBuilder line = new StringBuilder("- ").append(name.isEmpty() ? "未命名关系" : name);
        String description = first(item, "description", "comment", "summary");
        if (!description.isEmpty()) line.append('：').append(description);
        String cardinality = first(item, "cardinality", "relationType");
        if (!cardinality.isEmpty()) line.append('（').append(cardinality).append('）');
        return line.toString();
    }

    private static String example(JsonNode item) {
        String question = first(item, "question", "query", "input", "prompt");
        StringBuilder line =
                new StringBuilder("- 问：")
                        .append(question.isEmpty() ? "（未提供问题）" : question)
                        .append(" 答：")
                        .append(answer(item.path("answer")));
        String source = first(item, "sourceRef", "traceRef", "caseId");
        if (!source.isEmpty()) line.append("（来源：").append(source).append('）');
        return line.toString();
    }

    private static String answer(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return "（未提供答案）";
        return value.isValueNode() ? value.asText() : value.toString();
    }

    /** 固定 key 优先级探测，避免对象字段顺序影响渲染结果。 */
    private static String first(JsonNode item, String... keys) {
        for (String key : keys) {
            JsonNode value = item.path(key);
            if (value.isTextual() || value.isNumber()) {
                String text = value.asText().trim();
                if (!text.isEmpty() && !"null".equals(text)) return text;
            }
        }
        return "";
    }

    /** 取数组项的展示名：对象取 name/code/term，标量取文本；保持数组原序。 */
    private static String names(JsonNode array) {
        if (!array.isArray()) return "";
        List<String> names = new ArrayList<>();
        for (JsonNode item : array) {
            String name =
                    item.isObject()
                            ? first(item, "name", "code", "term", "label")
                            : item.asText("").trim();
            if (!name.isEmpty()) names.add(name);
        }
        return String.join("、", names);
    }
}
